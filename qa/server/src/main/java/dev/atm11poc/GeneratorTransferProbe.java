package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismChemicals;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import mekanism.generators.common.tile.TileEntityGasGenerator;
import mekanism.generators.common.tile.TileEntityGenerator;
import mekanism.generators.common.tile.TileEntityWindGenerator;
import mekanism.generators.common.tile.TileEntitySolarGenerator;
import mekanism.generators.common.registries.GeneratorsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class GeneratorTransferProbe {
    private static final BlockPos START = new BlockPos(8, 80, 288);
    private static final String[] IDS = {"heat_generator", "bio_generator", "solar_generator", "advanced_solar_generator", "wind_generator", "gas_burning_generator", "wind_generator"};
    private final BiConsumer<Boolean, String> check;
    private final List<List<BlockPos>> receivers = new ArrayList<>();
    private final int[] budgets = new int[7];
    private final long[] initial = new long[7];
    private JsonObject saved;
    private int stage;
    private int ticks;
    private long gasBefore;
    private long gasReceiverBefore;
    private int gasBudget;

    GeneratorTransferProbe(BiConsumer<Boolean, String> check) { this.check = check; }

    private BlockPos pos(int i) { return START.offset(i * 8, 0, 0); }
    private TileEntityGenerator source(ServerLevel level, int i) { return (TileEntityGenerator) level.getBlockEntity(pos(i)); }
    private TileEntityEnergyCube cube(ServerLevel level, BlockPos pos) { return (TileEntityEnergyCube) level.getBlockEntity(pos); }
    private TileEntityGasGenerator gas(ServerLevel level) { return (TileEntityGasGenerator) source(level, 5); }
    private TileEntityUniversalCable cable(ServerLevel level, int offset) { return (TileEntityUniversalCable) level.getBlockEntity(pos(6).north(offset)); }

    void prepare(ServerLevel level, JsonObject saved) {
        this.saved = saved;
        for (int i = 0; i < IDS.length; i++) {
            var pos = pos(i);
            for (int x = (pos.getX() - 2) >> 4; x <= (pos.getX() + 2) >> 4; x++) {
                for (int z = (pos.getZ() - 5) >> 4; z <= pos.getZ() >> 4; z++) level.setChunkForced(x, z, true);
            }
            if (saved == null) {
                level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.getValue(Identifier.parse("mekanismgenerators:" + IDS[i])).defaultBlockState());
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlockAndUpdate(pos.offset(dx, 6, dz), Blocks.STONE.defaultBlockState());
            }
            check.accept(source(level, i).getDirection() == Direction.NORTH, "generator transfer fixture has known output orientation " + i);
            List<BlockPos> targets = new ArrayList<>();
            if (i == 6) {
                if (saved == null) for (int d = 1; d <= 2; d++) level.setBlockAndUpdate(pos.north(d), MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState());
                targets.add(pos.north(3));
            } else {
                targets.add(i == 2 ? pos.below() : pos.north());
                if (i == 3 || i == 4) targets.add(pos.below());
            }
            receivers.add(targets);
            if (saved == null) for (var target : targets) {
                level.setBlockAndUpdate(target, MekanismBlocks.ULTIMATE_ENERGY_CUBE.defaultState());
                var config = cube(level, target).getConfig().getConfig(TransmissionType.ENERGY);
                for (var side : RelativeSide.values()) {
                    if (config.setDataType(DataType.INPUT, side)) cube(level, target).getConfig().sideChanged(TransmissionType.ENERGY, side);
                }
                config.setEjecting(false);
            }
        }
    }

    private long received(ServerLevel level, int i) {
        long total = 0;
        for (var target : receivers.get(i)) total += cube(level, target).energyContainer().getAmountAsLong();
        if (i == 6) total += cable(level, 1).getTransmitter().getBufferWithFallback();
        return total;
    }

    private static final class SolarClimateAccess extends TileEntitySolarGenerator {
        private SolarClimateAccess() { super(BlockPos.ZERO, GeneratorsBlocks.SOLAR_GENERATOR.defaultState()); }
        static float peak(ServerLevel level, BlockPos pos) { return new SolarCheck(level, pos).getPeakMultiplier(); }
    }

    private float peak(ServerLevel level, BlockPos pos) { return SolarClimateAccess.peak(level, pos); }

    private int expectedBudget(ServerLevel level, int i) {
        if (i == 0) return 888;
        if (i == 1) return 280;
        if (i == 2) return 2 * (int) (60 * peak(level, pos(i)));
        if (i == 3) {
            float total = peak(level, pos(i).above(2));
            for (int j = 0; j < 8; j++) {
                BlockPos panel = j < 3 ? pos(i).above(2).offset(-1, 0, j - 1) :
                      j == 3 ? pos(i).above(2).north() : j == 4 ? pos(i).above(2).south() : pos(i).above(2).offset(1, 0, j - 6);
                total += peak(level, panel);
            }
            return 2 * (int) (480 * (total / 9));
        }
        return i == 5 ? 160 : 720;
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (stage == 5) return true;
        if (stage == 0) {
            for (int i = 0; i < IDS.length; i++) if (source(level, i).ticker < 40) {
                if (elapsed > 200) throw new IllegalStateException("Generator transfer chunks did not start ticking");
                return false;
            }
            if (!cable(level, 1).getTransmitter().hasTransmitterNetwork() || !cable(level, 2).getTransmitter().hasTransmitterNetwork()) return false;
            check.accept(cable(level, 1).getTransmitter().getTransmitterNetwork() == cable(level, 2).getTransmitter().getTransmitterNetwork(),
                  "two physical universal cables form one shared energy network");
            check.accept(cable(level, 2).getTransmitter().getTransmitterNetworkNN().hasAcceptor(receivers.get(6).getFirst()),
                  "cable network discovers the configured energy cube input");
            if (saved != null) {
                for (int i = 0; i < IDS.length; i++) {
                    check.accept(source(level, i).energyContainer().getAmountAsLong() == saved.getAsJsonObject("fixture" + i).get("source").getAsLong(),
                          "generator transfer checkpoint restores exact source FE " + i);
                    check.accept(received(level, i) == saved.getAsJsonObject("fixture" + i).get("received").getAsLong(),
                          "generator transfer checkpoint restores receiver plus network FE " + i);
                }
                check.accept(gas(level).getMaxOutput() == 240, "restarted ethene generator reconstructs its initial burn-step transfer limit");
                source(level, 6).setControlType(RedstoneControl.DISABLED);
                initial[6] = source(level, 6).energyContainer().getAmountAsLong();
                gasReceiverBefore = received(level, 6);
                stage = 4;
                return false;
            }
            for (int i = 0; i < IDS.length; i++) {
                if (source(level, i) instanceof TileEntityWindGenerator wind) check.accept(wind.getCurrentGeneration() == 0,
                      "roof prevents new wind energy during transfer accounting " + i);
                if (source(level, i) instanceof TileEntitySolarGenerator solar) check.accept(solar.getProductionRate() == 0,
                      "roof prevents new solar energy during transfer accounting " + i);
                budgets[i] = expectedBudget(level, i);
                check.accept(source(level, i).getMaxOutput() == budgets[i], "ATM10 automatic output budget restored for " + IDS[i] + " fixture " + i);
                initial[i] = source(level, i).energyContainer().getCapacityAsLong();
                source(level, i).energyContainer().setEnergy(initial[i], null);
                check.accept(received(level, i) == 0, "generator receiver starts empty " + i);
            }
            stage = 1;
            return false;
        }
        if (stage == 4) {
            check.accept(initial[6] - source(level, 6).energyContainer().getAmountAsLong() == 720,
                  "restarted generator resumes the same 720 FE shared automatic output limit");
            check.accept(received(level, 6) - gasReceiverBefore == 720,
                  "restarted real cable network conserves the resumed generator transfer");
            source(level, 6).setControlType(RedstoneControl.HIGH);
            stage = 5;
            return true;
        }
        if (stage == 1) {
            ticks++;
            for (int i = 0; i < IDS.length; i++) {
                check.accept(initial[i] - source(level, i).energyContainer().getAmountAsLong() == (long) budgets[i] * ticks,
                      "normal generator tick obeys aggregate automatic output budget " + i + " tick " + ticks);
                check.accept(received(level, i) == (long) budgets[i] * ticks,
                      "actual receivers plus cable buffer conserve generated transfer " + i + " tick " + ticks);
            }
            if (ticks < 10) return false;
            for (int i : new int[]{3, 4}) for (var target : receivers.get(i)) check.accept(cube(level, target).energyContainer().getAmountAsLong() > 0,
                  "both physical output faces receive a share of one common budget " + i);
            check.accept(cube(level, receivers.get(6).getFirst()).energyContainer().getAmountAsLong() > 0,
                  "automatic generator output reaches an actual receiver through universal cables: " + cable(level, 1).getTransmitter().getTransmitterNetworkNN()
                        + " network=" + cable(level, 1).getTransmitter().getContainer().getAmountAsLong()
                        + " local1=" + cable(level, 1).getTransmitter().buffer.getAmountAsLong()
                        + " local2=" + cable(level, 2).getTransmitter().buffer.getAmountAsLong()
                        + " running=" + level.getServer().tickRateManager().runsNormally()
                        + " endpointAcceptor=" + cable(level, 2).getTransmitter().getAcceptor(Direction.NORTH));
            var fuel = ChemicalResource.of(MekanismChemicals.ETHENE);
            try (var tx = Transaction.openRoot()) {
                check.accept(gas(level).getFuelTank().insert(fuel, 18000, tx, AutomationType.EXTERNAL) == 18000,
                      "gas transfer fixture accepts fuel in a provisional transaction");
                check.accept(gas(level).getMaxOutput() == 160, "provisional fuel does not mutate the committed generator output limit");
            }
            check.accept(gas(level).getFuelTank().isEmpty() && gas(level).getMaxOutput() == 160,
                  "aborted fuel insertion preserves the empty-tank output limit");
            gas(level).getFuelTank().setContents(fuel, 18000, null);
            check.accept(gas(level).getMaxOutput() == 240, "committed ethene fuel establishes the 120 FE burn-step output basis");
            gas(level).energyContainer().setEnergy(0, null);
            stage = 2;
            return false;
        }
        if (stage == 2) {
            check.accept(gas(level).getProductionRate() == 30720 && gas(level).getMaxOutput() == 61440,
                  "full-pressure gas generation raises automatic output to twice actual 30720 FE production");
            gas(level).energyContainer().setEnergy(80000, null);
            gasBefore = 80000;
            gasReceiverBefore = received(level, 5);
            gasBudget = gas(level).getMaxOutput();
            stage = 3;
            return false;
        }
        check.accept(received(level, 5) - gasReceiverBefore == gasBudget,
              "gas generator emits its updated 61440 FE budget to a real receiver");
        check.accept(gasBefore + gas(level).getProductionRate() - gas(level).energyContainer().getAmountAsLong() == gasBudget,
              "dynamic gas transfer conserves buffer plus simultaneous fuel generation");
        for (int i = 0; i < IDS.length; i++) source(level, i).setControlType(RedstoneControl.HIGH);
        stage = 5;
        return true;
    }

    Map<String, Object> snapshot(ServerLevel level) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < IDS.length; i++) result.put("fixture" + i, Map.of("source", source(level, i).energyContainer().getAmountAsLong(),
              "received", received(level, i), "budget", source(level, i).getMaxOutput()));
        return result;
    }
}
