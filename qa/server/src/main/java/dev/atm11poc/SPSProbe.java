package dev.atm11poc;

import java.util.function.BiConsumer;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.command.builders.Builders;
import mekanism.common.config.MekanismConfig;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismChemicals;
import mekanism.common.tile.multiblock.TileEntitySPSPort;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class SPSProbe {
    private static final BlockPos START = new BlockPos(96, 80, 24);
    private static final BlockPos PORT = START.offset(3, 3, 0);
    private final BiConsumer<Boolean, String> check;
    private boolean supplied;
    private boolean complete;
    private int operatingTicks;
    private long input;

    SPSProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    private static final class Builder extends Builders.SPSBuilder {
        void place(Level level) {
            build(level, START, false);
        }
    }

    void build(ServerLevel level) {
        level.setChunkForced(6, 1, true);
        new Builder().place(level);
        level.setBlockAndUpdate(PORT, MekanismBlocks.SPS_PORT.defaultState());
        level.setBlockAndUpdate(PORT.offset(0, 0, 1), MekanismBlocks.SUPERCHARGED_COIL.defaultState());
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        var port = (TileEntitySPSPort) level.getBlockEntity(PORT);
        var sps = port.getMultiblock();
        if (!sps.isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Real SPS structure did not form within 200 ticks");
            return false;
        }
        if (!supplied) {
            check.accept(sps.coilData.coilMap.containsKey(PORT), "formed SPS detects actual port-connected coil");
            input = MekanismConfig.general.spsInputPerAntimatter.get();
            sps.inputTank.setContents(ChemicalResource.of(MekanismChemicals.POLONIUM), input, null);
            long energy = Math.multiplyExact(input, MekanismConfig.general.spsEnergyPerInput.get());
            port.getEnergyContainer().setEnergy(energy, null);
            check.accept(port.getEnergyContainer().getAmountAsLong() == energy, "SPS port accepts exact energy for one mB antimatter");
            supplied = true;
            return false;
        }
        if (++operatingTicks < 20) return false;
        check.accept(sps.inputTank.isEmpty(), "SPS consumes exact configured polonium input");
        check.accept(sps.outputTank.amountAsLong() == 1 && sps.outputTank.resource().equals(ChemicalResource.of(MekanismChemicals.ANTIMATTER)),
              "SPS converts polonium into exactly one mB antimatter");
        check.accept(port.getEnergyContainer().isEmpty(), "SPS actual port and coil consume exact supplied energy");
        complete = true;
        return true;
    }
}
