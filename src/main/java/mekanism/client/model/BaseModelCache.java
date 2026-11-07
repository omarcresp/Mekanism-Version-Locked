package mekanism.client.model;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.ResolvableModel.Resolver;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.util.context.ContextMap;
import net.neoforged.neoforge.client.event.ModelEvent.BakingCompleted;
import net.neoforged.neoforge.client.event.ModelEvent.RegisterStandalone;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.NeoForgeModelProperties;
import net.neoforged.neoforge.client.model.obj.ObjGeometry.Settings;
import net.neoforged.neoforge.client.model.obj.ObjGeometry;
import net.neoforged.neoforge.client.model.obj.ObjLoader;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.client.model.standalone.UnbakedStandaloneModel;

public class BaseModelCache {

    private final Map<Identifier, MekanismModelData> modelMap = new Object2ObjectOpenHashMap<>();

    private final String modid;

    protected BaseModelCache(String modid) {
        this.modid = modid;
    }

    private Identifier rl(String path) {
        return Identifier.fromNamespaceAndPath(modid, path);
    }

    public void onBake(ModelEvent.BakingCompleted evt) {
        for (MekanismModelData m : modelMap.values()) {
            m.reload(evt);
        }
    }

    public void setup(ModelEvent.RegisterStandalone event) {
        for (MekanismModelData mekanismModelData : modelMap.values()) {
            mekanismModelData.setup(event);
        }
    }

    protected OBJModelData registerOBJ(String path) {
        return registerOBJ(rl(path));
    }

    protected OBJModelData registerOBJ(Identifier rl) {
        return register(rl, OBJModelData::new);
    }

    protected BlockStateModelPartHelper registerJSON(String path) {
        return registerJSON(rl(path));
    }

    protected BlockStateModelPartHelper registerJSON(Identifier rl) {
        return register(rl, BlockStateModelPartHelper::new);
    }

    protected <DATA extends MekanismModelData> DATA register(Identifier rl, Function<Identifier, DATA> creator) {
        DATA data = creator.apply(rl);
        modelMap.put(rl, data);
        return data;
    }

    public static class MekanismModelData implements ModelDebugName {

        protected final Identifier rl;

        protected MekanismModelData(Identifier rl) {
            this.rl = rl;
        }

        @Override
        public String debugName() {
            return rl.toString();
        }

        protected void reload(ModelEvent.BakingCompleted evt) {
        }

        protected void setup(ModelEvent.RegisterStandalone event) {
        }


    }

    public static class OBJModelData extends MekanismModelData {
        private final StandaloneModelKey<BakedOBJData> key = new StandaloneModelKey<>(this);
        private ObjGeometry geometry;
        private BakedOBJData bakedData;

        protected OBJModelData(Identifier rl) {
            super(rl);
        }

        @Override
        protected void setup(RegisterStandalone event) {
            final ObjGeometry loadedGeometry = ObjLoader.INSTANCE.loadGeometry(new Settings(rl, true, useDiffuseLighting(), true, true, null));
            event.register(key, new UnbakedStandaloneModel<BakedOBJData>() {
                public BakedOBJData bake(ModelBaker baker, ModelDebugName name) {
                    return new BakedOBJData(loadedGeometry, baker, name);
                }

                public void resolveDependencies(Resolver resolver) {
                }
            });
        }

        @Override
        protected void reload(BakingCompleted evt) {
            super.reload(evt);
            bakedData = evt.getBakingResult().standaloneModels().get(key);
            geometry = bakedData == null ? null : bakedData.geometry();
        }

        public ObjGeometry getModel() {
            return geometry;
        }

        public List<BakedQuad> bake(Set<String> visibleParts) {
            if (geometry != null && bakedData != null && !visibleParts.isEmpty()) {
                Map<String, Boolean> visibility = new HashMap<>();

                for (String part : geometry.getRootComponentNames()) {
                    visibility.put(part, visibleParts.contains(part));
                }

                ContextKeySet keys = new ContextKeySet.Builder().optional(NeoForgeModelProperties.PART_VISIBILITY).build();
                ContextMap properties = new net.minecraft.util.context.ContextMap.Builder()
                    .withParameter(NeoForgeModelProperties.PART_VISIBILITY, Map.copyOf(visibility))
                    .create(keys);
                QuadCollection collection = geometry
                    .bake(MekaSuitTextureSlots.create(), bakedData.baker(), BlockModelRotation.IDENTITY, bakedData.debugName(), properties);
                return new ArrayList<>(collection.getAll());
            } else {
                return Collections.emptyList();
            }
        }

        protected boolean useDiffuseLighting() {
            return true;
        }

        private record BakedOBJData(ObjGeometry geometry, ModelBaker baker, ModelDebugName debugName) {
        }
    }

    public static class BlockStateModelPartHelper extends MekanismModelData {

        //this is a list due to SubmitNodeCollector wanting a list
        private List<BlockStateModelPart> bakedModel = Collections.emptyList();
        private final StandaloneModelKey<BlockStateModelPart> key;

        private BlockStateModelPartHelper(Identifier rl) {
            super(rl);
            key = new StandaloneModelKey<>(this);
        }

        @Override
        protected void reload(ModelEvent.BakingCompleted evt) {
            super.reload(evt);
            BlockStateModelPart modelPart = evt.getBakingResult().standaloneModels().get(key);
            bakedModel = modelPart == null ? Collections.emptyList() : List.of(modelPart);
        }

        @Override
        protected void setup(ModelEvent.RegisterStandalone event) {
            event.register(key, SimpleUnbakedStandaloneModel.simpleModelWrapper(rl));
        }

        public List<BlockStateModelPart> getBakedModel() {
            return bakedModel;
        }
    }
}
