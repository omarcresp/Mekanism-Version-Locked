package mekanism.generators.common.content.fusion;

import com.mojang.serialization.Codec;
import mekanism.api.SerializationConstants;
import mekanism.common.lib.multiblock.MultiblockCache;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public class FusionReactorCache extends MultiblockCache<FusionReactorMultiblockData> {

    private double plasmaTemperature = -1;
    private int injectionRate = -1;
    private boolean burning;
    private double generationRemainder;

    private int getInjectionRate() {
        if (injectionRate == -1) {
            //If it never got set default to 2
            return 2;
        }
        //Otherwise, return the actual so that it can be manually set down to zero
        return injectionRate;
    }

    @Override
    public void merge(MultiblockCache<FusionReactorMultiblockData> mergeCache, RejectContents rejectContents) {
        super.merge(mergeCache, rejectContents);
        plasmaTemperature = Math.max(plasmaTemperature, ((FusionReactorCache) mergeCache).plasmaTemperature);
        injectionRate = Math.max(injectionRate, ((FusionReactorCache) mergeCache).injectionRate);
        burning |= ((FusionReactorCache) mergeCache).burning;
        generationRemainder = Math.max(generationRemainder, ((FusionReactorCache) mergeCache).generationRemainder);
    }

    @Override
    public void apply(FusionReactorMultiblockData data, TransactionContext transaction) {
        super.apply(data, transaction);
        if (plasmaTemperature >= 0) {
            data.setPlasmaTemp(plasmaTemperature, transaction);
        }
        data.setInjectionRate(getInjectionRate(), transaction);
        data.setBurning(burning);
        data.setGenerationRemainder(generationRemainder, transaction);
        data.updateTemperatures();
    }

    @Override
    public void sync(FusionReactorMultiblockData data, TransactionContext transaction) {
        super.sync(data, transaction);
        plasmaTemperature = data.getPlasmaTemp();
        injectionRate = data.getInjectionRate();
        burning = data.isBurning();
        generationRemainder = data.getGenerationRemainder();
    }

    @Override
    public void load(ValueInput input) {
        super.load(input);
        //TODO - 26.1: These used to just get instead of only getting if present, should the fallback be zero or the existing value?
        plasmaTemperature = input.getDoubleOr(SerializationConstants.PLASMA_TEMP, plasmaTemperature);
        injectionRate = input.getIntOr(SerializationConstants.INJECTION_RATE, injectionRate);
        burning = input.getBooleanOr(SerializationConstants.BURNING, burning);
        generationRemainder = input.read("generationRemainder", Codec.doubleRange(0, Math.nextDown(1D))).orElse(0D);
    }

    @Override
    public void save(ValueOutput output) {
        super.save(output);
        output.putDouble(SerializationConstants.PLASMA_TEMP, plasmaTemperature);
        output.putInt(SerializationConstants.INJECTION_RATE, getInjectionRate());
        output.putBoolean(SerializationConstants.BURNING, burning);
        output.putDouble("generationRemainder", generationRemainder);
    }
}
