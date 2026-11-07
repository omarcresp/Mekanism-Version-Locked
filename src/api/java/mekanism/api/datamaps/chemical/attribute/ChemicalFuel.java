package mekanism.api.datamaps.chemical.attribute;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import mekanism.api.MekanismAPI;
import mekanism.api.SerializationConstants;
import mekanism.api.text.APILang;
import mekanism.api.text.EnumColor;
import mekanism.api.text.ITooltipHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;

/// A [`chemical`][MekanismAPI#CHEMICAL_REGISTRY] data map that allows defining fuel values for a chemical.
///
/// @param maxBurnPerTick maximum fuel burn steps per tick when the tank is full.
/// @param energyPerTick  The energy density in FE per mB of fuel; must be greater than zero.
/// @param burnTicksPerMB how many burn steps consume one mB. Defaults to one for existing data maps.
///
/// @since 10.7.11
public record ChemicalFuel(int maxBurnPerTick, int energyPerTick, int burnTicksPerMB) implements IChemicalAttribute {

    /// The ID of the data map.
    ///
    /// @see mekanism.api.datamaps.IMekanismDataMapTypes#chemicalFuel()
    public static final Identifier ID = Identifier.fromNamespaceAndPath(MekanismAPI.MEKANISM_MODID, "chemical_attribute_fuel");

    /// Codec for serializing and deserializing chemical fuel.
    public static final Codec<ChemicalFuel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
          ExtraCodecs.POSITIVE_INT.fieldOf(SerializationConstants.MAX_BURN_RATE).forGetter(ChemicalFuel::maxBurnPerTick),
          ExtraCodecs.POSITIVE_INT.fieldOf(SerializationConstants.ENERGY).forGetter(ChemicalFuel::energyPerTick),
          ExtraCodecs.POSITIVE_INT.optionalFieldOf("burnTicksPerMB", 1).forGetter(ChemicalFuel::burnTicksPerMB)
    ).apply(instance, ChemicalFuel::new));

    public ChemicalFuel(int maxBurnPerTick, int energyPerTick) {
        this(maxBurnPerTick, energyPerTick, 1);
    }

    public ChemicalFuel {
        if (maxBurnPerTick < 1) {
            throw new IllegalArgumentException("Fuel attributes must be able to burn at least 1 per tick. maxBurnPerTick: " + maxBurnPerTick);
        } else if (burnTicksPerMB < 1) {
            throw new IllegalArgumentException("Fuel burn steps per mB must be positive");
        } else if (energyPerTick < 1) {
            throw new IllegalArgumentException("Fuel attributes must have a per tick energy density greater than zero!");
        }
    }

    /// The energy density in one mB of fuel.
    public int energyDensity() {
        return energyPerTick;
    }

    @Override
    public void collectTooltips(TooltipContext context, List<Component> tooltips, TooltipFlag tooltipFlag) {
        ITooltipHelper tooltipHelper = ITooltipHelper.INSTANCE;
        tooltips.add(APILang.CHEMICAL_ATTRIBUTE_FUEL_MAX_BURN.translateColored(EnumColor.GRAY, EnumColor.INDIGO, tooltipHelper.getFluidDisplay(maxBurnPerTick() / (double) burnTicksPerMB(), true)));
        tooltips.add(APILang.CHEMICAL_ATTRIBUTE_FUEL_ENERGY_DENSITY.translateColored(EnumColor.GRAY, EnumColor.INDIGO,
              tooltipHelper.getEnergyPerMBDisplayShort(energyDensity())));
        tooltips.add(APILang.CHEMICAL_ATTRIBUTE_FUEL_ENERGY_MAX_TOTAL.translateColored(EnumColor.GRAY, EnumColor.INDIGO,
              tooltipHelper.getEnergyDisplay(getMaxEnergyPerTick(), true)));
    }

    public int getMaxEnergyPerTick() {
        return (int) Math.min(Integer.MAX_VALUE, (long) maxBurnPerTick * energyPerTick / burnTicksPerMB);
    }

    public int getEnergyForSpace(long space) {
        long steps = Math.min(Integer.MAX_VALUE, space) * burnTicksPerMB / energyPerTick;
        return (int) Math.min(Integer.MAX_VALUE, steps * energyPerTick / burnTicksPerMB);
    }

    public int getEnergyForFullness(long stored, long capacity) {
        long steps = (long) Math.ceil(maxBurnPerTick * Math.min(1, stored / (double) capacity));
        return (int) Math.min(Integer.MAX_VALUE, steps * energyPerTick / burnTicksPerMB);
    }
}
