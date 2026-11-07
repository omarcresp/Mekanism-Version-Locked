package mekanism.common.content.matrix;

import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Collection;
import java.util.Map;
import mekanism.api.AutomationType;
import mekanism.api.MekanismPreconditions;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.math.MathUtils;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transaction.SimpleLongJournal;
import mekanism.common.tier.InductionProviderTier;
import mekanism.common.tile.multiblock.TileEntityInductionCell;
import mekanism.common.tile.multiblock.TileEntityInductionProvider;
import mekanism.common.util.EnergyUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.Nullable;

public class MatrixEnergyContainer implements IEnergyContainer {

    private final Map<BlockPos, InductionProviderTier> providers = new Object2ObjectOpenHashMap<>();
    private final Map<BlockPos, IEnergyContainer> cells = new Object2ObjectOpenHashMap<>();
    private final SimpleLongJournal queuedInput = new SimpleLongJournal();
    private final SimpleLongJournal queuedOutput = new SimpleLongJournal();

    private long lastInput = 0L;
    private long lastOutput = 0L;

    private final SimpleLongJournal cachedTotal = new SimpleLongJournal();
    private long transferCap = 0L;
    private long storageCap = 0L;

    private final MatrixMultiblockData multiblock;

    public MatrixEnergyContainer(MatrixMultiblockData multiblock) {
        this.multiblock = multiblock;
    }

    public void addCell(BlockPos pos, TileEntityInductionCell cell) {
        //As we already have the two different variables just pass them instead of accessing world to get tile again
        MachineEnergyContainer<TileEntityInductionCell> energyContainer = cell.energyContainer();
        cells.put(pos, energyContainer);
        storageCap = MathUtils.addClamped(storageCap, energyContainer.getCapacityAsLong());
        cachedTotal.value = MathUtils.addClamped(cachedTotal.value, energyContainer.getAmountAsLong());
    }

    public void addProvider(BlockPos pos, TileEntityInductionProvider provider) {
        providers.put(pos, provider.tier);
        transferCap = MathUtils.addClamped(transferCap, provider.tier.getOutput());
    }

    public void removeInternal(BlockPos pos) {
        InductionProviderTier provider = providers.remove(pos);
        if (provider != null) transferCap -= provider.getOutput();
        IEnergyContainer cell = cells.remove(pos);
        if (cell != null) {
            storageCap -= cell.getCapacityAsLong();
            cachedTotal.value -= cell.getAmountAsLong();
        }
    }

    public void invalidate() {
        cells.clear();
        providers.clear();
        queuedOutput.value = 0L;
        queuedInput.value = 0L;
        lastOutput = 0L;
        lastInput = 0L;
        cachedTotal.value = 0L;
        transferCap = 0L;
        storageCap = 0L;
    }

    public boolean tick(Collection<BlockCapabilityCache<EnergyHandler, @Nullable Direction>> targets, @Nullable EnergyInventorySlot energyInputSlot,
          @Nullable EnergyInventorySlot energyOutputSlot, @Nullable TransactionContext transaction) {
        try (Transaction subTransaction = Transaction.open(transaction)) {
            if (energyInputSlot != null) energyInputSlot.drainContainerIntoSlot(subTransaction);
            if (energyOutputSlot != null) energyOutputSlot.fillContainerOrConvert(subTransaction);
            long available = Math.min(getRemainingOutput(), getAmountAsLong());
            if (available > 0 && !targets.isEmpty()) {
                long sent = EnergyUtils.emit(targets, available, subTransaction);
                if (sent > 0) {
                    queuedOutput.updateSnapshots(subTransaction);
                    queuedOutput.value += sent;
                    removeEnergy(sent, subTransaction);
                }
            }
            subTransaction.commit();
        }
        lastInput = queuedInput.value;
        lastOutput = queuedOutput.value;
        queuedInput.value = 0L;
        queuedOutput.value = 0L;

        return getLastInput() > 0 || getLastOutput() > 0;
    }

    private void addEnergy(long energy, TransactionContext transaction) {
        cachedTotal.updateSnapshots(transaction);
        cachedTotal.value += energy;
        for (IEnergyContainer container : cells.values()) {
            //Note: inserting into the cell's energy container handles marking the cell for saving if it changes
            long stored = container.getAmountAsLong();
            long needed = container.getCapacityAsLong() - stored;
            if (needed > 0) {
                if (energy <= needed) {
                    container.setEnergy(stored + energy, transaction);
                    //Nothing left to add
                    break;
                }
                container.setEnergy(stored + needed, transaction);
                energy -= needed;
            }
        }
    }

    private void removeEnergy(long energy, TransactionContext transaction) {
        cachedTotal.updateSnapshots(transaction);
        cachedTotal.value -= energy;
        for (IEnergyContainer container : cells.values()) {
            //Note: extracting from the cell's energy container handles marking the cell for saving if it changes
            long stored = container.getAmountAsLong();
            if (stored > 0) {
                if (energy <= stored) {
                    container.setEnergy(stored - energy, transaction);
                    //Nothing left to remove
                    break;
                }
                container.setEnergy(0, transaction);
                energy -= stored;
            }
        }
    }

    @Override
    public long getAmountAsLong() {
        return cachedTotal.value;
    }

    @Override
    public void setEnergy(@Range(from = 0, to = Long.MAX_VALUE) long energy, @Nullable TransactionContext transaction) {
        //Throws a RuntimeException as specified is allowed when something unexpected happens
        // As setEnergy is more meant to be used as an internal method
        throw new RuntimeException("Unexpected call to setEnergy. The matrix energy container does not support directly setting the energy.");
    }

    @Override
    @Range(from = 0, to = Integer.MAX_VALUE)
    public int insert(@Range(from = 0, to = Integer.MAX_VALUE) int amount, TransactionContext transaction, AutomationType automationType) {
        MekanismPreconditions.checkNonNegative(amount);
        if (amount == 0 || !multiblock.isFormed() || !isValidForInsertion(automationType)) {
            return 0;
        }
        int toAdd = Ints.saturatedCast(Math.min(Math.min(amount, getRemainingInput()), getNeededAsLong()));
        if (toAdd > 0) {
            queuedInput.updateSnapshots(transaction);
            //Increase how much we are inputting
            queuedInput.value += toAdd;
            addEnergy(toAdd, transaction);
        }
        return toAdd;
    }

    @Override
    @Range(from = 0, to = Integer.MAX_VALUE)
    public int extract(@Range(from = 0, to = Integer.MAX_VALUE) int amount, TransactionContext transaction, AutomationType automationType) {
        MekanismPreconditions.checkNonNegative(amount);
        if (isEmpty() || amount == 0 || !multiblock.isFormed() || !isValidForExtraction(automationType)) {
            return 0;
        }
        //We limit it overall by the amount we can extract plus how much energy we have
        // as we want to be as accurate as possible with the values we return
        // It is possible that the energy we have stored is a lot less than the amount we
        // can output at once such as if the matrix is almost empty.
        int toRemove = Ints.saturatedCast(Math.min(Math.min(amount, getRemainingOutput()), getAmountAsLong()));
        if (toRemove > 0) {
            //Increase how much we are outputting by the amount we sent
            queuedOutput.updateSnapshots(transaction);
            //Increase how much we are inputting
            queuedOutput.value += toRemove;
            removeEnergy(toRemove, transaction);
        }
        return toRemove;
    }

    @Override
    public boolean isValidForExtraction(AutomationType automationType) {
        // Allow networks to pull through output ports; input ports remain blocked by their holder.
        // The port holder enforces input/output mode. External pull shares the same per-tick budget as push.
        return true;
    }

    @Override
    @Range(from = 0, to = Long.MAX_VALUE)
    public long getCapacityAsLong() {
        return storageCap;
    }

    @Override
    public void serialize(ValueOutput output) {
        //Note: We don't actually have any specific serialization
    }

    @Override
    public void deserialize(ValueInput input) {
    }

    @Override
    public void copyContents(IEnergyContainer other, @Nullable TransactionContext transaction) {
    }

    private long getRemainingInput() {
        return transferCap - queuedInput.value;
    }

    private long getRemainingOutput() {
        return transferCap - queuedOutput.value;
    }

    public long getMaxTransfer() {
        return transferCap;
    }

    public long getLastInput() {
        return lastInput;
    }

    public long getLastOutput() {
        return lastOutput;
    }

    public int getCells() {
        return cells.size();
    }

    public int getProviders() {
        return providers.size();
    }
}
