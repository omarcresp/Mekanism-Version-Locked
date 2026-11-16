package mekanism.client.model;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.util.context.ContextMap;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.NeoForgeModelProperties;
import net.neoforged.neoforge.client.model.obj.ObjGeometry;
import net.neoforged.neoforge.client.model.obj.ObjLoader;
import net.neoforged.neoforge.client.model.obj.ObjTokenizer;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.client.model.standalone.UnbakedStandaloneModel;
import org.jspecify.annotations.Nullable;

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

    protected OBJModelData registerOBJGeometry(String path) {
        return register(rl(path), GeometryOnlyOBJModelData::new);
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

        private static final ContextKeySet PART_VISIBILITY_KEYS = new ContextKeySet.Builder().optional(NeoForgeModelProperties.PART_VISIBILITY).build();

        private final StandaloneModelKey<BakedOBJData> key = new StandaloneModelKey<>(this);
        @Nullable
        private BakedOBJData bakedData;
        @Nullable
        private ObjGeometry geometry;

        protected OBJModelData(Identifier rl) {
            super(rl);
        }

        @Override
        protected void setup(ModelEvent.RegisterStandalone event) {
            ObjGeometry geometry = loadGeometry();
            TextureSlots textures = loadTextureSlots();
            event.register(key, new UnbakedStandaloneModel<>() {
                @Override
                public BakedOBJData bake(ModelBaker baker, ModelDebugName name) {
                    Map<String, List<BakedQuad>> parts = new HashMap<>();
                    for (String part : geometry.getRootComponentNames()) {
                        Map<String, Boolean> visibility = new HashMap<>();
                        for (String root : geometry.getRootComponentNames()) {
                            visibility.put(root, root.equals(part));
                        }
                        ContextMap properties = new ContextMap.Builder()
                              .withParameter(NeoForgeModelProperties.PART_VISIBILITY, visibility)
                              .create(PART_VISIBILITY_KEYS);
                        parts.put(part, List.copyOf(geometry.bake(textures, baker, BlockModelRotation.IDENTITY, name, properties).getAll()));
                    }
                    return new BakedOBJData(geometry, Map.copyOf(parts));
                }

                @Override
                public void resolveDependencies(Resolver resolver) {
                }
            });
        }

        @Override
        protected void reload(ModelEvent.BakingCompleted evt) {
            super.reload(evt);
            bakedData = evt.getBakingResult().standaloneModels().get(key);
        }

        public ObjGeometry getModel() {
            return Objects.requireNonNull(geometry, "Model not loaded");
        }

        protected ObjGeometry loadGeometry() {
            geometry = ObjLoader.INSTANCE.loadGeometry(new ObjGeometry.Settings(rl, true, useDiffuseLighting(), true, true, null));
            return geometry;
        }

        public List<BakedQuad> bake(Set<String> visibleParts) {
            if (bakedData == null || visibleParts.isEmpty()) {
                return Collections.emptyList();
            }
            List<BakedQuad> quads = new ArrayList<>();
            for (String part : bakedData.geometry().getRootComponentNames()) {
                if (visibleParts.contains(part)) {
                    quads.addAll(bakedData.parts().get(part));
                }
            }
            return quads;
        }

        private TextureSlots loadTextureSlots() {
            TextureSlots.Data.Builder textures = new TextureSlots.Data.Builder();
            var resources = Minecraft.getInstance().getResourceManager();
            try (ObjTokenizer model = new ObjTokenizer(resources.getResource(rl).orElseThrow().open())) {
                String[] line;
                while ((line = model.readAndSplitLine(true)) != null) {
                    if (line[0].equals("mtllib")) {
                        String library = line[1];
                        Identifier location = library.contains(":") ? Identifier.parse(library) : Identifier.fromNamespaceAndPath(rl.getNamespace(),
                              rl.getPath().substring(0, rl.getPath().lastIndexOf('/') + 1) + library);
                        try (ObjTokenizer materials = new ObjTokenizer(resources.getResource(location).orElseThrow().open())) {
                            String[] material;
                            while ((material = materials.readAndSplitLine(true)) != null) {
                                if (material[0].equals("map_Kd")) {
                                    String texture = material[material.length - 1];
                                    if (!texture.startsWith("#")) {
                                        textures.addTexture(texture, new Material(Identifier.parse(texture)));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read OBJ textures for " + rl, e);
            }
            return new TextureSlots.Resolver().addLast(textures.build()).resolve(this);
        }

        protected boolean useDiffuseLighting() {
            return true;
        }

        private record BakedOBJData(ObjGeometry geometry, Map<String, List<BakedQuad>> parts) {
        }
    }

    public static class GeometryOnlyOBJModelData extends OBJModelData {

        protected GeometryOnlyOBJModelData(Identifier rl) {
            super(rl);
        }

        @Override
        protected void setup(ModelEvent.RegisterStandalone event) {
            loadGeometry();
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
