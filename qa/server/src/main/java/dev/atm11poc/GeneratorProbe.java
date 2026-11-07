package dev.atm11poc;

import java.util.function.BiConsumer;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.tile.TileEntitySolarGenerator;
import mekanism.generators.common.tile.TileEntityWindGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Isolated, disconnected generators: real tick output, buffers and persisted energy. */
final class GeneratorProbe {
    private static final String[] IDS = {"heat_generator", "bio_generator", "solar_generator", "advanced_solar_generator", "wind_generator", "wind_generator"};
    private static final long[] CAPACITIES = {64000, 64000, 38400, 1000000, 80000, 80000};
    private static final BlockPos[] POSITIONS = {new BlockPos(8, 200, 112), new BlockPos(16, 200, 112),
          new BlockPos(24, 200, 112), new BlockPos(32, 200, 112), new BlockPos(40, 20, 112), new BlockPos(48, 315, 112)};
    private final BiConsumer<Boolean, String> check;
    private final long[] generated = new long[6];

    GeneratorProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    void prepare(ServerLevel level, boolean reopen) {
        var config = MekanismGeneratorsConfig.generators;
        check.accept(config.solarGeneration.get() == 60 && config.advancedSolarGeneration.get() == 480,
              "ATM10 solar ratings are 60/480 FE per tick");
        check.accept(config.windGenerationMin.get() == 60 && config.windGenerationMax.get() == 360,
              "ATM10 wind rating spans 60 to 360 FE per tick");
        for (int i = 0; i < IDS.length; i++) {
            BlockPos pos = POSITIONS[i];
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            if (!reopen) level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.getValue(Identifier.parse("mekanismgenerators:" + IDS[i])).defaultBlockState());
            var tile = tile(level, i);
            check.accept(tile != null && tile.getEnergyContainer().getCapacityAsLong() == CAPACITIES[i], "ATM10 generator capacity " + IDS[i] + " at " + pos);
            if (reopen) check.accept(tile.getEnergyContainer().getAmountAsLong() == 12345, "generator retains exact stored FE across JVM restart at " + pos);
        }
    }

    boolean tick(ServerLevel level, int elapsed, boolean reopen) {
        if (reopen) return true;
        if (elapsed <= 100) {
            for (int i = 2; i < IDS.length; i++) {
                var tile = tile(level, i);
                generated[i] += tile instanceof TileEntitySolarGenerator solar ? solar.getProductionRate() : ((TileEntityWindGenerator) tile).getCurrentGeneration();
            }
        }
        if (elapsed == 100) {
            for (int i = 2; i < IDS.length; i++) {
                check.accept(generated[i] > 1000, "generator produces restored-scale FE through normal ticks at " + POSITIONS[i]);
                check.accept(tile(level, i).getEnergyContainer().getAmountAsLong() == generated[i], "generator stores exact sum of actual tick output at " + POSITIONS[i]);
            }
            check.accept(((TileEntityWindGenerator) tile(level, 4)).getCurrentGeneration() == 60, "wind at minimum height produces 60 FE/t");
            check.accept(((TileEntityWindGenerator) tile(level, 5)).getCurrentGeneration() == 360, "wind at maximum height produces 360 FE/t");
            for (BlockPos pos : POSITIONS) {
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(pos.offset(x, 4, z), Blocks.STONE.defaultBlockState());
            }
        }
        if (elapsed == 130) {
            for (int i = 0; i < IDS.length; i++) tile(level, i).getEnergyContainer().setEnergy(12345, null);
        }
        return elapsed >= 130;
    }

    private TileEntityMekanism tile(ServerLevel level, int index) {
        return (TileEntityMekanism) level.getBlockEntity(POSITIONS[index]);
    }
}
