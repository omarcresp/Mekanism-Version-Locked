package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.content.matrix.MatrixMultiblockData;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.TileEntityChemicalTank;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.laser.TileEntityLaser;
import mekanism.common.tile.laser.TileEntityLaserAmplifier;
import mekanism.common.tile.multiblock.TileEntityInductionCasing;
import mekanism.common.tile.transmitter.TileEntityPressurizedTube;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import mekanism.generators.common.content.fusion.FusionReactorMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.registries.GeneratorsChemicals;
import mekanism.generators.common.registries.GeneratorsItems;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorController;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorPort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Saves real laser-ignited fusion with external fuel feeds and a physical power receiver. */
final class HotFusionProbe {
    private static final BlockPos START = new BlockPos(80, 80, 680);
    private static final BlockPos LASER = START.offset(-3, 2, 2);
    private static final BlockPos SOURCE = LASER.offset(0, 0, -3);
    private static final BlockPos MATRIX = START.offset(10, 0, 1);
    private static final int IGNITION_FE = 400000000;
    private final BiConsumer<Boolean, String> check;
    private JsonObject saved;
    private int stage;
    private int suppliedFE;
    private int measuredTicks;
    private int shutdownTicks;
    private long initialDeuterium;
    private long initialTritium;
    private long generated;
    private long initialEnergy;
    private double previousFraction;
    private double firstPlasma;
    private double firstCase;
    private boolean observedRestart;
    private boolean ignited;

    HotFusionProbe(BiConsumer<Boolean, String> check) { this.check = check; }

    private static final class Builder extends GeneratorsBuilders.FusionReactorBuilder {
        void place(Level level) { build(level, START, false); }
    }

    void prepare(ServerLevel level, JsonObject saved) {
        this.saved = saved;
        if (saved != null) previousFraction = saved.get("fraction").getAsDouble();
        for (int x = 4; x <= 6; x++) for (int z = 42; z <= 43; z++) level.setChunkForced(x, z, true);
        if (saved != null) return;
        new Builder().place(level);
        level.setBlockAndUpdate(START.offset(0, 2, 2), GeneratorsBlocks.LASER_FOCUS_MATRIX.defaultState());
        level.setBlockAndUpdate(LASER, Attribute.setFacing(MekanismBlocks.LASER_AMPLIFIER.defaultState(), Direction.EAST));
        level.setBlockAndUpdate(SOURCE, Attribute.setFacing(MekanismBlocks.LASER.defaultState(), Direction.SOUTH));
        for (int x : new int[]{1, 3}) {
            level.setBlockAndUpdate(START.offset(x, 2, 0), GeneratorsBlocks.FUSION_REACTOR_PORT.defaultState());
            for (int z = -3; z < 0; z++) level.setBlockAndUpdate(START.offset(x, 2, z), MekanismBlocks.ULTIMATE_PRESSURIZED_TUBE.defaultState());
            BlockPos pos = START.offset(x, 2, -4);
            level.setBlockAndUpdate(pos, MekanismBlocks.ULTIMATE_CHEMICAL_TANK.defaultState());
            var tank = tank(level, x);
            var config = tank.getConfig().getConfig(TransmissionType.CHEMICAL);
            for (var side : RelativeSide.values()) if (config.setDataType(DataType.OUTPUT, side)) tank.getConfig().sideChanged(TransmissionType.CHEMICAL, side);
            config.setEjecting(true);
            tank.getChemicalTank().setContents(x == 1 ? GeneratorsChemicals.DEUTERIUM.asResource() : GeneratorsChemicals.TRITIUM.asResource(), 50000, null);
        }
        level.setBlockAndUpdate(START.offset(4, 1, 2), GeneratorsBlocks.FUSION_REACTOR_PORT.defaultState());
        ((TileEntityFusionReactorPort) level.getBlockEntity(START.offset(4, 1, 2))).setActive(true);
        for (int x = 5; x < 10; x++) level.setBlockAndUpdate(START.offset(x, 1, 2), MekanismBlocks.ULTIMATE_UNIVERSAL_CABLE.defaultState());
        for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) {
            boolean wall = x == 0 || x == 2 || y == 0 || y == 3 || z == 0 || z == 2;
            level.setBlockAndUpdate(MATRIX.offset(x, y, z), wall ? MekanismBlocks.INDUCTION_CASING.defaultState() : Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(MATRIX.offset(1, 1, 1), MekanismBlocks.BASIC_INDUCTION_CELL.defaultState());
        level.setBlockAndUpdate(MATRIX.offset(1, 2, 1), MekanismBlocks.ADVANCED_INDUCTION_PROVIDER.defaultState());
        level.setBlockAndUpdate(MATRIX.offset(0, 1, 1), MekanismBlocks.INDUCTION_PORT.defaultState());
    }

    private FusionReactorMultiblockData reactor(ServerLevel level) {
        return ((TileEntityFusionReactorController) level.getBlockEntity(START.offset(2, 4, 2))).getMultiblock();
    }

    private MatrixMultiblockData matrix(ServerLevel level) {
        return ((TileEntityInductionCasing) level.getBlockEntity(MATRIX)).getMultiblock();
    }

    private TileEntityChemicalTank tank(ServerLevel level, int x) {
        return (TileEntityChemicalTank) level.getBlockEntity(START.offset(x, 2, -4));
    }

    private long fuel(ServerLevel level, int x) {
        var tube = (TileEntityPressurizedTube) level.getBlockEntity(START.offset(x, 2, -3));
        var r = reactor(level);
        return tank(level, x).getChemicalTank().amountAsLong() + tube.getTransmitter().getBufferWithFallback().amount()
              + (x == 1 ? r.deuteriumTank.amountAsLong() : r.tritiumTank.amountAsLong());
    }

    private long energy(ServerLevel level) {
        return reactor(level).energyContainer().getAmountAsLong() + matrix(level).getEnergy()
              + ((TileEntityUniversalCable) level.getBlockEntity(START.offset(5, 1, 2))).getTransmitter().getBufferWithFallback();
    }

    private boolean networksReady(ServerLevel level) {
        for (int x : new int[]{1, 3}) {
            var first = ((TileEntityPressurizedTube) level.getBlockEntity(START.offset(x, 2, -3))).getTransmitter();
            if (!first.hasTransmitterNetwork()) return false;
            for (int z = -2; z < 0; z++) if (((TileEntityPressurizedTube) level.getBlockEntity(START.offset(x, 2, z))).getTransmitter().getTransmitterNetwork() != first.getTransmitterNetwork()) return false;
        }
        var first = ((TileEntityUniversalCable) level.getBlockEntity(START.offset(5, 1, 2))).getTransmitter();
        if (!first.hasTransmitterNetwork()) return false;
        for (int x = 6; x < 10; x++) if (((TileEntityUniversalCable) level.getBlockEntity(START.offset(x, 1, 2))).getTransmitter().getTransmitterNetwork() != first.getTransmitterNetwork()) return false;
        return true;
    }

    boolean tick(ServerLevel level, int elapsed) {
        var r = reactor(level);
        if (!r.isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Hot fusion reactor did not form");
            return false;
        }
        // Account from the first formed observation, including generation before networks reconnect.
        double converted = Math.floor(Math.max(0, r.lastEnvironmentLoss) * 0.04) / 2.5 + previousFraction;
        generated += (long) converted;
        previousFraction = converted - Math.floor(converted);
        checkFraction(r);
        if (saved != null && !observedRestart) {
            firstPlasma = r.getPlasmaTemp();
            firstCase = r.getHeatCapacitor().getTemperature();
            check.accept(r.isBurning() && r.getInjectionRate() == 4, "hot fusion: burning state and injection rate restore before QA changes");
            check.accept(firstPlasma > FusionReactorMultiblockData.BURN_TEMPERATURE
                        && Math.abs(firstPlasma - saved.get("plasma").getAsDouble()) < saved.get("plasma").getAsDouble() * 0.05,
                  "hot fusion: first formed tick restores hot plasma (temperature=" + firstPlasma + ")");
            check.accept(firstCase > 100000 && Math.abs(firstCase - saved.get("case").getAsDouble()) < saved.get("case").getAsDouble() * 0.05,
                  "hot fusion: first formed tick restores hot casing (temperature=" + firstCase + ")");
            check.accept(r.getInventorySlots().getFirst().isEmpty(), "hot fusion: resumed burning needs no replacement hohlraum");
            observedRestart = true;
        }
        if (stage == 0) {
            if (elapsed < 40 || !matrix(level).isFormed() || !networksReady(level)) return false;
            check.accept(matrix(level).getCellCount() == 1 && matrix(level).getProviderCount() == 1, "hot fusion: physical induction matrix forms with cell and provider");
            if (saved == null) {
                check.accept(fuel(level, 1) == 50000 && fuel(level, 3) == 50000, "hot fusion: separate physical feeds conserve both initial fuel charges");
                check.accept(r.deuteriumTank.amountAsLong() == 500 && r.tritiumTank.amountAsLong() == 500,
                      "hot fusion: tubes fill reactor through actual chemical input ports");
                var hohlraum = ContainerType.CHEMICAL.getFilledVariant(GeneratorsItems.HOHLRAUM, GeneratorsChemicals.FUSION_FUEL, null);
                r.getInventorySlots().getFirst().setContents(ItemResource.of(hohlraum), 1, null);
                r.setInjectionRate(4);
                var amplifier = (TileEntityLaserAmplifier) level.getBlockEntity(LASER);
                amplifier.setMinThresholdFromPacket(IGNITION_FE);
                amplifier.setMaxThresholdFromPacket(IGNITION_FE);
                initialEnergy = energy(level);
                stage = 1;
            } else {
                check.accept(energy(level) == saved.get("energy").getAsLong() + generated, "hot fusion: exact aggregate FE restores with independently counted boot generation");
                check.accept(fuel(level, 1) == fuel(level, 3), "hot fusion: both external fuel inventories resume symmetrically");
                initialEnergy = saved.get("energy").getAsLong();
                initialDeuterium = fuel(level, 1);
                initialTritium = fuel(level, 3);
                stage = 2;
            }
            return false;
        }
        if (stage == 1) {
            if (suppliedFE < IGNITION_FE) {
                var source = (TileEntityLaser) level.getBlockEntity(SOURCE);
                int amount = Math.min(20000, IGNITION_FE - suppliedFE);
                try (var tx = Transaction.openRoot()) {
                    checkSupply(source.getEnergyContainer().insert(amount, tx, AutomationType.EXTERNAL) == amount);
                    tx.commit();
                }
                suppliedFE += amount;
            }
            if (!r.isBurning()) return false;
            check.accept(suppliedFE == IGNITION_FE && r.getInventorySlots().getFirst().isEmpty(), "hot fusion: physical 400 million FE laser ignition consumes the hohlraum");
            check.accept(((TileEntityLaserAmplifier) level.getBlockEntity(LASER)).getEnergyContainer().isEmpty()
                        && ((TileEntityLaser) level.getBlockEntity(SOURCE)).getEnergyContainer().isEmpty(), "hot fusion: laser source and amplifier transfer their full budgets");
            initialDeuterium = fuel(level, 1);
            initialTritium = fuel(level, 3);
            ignited = true;
            stage = 2;
            return false;
        }
        if (stage == 2) {
            measuredTicks++;
            if (measuredTicks % 50 == 0) {
                check.accept(r.isBurning() && r.getPlasmaTemp() > FusionReactorMultiblockData.BURN_TEMPERATURE,
                      "hot fusion: plasma remains burning at measured tick " + measuredTicks);
                check.accept(initialDeuterium - fuel(level, 1) == measuredTicks * 2L
                            && initialTritium - fuel(level, 3) == measuredTicks * 2L,
                      "hot fusion: separate physical fuel feeds each supply two mB per tick at " + measuredTicks);
                check.accept(energy(level) == initialEnergy + generated, "hot fusion: physical electrical inventory equals thermal generation ledger at " + measuredTicks);
                check.accept(matrix(level).getEnergy() > 0, "hot fusion: output port and cables deliver actual FE into induction cell at " + measuredTicks);
            }
            if (measuredTicks < 200) return false;
            if (saved == null) { stage = 4; return true; }
            r.setInjectionRate(0);
            initialDeuterium = fuel(level, 1);
            initialTritium = fuel(level, 3);
            stage = 3;
            return false;
        }
        if (stage == 3) {
            if (++shutdownTicks < 20) return false;
            check.accept(!r.isBurning() && r.fuelTank.isEmpty(), "hot fusion: zero injection burns out the remaining mixed fuel and stops reaction");
            check.accept(fuel(level, 1) == initialDeuterium && fuel(level, 3) == initialTritium,
                  "hot fusion: zero injection preserves both externally supplied fuels");
            check.accept(r.getInventorySlots().getFirst().isEmpty(), "hot fusion: stopped reaction has no replacement ignition capsule");
            stage = 4;
        }
        return stage == 4;
    }

    private void checkFraction(FusionReactorMultiblockData r) {
        if (Math.abs(r.getGenerationRemainder() - previousFraction) > 1e-8) throw new IllegalStateException("Hot fusion fractional generation journal disagrees with thermal ledger");
    }

    private void checkSupply(boolean ok) {
        if (!ok) throw new IllegalStateException("Hot fusion powered laser rejected bounded electrical supply");
    }

    Map<String, Object> snapshot(ServerLevel level) {
        var r = reactor(level);
        check.accept(energy(level) == initialEnergy + generated, "hot fusion: complete electrical ledger remains conserved until final save");
        if (saved == null) check.accept(ignited && r.isBurning() && r.getPlasmaTemp() > FusionReactorMultiblockData.BURN_TEMPERATURE,
              "hot fusion: seed saves the physically ignited reactor while hot and burning");
        var out = new LinkedHashMap<String, Object>();
        out.put("burning", r.isBurning());
        out.put("injection", r.getInjectionRate());
        out.put("plasma", r.getPlasmaTemp());
        out.put("case", r.getHeatCapacitor().getTemperature());
        out.put("fraction", r.getGenerationRemainder());
        out.put("deuterium", fuel(level, 1));
        out.put("tritium", fuel(level, 3));
        out.put("mixedFuel", r.fuelTank.amountAsLong());
        out.put("energy", energy(level));
        out.put("matrixEnergy", matrix(level).getEnergy());
        out.put("generated", generated);
        out.put("laserSupply", suppliedFE);
        out.put("firstRestartPlasma", firstPlasma);
        out.put("firstRestartCase", firstCase);
        return out;
    }
}
