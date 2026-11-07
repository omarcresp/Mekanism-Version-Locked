package mekanism.common.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import mekanism.api.chemical.ChemicalResource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;

/** Energy purchased from a whole mB of fuel, awaiting conversion into output FE. */
public record FuelBurnBuffer(ChemicalResource fuel, int energy) {
    public static final Codec<FuelBurnBuffer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
          ChemicalResource.CODEC.fieldOf("chemical").forGetter(FuelBurnBuffer::fuel),
          ExtraCodecs.POSITIVE_INT.fieldOf("energy").forGetter(FuelBurnBuffer::energy)
    ).apply(instance, FuelBurnBuffer::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, FuelBurnBuffer> STREAM_CODEC = StreamCodec.composite(
          ChemicalResource.STREAM_CODEC, FuelBurnBuffer::fuel,
          ByteBufCodecs.VAR_INT, FuelBurnBuffer::energy,
          FuelBurnBuffer::new
    );

    public FuelBurnBuffer {
        if (fuel.isEmpty() || energy <= 0) {
            throw new IllegalArgumentException("Buffered fuel requires a chemical and positive remaining energy");
        }
    }
}
