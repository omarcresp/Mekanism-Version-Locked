package mekanism.api.datamaps.chemical.attribute;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChemicalFuelTest {
    @Test
    void existingDataMapsKeepOneBurnStepPerMB() {
        var fuel = ChemicalFuel.CODEC.parse(JsonOps.INSTANCE,
              JsonParser.parseString("{\"maxBurnPerTick\":10,\"energy\":200}")).getOrThrow();
        Assertions.assertEquals(1, fuel.burnTicksPerMB());
        Assertions.assertEquals(2000, fuel.getMaxEnergyPerTick());
    }

    @Test
    void atm10EtheneHasFractionalThroughputAndPressure() {
        var fuel = new ChemicalFuel(256, 2400, 20);
        Assertions.assertEquals(30720, fuel.getEnergyForFullness(18000, 18000));
        Assertions.assertEquals(15360, fuel.getEnergyForFullness(9000, 18000));
        Assertions.assertEquals(120, fuel.getEnergyForFullness(1, 18000));
        Assertions.assertEquals(0, fuel.getEnergyForFullness(0, 18000));
        var encoded = ChemicalFuel.CODEC.encodeStart(JsonOps.INSTANCE, fuel).getOrThrow();
        Assertions.assertEquals(fuel, ChemicalFuel.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void highDensityFuelSaturatesWithoutIntegerOverflow() {
        var fuel = new ChemicalFuel(Integer.MAX_VALUE, Integer.MAX_VALUE, 20);
        Assertions.assertEquals(Integer.MAX_VALUE, fuel.getMaxEnergyPerTick());
        Assertions.assertEquals(Integer.MAX_VALUE, fuel.getEnergyForFullness(18000, 18000));
    }

    @Test
    void zeroBurnDurationIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ChemicalFuel(256, 2400, 0));
    }
}
