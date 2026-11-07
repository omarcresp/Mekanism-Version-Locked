package mekanism.generators.common.content.turbine;

import com.mojang.serialization.Codec;
import mekanism.api.SerializationConstants;
import mekanism.common.lib.multiblock.MultiblockCache;
import mekanism.common.tile.TileEntityChemicalTank.GasMode;
import mekanism.common.util.NBTUtils;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public class TurbineCache extends MultiblockCache<TurbineMultiblockData> {

    private GasMode dumpMode = GasMode.IDLE;
    private double generationRemainder;

    @Override
    public void merge(MultiblockCache<TurbineMultiblockData> mergeCache, RejectContents rejectContents) {
        super.merge(mergeCache, rejectContents);
        dumpMode = ((TurbineCache) mergeCache).dumpMode;
        generationRemainder = Math.max(generationRemainder, ((TurbineCache) mergeCache).generationRemainder);
    }

    @Override
    public void apply(TurbineMultiblockData data, TransactionContext transaction) {
        super.apply(data, transaction);
        data.dumpMode = dumpMode;
        data.setGenerationRemainder(generationRemainder, transaction);
    }

    @Override
    public void sync(TurbineMultiblockData data, TransactionContext transaction) {
        super.sync(data, transaction);
        dumpMode = data.dumpMode;
        generationRemainder = data.getGenerationRemainder();
    }

    @Override
    public void load(ValueInput input) {
        super.load(input);
        generationRemainder = input.read("generationRemainder", Codec.doubleRange(0, Math.nextDown(1D))).orElse(0D);
        NBTUtils.setEnumIfPresent(input, SerializationConstants.DUMP_MODE, GasMode.BY_ID, mode -> dumpMode = mode);
    }

    @Override
    public void save(ValueOutput output) {
        super.save(output);
        output.putDouble("generationRemainder", generationRemainder);
        NBTUtils.writeEnum(output, SerializationConstants.DUMP_MODE, dumpMode);
    }
}