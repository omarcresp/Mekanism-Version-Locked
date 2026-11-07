package mekanism.client.model;

import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.client.resources.model.sprite.TextureSlots.Resolver;
import net.minecraft.client.resources.model.sprite.TextureSlots.Data.Builder;
import net.minecraft.resources.Identifier;

public final class MekaSuitTextureSlots {
    private static final ModelDebugName DEBUG_NAME = new ModelDebugName() {
        public String debugName() {
            return "mekanism:mekasuit";
        }
    };

    private MekaSuitTextureSlots() {
    }

    public static TextureSlots create() {
        Builder builder = new Builder();
        add(builder, "mekanism:entity/armor/mekasuit_player");
        add(builder, "mekanism:entity/armor/mekasuit_armor_body");
        add(builder, "mekanism:entity/armor/mekasuit_armor_helmet");
        add(builder, "mekanism:entity/armor/mekasuit_armor_exoskeleton");
        add(builder, "mekanism:entity/armor/mekasuit_elytra");
        add(builder, "mekanism:entity/armor/mekasuit_armor_modules");
        add(builder, "mekanism:entity/armor/mekasuit_gravitational_modulator");
        add(builder, "mekanism:entity/armor/mekatool");
        add(builder, "mekanism:item/leds");
        return new Resolver().addLast(builder.build()).resolve(DEBUG_NAME);
    }

    private static void add(Builder builder, String texture) {
        builder.addTexture(texture, new Material(Identifier.parse(texture)));
    }
}
