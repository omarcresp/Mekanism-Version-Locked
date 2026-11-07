package dev.atm11poc;

import java.util.function.BiConsumer;
import mekanism.api.AutomationType;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.registries.MekanismChemicals;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.TileEntityGasGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Real tick tests for fractional burning, conservation, item pickup and restart. */
final class GasProbe {
    private static final BlockPos START = new BlockPos(8, 80, 144);
    private ChemicalResource ethene;
    private ChemicalResource hydrogen;
    private final BiConsumer<Boolean, String> check;
    private boolean fullChecked;
    private boolean partialChecked;
    private boolean emptyChecked;
    private boolean hydrogenChecked;
    private boolean complete;

    GasProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    void prepare(ServerLevel level, boolean reopen) {
        ethene = ChemicalResource.of(MekanismChemicals.ETHENE);
        hydrogen = ChemicalResource.of(MekanismChemicals.HYDROGEN);
        for (int i = 0; i < 6; i++) {
            BlockPos pos = START.offset(i * 3, 0, 0);
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            if (!reopen) level.setBlockAndUpdate(pos, GeneratorsBlocks.GAS_BURNING_GENERATOR.value().defaultBlockState());
        }
        check.accept(tile(level, 0).energyContainer().getCapacityAsLong() == 80000, "ATM10 gas generator capacity is 80000 FE");
        check.accept(tile(level, 0).getFuelTank().capacityAsLong(ethene) == 18000, "ATM10 gas generator tank capacity is 18000 mB");
        if (reopen) {
            check.accept(tile(level, 0).getFuelTank().amountAsLong() == 17987 && tile(level, 0).getBufferedFuelEnergy() == 480,
                  "full-pressure burn retains exact whole fuel and fractional credit across JVM restart");
            check.accept(tile(level, 0).energyContainer().getAmountAsLong() == 30720, "full-pressure burn retains exact output across JVM restart");
            var partial = tile(level, 1);
            check.accept(partial.getFuelTank().isEmpty() && partial.getBufferedFuelEnergy() == 2280,
                  "empty tank retains 2280 FE of partial ethene across JVM restart");
            check.accept(partial.energyContainer().getAmountAsLong() == 79963 && partial.getControlType() == RedstoneControl.HIGH,
                  "partial burn retains full output buffer and shutdown mode across JVM restart");
            check.accept(partial.collectComponents().get(MekanismDataComponents.FUEL_BURN_BUFFER).fuel().equals(ethene),
                  "saved partial fuel retains chemical identity");
            partial.energyContainer().setEnergy(0, null);
            partial.setControlType(RedstoneControl.DISABLED);
            return;
        }
        tile(level, 0).getFuelTank().setContents(ethene, 18000, null);
        tile(level, 1).getFuelTank().setContents(ethene, 1, null);
        tile(level, 1).energyContainer().setEnergy(79843, null);
        tile(level, 2).getFuelTank().setContents(ethene, 1, null);
        tile(level, 3).getFuelTank().setContents(ethene, 1, null);
        tile(level, 3).energyContainer().setEnergy(79963, null);
        tile(level, 5).getFuelTank().setContents(ethene, 1, null);
        tile(level, 5).energyContainer().setEnergy(80000, null);
        tile(level, 4).getFuelTank().setContents(hydrogen, 1, null);
    }

    boolean tick(ServerLevel level, int elapsed, boolean reopen) {
        if (complete) return true;
        if (elapsed > 200) throw new IllegalStateException("Gas fuel conservation fixture did not finish within 200 ticks");
        if (reopen) {
            var tile = tile(level, 1);
            if (tile.getBufferedFuelEnergy() != 0) return false;
            check.accept(tile.getFuelTank().isEmpty() && tile.energyContainer().getAmountAsLong() == 2280,
                  "restarted generator converts every remaining FE of partial fuel with an empty tank");
            complete = true;
            return true;
        }
        var full = tile(level, 0);
        if (!fullChecked && full.getProductionRate() > 0) {
            check.accept(full.getProductionRate() == 30720 && full.energyContainer().getAmountAsLong() == 30720,
                  "full ethene tank generates ATM10 output of 30720 FE in one tick");
            check.accept(full.getFuelTank().amountAsLong() == 17987 && full.getBufferedFuelEnergy() == 480,
                  "full-pressure output consumes exactly 12.8 mB equivalent without losing the fraction");
            full.setControlType(RedstoneControl.HIGH);
            check.accept(full.getControlType() == RedstoneControl.HIGH, "gas fixture supports redstone shutdown");
            fullChecked = true;
        }
        var partial = tile(level, 1);
        if (!partialChecked && partial.getProductionRate() > 0) {
            check.accept(partial.getProductionRate() == 120 && partial.energyContainer().getAmountAsLong() == 79963,
                  "a 157 FE gap admits one 120 FE burn step and retains 37 FE free");
            check.accept(partial.getFuelTank().isEmpty() && partial.getBufferedFuelEnergy() == 2280,
                  "partial ethene burn preserves all 2280 ungenerated FE");
            try (var tx = Transaction.openRoot()) {
                check.accept(partial.getFuelTank().insert(hydrogen, 1, tx, AutomationType.EXTERNAL) == 0,
                      "partial ethene credit cannot be mixed with a different fuel");
            }
            partial.setControlType(RedstoneControl.HIGH);
            var state = partial.getBlockState();
            var drops = Block.getDrops(state, level, partial.getBlockPos(), partial);
            var item = drops.stream().filter(stack -> stack.is(GeneratorsBlocks.GAS_BURNING_GENERATOR.asItem())).findFirst().orElseThrow();
            var buffer = item.get(MekanismDataComponents.FUEL_BURN_BUFFER);
            check.accept(buffer != null && buffer.energy() == 2280 && buffer.fuel().equals(ethene),
                  "normal generator loot preserves remaining fuel energy and chemical identity");
            level.setBlockAndUpdate(partial.getBlockPos(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(partial.getBlockPos(), state);
            partial = tile(level, 1);
            partial.applyComponentsFromItemStack(item);
            check.accept(partial.getBufferedFuelEnergy() == 2280 && partial.energyContainer().getAmountAsLong() == 79963,
                  "placing the dropped generator restores both fuel credit and output energy");
            partialChecked = true;
        }
        var empty = tile(level, 2);
        if (!emptyChecked && empty.getFuelTank().isEmpty() && empty.getBufferedFuelEnergy() == 0 && empty.energyContainer().getAmountAsLong() > 0) {
            check.accept(empty.energyContainer().getAmountAsLong() == 2400, "one ethene mB yields exactly 2400 FE including final empty-tank credit");
            try (var tx = Transaction.openRoot()) {
                check.accept(empty.getFuelTank().insert(hydrogen, 1, tx, AutomationType.EXTERNAL) == 1,
                      "generator accepts a different fuel after partial credit is exhausted");
            }
            check.accept(empty.getFuelTank().isEmpty(), "aborted fuel insertion rolls back without creating fuel");
            emptyChecked = true;
        }
        var hydrogen = tile(level, 4);
        if (!hydrogenChecked && hydrogen.energyContainer().getAmountAsLong() > 0) {
            check.accept(hydrogen.energyContainer().getAmountAsLong() == 80 && hydrogen.getFuelTank().isEmpty() && hydrogen.getBufferedFuelEnergy() == 0,
                  "one hydrogen mB yields exactly 80 FE");
            hydrogenChecked = true;
        }
        if (fullChecked && partialChecked && emptyChecked && hydrogenChecked) {
            var blocked = tile(level, 3);
            check.accept(blocked.getFuelTank().amountAsLong() == 1 && blocked.getBufferedFuelEnergy() == 0,
                  "37 FE of free space consumes no fuel or partial credit");
            check.accept(tile(level, 5).getFuelTank().amountAsLong() == 1 && tile(level, 5).getBufferedFuelEnergy() == 0,
                  "full energy buffer consumes no fuel or partial credit");
            try (var tx = Transaction.openRoot()) {
                check.accept(blocked.getFuelTank().extract(ethene, 1, tx, AutomationType.INTERNAL) == 1,
                      "fuel extraction is visible inside a transaction");
            }
            check.accept(blocked.getFuelTank().amountAsLong() == 1 && blocked.getBufferedFuelEnergy() == 0,
                  "aborted fuel extraction restores exact fuel without changing burn credit");
            complete = true;
        }
        return complete;
    }

    private TileEntityGasGenerator tile(ServerLevel level, int index) {
        return (TileEntityGasGenerator) level.getBlockEntity(START.offset(index * 3, 0, 0));
    }
}
