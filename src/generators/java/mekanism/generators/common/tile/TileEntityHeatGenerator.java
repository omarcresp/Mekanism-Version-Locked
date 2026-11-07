package mekanism.generators.common.tile;

import com.google.common.primitives.Ints;
import java.util.Objects;
import java.util.List;
import com.mojang.serialization.Codec;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IFluidTank;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.HeatAPI.HeatTransfer;
import mekanism.api.heat.HeatCapacitorWrapper;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IHeatHandler;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.fluid.VariableCapacityFluidTank;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import mekanism.common.capabilities.heat.CachedAmbientTemperature;
import mekanism.common.capabilities.holder.container.IContainerHolder;
import mekanism.common.capabilities.holder.container.MekContainerHelper;
import mekanism.common.capabilities.holder.single.ISingleContainerHolder;
import mekanism.common.capabilities.holder.single.OverridingSingleHolder;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.component.containers.type.IContainerType;
import mekanism.common.config.listener.ConfigBasedCachedIntSupplier;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerFluidTankWrapper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerHeatCapacitorWrapper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerIInventorySlotWrapper;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableDouble;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.WorldUtils;
import mekanism.common.util.HeatUtils;
import mekanism.common.lib.transaction.SimpleDoubleJournal;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.slot.FluidFuelInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.Nullable;

public class TileEntityHeatGenerator extends TileEntityGenerator {

    public static final double HEAT_CAPACITY = 10;
    public static final double INVERSE_CONDUCTION_COEFFICIENT = 5;
    public static final double INVERSE_INSULATION_COEFFICIENT = 100;
    private static final double THERMAL_EFFICIENCY = 0.5;
    //Default configs this is 510 compared to the previous 500
    private static final ConfigBasedCachedIntSupplier MAX_PRODUCTION = new ConfigBasedCachedIntSupplier(() -> {
        int passiveMax = MekanismGeneratorsConfig.generators.heatGenerationLava.get() * (EnumUtils.DIRECTIONS.length + 1);
        passiveMax = MathUtils.addClamped(passiveMax, MekanismGeneratorsConfig.generators.heatGenerationNether.get());
        return MathUtils.addClamped(passiveMax, MekanismGeneratorsConfig.generators.heatGeneration.get());
    }, MekanismGeneratorsConfig.generators.heatGeneration, MekanismGeneratorsConfig.generators.heatGenerationLava, MekanismGeneratorsConfig.generators.heatGenerationNether);

    /// The FluidTank for this generator.
    @UnknownNullability//Initialized via getInitialFluidTanks
    @WrappingComputerMethod(wrapper = ComputerFluidTankWrapper.class, methodNames = {"getLava", "getLavaCapacity", "getLavaNeeded",
                                                                                     "getLavaFilledPercentage"}, docPlaceholder = "lava tank")
    public BasicFluidTank lavaTank;
    private int producingEnergy = 0;
    private final SimpleDoubleJournal generationRemainder = new SimpleDoubleJournal() {
        @Override
        protected void onRootCommit(Double originalState) {
            super.onRootCommit(originalState);
            if (originalState != value) setChanged();
        }
    };
    private double lastTransferLoss;
    private double lastEnvironmentLoss;

    @UnknownNullability//Initialized via getInitialHeatCapacitors
    @WrappingComputerMethod(wrapper = ComputerHeatCapacitorWrapper.class, methodNames = "getTemperature", docPlaceholder = "generator")
    BasicHeatCapacitor heatCapacitor;
    @UnknownNullability//Initialized via getInitialInventory
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getFuelItem", docPlaceholder = "fuel item slot")
    FluidFuelInventorySlot fuelSlot;
    @UnknownNullability//Initialized via getInitialInventory
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getEnergyItem", docPlaceholder = "energy item slot")
    EnergyInventorySlot energySlot;

    public TileEntityHeatGenerator(BlockPos pos, BlockState state) {
        super(GeneratorsBlocks.HEAT_GENERATOR, pos, state, () -> MathUtils.clampToInt(HeatUtils.joulesToFE(MAX_PRODUCTION.getAsInt())));
    }

    @Override
    protected IContainerHolder<IFluidTank> getInitialFluidTanks(IContentsListener listener) {
        MekContainerHelper<IFluidTank> builder = MekContainerHelper.forSide(facingSupplier);
        builder.addContainer(lavaTank = VariableCapacityFluidTank.input(MekanismGeneratorsConfig.generators.heatTankCapacity,
                    fluidStack -> fluidStack.is(FluidTags.LAVA), listener), RelativeSide.LEFT, RelativeSide.RIGHT, RelativeSide.BACK,
              RelativeSide.TOP, RelativeSide.BOTTOM);
        return builder.build();
    }

    @Override
    protected IContainerHolder<IInventorySlot> getInitialInventory(IContentsListener listener) {
        MekContainerHelper<IInventorySlot> builder = MekContainerHelper.forSide(facingSupplier);
        //Divide the burn time by 20 as that is the ratio of how much a bucket of lava would burn for
        //TODO: Eventually we may want to grab the 20 dynamically in case some mod is changing the burn time of a lava bucket
        builder.addContainer(fuelSlot = FluidFuelInventorySlot.forFuel(lavaTank, itemType -> level == null ? 0 : itemType.toStack().getBurnTime(null, level.fuelValues()) / 20,
              Fluids.LAVA.builtInRegistryHolder(), listener, 17, 35), RelativeSide.FRONT, RelativeSide.LEFT, RelativeSide.BACK, RelativeSide.TOP, RelativeSide.BOTTOM);
        builder.addContainer(energySlot = EnergyInventorySlot.drain(energyContainer(), listener, 143, 35), RelativeSide.RIGHT);
        return builder.build();
    }

    @Override
    protected ISingleContainerHolder<IHeatCapacitor> getInitialHeatCapacitor(IContentsListener listener, CachedAmbientTemperature ambientTemperature) {
        heatCapacitor = BasicHeatCapacitor.create(HEAT_CAPACITY, INVERSE_CONDUCTION_COEFFICIENT, INVERSE_INSULATION_COEFFICIENT, ambientTemperature, listener);
        return new OverridingSingleHolder<>(heatCapacitor, facingSupplier, (capacitor, side) -> {
            if (side == RelativeSide.BOTTOM) {
                return new DefaultInsulationCapacitor(capacitor);
            }
            return capacitor;
        });
    }

    @Override
    protected boolean onUpdateServer(ServerLevel level) {
        boolean sendUpdatePacket = super.onUpdateServer(level);
        try (Transaction transaction = Transaction.openRoot()) {
            energySlot.drainContainerIntoSlot(transaction);
            fuelSlot.fillOrBurn(transaction);
            long prev = energyContainer().getAmountAsLong();
            heatCapacitor.handleHeat(getBoost(), transaction);
            FluidResource lavaResource = lavaTank.resource();
            boolean isActive = false;
            if (canFunction() && !lavaResource.isEmpty() && !EnergyHandlerUtil.isFull(energyContainer())) {
                try (Transaction subTransaction = Transaction.open(transaction)) {
                    int fluidRate = MekanismGeneratorsConfig.generators.heatGenerationFluidRate.get();
                    if (lavaTank.extract(lavaResource, fluidRate, subTransaction, AutomationType.INTERNAL) == fluidRate) {
                        isActive = true;
                        heatCapacitor.handleHeat(MekanismGeneratorsConfig.generators.heatGeneration.get(), subTransaction);
                        subTransaction.commit();
                    }
                }
            }
            setActive(isActive);
            HeatTransfer loss = simulate(transaction);
            lastTransferLoss = loss.adjacentTransfer();
            lastEnvironmentLoss = loss.environmentTransfer();
            producingEnergy = Math.max(0, Ints.saturatedCast(energyContainer().getAmountAsLong() - prev));
            transaction.commit();
        }
        return sendUpdatePacket;
    }

    private int getBoost() {
        if (level == null) {
            return 0;
        }
        int boost;
        int passiveLavaAmount = MekanismGeneratorsConfig.generators.heatGenerationLava.get();
        if (passiveLavaAmount == 0) {
            //If neighboring lava blocks produce no energy, don't bother checking the sides for them
            boost = 0;
        } else {
            //Otherwise, calculate boost to apply from lava
            //Only check and add loaded neighbors to the which sides have lava on them
            MutableBlockPos mutable = new MutableBlockPos();
            int lavaSides = 0;
            for (Direction dir : EnumUtils.DIRECTIONS) {
                //Only check and add loaded neighbors to the which sides have lava on them
                mutable.setWithOffset(worldPosition, dir);
                if (WorldUtils.getFluidState(level, mutable).filter(state -> state.is(FluidTags.LAVA)).isPresent()) {
                    lavaSides++;
                }
            }
            if (getBlockState().getFluidState().is(FluidTags.LAVA)) {
                //If the heat generator is lava-logged then add it as another side that is adjacent to lava for the heat calculations
                lavaSides++;
            }
            boost = passiveLavaAmount * lavaSides;
        }
        if (level.dimension() == Level.NETHER) {
            return MathUtils.addClamped(boost, MekanismGeneratorsConfig.generators.heatGenerationNether.get());
        }
        return boost;
    }

    @Override
    public HeatTransfer simulate(TransactionContext transaction) {
        double ambientTemp = Objects.requireNonNull(ambientTemperature, "Tile cannot simulate temperature before initialization").getAsDouble();
        double temp = getTemperature();
        // 1 - Qc / Qh
        double carnotEfficiency = 1 - Math.min(ambientTemp, temp) / Math.max(ambientTemp, temp);
        double heatLost = THERMAL_EFFICIENCY * (temp - ambientTemp);
        heatCapacitor.handleHeat(-heatLost, transaction);
        int joulesFromHeat = MathUtils.clampToInt(Math.abs(heatLost) * carnotEfficiency);
        double generated = HeatUtils.joulesToFE(Math.min(joulesFromHeat, MAX_PRODUCTION.getAsInt())) + generationRemainder.value;
        energyContainer().insert(MathUtils.clampToInt(generated), transaction, AutomationType.INTERNAL);
        generationRemainder.updateSnapshots(transaction);
        generationRemainder.value = generated - Math.floor(generated);
        return super.simulate(transaction);
    }

    @Nullable
    @Override
    public IHeatHandler getAdjacent(Direction side) {
        //Only allow adjacent heat transfer through the bottom face
        return side == Direction.DOWN ? super.getAdjacent(side) : null;
    }

    @Override
    public int getProductionRate() {
        return producingEnergy;
    }

    public double getTemperature() {
        return heatCapacitor.getTemperature();
    }

    @ComputerMethod(nameOverride = "getTransferLoss")
    public double getLastTransferLoss() {
        return lastTransferLoss;
    }

    @ComputerMethod(nameOverride = "getEnvironmentalLoss")
    public double getLastEnvironmentLoss() {
        return lastEnvironmentLoss;
    }

    @Override
    public int getRedstoneLevel() {
        return ContainerType.FLUID.getRedstoneSignalFromContainer(lavaTank);
    }

    @Override
    protected boolean makesComparatorDirty(IContainerType<?, ?> type) {
        return type == ContainerType.FLUID;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableInt.create(this::getProductionRate, value -> producingEnergy = value));
        container.track(SyncableDouble.create(this::getLastTransferLoss, value -> lastTransferLoss = value));
        container.track(SyncableDouble.create(this::getLastEnvironmentLoss, value -> lastEnvironmentLoss = value));
    }

    public double getGenerationRemainder() {
        return generationRemainder.value;
    }

    @Override
    public void readSustainedData(ValueInput input) {
        super.readSustainedData(input);
        generationRemainder.value = input.read("generationRemainder", Codec.doubleRange(0, Math.nextDown(1D))).orElse(0D);
    }

    @Override
    public void writeSustainedData(ValueOutput output) {
        super.writeSustainedData(output);
        output.putDouble("generationRemainder", generationRemainder.value);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter input) {
        super.applyImplicitComponents(input);
        generationRemainder.value = input.getOrDefault(MekanismDataComponents.GENERATION_REMAINDER, 0D);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (generationRemainder.value > 0) builder.set(MekanismDataComponents.GENERATION_REMAINDER, generationRemainder.value);
    }

    @Override
    public List<DataComponentType<?>> getRemapEntries() {
        var entries = super.getRemapEntries();
        if (!entries.contains(MekanismDataComponents.GENERATION_REMAINDER.get())) entries.add(MekanismDataComponents.GENERATION_REMAINDER.get());
        return entries;
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("generationRemainder");
    }

    private static class DefaultInsulationCapacitor extends HeatCapacitorWrapper {

        protected DefaultInsulationCapacitor(IHeatCapacitor internal) {
            super(internal);
        }

        @Override
        public double getInverseInsulation() {
            return HeatAPI.DEFAULT_INVERSE_INSULATION;
        }
    }
}
