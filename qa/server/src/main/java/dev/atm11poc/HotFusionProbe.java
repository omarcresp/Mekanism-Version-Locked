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
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.laser.TileEntityLaser;
import mekanism.common.tile.laser.TileEntityLaserAmplifier;
import mekanism.common.tile.multiblock.TileEntityInductionCasing;
import mekanism.common.tile.multiblock.TileEntityInductionCell;
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
    enum Cooling { AIR, WATER }
    private final BlockPos START;
    private final BlockPos LASER;
    private final BlockPos SOURCE;
    private final BlockPos MATRIX;
    private final BlockPos CUBE;
    private final FusionCoolingLoop cooling;
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
    private long injectedPerFuel;
    private long initialEnergy;
    private double previousFraction;
    private double firstPlasma;
    private double firstCase;
    private boolean observedRestart;
    private boolean ignited;

    HotFusionProbe(BiConsumer<Boolean, String> check) { this(check, Cooling.AIR); }

    HotFusionProbe(BiConsumer<Boolean, String> check, Cooling mode) {
        this.check = (ok, message) -> check.accept(ok, mode.name().toLowerCase(java.util.Locale.ROOT) + " " + message);
        START = new BlockPos(80, 80, mode == Cooling.AIR ? 680 : 960);
        LASER = START.offset(-3, 2, 2);
        SOURCE = LASER.offset(0, 0, -3);
        MATRIX = START.offset(10, 0, 1);
        CUBE = MATRIX.offset(4, 2, 1);
        cooling = mode == Cooling.WATER ? new FusionCoolingLoop(START, this.check) : null;
    }

    private static final class Builder extends GeneratorsBuilders.FusionReactorBuilder {
        void place(Level level, BlockPos start) { build(level, start, false); }
    }

    void prepare(ServerLevel level, JsonObject saved) {
        this.saved = saved;
        if (saved != null) previousFraction = saved.get("fraction").getAsDouble();
        for (int x = 4; x <= 6; x++) for (int z = (START.getZ() - 4) >> 4; z <= (START.getZ() + 4) >> 4; z++) level.setChunkForced(x, z, true);
        if (saved != null) {
            if (cooling != null) {
                cooling.prepare(level, true);
                cooling.restore(saved.getAsJsonObject("cooling").get("coolant").getAsLong());
            }
            return;
        }
        new Builder().place(level, START);
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
        level.setBlockAndUpdate(MATRIX.offset(2, 2, 1), MekanismBlocks.INDUCTION_PORT.defaultState());
        ((TileEntityInductionCasing) level.getBlockEntity(MATRIX.offset(2, 2, 1))).setActive(true);
        level.setBlockAndUpdate(MATRIX.offset(3, 2, 1), MekanismBlocks.ULTIMATE_UNIVERSAL_CABLE.defaultState());
        level.setBlockAndUpdate(CUBE, MekanismBlocks.ULTIMATE_ENERGY_CUBE.defaultState());
        var cube = (TileEntityEnergyCube) level.getBlockEntity(CUBE);
        var config = cube.getConfig().getConfig(TransmissionType.ENERGY);
        for (var side : RelativeSide.values()) if (config.setDataType(DataType.INPUT, side)) cube.getConfig().sideChanged(TransmissionType.ENERGY, side);
        config.setEjecting(false);
        if (cooling != null) cooling.prepare(level, false);
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
        return (cooling == null ? 0 : cooling.energy(level)) + reactor(level).energyContainer().getAmountAsLong() + matrix(level).getEnergy()
              + ((TileEntityEnergyCube) level.getBlockEntity(CUBE)).energyContainer().getAmountAsLong()
              + ((TileEntityUniversalCable) level.getBlockEntity(MATRIX.offset(3, 2, 1))).getTransmitter().getBufferWithFallback()
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
        return ((TileEntityUniversalCable) level.getBlockEntity(MATRIX.offset(3, 2, 1))).getTransmitter().hasTransmitterNetwork();
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
        if (cooling != null) generated += cooling.tick(level, r);
        previousFraction = converted - Math.floor(converted);
        checkFraction(r);
        if (r.isBurning() && r.lastEnvironmentLoss > 0 && r.getInjectionRate() == 4) injectedPerFuel += 2;
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
            if (elapsed < 40 || !matrix(level).isFormed() || !networksReady(level) || (cooling != null && !cooling.ready(level))) {
                if (elapsed > 200) throw new IllegalStateException("Hot fusion transport or cooling circuit did not form");
                return false;
            }
            check.accept(matrix(level).getCellCount() == 1 && matrix(level).getProviderCount() == 1, "hot fusion: physical induction matrix forms with cell and provider");
            if (saved == null) {
                check.accept(energy(level) == 0, "induction: active output port cannot create energy while the matrix is empty");
                checkMatrixTransactions(level);
                check.accept(fuel(level, 1) == 50000 && fuel(level, 3) == 50000, "hot fusion: separate physical feeds conserve both initial fuel charges");
                check.accept(r.deuteriumTank.amountAsLong() == 500 && r.tritiumTank.amountAsLong() == 500,
                      "hot fusion: tubes fill reactor through actual chemical input ports");
                var hohlraum = ContainerType.CHEMICAL.getFilledVariant(GeneratorsItems.HOHLRAUM, GeneratorsChemicals.FUSION_FUEL, null);
                r.getInventorySlots().getFirst().setContents(ItemResource.of(hohlraum), 1, null);
                r.setInjectionRate(4);
                if (cooling != null) cooling.charge(level, r);
                var amplifier = (TileEntityLaserAmplifier) level.getBlockEntity(LASER);
                amplifier.setMinThresholdFromPacket(IGNITION_FE);
                amplifier.setMaxThresholdFromPacket(IGNITION_FE);
                initialEnergy = energy(level);
                stage = 1;
            } else {
                check.accept(energy(level) == saved.get("energy").getAsLong() + generated, "hot fusion: exact aggregate FE restores with independently counted boot generation"
                      + " (actual=" + energy(level) + ", saved=" + saved.get("energy").getAsLong() + ", boot=" + generated + ")");
                check.accept(fuel(level, 1) == saved.get("deuterium").getAsLong() - injectedPerFuel
                            && fuel(level, 3) == saved.get("tritium").getAsLong() - injectedPerFuel,
                      "hot fusion: exact saved fuel inventories restore after counted boot injection");
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
                check.accept(((TileEntityEnergyCube) level.getBlockEntity(CUBE)).energyContainer().getAmountAsLong() > 0, "hot fusion: physical induction output and cable deliver FE into receiver at " + measuredTicks);
            }
            if (measuredTicks < 200) return false;
            if (saved == null) { stage = 4; return true; }
            if (cooling == null) {
                r.setInjectionRate(0);
            } else {
                cooling.verify(level, r);
                for (int x : new int[]{1, 3}) {
                    var tube = ((TileEntityPressurizedTube) level.getBlockEntity(START.offset(x, 2, -1))).getTransmitter();
                    tube.setConnectionTypeRaw(Direction.SOUTH, mekanism.common.lib.transmitter.ConnectionType.NONE);
                    tube.onModeChange(Direction.SOUTH);
                    tube.refreshConnections();
                    tube.notifyTileChange();
                }
            }
            initialDeuterium = fuel(level, 1);
            initialTritium = fuel(level, 3);
            stage = 3;
            return false;
        }
        if (stage == 3) {
            shutdownTicks++;
            if (cooling != null) {
                if (r.isBurning()) {
                    if (shutdownTicks > 300) throw new IllegalStateException("Disconnected fusion fuel feeds did not starve reactor");
                    return false;
                }
                check.accept(r.getInjectionRate() == 4 && r.deuteriumTank.isEmpty() && r.tritiumTank.isEmpty() && r.fuelTank.isEmpty(),
                      "water-cooled fusion: disconnected physical feeds exhaust stored fuel and stop burning without a commanded shutdown");
                cooling.verify(level, r);
                stage = 4;
                return true;
            }
            if (shutdownTicks < 20) return false;
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

    private void checkMatrixTransactions(ServerLevel level) {
        var matrix = matrix(level);
        var container = matrix.energyContainer();
        var cell = ((TileEntityInductionCell) level.getBlockEntity(MATRIX.offset(1, 1, 1))).energyContainer();
        check.accept(container.getAmountAsLong() == 0 && cell.getAmountAsLong() == 0, "induction: empty matrix and physical cell agree");
        try (var tx = Transaction.openRoot()) {
            check.accept(container.insert(12345, tx, AutomationType.EXTERNAL) == 12345, "induction: real matrix accepts committed input");
            tx.commit();
        }
        check.accept(cell.getAmountAsLong() == 12345 && container.getAmountAsLong() == 12345,
              "induction: committed energy reaches persistable cell before another world tick");
        try (var tx = Transaction.openRoot()) {
            check.accept(container.extract(345, tx, AutomationType.EXTERNAL) == 345 && cell.getAmountAsLong() == 12000,
                  "induction: transactional extraction updates the actual cell immediately");
        }
        check.accept(cell.getAmountAsLong() == 12345 && container.getAmountAsLong() == 12345,
              "induction: aborted extraction restores cell, aggregate and transfer budget");
        try (var tx = Transaction.openRoot()) {
            try (var nested = Transaction.open(tx)) {
                check.accept(container.extract(345, nested, AutomationType.EXTERNAL) == 345, "induction: nested extraction sees restored output budget");
                nested.commit();
            }
        }
        check.accept(cell.getAmountAsLong() == 12345 && container.getAmountAsLong() == 12345,
              "induction: outer abort restores a committed nested cell transfer");
        try (var tx = Transaction.openRoot()) {
            long remaining = matrix.getTransferCap() - 12345;
            check.accept(container.insert(Integer.MAX_VALUE, tx, AutomationType.EXTERNAL) == remaining
                        && container.insert(1, tx, AutomationType.EXTERNAL) == 0,
                  "induction: immediate cell storage preserves the shared input throughput limit");
        }
        check.accept(container.getAmountAsLong() == 12345 && cell.getAmountAsLong() == 12345,
              "induction: aborted full-budget input restores persistable storage");
        try (var tx = Transaction.openRoot()) {
            check.accept(container.extract(12345, tx, AutomationType.EXTERNAL) == 12345, "induction: full committed extraction uses restored budget");
            tx.commit();
        }
        check.accept(cell.getAmountAsLong() == 0 && container.getAmountAsLong() == 0,
              "induction: committed extraction is reflected in persistable cell immediately");
        long capacity = container.getCapacityAsLong();
        container.removeInternal(MATRIX.offset(1, 1, 1));
        container.removeInternal(MATRIX.offset(1, 1, 1));
        check.accept(container.getCapacityAsLong() == 0 && container.getAmountAsLong() == 0,
              "induction: removing a cell twice subtracts capacity once");
        container.addCell(MATRIX.offset(1, 1, 1), (TileEntityInductionCell) level.getBlockEntity(MATRIX.offset(1, 1, 1)));
        check.accept(container.getCapacityAsLong() == capacity, "induction: reattaching physical cell restores exact capacity");
    }

    private void checkSupply(boolean ok) {
        if (!ok) throw new IllegalStateException("Hot fusion powered laser rejected bounded electrical supply");
    }

    Map<String, Object> snapshot(ServerLevel level) {
        var r = reactor(level);
        check.accept(energy(level) == initialEnergy + generated, "hot fusion: complete electrical ledger remains conserved until final save");
        check.accept(fuel(level, 1) == (saved == null ? 50000 : saved.get("deuterium").getAsLong()) - injectedPerFuel
                    && fuel(level, 3) == (saved == null ? 50000 : saved.get("tritium").getAsLong()) - injectedPerFuel,
              "hot fusion: complete separate fuel ledgers remain conserved until final save");
        if (saved == null) check.accept(ignited && r.isBurning() && r.getPlasmaTemp() > FusionReactorMultiblockData.BURN_TEMPERATURE,
              "hot fusion: seed saves the physically ignited reactor while hot and burning");
        var out = new LinkedHashMap<String, Object>();
        if (cooling != null) out.put("cooling", cooling.snapshot(level, r));
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
        out.put("cubeEnergy", ((TileEntityEnergyCube) level.getBlockEntity(CUBE)).energyContainer().getAmountAsLong());
        out.put("generated", generated);
        out.put("injectedPerFuel", injectedPerFuel);
        out.put("laserSupply", suppliedFE);
        out.put("firstRestartPlasma", firstPlasma);
        out.put("firstRestartCase", firstCase);
        return out;
    }
}
