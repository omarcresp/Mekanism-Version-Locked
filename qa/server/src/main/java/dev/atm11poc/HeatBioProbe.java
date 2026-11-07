package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.registries.MekanismItems;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.registries.GeneratorsFluids;
import mekanism.generators.common.tile.TileEntityBioGenerator;
import mekanism.generators.common.tile.TileEntityHeatGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class HeatBioProbe {
    private static final BlockPos START = new BlockPos(8, 80, 240);
    private final BiConsumer<Boolean, String> check;
    private final double[] ambient = new double[4];
    private double savedRemainder;
    private boolean reopen;
    private boolean complete;
    private boolean started;
    private final int[] startingTicks = new int[7];
    private int ticks;
    private double lavaInitialHeat;
    private double passiveInitialHeat;

    HeatBioProbe(BiConsumer<Boolean, String> check) { this.check = check; }

    void prepare(ServerLevel level, JsonObject saved) {
        reopen = saved != null;
        var config = MekanismGeneratorsConfig.generators;
        check.accept(config.bioGeneration.get() == 140 && config.bioFuelPerItem.get() == 200 && config.bioTankCapacity.get() == 24000,
              "ATM10 bio generator restores 140 FE/t, 200 ticks per item and 24000 mB tank");
        check.accept(config.heatGeneration.get() == 800 && config.heatGenerationLava.get() == 30 && config.heatGenerationNether.get() == 100,
              "ATM10 heat generator restores thermal Joules for active, lava-adjacent and Nether heat");
        check.accept(config.heatGenerationFluidRate.get() == 10 && config.heatTankCapacity.get() == 24000,
              "ATM10 heat generator consumes 10 mB per active tick with a 24000 mB tank");
        for (int i = 0; i < 7; i++) {
            BlockPos pos = START.offset(i * 4, 0, 0);
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            if (!reopen) level.setBlockAndUpdate(pos, i < 4 ? GeneratorsBlocks.HEAT_GENERATOR.defaultState() : GeneratorsBlocks.BIO_GENERATOR.defaultState());
            if (i < 4) ambient[i] = heat(level, i).getHeatCapacitor().getTemperature();
        }
        if (reopen) {
            savedRemainder = saved.get("heatRemainder").getAsDouble();
            check.accept(heat(level, 0).getGenerationRemainder() == savedRemainder && heat(level, 0).energyContainer().getAmountAsLong() == 1,
                  "heat generator restores exact fractional output and stored FE across JVM restart");
            check.accept(bio(level, 4).bioFuelTank.isEmpty() && bio(level, 4).energyContainer().getAmountAsLong() == 28000,
                  "bio generator retains complete single-item output across JVM restart");
            check.accept(bio(level, 5).bioFuelTank.amountAsLong() == 1799 && bio(level, 5).energyContainer().getAmountAsLong() == 140,
                  "biofuel block retains exact remaining burn time and FE across JVM restart");
        }
    }

    private void start(ServerLevel level) {
        for (int i = 0; i < startingTicks.length; i++) startingTicks[i] = i < 4 ? heat(level, i).ticker : bio(level, i).ticker;
        if (reopen) {
            bio(level, 5).setControlType(RedstoneControl.DISABLED);
            pulse(level);
            return;
        }
        pulse(level);
        setTemperature(heat(level, 1), ambient[1] + 1000000);
        var lava = heat(level, 2);
        lavaInitialHeat = lava.getHeatCapacitor().getHeat();
        lava.lavaTank.setContents(FluidResource.of(Fluids.LAVA), 10, null);
        var passive = heat(level, 3);
        passiveInitialHeat = passive.getHeatCapacitor().getHeat();
        level.setBlockAndUpdate(passive.getBlockPos().east(), Blocks.LAVA.defaultBlockState());
        bio(level, 4).getInventorySlots().get(0).setContents(ItemResource.of(MekanismItems.BIO_FUEL.asItem()), 1, null);
        bio(level, 5).getInventorySlots().get(0).setContents(ItemResource.of(mekanism.common.registries.MekanismBlocks.BIO_FUEL_BLOCK.asItem()), 1, null);
        bio(level, 6).bioFuelTank.setContents(GeneratorsFluids.BIOETHANOL.asResource(), 1, null);
        bio(level, 6).energyContainer().setEnergy(64000 - 139, null);
    }

    private TileEntityHeatGenerator heat(ServerLevel level, int i) {
        return (TileEntityHeatGenerator) level.getBlockEntity(START.offset(i * 4, 0, 0));
    }

    private TileEntityBioGenerator bio(ServerLevel level, int i) {
        return (TileEntityBioGenerator) level.getBlockEntity(START.offset(i * 4, 0, 0));
    }

    private void setTemperature(TileEntityHeatGenerator tile, double temperature) {
        tile.getHeatCapacitor().setHeat(temperature * tile.getHeatCapacitor().getHeatCapacity(), null);
    }

    private void pulse(ServerLevel level) {
        // A Carnot output between one and two Joules rounds to one whole Joule.
        double q = 1.25;
        double delta = q + Math.sqrt(q * q + 2 * q * ambient[0]);
        setTemperature(heat(level, 0), ambient[0] + delta);
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        if (!started) {
            for (int i = 0; i < startingTicks.length; i++) {
                int counter = i < 4 ? heat(level, i).ticker : bio(level, i).ticker;
                if (counter < 2) {
                    if (elapsed > 100) throw new IllegalStateException("Heat/bio fixture chunk did not begin ticking");
                    return false;
                }
            }
            start(level);
            started = true;
            return false;
        }
        ticks++;
        if (ticks == 1) {
            for (int i = 0; i < startingTicks.length; i++) {
                int counter = i < 4 ? heat(level, i).ticker : bio(level, i).ticker;
                check.accept(counter == startingTicks[i] + 1, "heat/bio fixture measures exactly one actual tile update " + i);
            }
        }
        if (reopen) {
            if (ticks == 1) {
                check.accept(bio(level, 5).bioFuelTank.amountAsLong() == 1798 && bio(level, 5).energyContainer().getAmountAsLong() == 280,
                      "biofuel block resumes exactly one 140 FE burn tick after restart");
                bio(level, 5).setControlType(RedstoneControl.HIGH);
                pulse(level);
                return false;
            }
            check.accept(heat(level, 0).energyContainer().getAmountAsLong() == 2 && Math.abs(heat(level, 0).getGenerationRemainder()) < 1e-9,
                  "saved heat-generator credit combines with two one-Joule pulses into exactly one more FE");
            setTemperature(heat(level, 0), ambient[0]);
            complete = true;
            return true;
        }
        if (ticks == 1) {
            check.accept(heat(level, 0).energyContainer().isEmpty() && Math.abs(heat(level, 0).getGenerationRemainder() - 0.4) < 1e-9,
                  "heat generator retains four tenths of one FE from one whole thermal Joule; actual FE=" + heat(level, 0).energyContainer().getAmountAsLong() + " remainder=" + heat(level, 0).getGenerationRemainder());
            check.accept(heat(level, 1).getProductionRate() == 444 && heat(level, 1).energyContainer().getAmountAsLong() == 444,
                  "maximum thermal conversion caps at 1110 Joules and generates 444 FE");
            setTemperature(heat(level, 1), ambient[1]);
            var lava = heat(level, 2);
            double accounted = lava.getHeatCapacitor().getHeat() - lavaInitialHeat +
                  (lava.getLastEnvironmentLoss() + lava.getLastTransferLoss()) * lava.getHeatCapacitor().getHeatCapacity() + 40;
            check.accept(lava.lavaTank.isEmpty() && Math.abs(accounted - 800) < 1e-6,
                  "10 mB of lava supplies exactly 800 accounted thermal Joules");
            setTemperature(lava, ambient[2]);
            var passive = heat(level, 3);
            double passiveAccounted = passive.getHeatCapacitor().getHeat() - passiveInitialHeat +
                  (passive.getLastEnvironmentLoss() + passive.getLastTransferLoss()) * passive.getHeatCapacitor().getHeatCapacity() + 1.5;
            check.accept(Math.abs(passiveAccounted - 30) < 1e-6 && passive.lavaTank.isEmpty(),
                  "adjacent lava supplies 30 thermal Joules without tank fuel");
            level.setBlockAndUpdate(passive.getBlockPos().east(), Blocks.AIR.defaultBlockState());
            setTemperature(passive, ambient[3]);
            check.accept(bio(level, 5).bioFuelTank.amountAsLong() == 1799 && bio(level, 5).energyContainer().getAmountAsLong() == 140,
                  "one biofuel block supplies 1800 burn ticks and produces its first 140 FE");
            bio(level, 5).setControlType(RedstoneControl.HIGH);
            check.accept(bio(level, 6).bioFuelTank.amountAsLong() == 1 && bio(level, 6).energyContainer().getAmountAsLong() == 63861,
                  "bio generation rolls back fuel and FE when only 139 FE fit");
            bio(level, 6).energyContainer().setEnergy(63860, null);
        }
        if (ticks == 2) {
            check.accept(bio(level, 6).bioFuelTank.isEmpty() && bio(level, 6).energyContainer().getAmountAsLong() == 64000,
                  "bio generation consumes exactly one mB when all 140 FE fit");
        }
        if (ticks < 3) pulse(level);
        if (ticks == 3) {
            var tile = heat(level, 0);
            check.accept(tile.energyContainer().getAmountAsLong() == 1 && Math.abs(tile.getGenerationRemainder() - 0.2) < 1e-9,
                  "three whole-Joule heat pulses store one FE and retain one fifth FE");
            savedRemainder = tile.getGenerationRemainder();
            setTemperature(tile, ambient[0]);
            try (var tx = Transaction.openRoot()) {
                setTemperatureForTransaction(tile, ambient[0] + 1000000, tx);
                tile.simulate(tx);
            }
            check.accept(tile.getGenerationRemainder() == savedRemainder && tile.energyContainer().getAmountAsLong() == 1,
                  "aborted heat simulation restores fractional credit and stored FE");
            var state = tile.getBlockState();
            var item = Block.getDrops(state, level, tile.getBlockPos(), tile).stream().filter(stack -> stack.is(GeneratorsBlocks.HEAT_GENERATOR.asItem())).findFirst().orElseThrow();
            check.accept(item.get(MekanismDataComponents.GENERATION_REMAINDER) == savedRemainder,
                  "normal heat-generator loot preserves earned fractional FE");
            level.setBlockAndUpdate(tile.getBlockPos(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(tile.getBlockPos(), state);
            heat(level, 0).applyComponentsFromItemStack(item);
            check.accept(heat(level, 0).getGenerationRemainder() == savedRemainder && heat(level, 0).energyContainer().getAmountAsLong() == 1,
                  "placing dropped heat generator restores FE and fractional credit");
            setTemperature(heat(level, 0), ambient[0]);
        }
        if (ticks < 201) return false;
        check.accept(bio(level, 4).bioFuelTank.isEmpty() && bio(level, 4).energyContainer().getAmountAsLong() == 28000,
              "one biofuel item burns for 200 ticks and yields exactly 28000 FE");
        complete = true;
        return true;
    }

    private void setTemperatureForTransaction(TileEntityHeatGenerator tile, double temperature, Transaction tx) {
        tile.getHeatCapacitor().setHeat(temperature * tile.getHeatCapacitor().getHeatCapacity(), tx);
    }

    Map<String, Object> snapshot(ServerLevel level) {
        return Map.of("heatRemainder", heat(level, 0).getGenerationRemainder(), "heatFE", heat(level, 0).energyContainer().getAmountAsLong(),
              "bioFE", bio(level, 4).energyContainer().getAmountAsLong(), "bioBlockRemaining", bio(level, 5).bioFuelTank.amountAsLong());
    }
}
