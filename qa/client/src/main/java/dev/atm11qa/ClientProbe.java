package dev.atm11qa;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.client.model.MekanismModelCache;
import mekanism.client.render.MekanismRenderType;
import mekanism.client.render.armor.MekaSuitModel;
import mekanism.common.registries.MekanismItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "atm11clientqa", dist = Dist.CLIENT)
public final class ClientProbe {
    private boolean ran;
    private boolean titleObserved;
    private final List<String> checks = new ArrayList<>();
    private int vertices;
    private int batches;
    private int worldTicks;
    private boolean worldPrepared;
    private boolean worldRecorded;

    public ClientProbe() {
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void check(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException(label);
        }
        checks.add(label);
    }

    private void probeWorld(Minecraft client) {
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            return;
        }
        if (!worldPrepared) {
            worldPrepared = true;
            var uuid = client.player.getUUID();
            var server = client.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, MekanismItems.MEKASUIT_HELMET.asStack());
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, MekanismItems.MEKASUIT_BODYARMOR.asStack());
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, MekanismItems.MEKASUIT_PANTS.asStack());
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, MekanismItems.MEKASUIT_BOOTS.asStack());
                }
            });
            client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        }
        if (++worldTicks < 200 || worldRecorded) {
            return;
        }
        worldRecorded = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", ProcessHandle.current().pid());
        result.put("worldTicks", worldTicks);
        List<String> worldChecks = new ArrayList<>();
        boolean success = true;
        for (var slot : List.of(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
              net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET)) {
            boolean equipped = client.player.getItemBySlot(slot).getItem() instanceof mekanism.common.item.gear.ItemMekaSuitArmor;
            success &= equipped;
            worldChecks.add(slot + " MekaSuit equipped: " + equipped);
        }
        boolean muted = client.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER) == 0;
        success &= muted;
        worldChecks.add("world master volume muted: " + muted);
        result.put("success", success);
        result.put("checks", worldChecks);
        try {
            Files.writeString(client.gameDirectory.toPath().resolve("client-world-result.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        client.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
        if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
            titleObserved = true;
        }
        probeWorld(client);
        if (ran || client.level == null || client.player == null || worldTicks < 20 || client.getOverlay() != null) {
            return;
        }
        ran = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", ProcessHandle.current().pid());
        try {
            check(client.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER) == 0, "test client master volume is muted");
            check(titleObserved, "real client reached title screen with the full ATM11 renderer stack");
            check(MekanismRenderType.MEKASUIT != null, "MekaSuit render type is available");
            var data = MekanismModelCache.INSTANCE.MEKASUIT;
            check(data.getModel() != null, "MekaSuit OBJ geometry loaded in real model bake");
            var parts = data.getModel().getRootComponentNames();
            check(!parts.isEmpty(), "MekaSuit OBJ has named model parts");
            var quads = data.bake(parts);
            check(!quads.isEmpty(), "MekaSuit selected parts bake into actual quads");
            check(quads.stream().allMatch(quad -> quad.materialInfo().sprite().atlasLocation()
                  .equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_ITEMS)), "armor UVs are baked from the item atlas");
            check(MekanismRenderType.MEKASUIT == net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(
                  net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_ITEMS), "armor render type binds the matching item atlas");
            check(data.bake(java.util.Set.of()).isEmpty(), "empty part selection emits no geometry");
            VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    if (method.getName().equals("addVertex")) {
                        vertices++;
                        for (Object value : args) {
                            if (value instanceof Float number && !Float.isFinite(number)) {
                                throw new IllegalStateException("non-finite rendered vertex");
                            }
                        }
                    }
                    return method.getReturnType() == VertexConsumer.class ? proxy : null;
                });
            SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry")) {
                        if (args[1] == null) {
                            throw new IllegalStateException("null render type");
                        }
                        batches++;
                        ((SubmitNodeCollector.CustomGeometryRenderer) args[2]).render(((PoseStack) args[0]).last(), consumer);
                    }
                    return method.getName().equals("order") ? proxy : null;
                });
            HumanoidModel<HumanoidRenderState> model = new HumanoidModel<>(client.getEntityModels().bakeLayer(ModelLayers.PLAYER));
            HumanoidRenderState state = new HumanoidRenderState();
            state.mainArm = net.minecraft.world.entity.HumanoidArm.RIGHT;
            state.headEquipment = MekanismItems.MEKASUIT_HELMET.asStack();
            state.chestEquipment = MekanismItems.MEKASUIT_BODYARMOR.asStack();
            state.legsEquipment = MekanismItems.MEKASUIT_PANTS.asStack();
            state.feetEquipment = MekanismItems.MEKASUIT_BOOTS.asStack();
            state.leftHandItemStack = net.minecraft.world.item.ItemStack.EMPTY;
            state.rightHandItemStack = net.minecraft.world.item.ItemStack.EMPTY;
            for (var armor : List.of(MekaSuitModel.HELMET, MekaSuitModel.BODYARMOR, MekaSuitModel.PANTS, MekaSuitModel.BOOTS)) {
                int before = vertices;
                armor.render(model, new PoseStack(), collector, 15728880, state, state.chestEquipment);
                check(vertices > before, "armor slot emits finite rendered vertices " + checks.size());
            }
            int before = vertices;
            check(MekaSuitModel.BODYARMOR.renderArm(model, new PoseStack(), collector, 15728880, state, state.chestEquipment, true),
                "first-person right arm replaces vanilla arm when geometry exists");
            check(vertices > before, "first-person arm emits rendered vertices");
            result.put("success", true);
            result.put("vertices", vertices);
            result.put("batches", batches);
        } catch (Throwable error) {
            result.put("success", false);
            result.put("error", error.toString());
            error.printStackTrace();
        }
        result.put("checks", checks);
        try {
            Files.writeString(client.gameDirectory.toPath().resolve("client-render-result.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
