package mekanism.generators.common.tile;

import java.util.List;
import java.util.function.Predicate;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.datamaps.IMekanismDataMapTypes;
import mekanism.api.datamaps.chemical.attribute.ChemicalFuel;
import mekanism.api.functions.ConstantPredicates;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.chemical.VariableCapacityChemicalTank;
import mekanism.common.capabilities.holder.container.IContainerHolder;
import mekanism.common.capabilities.holder.container.MekContainerHelper;
import mekanism.common.component.FuelBurnBuffer;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.component.containers.type.IContainerType;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerChemicalTankWrapper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerIInventorySlotWrapper;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.container.sync.SyncableDouble;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.inventory.slot.ChemicalInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.Nullable;

public class TileEntityGasGenerator extends TileEntityGenerator {

    public static final Predicate<ChemicalResource> HAS_FUEL = chemical -> chemical.getData(IMekanismDataMapTypes.INSTANCE.chemicalFuel()) != null;

    /// The tank this block is storing fuel in.
    @UnknownNullability//Initialized via getInitialChemicalTanks
    @WrappingComputerMethod(wrapper = ComputerChemicalTankWrapper.class, methodNames = {"getFuel", "getFuelCapacity", "getFuelNeeded",
                                                                                        "getFuelFilledPercentage"}, docPlaceholder = "fuel tank")
    IChemicalTank fuelTank;
    @Nullable
    private FuelBurnBuffer burnBuffer;
    private double gasUsedLastTick;
    private int productionLastTick;

    @UnknownNullability//Initialized via getInitialInventory
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getFuelItem", docPlaceholder = "fuel item slot")
    ChemicalInventorySlot fuelSlot;
    @UnknownNullability//Initialized via getInitialInventory
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getEnergyItem", docPlaceholder = "energy item slot")
    EnergyInventorySlot energySlot;

    public TileEntityGasGenerator(BlockPos pos, BlockState state) {
        super(GeneratorsBlocks.GAS_BURNING_GENERATOR, pos, state);
    }

    @Override
    public IContainerHolder<IChemicalTank> getInitialChemicalTanks(IContentsListener listener) {
        MekContainerHelper<IChemicalTank> builder = MekContainerHelper.forSide(facingSupplier);
        builder.addContainer(fuelTank = new VariableCapacityChemicalTank(MekanismGeneratorsConfig.generators.gbgTankCapacity,
              ConstantPredicates.notExternal(), ConstantPredicates.alwaysTrueBi(),
              resource -> HAS_FUEL.test(resource) && (burnBuffer == null || burnBuffer.fuel().equals(resource)),
              null, null, null, listener), RelativeSide.LEFT, RelativeSide.RIGHT, RelativeSide.BACK, RelativeSide.TOP, RelativeSide.BOTTOM);
        return builder.build();
    }

    @Override
    protected IContainerHolder<IInventorySlot> getInitialInventory(IContentsListener listener) {
        MekContainerHelper<IInventorySlot> builder = MekContainerHelper.forSide(facingSupplier);
        builder.addContainer(fuelSlot = ChemicalInventorySlot.fill(fuelTank, listener, 17, 35), RelativeSide.FRONT, RelativeSide.LEFT, RelativeSide.BACK, RelativeSide.TOP,
              RelativeSide.BOTTOM);
        builder.addContainer(energySlot = EnergyInventorySlot.drain(energyContainer(), listener, 143, 35), RelativeSide.RIGHT);
        fuelSlot.setSlotOverlay(SlotOverlay.MINUS);
        return builder.build();
    }

    @Override
    protected boolean onUpdateServer(ServerLevel level) {
        boolean sendUpdatePacket = super.onUpdateServer(level);
        energySlot.drainContainerIntoSlot(null);
        fuelSlot.fillTankFromSlot(null);
        gasUsedLastTick = 0;
        productionLastTick = 0;
        ChemicalResource fuel = burnBuffer == null ? fuelTank.resource() : burnBuffer.fuel();
        ChemicalFuel attributes = fuel.isEmpty() ? null : fuel.getData(IMekanismDataMapTypes.INSTANCE.chemicalFuel());
        if (canFunction() && attributes != null) {
            int credit = getBufferedFuelEnergy();
            int availableFuel = fuelTank.resource().equals(fuel) ? fuelTank.amountAsInt() : 0;
            long availableEnergy = (long) availableFuel * attributes.energyDensity() + credit;
            // Retain ATM10's pressure curve, and allow the final partial mB to finish burning.
            long pressureAmount = availableFuel == 0 && credit > 0 ? 1 : availableFuel;
            int maxEnergy = (int) Math.min(availableEnergy, Math.min(attributes.getEnergyForSpace(energyContainer().getCapacityAsLong() - energyContainer().getAmountAsLong()),
                  attributes.getEnergyForFullness(pressureAmount, fuelTank.capacityAsLong(fuel))));
            if (maxEnergy > 0) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int inserted = energyContainer().insert(maxEnergy, transaction, AutomationType.INTERNAL);
                    int neededFuel = (int) Math.ceilDiv(Math.max(0L, (long) inserted - credit), attributes.energyDensity());
                    if (inserted > 0 && (neededFuel == 0 || fuelTank.extract(fuel, neededFuel, transaction, AutomationType.INTERNAL) == neededFuel)) {
                        int remaining = (int) (credit + (long) neededFuel * attributes.energyDensity() - inserted);
                        transaction.commit();
                        burnBuffer = remaining == 0 ? null : new FuelBurnBuffer(fuel, remaining);
                        gasUsedLastTick = inserted / (double) attributes.energyDensity();
                        productionLastTick = inserted;
                    }
                }
            }
        }
        setActive(productionLastTick != 0);
        return sendUpdatePacket;
    }

    public IChemicalTank getFuelTank() {
        return fuelTank;
    }

    @ComputerMethod(nameOverride = "getBurnRate")
    public double getUsed() {
        return Math.round(gasUsedLastTick * 100) / 100D;
    }

    @Override
    public int getRedstoneLevel() {
        return ContainerType.CHEMICAL.getRedstoneSignalFromContainer(fuelTank);
    }

    @Override
    protected boolean makesComparatorDirty(IContainerType<?, ?> type) {
        return type == ContainerType.CHEMICAL;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableDouble.create(this::getUsed, value -> gasUsedLastTick = value));
        container.track(SyncableInt.create(this::getProductionRate, value -> productionLastTick = value));
    }

    @Nullable
    public ChemicalFuel getCachedFuel() {
        ChemicalResource fuel = burnBuffer == null ? fuelTank.resource() : burnBuffer.fuel();
        return fuel.isEmpty() ? null : fuel.getData(IMekanismDataMapTypes.INSTANCE.chemicalFuel());
    }

    @ComputerMethod
    public int getBufferedFuelEnergy() {
        return burnBuffer == null ? 0 : burnBuffer.energy();
    }

    @Override
    public int getProductionRate() {
        return productionLastTick;
    }

    @Override
    public void readSustainedData(ValueInput input) {
        super.readSustainedData(input);
        burnBuffer = input.read("fuelBurnBuffer", FuelBurnBuffer.CODEC).orElse(null);
    }

    @Override
    public void writeSustainedData(ValueOutput output) {
        super.writeSustainedData(output);
        if (burnBuffer != null) output.store("fuelBurnBuffer", FuelBurnBuffer.CODEC, burnBuffer);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter input) {
        super.applyImplicitComponents(input);
        burnBuffer = input.get(MekanismDataComponents.FUEL_BURN_BUFFER);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (burnBuffer != null) builder.set(MekanismDataComponents.FUEL_BURN_BUFFER, burnBuffer);
    }

    @Override
    public List<DataComponentType<?>> getRemapEntries() {
        var entries = super.getRemapEntries();
        if (!entries.contains(MekanismDataComponents.FUEL_BURN_BUFFER.get())) entries.add(MekanismDataComponents.FUEL_BURN_BUFFER.get());
        return entries;
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("fuelBurnBuffer");
    }
}
