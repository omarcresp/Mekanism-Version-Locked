package dev.atm11poc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.RelativeSide;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.transmitter.TileEntityMechanicalPipe;
import mekanism.common.tile.transmitter.TileEntityPressurizedTube;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import mekanism.generators.common.content.fusion.FusionReactorMultiblockData;
import mekanism.generators.common.content.turbine.TurbineMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorPort;
import mekanism.generators.common.tile.turbine.TileEntityTurbineCasing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Physical fusion steam, turbine, condenser return and electrical receiver. */
final class FusionCoolingLoop {
    private final BlockPos start;
    private final BlockPos turbine;
    private final BlockPos cable;
    private final List<BlockPos> steam = new ArrayList<>();
    private final List<BlockPos> water = new ArrayList<>();
    private final BiConsumer<Boolean, String> check;
    private long initialWater;
    private long generated;
    private long previousWater;
    private boolean sawReturn;
    private boolean sawSteam;

    FusionCoolingLoop(BlockPos start, BiConsumer<Boolean, String> check) {
        this.start = start;
        this.check = check;
        turbine = start.offset(24, 0, 0);
        cable = turbine.offset(17, 1, 8);
        for (int z = 5; z <= 8; z++) steam.add(start.offset(2, 1, z));
        for (int x = 3; x < 24; x++) steam.add(start.offset(x, 1, 8));
        for (int x = 2; x < 24; x++) water.add(start.offset(x, 16, 8));
        for (int z = -1; z < 8; z++) water.add(start.offset(2, 16, z));
        for (int y = 1; y < 16; y++) water.add(start.offset(2, y, -1));
    }

    private static final class Builder extends GeneratorsBuilders.TurbineBuilder {
        void place(Level level, BlockPos pos) { build(level, pos, false); }
    }

    void prepare(ServerLevel level, boolean reopen) {
        for (int x = start.getX() >> 4; x <= (cable.getX() + 1) >> 4; x++) {
            for (int z = (start.getZ() - 1) >> 4; z <= (start.getZ() + 17) >> 4; z++) level.setChunkForced(x, z, true);
        }
        if (reopen) return;
        new Builder().place(level, turbine);
        level.setBlockAndUpdate(turbine.offset(0, 1, 8), GeneratorsBlocks.TURBINE_VALVE.defaultState());
        level.setBlockAndUpdate(turbine.offset(16, 1, 8), GeneratorsBlocks.TURBINE_VALVE.defaultState());
        level.setBlockAndUpdate(start.offset(2, 1, 4), GeneratorsBlocks.FUSION_REACTOR_PORT.defaultState());
        ((TileEntityFusionReactorPort) level.getBlockEntity(start.offset(2, 1, 4))).setActive(true);
        level.setBlockAndUpdate(start.offset(2, 1, 0), GeneratorsBlocks.FUSION_REACTOR_PORT.defaultState());
        for (var pos : steam) level.setBlockAndUpdate(pos, MekanismBlocks.ULTIMATE_PRESSURIZED_TUBE.defaultState());
        for (var pos : water) level.setBlockAndUpdate(pos, MekanismBlocks.ULTIMATE_MECHANICAL_PIPE.defaultState());
        level.setBlockAndUpdate(cable, MekanismBlocks.ULTIMATE_UNIVERSAL_CABLE.defaultState());
        level.setBlockAndUpdate(cable.east(), MekanismBlocks.ULTIMATE_ENERGY_CUBE.defaultState());
        var cube = (TileEntityEnergyCube) level.getBlockEntity(cable.east());
        var config = cube.getConfig().getConfig(TransmissionType.ENERGY);
        for (var side : RelativeSide.values()) if (config.setDataType(DataType.INPUT, side)) cube.getConfig().sideChanged(TransmissionType.ENERGY, side);
        config.setEjecting(false);
    }

    private TurbineMultiblockData turbine(ServerLevel level) {
        return ((TileEntityTurbineCasing) level.getBlockEntity(turbine)).getMultiblock();
    }

    boolean ready(ServerLevel level) {
        if (!turbine(level).isFormed()) return false;
        var firstSteam = ((TileEntityPressurizedTube) level.getBlockEntity(steam.getFirst())).getTransmitter();
        var firstWater = ((TileEntityMechanicalPipe) level.getBlockEntity(water.getFirst())).getTransmitter();
        if (!firstSteam.hasTransmitterNetwork() || !firstWater.hasTransmitterNetwork()) return false;
        for (var pos : steam) if (((TileEntityPressurizedTube) level.getBlockEntity(pos)).getTransmitter().getTransmitterNetwork() != firstSteam.getTransmitterNetwork()) return false;
        for (var pos : water) if (((TileEntityMechanicalPipe) level.getBlockEntity(pos)).getTransmitter().getTransmitterNetwork() != firstWater.getTransmitterNetwork()) return false;
        return ((TileEntityUniversalCable) level.getBlockEntity(cable)).getTransmitter().hasTransmitterNetwork();
    }

    void charge(ServerLevel level, FusionReactorMultiblockData reactor) {
        var fluid = FluidResource.of(Fluids.WATER);
        initialWater = reactor.waterTank.capacityAsLong(fluid);
        var input = level.getCapability(Capabilities.FLUID.block(), start.offset(2, 1, 0), Direction.NORTH);
        check.accept(input != null, "water-cooled fusion: real input port exposes water capability");
        try (var tx = Transaction.openRoot()) {
            check.accept(input.insert(fluid, (int) initialWater, tx) == initialWater, "water-cooled fusion: finite initial coolant charge enters real port");
            tx.commit();
        }
        previousWater = reactor.waterTank.amountAsLong();
    }

    long energy(ServerLevel level) {
        return turbine(level).energyContainer().getAmountAsLong()
              + ((TileEntityUniversalCable) level.getBlockEntity(cable)).getTransmitter().getBufferWithFallback()
              + ((TileEntityEnergyCube) level.getBlockEntity(cable.east())).energyContainer().getAmountAsLong();
    }

    long coolant(ServerLevel level, FusionReactorMultiblockData reactor) {
        return reactor.waterTank.amountAsLong() + reactor.steamTank.amountAsLong()
              + ((TileEntityPressurizedTube) level.getBlockEntity(steam.getFirst())).getTransmitter().getBufferWithFallback().amount()
              + turbine(level).chemicalTank.amountAsLong() + turbine(level).ventTank.amountAsLong()
              + ((TileEntityMechanicalPipe) level.getBlockEntity(water.getFirst())).getTransmitter().getBufferWithFallback().amount();
    }

    long tick(ServerLevel level, FusionReactorMultiblockData reactor) {
        var t = turbine(level);
        if (!t.isFormed()) return 0;
        long produced = t.getProductionRate();
        generated += produced;
        long current = reactor.waterTank.amountAsLong();
        if (current > previousWater) sawReturn = true;
        previousWater = current;
        if (produced > 0) sawSteam = true;
        return produced;
    }

    void restore(long coolant) { initialWater = coolant; }

    void verify(ServerLevel level, FusionReactorMultiblockData reactor) {
        check.accept(coolant(level, reactor) == initialWater, "water-cooled fusion: finite coolant conserved across reactor, steam, turbine, condenser and return pipes");
        check.accept(sawSteam && generated > 0, "water-cooled fusion: physical reactor steam generates turbine power");
        check.accept(sawReturn, "water-cooled fusion: condenser water returns through physical pipes");
        check.accept(((TileEntityEnergyCube) level.getBlockEntity(cable.east())).energyContainer().getAmountAsLong() > 0,
              "water-cooled fusion: turbine output reaches physical cable receiver");
    }

    Map<String, Object> snapshot(ServerLevel level, FusionReactorMultiblockData reactor) {
        verify(level, reactor);
        return Map.of("coolant", coolant(level, reactor), "turbineGenerated", generated, "turbineEnergy", energy(level));
    }
}
