package dev.atm11poc;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.registries.MekanismChemicals;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Development fixture, installed only in a marked disposable POC world. */
@Mod("atm11poc")
public final class Probe {
    private static final BlockPos MACHINE = new BlockPos(8, 80, 8);
    private static final BlockPos PLATINUM = new BlockPos(8, 80, 10);
    private final ArrayList<String> checks = new ArrayList<>();
    private final LinkedHashMap<String, Object> observations = new LinkedHashMap<>();
    private MinecraftServer server;
    private int ticks;
    private boolean finished;
    private boolean reopen;
    private final EnergyProbe energy = new EnergyProbe(this::check);
    private final GeneratorProbe generators = new GeneratorProbe(this::check);
    private final GasProbe gas = new GasProbe(this::check);
    private final HeaterProbe heater = new HeaterProbe(this::check);
    private final HeatBioProbe heatBio = new HeatBioProbe(this::check);
    private final FuelChainProbe fuelChain = new FuelChainProbe(this::check);
    private final GeneratorTransferProbe generatorTransfer = new GeneratorTransferProbe(this::check);
    private long initialMachineEnergy;
    private final FusionProbe fusion = new FusionProbe(this::check);
    private final SPSProbe sps = new SPSProbe(this::check);
    private final TurbineProbe turbine = new TurbineProbe(this::check);
    private final ReactorProbe reactors = new ReactorProbe(this::check);

    public Probe() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
        checks.add(message);
        System.out.println("ATM11_POC PASS " + message);
    }

    private TileEntityMekanism machine() {
        return (TileEntityMekanism) server.overworld().getBlockEntity(MACHINE);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        try {
            check(Files.exists(Path.of("poc-owned-world")), "disposable world ownership marker");
            reopen = Files.exists(Path.of("seed-result.json"));
            observations.put("phase", reopen ? "restart" : "seed");
            observations.put("pid", ProcessHandle.current().pid());
            var level = server.overworld();
            level.setChunkForced(0, 0, true);
            if (reopen) {
                check(machine() != null, "machine loaded from saved world");
                var output = machine().getInventorySlots().get(1);
                check(output.amountAsLong() == 2, "exactly two processed items survive JVM restart");
                check(output.resource().is(BuiltInRegistries.ITEM.getValue(Identifier.parse("mekanism:enriched_carbon"))),
                      "processed item identity survives JVM restart");
                machine().getInventorySlots().get(0).setContents(ItemResource.of(Items.COAL), 1, null);
            } else {
                for (String id : new String[]{"mekanism:enrichment_chamber", "mekanism:metallurgic_infuser",
                        "mekanism:chemical_dissolution_chamber", "mekanism:sps_casing",
                        "mekanismgenerators:fission_reactor_casing", "mekanismgenerators:fusion_reactor_controller"}) {
                    var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
                    check(block != net.minecraft.world.level.block.Blocks.AIR, "registered " + id);
                }
                var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("mekanism:enrichment_chamber"));
                level.setBlockAndUpdate(MACHINE, block.defaultBlockState());
                check(machine() != null, "enrichment chamber block entity created");
                machine().getInventorySlots().get(0).setContents(ItemResource.of(Items.COAL), 2, null);
            }
            reactors.prepare(level, reopen ? JsonParser.parseString(Files.readString(Path.of("seed-result.json")))
                  .getAsJsonObject().getAsJsonObject("fission") : null);
            if (!reopen) {
                sps.build(level);
            }
            turbine.prepare(level, reopen ? JsonParser.parseString(Files.readString(Path.of("seed-result.json")))
                  .getAsJsonObject().getAsJsonObject("turbines") : null);
            fusion.prepare(level, reopen);
            energy.prepare(level, reopen);
            generators.prepare(level, reopen);
            gas.prepare(level, reopen);
            heater.prepare(level, reopen);
            heatBio.prepare(level, reopen ? JsonParser.parseString(Files.readString(Path.of("seed-result.json")))
                  .getAsJsonObject().getAsJsonObject("heatBio") : null);
            generatorTransfer.prepare(level, reopen ? JsonParser.parseString(Files.readString(Path.of("seed-result.json")))
                  .getAsJsonObject().getAsJsonObject("generatorTransfer") : null);
            fuelChain.prepare(level, reopen ? JsonParser.parseString(Files.readString(Path.of("seed-result.json")))
                  .getAsJsonObject().getAsJsonObject("fuelChain") : null);
            initialMachineEnergy = machine().getEnergyContainer().getCapacityAsLong();
            machine().getEnergyContainer().setEnergy(initialMachineEnergy, null);
            chemicalInterop();
            if (ModList.get().isLoaded("atm11mekcompat")) prepareCompat();
            observations.put("recipeCount", server.getRecipeManager().getRecipes().size());
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void prepareCompat() throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        check(!mekanism.common.config.MekanismConfig.usage.randomizedConsumption.get(),
              "ATM10 8.5 deterministic secondary chemical consumption is active");
        var fuelType = mekanism.api.datamaps.IMekanismDataMapTypes.INSTANCE.chemicalFuel();
        var ethene = ChemicalResource.of(MekanismChemicals.ETHENE).getData(fuelType);
        var hydrogen = ChemicalResource.of(MekanismChemicals.HYDROGEN).getData(fuelType);
        check(ethene != null && ethene.energyDensity() == 2400 && ethene.maxBurnPerTick() == 256 && ethene.burnTicksPerMB() == 20,
              "ATM10 ethene fuel map loads with 2400 FE per mB and 20 burn steps");
        check(hydrogen != null && hydrogen.energyDensity() == 80 && hydrogen.maxBurnPerTick() == 256,
              "ATM10 hydrogen fuel map loads with 80 FE per mB");
        var creative = new net.minecraft.commands.arguments.item.ItemParser(server.registryAccess())
              .parse(new com.mojang.brigadier.StringReader("mekanism:creative_energy_cube[mekanism:energy=9223372036854775807L]"))
              .createItemStack(1);
        check(creative.get(mekanism.common.registries.MekanismDataComponents.ATTACHED_ENERGY.get()) == Long.MAX_VALUE,
              "creative cube component parses and retains exact long energy value");
        check(server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE,
              Identifier.parse("allthemods:energizing/mekanism_creative_energy_cube"))).isPresent(),
              "ATM creative energy cube progression recipe loads");
        for (String id : new String[]{"atm11mekcompat:platinum/from_raw",
                "arsmagicalegacy:compat/mekanism/crushing/aum",
                "arsmagicalegacy:compat/mekanism/enriching/aum",
                "arsmagicalegacy:compat/mekanism/pigment_extracting/aum",
                "arsmagicalegacy:compat/mekanism/sawing/witchwood_logs",
                "allthemods:mekanism/sawing/undergarden/grongle_planks",
                "productivebees:mekanism/oxidizing/honeycomb_lithium",
                "productivebees:mekanism/oxidizing/honeycomb_wasted_radioactive",
                "productivebees:mekanism/oxidizing/comb_block_lithium",
                "productivebees:mekanism/oxidizing/comb_block_wasted_radioactive"}) {
            check(server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(id))).isPresent(),
                  "compat recipe decoded and loaded " + id);
        }
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var raw = ItemStackIngredient.CODEC.parse(ops, JsonParser.parseString("\"minecraft:coal\"")).getOrThrow();
        check(raw.ingredient().count() == 1, "uncounted external recipe input defaults to one item");
        var sized = ItemStackIngredient.CODEC.parse(ops, JsonParser.parseString(
              "{\"count\":3,\"ingredient\":\"minecraft:coal\"}")).getOrThrow();
        check(sized.ingredient().count() == 3, "counted recipe input preserves quantity");
        check(ItemStackIngredient.CODEC.parse(ops, JsonParser.parseString(
              "{\"count\":0,\"ingredient\":\"minecraft:coal\"}")).error().isPresent(),
              "zero-count input rejected instead of silently defaulting");
        var encoded = ItemStackIngredient.CODEC.encodeStart(ops, raw).getOrThrow();
        check(encoded.isJsonObject() && encoded.getAsJsonObject().has("ingredient"),
              "uncounted input encodes back to canonical counted schema");
        var level = server.overworld();
        if (!reopen) {
            level.setBlockAndUpdate(PLATINUM, BuiltInRegistries.BLOCK.getValue(
                  Identifier.parse("mekanism:enrichment_chamber")).defaultBlockState());
        }
        var machine = (TileEntityMekanism) level.getBlockEntity(PLATINUM);
        check(machine != null, "platinum processing chamber available");
        if (reopen) {
            check(machine.getInventorySlots().get(1).amountAsLong() == 4,
                  "four platinum dust survive JVM restart");
        }
        machine.getInventorySlots().get(0).setContents(ItemResource.of(BuiltInRegistries.ITEM.getValue(
              Identifier.parse("alltheores:raw_platinum"))), 3, null);
        machine.getEnergyContainer().setEnergy(machine.getEnergyContainer().getCapacityAsLong(), null);
    }

    private void chemicalInterop() {
        var level = server.overworld();
        var hydrogen = ChemicalResource.of(MekanismChemicals.HYDROGEN);
        for (String id : new String[]{"trashcans:liquid_trash_can", "trashcans:ultimate_trash_can"}) {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            check(block != net.minecraft.world.level.block.Blocks.AIR, "pack integration block registered " + id);
            var pos = new BlockPos(id.contains("ultimate") ? 12 : 10, 80, 8);
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            var handler = level.getCapability(Capabilities.CHEMICAL.block(), pos, Direction.UP);
            check(handler != null, "chemical capability available on " + id);
            try (var tx = Transaction.openRoot()) {
                check(handler.insert(0, hydrogen, 100, tx) == 100, "chemical insertion accepted by " + id);
                tx.commit();
            }
        }
        var tankPos = new BlockPos(14, 80, 8);
        if (!reopen) {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("mekanism:basic_chemical_tank"));
            level.setBlockAndUpdate(tankPos, block.defaultBlockState());
        }
        var tank = (TileEntityMekanism) level.getBlockEntity(tankPos);
        check(tank != null, "chemical tank block entity available");
        var storage = tank.getChemicalTanks().getFirst();
        if (reopen) {
            check(storage.amountAsLong() == 1000 && storage.resource().equals(hydrogen),
                  "chemical amount and identity survive JVM restart");
        } else {
            storage.setContents(hydrogen, 1000, null);
        }
        try (var tx = Transaction.openRoot()) {
            storage.setContents(hydrogen, 250, tx);
            check(storage.amountAsLong() == 250, "chemical transaction changes visible before abort");
        }
        check(storage.amountAsLong() == 1000, "aborted chemical transaction restores exact amount");
    }

    private void tick(ServerTickEvent.Post event) {
        if (server == null || finished || event.getServer() != server) return;
        try {
            ticks++;
            if (ticks % 100 == 0) System.out.println("ATM11_POC tick " + ticks);
            boolean reactorsComplete = reactors.tick(server.overworld(), ticks);
            boolean turbineComplete = turbine.tick(server.overworld(), ticks);
            boolean fusionComplete = fusion.tick(server.overworld(), ticks);
            boolean spsComplete = reopen || sps.tick(server.overworld(), ticks);
            boolean generatorsComplete = generators.tick(server.overworld(), ticks, reopen);
            boolean gasComplete = gas.tick(server.overworld(), ticks, reopen);
            boolean heaterComplete = heater.tick(server.overworld(), ticks);
            boolean heatBioComplete = heatBio.tick(server.overworld(), ticks);
            boolean fuelChainComplete = fuelChain.tick(server.overworld(), ticks);
            boolean generatorTransferComplete = generatorTransfer.tick(server.overworld(), ticks);
            var output = machine().getInventorySlots().get(1);
            long expected = reopen ? 3 : 2;
            if (output.amountAsLong() == expected && reactorsComplete && turbineComplete && fusionComplete && spsComplete && generatorsComplete && gasComplete && heaterComplete && heatBioComplete && generatorTransferComplete && fuelChainComplete) {
                if (ModList.get().isLoaded("atm11mekcompat")) {
                    var platinum = (TileEntityMekanism) server.overworld().getBlockEntity(PLATINUM);
                    var dust = platinum.getInventorySlots().get(1);
                    check(dust.amountAsLong() == (reopen ? 8 : 4), "three raw platinum processed into exactly four dust");
                    check(dust.resource().is(BuiltInRegistries.ITEM.getValue(Identifier.parse("alltheores:platinum_dust"))),
                          "compat processing outputs native AllTheOres platinum dust");
                    check(platinum.getInventorySlots().get(0).isEmpty(), "platinum recipe consumed exactly three raw items");
                }
                check(output.resource().is(BuiltInRegistries.ITEM.getValue(Identifier.parse("mekanism:enriched_carbon"))),
                      "native enrichment recipe produces enriched carbon");
                check(machine().getInventorySlots().get(0).isEmpty(), "machine consumed exact supplied input");
                check(machine().getEnergyContainer().getAmountAsLong() < initialMachineEnergy, "machine consumed energy while processing");
                observations.put("fission", reactors.snapshot(server.overworld()));
                observations.put("turbines", turbine.snapshot(server.overworld()));
                observations.put("heatBio", heatBio.snapshot(server.overworld()));
                observations.put("fuelChain", fuelChain.snapshot(server.overworld()));
                observations.put("generatorTransfer", generatorTransfer.snapshot(server.overworld()));
                if (!reopen) observations.put("fusionShutdown", fusion.shutdownForSave(server.overworld()));
                observations.put("outputCount", output.amountAsLong());
                observations.put("processingTicks", ticks);
                finish(null);
            } else if (ticks > 22000) {
                observations.put("input", machine().getInventorySlots().get(0).asStack().toString());
                observations.put("output", output.asStack().toString());
                throw new IllegalStateException("QA fixtures failed to complete within 22000 ticks");
            }
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void finish(Throwable failure) {
        finished = true;
        observations.put("success", failure == null);
        observations.put("checks", checks);
        if (failure != null) {
            failure.printStackTrace();
            observations.put("error", failure.toString());
        }
        try {
            Files.writeString(Path.of(reopen ? "restart-result.json" : "seed-result.json"),
                  new GsonBuilder().setPrettyPrinting().create().toJson(observations));
        } catch (Exception e) { throw new RuntimeException(e); }
        server.halt(false);
    }
}
