package dev.atm11poc;

import java.util.function.BiConsumer;
import mekanism.api.AutomationType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.machine.TileEntityResistiveHeater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class HeaterProbe {
    private static final BlockPos POS = new BlockPos(8, 80, 176);
    private final BiConsumer<Boolean, String> check;
    private double initialHeat;
    private boolean complete;

    HeaterProbe(BiConsumer<Boolean, String> check) { this.check = check; }

    void prepare(ServerLevel level, boolean reopen) {
        level.setChunkForced(0, 11, true);
        if (reopen) { complete = true; return; }
        level.setBlockAndUpdate(POS, MekanismBlocks.RESISTIVE_HEATER.defaultState());
        var tile = (TileEntityResistiveHeater) level.getBlockEntity(POS);
        check.accept(tile.energyContainer().getEnergyPerTick() == 40 && tile.energyContainer().getCapacityAsLong() == 16000,
              "ATM10 resistive heater starts at 40 FE/t with a 16000 FE buffer");
        initialHeat = tile.getHeatCapacitor().getHeat();
        try (var tx = Transaction.openRoot()) {
            check.accept(tile.energyContainer().insert(40, tx, AutomationType.EXTERNAL) == 40,
                  "heater accepts its one-tick electrical input");
            tx.commit();
        }
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        var tile = (TileEntityResistiveHeater) level.getBlockEntity(POS);
        if (tile.getEnergyUsed() == 0) {
            if (elapsed > 100) throw new IllegalStateException("Resistive heater did not consume input");
            return false;
        }
        check.accept(tile.getEnergyUsed() == 40 && tile.energyContainer().isEmpty(), "heater consumes exactly 40 FE");
        double accountedHeat = tile.getHeatCapacitor().getHeat() - initialHeat +
              (tile.getLastEnvironmentLoss() + tile.getLastTransferLoss()) * tile.getHeatCapacitor().getHeatCapacity();
        check.accept(Math.abs(accountedHeat - 60) < 1e-6,
              "40 FE becomes 100 Joules and produces 60 Joules of accounted heater heat at 60 percent efficiency");
        complete = true;
        return true;
    }
}
