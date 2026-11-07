package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.registries.MekanismChemicals;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.tile.turbine.TileEntityTurbineCasing;
import mekanism.generators.common.tile.turbine.TileEntityTurbineRotor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class TurbineProbe {
    private static final BlockPos[] STARTS = {new BlockPos(48, 80, 24), new BlockPos(48, 80, 208)};
    private final BiConsumer<Boolean, String> check;
    private final long[] initialSteam = new long[2];
    private final long[] savedEnergy = new long[2];
    private final double[] savedRemainder = new double[2];
    private boolean reopen;
    private int stage;
    private int operatingTicks;

    TurbineProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    private static final class Builder extends GeneratorsBuilders.TurbineBuilder {
        void place(Level level, BlockPos start, boolean singleBlade) {
            build(level, start, false);
            if (singleBlade) {
                for (int y = 1; y <= 14; y++) {
                    ((TileEntityTurbineRotor) level.getBlockEntity(start.offset(8, y, 8))).blades = y == 1 ? 1 : 0;
                }
            }
        }
    }

    void prepare(ServerLevel level, JsonObject saved) {
        reopen = saved != null;
        var config = MekanismGeneratorsConfig.generators;
        check.accept(config.turbineBladesPerCoil.get() == 8 && config.condenserRate.get() == 128000,
              "ATM10 turbine supports eight blades per coil and 128000 mB per condenser");
        check.accept(config.turbineChemicalPerTank.get() == 6400 && config.turbineEnergyCapacityPerVolume.get() == 6400000,
              "ATM10 turbine cavity and energy capacities restored");
        check.accept(config.turbineJoulesPerSteam.get() == 10 && config.turbineSteamDivisor.get() == 1,
              "ATM10 steam carries ten joules without a flow divisor");
        check.accept(config.turbineVentChemicalFlow.get() == 43478.262 && config.turbineDisperserChemicalFlow.get() == 1280,
              "ATM10 turbine vent and disperser rates restored");
        for (int i = 0; i < STARTS.length; i++) {
            var start = STARTS[i];
            for (int x = start.getX() >> 4; x <= (start.getX() + 16) >> 4; x++) {
                for (int z = start.getZ() >> 4; z <= (start.getZ() + 16) >> 4; z++) level.setChunkForced(x, z, true);
            }
            if (!reopen) new Builder().place(level, start, i == 1);
            else {
                savedEnergy[i] = saved.getAsJsonObject("turbine" + i).get("energy").getAsLong();
                savedRemainder[i] = saved.getAsJsonObject("turbine" + i).get("remainder").getAsDouble();
            }
        }
    }

    private mekanism.generators.common.content.turbine.TurbineMultiblockData data(ServerLevel level, int i) {
        return ((TileEntityTurbineCasing) level.getBlockEntity(STARTS[i])).getMultiblock();
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (stage == 4) return true;
        for (int i = 0; i < STARTS.length; i++) if (!data(level, i).isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Real turbine structure did not form within 200 ticks");
            return false;
        }
        if (reopen) {
            for (int i = 0; i < STARTS.length; i++) {
                var turbine = data(level, i);
                if (stage == 0) {
                    check.accept(turbine.energyContainer().getAmountAsLong() == savedEnergy[i], "turbine restores exact FE across JVM restart " + i);
                    check.accept(turbine.getGenerationRemainder() == savedRemainder[i], "turbine restores exact fractional FE across JVM restart " + i);
                    check.accept(turbine.chemicalTank.isEmpty(), "idle turbine restores empty steam cavity " + i);
                    turbine.chemicalTank.setContents(ChemicalResource.of(MekanismChemicals.STEAM), turbine.getSteamCapacity(), null);
                    initialSteam[i] = turbine.chemicalTank.amountAsLong();
                } else {
                    long consumed = initialSteam[i] - turbine.chemicalTank.amountAsLong();
                    double expected = savedRemainder[i] + consumed * (i == 0 ? 4D : 1D / 7);
                    double actual = turbine.energyContainer().getAmountAsLong() - savedEnergy[i] + turbine.getGenerationRemainder();
                    check.accept(consumed > 0 && Math.abs(actual - expected) < 0.000001,
                          "turbine resumes generation using persisted fractional credit " + i);
                    turbine.chemicalTank.setContents(ChemicalResource.EMPTY, 0, null);
                }
            }
            if (stage == 0) { stage = 1; return false; }
            stage = 4;
            return true;
        }
        if (stage == 0) {
            for (int i = 0; i < STARTS.length; i++) {
                var turbine = data(level, i);
                check.accept(turbine.blades == (i == 0 ? 28 : 1) && turbine.coils > 0 && turbine.condensers > 0,
                      "formed turbine detects physical rotor blade count " + i);
                check.accept(turbine.energyContainer().getCapacityAsLong() == turbine.getVolume() * 6400000L &&
                      turbine.energyContainer().getCapacityAsLong() > Integer.MAX_VALUE, "turbine retains ATM10 buffer above integer FE range " + i);
                double maxFlow = Math.min(turbine.lowerVolume * turbine.getDispersers() * 1280D, turbine.vents * 43478.262);
                check.accept(turbine.getMaxProduction() == (int) ((i == 0 ? 4D : 1D / 7) * maxFlow),
                      "turbine maximum production reports converted FE " + i);
                turbine.chemicalTank.setContents(ChemicalResource.of(MekanismChemicals.STEAM), turbine.getSteamCapacity(), null);
                initialSteam[i] = turbine.chemicalTank.amountAsLong();
                turbine.ventTank.setContents(FluidResource.of(Fluids.WATER), turbine.ventTank.capacityAsLong(FluidResource.of(Fluids.WATER)), null);
                check.accept(turbine.energyContainer().getAmountAsLong() == 0, "turbine begins without stored FE " + i);
            }
            stage = 1;
            return false;
        }
        if (stage == 1) {
            if (++operatingTicks < 20) return false;
            for (int i = 0; i < STARTS.length; i++) {
                var turbine = data(level, i);
                long consumed = initialSteam[i] - turbine.chemicalTank.amountAsLong();
                double expected = consumed * (i == 0 ? 4D : 1D / 7);
                double actual = turbine.energyContainer().getAmountAsLong() + turbine.getGenerationRemainder();
                check.accept(consumed > 0 && Math.abs(actual - expected) < 0.000001,
                      "normal turbine ticks conserve FE per whole mB with fractional credit " + i);
                check.accept(turbine.ventTank.amountAsLong() == turbine.ventTank.capacityAsLong(FluidResource.of(Fluids.WATER)),
                      "full condenser tank permits continued generation " + i);
                check.accept(turbine.getProductionRate() > 0, "turbine reports committed FE production " + i);
                double original = turbine.getGenerationRemainder();
                try (Transaction transaction = Transaction.openRoot()) {
                    turbine.setGenerationRemainder(0.9, transaction);
                }
                check.accept(turbine.getGenerationRemainder() == original, "aborted transaction restores fractional turbine credit " + i);
                savedRemainder[i] = original;
                turbine.energyContainer().setEnergy(turbine.energyContainer().getCapacityAsLong(), null);
                initialSteam[i] = turbine.chemicalTank.amountAsLong();
            }
            stage = 2;
            return false;
        }
        if (stage == 2) {
            for (int i = 0; i < STARTS.length; i++) {
                var turbine = data(level, i);
                check.accept(turbine.chemicalTank.amountAsLong() == initialSteam[i] && turbine.getProductionRate() == 0,
                      "full turbine buffer stops steam consumption " + i);
                check.accept(turbine.getGenerationRemainder() == savedRemainder[i], "full turbine buffer preserves fractional credit " + i);
                turbine.energyContainer().setEnergy(12345, null);
                turbine.chemicalTank.setContents(ChemicalResource.EMPTY, 0, null);
            }
            stage = 3;
            return false;
        }
        for (int i = 0; i < STARTS.length; i++) {
            check.accept(data(level, i).getProductionRate() == 0, "idle turbine reports zero generation " + i);
        }
        stage = 4;
        return true;
    }

    Map<String, Object> snapshot(ServerLevel level) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < STARTS.length; i++) {
            var turbine = data(level, i);
            result.put("turbine" + i, Map.of("energy", turbine.energyContainer().getAmountAsLong(), "remainder", turbine.getGenerationRemainder()));
        }
        return result;
    }
}
