package mekanism.common.block.attribute;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import mekanism.api.functions.ConstantPredicates;
import org.jspecify.annotations.Nullable;

//TODO: Eventually we may want to make these suppliers be used more like suppliers in that:
// if the config updates it doesn't require a server restart (or chunk reload to take effect
public class AttributeEnergy implements Attribute {

    /// Default buffer in ticks for machines without an explicit storage setting.
    ///
    /// @implNote This is a long so that we don't have to worry about overflow if someone sets the usage config to max int
    public static final long STORAGE_MULTIPLIER = 400;

    private IntSupplier energyUsage = ConstantPredicates.ZERO;
    private LongSupplier energyStorage = () -> STORAGE_MULTIPLIER * energyUsage.getAsInt();

    public AttributeEnergy(@Nullable IntSupplier energyUsage, @Nullable LongSupplier energyStorage) {
        if (energyUsage != null) {
            this.energyUsage = energyUsage;
        }
        if (energyStorage != null) {
            this.energyStorage = energyStorage;
        }
    }

    public int getUsage() {
        return energyUsage.getAsInt();
    }

    public long getConfigStorage() {
        return energyStorage.getAsLong();
    }

    public long getStorage() {
        return Math.max(getConfigStorage(), getUsage());
    }
}
