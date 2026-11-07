package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import mekanism.api.RelativeSide;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismChemicals;
import mekanism.common.tile.TileEntityChemicalTank;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.transmitter.TileEntityMechanicalPipe;
import mekanism.common.tile.transmitter.TileEntityPressurizedTube;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import mekanism.generators.common.block.attribute.AttributeStateFissionPortMode;
import mekanism.generators.common.block.attribute.AttributeStateFissionPortMode.FissionPortMode;
import mekanism.generators.common.content.fission.FissionReactorMultiblockData;
import mekanism.generators.common.content.turbine.TurbineMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.tile.fission.TileEntityFissionReactorCasing;
import mekanism.generators.common.tile.turbine.TileEntityTurbineCasing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class ConnectedFissionProbe {
    enum Cooling { WATER, SODIUM }
    private final Cooling cooling;
    private final BlockPos REACTOR;
    private final BlockPos TURBINE;
    private final BlockPos FUEL;
    private final BlockPos WASTE;
    private final BlockPos RETURN;
    private final BlockPos CABLE;
    private final BlockPos CUBE;
    private final BlockPos BOILER;
    private final List<BlockPos> steam = new ArrayList<>();
    private final List<BlockPos> water = new ArrayList<>();
    private final List<BlockPos> heatedSodium = new ArrayList<>();
    private final List<BlockPos> cooledSodium = new ArrayList<>();
    private final List<BlockPos> fuel = new ArrayList<>();
    private final List<BlockPos> waste = new ArrayList<>();
    private final BiConsumer<Boolean, String> check;
    private JsonObject saved;
    private int stage;
    private int operationTicks;
    private long initialWater;
    private long initialSodium;
    private long previousBoilerWater;
    private boolean sawCondenserReturn;
    private long fuelAtStart;
    private long energyAtStart;
    private long production;
    private long bootProduction;
    private long previousReactorCoolant;
    private long decayedWaste;
    private int previousDecayProgress;
    private boolean sawReturn;
    private boolean observedHotRestart;
    private double firstRestartTemperature;

    ConnectedFissionProbe(BiConsumer<Boolean, String> check) { this(check, Cooling.WATER); }
    ConnectedFissionProbe(BiConsumer<Boolean, String> check, Cooling cooling) {
        this.check = check;
        this.cooling = cooling;
        int z = cooling == Cooling.WATER ? 400 : 480;
        REACTOR = new BlockPos(24, 80, z);
        TURBINE = new BlockPos(48, 80, z);
        BOILER = new BlockPos(36, 80, z);
        FUEL = new BlockPos(20, 81, z + 2);
        WASTE = new BlockPos(26, 80, z - 1);
        RETURN = cooling == Cooling.WATER ? new BlockPos(23, 82, z + 2) : BOILER.offset(2, 1, -1);
        CABLE = new BlockPos(65, 81, z + 8);
        CUBE = CABLE.east();
    }
    private mekanism.common.content.boiler.BoilerMultiblockData boiler(ServerLevel l) {
        return ((mekanism.common.tile.multiblock.TileEntityBoilerCasing) l.getBlockEntity(BOILER)).getMultiblock();
    }
    private long reactorCoolant(ServerLevel l) {
        return cooling == Cooling.WATER ? reactor(l).coolantTank.getFluidTank().amountAsLong() : reactor(l).coolantTank.getChemicalTank().amountAsLong();
    }
    private long sodiumTotal(ServerLevel l) {
        return reactorCoolant(l) + reactor(l).heatedCoolantTank.amountAsLong() + chemicalBuffer(l, heatedSodium) + chemicalBuffer(l, cooledSodium)
              + boiler(l).superheatedCoolantTank.amountAsLong() + boiler(l).cooledCoolantTank.amountAsLong();
    }
    private FissionReactorMultiblockData reactor(ServerLevel l) { return ((TileEntityFissionReactorCasing) l.getBlockEntity(REACTOR)).getMultiblock(); }
    private TurbineMultiblockData turbine(ServerLevel l) { return ((TileEntityTurbineCasing) l.getBlockEntity(TURBINE)).getMultiblock(); }
    private TileEntityChemicalTank tank(ServerLevel l, BlockPos p) { return (TileEntityChemicalTank) l.getBlockEntity(p); }
    private TileEntityPressurizedTube tube(ServerLevel l, BlockPos p) { return (TileEntityPressurizedTube) l.getBlockEntity(p); }
    private TileEntityMechanicalPipe pipe(ServerLevel l, BlockPos p) { return (TileEntityMechanicalPipe) l.getBlockEntity(p); }
    private long chemicalBuffer(ServerLevel l, List<BlockPos> p) { return tube(l, p.getFirst()).getTransmitter().getBufferWithFallback().amount(); }
    private long waterBuffer(ServerLevel l) { return pipe(l, water.getFirst()).getTransmitter().getBufferWithFallback().amount(); }
    private long fuelTotal(ServerLevel l) { return tank(l, FUEL).getChemicalTank().amountAsLong() + chemicalBuffer(l, fuel) + reactor(l).fuelTank.amountAsLong(); }
    private mekanism.common.tile.TileEntityRadioactiveWasteBarrel barrel(ServerLevel l) { return (mekanism.common.tile.TileEntityRadioactiveWasteBarrel) l.getBlockEntity(WASTE); }
    private long wasteTotal(ServerLevel l) { return barrel(l).getChemicalTank().amountAsLong() + chemicalBuffer(l, waste) + reactor(l).wasteTank.amountAsLong(); }
    private long coolantTotal(ServerLevel l) {
        long source = cooling == Cooling.WATER ? reactorCoolant(l) + reactor(l).heatedCoolantTank.amountAsLong()
              : boiler(l).waterTank.amountAsLong() + boiler(l).steamTank.amountAsLong();
        return source + chemicalBuffer(l, steam) + turbine(l).chemicalTank.amountAsLong() + turbine(l).ventTank.amountAsLong() + waterBuffer(l);
    }
    private long energyTotal(ServerLevel l) {
        return turbine(l).energyContainer().getAmountAsLong() + ((TileEntityUniversalCable) l.getBlockEntity(CABLE)).getTransmitter().getBufferWithFallback()
              + ((TileEntityEnergyCube) l.getBlockEntity(CUBE)).energyContainer().getAmountAsLong();
    }
    private static final class Builder extends GeneratorsBuilders.TurbineBuilder {
        void place(Level l, BlockPos start) { build(l, start, false); }
    }

    void prepare(ServerLevel l, JsonObject saved) {
        this.saved = saved;
        int baseZ = REACTOR.getZ();
        for (int x = 1; x <= 4; x++) for (int z = (baseZ - 2) >> 4; z <= (baseZ + 17) >> 4; z++) l.setChunkForced(x, z, true);
        int steamY = cooling == Cooling.WATER ? 81 : 83;
        for (int x = cooling == Cooling.WATER ? 29 : 40; x <= 47; x++) steam.add(new BlockPos(x, steamY, baseZ + 2));
        for (int z = baseZ + 3; z <= baseZ + 8; z++) steam.add(new BlockPos(47, steamY, z));
        if (cooling == Cooling.WATER) {
            for (int x = 23; x <= 47; x++) water.add(new BlockPos(x, 96, baseZ + 8));
            for (int y = 82; y < 96; y++) water.add(new BlockPos(23, y, baseZ + 8));
            for (int z = baseZ + 2; z < baseZ + 8; z++) water.add(new BlockPos(23, 82, z));
        } else {
            for (int x = 35; x <= 47; x++) water.add(new BlockPos(x, 96, baseZ + 8));
            for (int z = baseZ - 1; z < baseZ + 8; z++) water.add(new BlockPos(35, 96, z));
            for (int y = 81; y < 96; y++) water.add(new BlockPos(35, y, baseZ - 1));
            for (int x = 36; x <= 38; x++) water.add(new BlockPos(x, 81, baseZ - 1));
            for (int x = 29; x <= 35; x++) heatedSodium.add(new BlockPos(x, 81, baseZ + 2));
            for (int y = 83; y <= 86; y++) cooledSodium.add(new BlockPos(35, y, baseZ + 2));
            for (int x = 26; x < 35; x++) cooledSodium.add(new BlockPos(x, 86, baseZ + 2));
            cooledSodium.add(new BlockPos(26, 85, baseZ + 2));
        }
        for (int x = 21; x <= 23; x++) fuel.add(new BlockPos(x, 81, baseZ + 2));
        waste.add(new BlockPos(26, 81, baseZ - 1));
        if (saved != null) {
            initialWater = saved.get("coolant").getAsLong();
            decayedWaste = saved.get("decayedWaste").getAsLong();
            if (cooling == Cooling.SODIUM) initialSodium = saved.get("sodium").getAsLong();
            return;
        }
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
            boolean wall = x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4;
            l.setBlockAndUpdate(REACTOR.offset(x, y, z), wall ? GeneratorsBlocks.FISSION_REACTOR_CASING.defaultState() : Blocks.AIR.defaultBlockState());
        }
        l.setBlockAndUpdate(REACTOR.offset(2, 1, 2), GeneratorsBlocks.FISSION_FUEL_ASSEMBLY.defaultState());
        l.setBlockAndUpdate(REACTOR.offset(2, 2, 2), GeneratorsBlocks.FISSION_FUEL_ASSEMBLY.defaultState());
        l.setBlockAndUpdate(REACTOR.offset(2, 3, 2), GeneratorsBlocks.CONTROL_ROD_ASSEMBLY.defaultState());
        l.setBlockAndUpdate(REACTOR.offset(0, 1, 2), GeneratorsBlocks.FISSION_REACTOR_PORT.defaultState());
        l.setBlockAndUpdate(cooling == Cooling.WATER ? REACTOR.offset(0, 2, 2) : REACTOR.offset(2, 4, 2), GeneratorsBlocks.FISSION_REACTOR_PORT.defaultState());
        l.setBlockAndUpdate(REACTOR.offset(4, 1, 2), GeneratorsBlocks.FISSION_REACTOR_PORT.defaultState().setValue(AttributeStateFissionPortMode.modeProperty, FissionPortMode.OUTPUT_COOLANT));
        l.setBlockAndUpdate(REACTOR.offset(2, 1, 0), GeneratorsBlocks.FISSION_REACTOR_PORT.defaultState().setValue(AttributeStateFissionPortMode.modeProperty, FissionPortMode.OUTPUT_WASTE));
        if (cooling == Cooling.SODIUM) buildBoiler(l);
        new Builder().place(l, TURBINE);
        l.setBlockAndUpdate(TURBINE.offset(0, steamY - 80, 8), GeneratorsBlocks.TURBINE_VALVE.defaultState());
        l.setBlockAndUpdate(TURBINE.offset(16, 1, 8), GeneratorsBlocks.TURBINE_VALVE.defaultState());
        l.setBlockAndUpdate(FUEL, MekanismBlocks.ULTIMATE_CHEMICAL_TANK.defaultState());
        l.setBlockAndUpdate(WASTE, MekanismBlocks.RADIOACTIVE_WASTE_BARREL.defaultState());
        configureTank(l, FUEL, DataType.OUTPUT, false);
        for (var positions : List.of(steam, fuel, waste, heatedSodium, cooledSodium)) for (var p : positions) l.setBlockAndUpdate(p, MekanismBlocks.ULTIMATE_PRESSURIZED_TUBE.defaultState());
        for (var p : water) l.setBlockAndUpdate(p, MekanismBlocks.ULTIMATE_MECHANICAL_PIPE.defaultState());
        l.setBlockAndUpdate(CABLE, MekanismBlocks.ULTIMATE_UNIVERSAL_CABLE.defaultState());
        l.setBlockAndUpdate(CUBE, MekanismBlocks.ULTIMATE_ENERGY_CUBE.defaultState());
        var cube = (TileEntityEnergyCube) l.getBlockEntity(CUBE);
        var config = cube.getConfig().getConfig(TransmissionType.ENERGY);
        for (var side : RelativeSide.values()) if (config.setDataType(DataType.INPUT, side)) cube.getConfig().sideChanged(TransmissionType.ENERGY, side);
        config.setEjecting(false);
    }

    private void buildBoiler(ServerLevel l) {
        for (int x = 0; x < 4; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 4; z++) {
            boolean wall = x == 0 || x == 3 || y == 0 || y == 4 || z == 0 || z == 3;
            l.setBlockAndUpdate(BOILER.offset(x, y, z), wall ? MekanismBlocks.BOILER_CASING.defaultState() : Blocks.AIR.defaultBlockState());
        }
        for (int x = 1; x <= 2; x++) for (int z = 1; z <= 2; z++) l.setBlockAndUpdate(BOILER.offset(x, 2, z), MekanismBlocks.PRESSURE_DISPERSER.defaultState());
        l.setBlockAndUpdate(BOILER.offset(1, 1, 1), MekanismBlocks.SUPERHEATING_ELEMENT.defaultState());
        var valve = MekanismBlocks.BOILER_VALVE.defaultState();
        var property = mekanism.common.block.attribute.AttributeStateBoilerValveMode.modeProperty;
        l.setBlockAndUpdate(BOILER.offset(0, 1, 2), valve);
        l.setBlockAndUpdate(BOILER.offset(2, 1, 0), valve);
        l.setBlockAndUpdate(BOILER.offset(0, 3, 2), valve.setValue(property, mekanism.common.block.attribute.AttributeStateBoilerValveMode.BoilerValveMode.OUTPUT_COOLANT));
        l.setBlockAndUpdate(BOILER.offset(3, 3, 2), valve.setValue(property, mekanism.common.block.attribute.AttributeStateBoilerValveMode.BoilerValveMode.OUTPUT_STEAM));
    }

    private void configureTank(ServerLevel l, BlockPos p, DataType mode, boolean eject) {
        var tile = tank(l, p);
        var config = tile.getConfig().getConfig(TransmissionType.CHEMICAL);
        for (var side : RelativeSide.values()) if (config.setDataType(mode, side)) tile.getConfig().sideChanged(TransmissionType.CHEMICAL, side);
        config.setEjecting(eject);
    }

    private boolean networksReady(ServerLevel l) {
        for (var positions : List.of(steam, fuel, waste, heatedSodium, cooledSodium)) {
            if (positions.isEmpty()) continue;
            var first = tube(l, positions.getFirst()).getTransmitter();
            if (!first.hasTransmitterNetwork()) return false;
            for (var p : positions) if (tube(l, p).getTransmitter().getTransmitterNetwork() != first.getTransmitterNetwork()) return false;
        }
        var first = pipe(l, water.getFirst()).getTransmitter();
        if (!first.hasTransmitterNetwork()) return false;
        for (var p : water) if (pipe(l, p).getTransmitter().getTransmitterNetwork() != first.getTransmitterNetwork()) return false;
        return ((TileEntityUniversalCable) l.getBlockEntity(CABLE)).getTransmitter().hasTransmitterNetwork();
    }

    boolean tick(ServerLevel l, int elapsed, boolean ignitionChecksComplete) {
        if (stage == 5) return true;
        var sync = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, l.registryAccess());
        barrel(l).writeReducedUpdatedTag(sync);
        int progress = sync.buildResult().getIntOr(mekanism.api.SerializationConstants.PROGRESS, 0);
        if (progress < previousDecayProgress) decayedWaste += 2;
        previousDecayProgress = progress;
        var r = reactor(l);
        var t = turbine(l);
        if (saved != null && t.isFormed()) bootProduction += t.getProductionRate();
        if (saved != null && r.isFormed() && !observedHotRestart) {
            firstRestartTemperature = r.getHeatCapacitor().getTemperature();
            check.accept(saved.get("active").getAsBoolean() && r.isActive(), "active connected fission state restores before QA changes it");
            check.accept(firstRestartTemperature > 350 && Math.abs(firstRestartTemperature - saved.get("temperature").getAsDouble()) < 10,
                  "first formed restart tick restores hot reactor temperature: " + firstRestartTemperature + " K at tick " + elapsed);
            observedHotRestart = true;
        }
        if (stage == 0) {
            if (elapsed < 40 || !r.isFormed() || !t.isFormed() || (cooling == Cooling.SODIUM && !boiler(l).isFormed()) || !networksReady(l)) {
                if (elapsed > 200) throw new IllegalStateException("Connected fission structures or transport networks did not form");
                return false;
            }
            check.accept(t.blades == 28 && t.condensers > 0, "connected turbine forms with full rotor and condensers");
            check.accept(r.reactorDamage == 0, "connected reactor begins without damage");
            if (saved == null) {
                var waterType = FluidResource.of(Fluids.WATER);
                var handler = l.getCapability(Capabilities.FLUID.block(), cooling == Cooling.WATER ? RETURN.east() : RETURN.south(), cooling == Cooling.WATER ? Direction.WEST : Direction.NORTH);
                initialWater = cooling == Cooling.WATER ? r.coolantTank.getFluidTank().capacityAsLong(waterType) : boiler(l).waterTank.capacityAsLong(waterType);
                check.accept(handler != null, "real coolant circuit input port exposes external fluid capability");
                try (var tx = Transaction.openRoot()) {
                    check.accept(handler.insert(FluidResource.of(Fluids.WATER), (int) initialWater, tx) == initialWater, "initial coolant charge enters through real input port");
                    tx.commit();
                }
                if (cooling == Cooling.SODIUM) {
                    var sodium = ChemicalResource.of(MekanismChemicals.SODIUM);
                    initialSodium = r.coolantTank.getChemicalTank().capacityAsLong(sodium);
                    var sodiumHandler = l.getCapability(Capabilities.CHEMICAL.block(), REACTOR.offset(2, 4, 2), Direction.UP);
                    check.accept(sodiumHandler != null, "real reactor roof input port exposes sodium capability");
                    try (var tx = Transaction.openRoot()) {
                        check.accept(sodiumHandler.insert(sodium, (int) initialSodium, tx) == initialSodium, "initial sodium charge enters real reactor input port");
                        tx.commit();
                    }
                }
                tank(l, FUEL).getChemicalTank().setContents(ChemicalResource.of(MekanismChemicals.FISSILE_FUEL), 3000, null);
                configureTank(l, FUEL, DataType.OUTPUT, true);
                r.setRateLimit(1);
            } else {
                check.accept(observedHotRestart && r.isActive(), "connected reactor continues active after network reconstruction");
                check.accept(energyTotal(l) == saved.get("energy").getAsLong() + bootProduction,
                      "exact saved circuit FE plus observed boot production survives JVM restart");
                check.accept(fuelTotal(l) < saved.get("fuel").getAsLong() && wasteTotal(l) + decayedWaste > saved.get("waste").getAsLong() + saved.get("decayedWaste").getAsLong(),
                      "active hot restart burns fuel and moves waste before QA resumes measurement");
            }
            stage = 1;
            return false;
        }
        if (stage == 1) {
            if (r.fuelTank.amountAsLong() < 1000 || !ignitionChecksComplete) return false;
            check.accept(coolantTotal(l) == initialWater, "all water remains in the connected circuit before measurement");
            if (cooling == Cooling.SODIUM) check.accept(sodiumTotal(l) == initialSodium, "all sodium remains in the separate reactor boiler circuit before measurement");
            check.accept(fuelTotal(l) + wasteTotal(l) + decayedWaste == 3000, "physical fuel, remaining waste and independently counted barrel decay conserve all 3000 mB");
            if (saved == null) {
                check.accept(tank(l, FUEL).getChemicalTank().amountAsLong() < 3000, "external chemical tank supplies fissile fuel through physical tubes and port");
                r.setActive(true);
            }
            fuelAtStart = fuelTotal(l);
            energyAtStart = energyTotal(l);
            previousReactorCoolant = reactorCoolant(l);
            if (cooling == Cooling.SODIUM) previousBoilerWater = boiler(l).waterTank.amountAsLong();
            stage = 2;
            return false;
        }
        operationTicks++;
        production += t.getProductionRate();
        long currentCoolant = reactorCoolant(l);
        if (currentCoolant > previousReactorCoolant - r.lastBoilRate) sawReturn = true;
        previousReactorCoolant = currentCoolant;
        if (cooling == Cooling.SODIUM) {
            long boilerWater = boiler(l).waterTank.amountAsLong();
            if (boilerWater > previousBoilerWater - boiler(l).lastBoilRate) sawCondenserReturn = true;
            previousBoilerWater = boilerWater;
        }
        if (stage == 3) {
            if (operationTicks < 10) return false;
            check.accept(fuelTotal(l) == fuelAtStart && !r.isActive(), "reactor shutdown stops fuel consumption for ten actual updates");
            check.accept(coolantTotal(l) == initialWater && r.reactorDamage == 0, "closed coolant loop continues safely after shutdown");
            if (cooling == Cooling.SODIUM) check.accept(sodiumTotal(l) == initialSodium, "closed sodium circuit conserves coolant after shutdown");
            stage = 5;
            return true;
        }
        if (operationTicks % 50 == 0) {
            check.accept(coolantTotal(l) == initialWater, "physical steam and water loop conserves coolant at tick " + operationTicks);
            if (cooling == Cooling.SODIUM) check.accept(sodiumTotal(l) == initialSodium, "physical heated and cooled sodium networks conserve coolant at tick " + operationTicks);
            check.accept(fuelAtStart - fuelTotal(l) == operationTicks && fuelTotal(l) + wasteTotal(l) + decayedWaste == 3000,
                  "one mB burn and physical waste output plus barrel decay conserve fuel at tick " + operationTicks);
            check.accept(energyTotal(l) - energyAtStart == production, "turbine and physical cable receiver conserve all reported FE at tick " + operationTicks);
            check.accept(r.reactorDamage == 0 && currentCoolant > 0, "connected cooling prevents damage and keeps coolant at tick " + operationTicks);
        }
        if (operationTicks < (saved == null ? 400 : 200)) return false;
        check.accept(sawReturn, "cooled coolant returns through physical transport into the reactor coolant port");
        if (cooling == Cooling.SODIUM) {
            check.accept(sawCondenserReturn, "condenser water returns through physical pipes into boiler input valve");
            check.accept(boiler(l).getTemperature() > 350, "actual hot sodium heats the boiler and produces turbine steam");
        }
        check.accept(barrel(l).getChemicalTank().amountAsLong() > 0, "real waste output port delivers nuclear waste through tubes into the barrel top");
        check.accept(((TileEntityEnergyCube) l.getBlockEntity(CUBE)).energyContainer().getAmountAsLong() > 0,
              "reactor steam produces turbine power delivered through real valve and cable to energy cube");
        check.accept(r.getHeatCapacitor().getTemperature() > 350 && production > 0, "connected fission operates hot and produces committed electrical output");
        if (saved == null) { stage = 5; return true; }
        r.setActive(false);
        fuelAtStart = fuelTotal(l);
        operationTicks = 0;
        stage = 3;
        return false;
    }

    Map<String, Object> snapshot(ServerLevel l) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fuel", fuelTotal(l));
        r.put("waste", wasteTotal(l));
        r.put("decayedWaste", decayedWaste);
        r.put("coolant", coolantTotal(l));
        r.put("energy", energyTotal(l));
        r.put("cubeEnergy", ((TileEntityEnergyCube) l.getBlockEntity(CUBE)).energyContainer().getAmountAsLong());
        r.put("active", reactor(l).isActive());
        r.put("temperature", reactor(l).getHeatCapacitor().getTemperature());
        r.put("damage", reactor(l).reactorDamage);
        r.put("observedReturnedWater", sawReturn);
        r.put("reportedProduction", production);
        r.put("firstRestartTemperature", firstRestartTemperature);
        if (cooling == Cooling.SODIUM) {
            r.put("sodium", sodiumTotal(l));
            r.put("boilerTemperature", boiler(l).getTemperature());
            r.put("observedCondenserReturn", sawCondenserReturn);
        }
        return r;
    }
}
