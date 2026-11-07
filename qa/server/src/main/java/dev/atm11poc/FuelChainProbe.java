package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.Upgrade;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.heat.HeatAPI;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.boiler.BoilerMultiblockData;
import mekanism.common.content.evaporation.EvaporationMultiblockData;
import mekanism.common.lib.radiation.RadiationSource;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismChemicals;
import mekanism.common.registries.MekanismFluids;
import mekanism.common.tile.TileEntityRadioactiveWasteBarrel;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.tile.machine.TileEntityElectricPump;
import mekanism.common.tile.machine.TileEntitySolarNeutronActivator;
import mekanism.common.tile.multiblock.TileEntityBoilerCasing;
import mekanism.common.tile.multiblock.TileEntityThermalEvaporationController;
import mekanism.common.util.WorldUtils;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.TileEntitySolarGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

final class FuelChainProbe {
    private static final BlockPos SNA = new BlockPos(8, 80, 336);
    private static final BlockPos PUMP = SNA.east(5);
    private static final BlockPos WASTE = SNA.east(10);
    private static final BlockPos ISOTOPE = WASTE.east(3);
    private static final BlockPos BOILER = SNA.east(24);
    private static final BlockPos EVAP = SNA.east(40);
    private final BiConsumer<Boolean, String> check;
    private JsonObject saved;
    private int stage;
    private int ticks;
    private long activatorBefore;
    private long evapBefore;
    private long activatorInput;
    private long evapInput;

    FuelChainProbe(BiConsumer<Boolean, String> check) { this.check = check; }
    private TileEntitySolarNeutronActivator sna(ServerLevel l) { return (TileEntitySolarNeutronActivator) l.getBlockEntity(SNA); }
    private TileEntityElectricPump pump(ServerLevel l) { return (TileEntityElectricPump) l.getBlockEntity(PUMP); }
    private TileEntityRadioactiveWasteBarrel barrel(ServerLevel l, BlockPos p) { return (TileEntityRadioactiveWasteBarrel) l.getBlockEntity(p); }
    private BoilerMultiblockData boiler(ServerLevel l) { return ((TileEntityBoilerCasing) l.getBlockEntity(BOILER)).getMultiblock(); }
    private EvaporationMultiblockData evap(ServerLevel l) { return ((TileEntityThermalEvaporationController) l.getBlockEntity(EVAP.offset(1, 1, 0))).getMultiblock(); }

    private static final class ClimateAccess extends TileEntitySolarGenerator {
        private ClimateAccess() { super(BlockPos.ZERO, GeneratorsBlocks.SOLAR_GENERATOR.defaultState()); }
        static float peak(ServerLevel l, BlockPos p) { return new SolarCheck(l, p).getPeakMultiplier(); }
    }

    void prepare(ServerLevel l, JsonObject saved) {
        this.saved = saved;
        for (int x = 0; x <= 3; x++) for (int z = 20; z <= 21; z++) l.setChunkForced(x, z, true);
        var c = MekanismConfig.general;
        check.accept(c.maxSolarNeutronActivatorRate.get() == 256, "ATM10 peak neutron activation rate is 256 mB/t");
        check.accept(c.pumpHeavyWaterAmount.get() == 50, "ATM10 filtered pump yield is 50 mB per source");
        check.accept(c.evaporationTempMultiplier.get() == 1.85 && c.evaporationHeatCapacity.get() == 600,
              "ATM10 evaporation temperature multiplier and layer heat capacity restored");
        check.accept(c.radioactiveWasteBarrelProcessTicks.get() == 10 && c.radioactiveWasteBarrelDecayAmount.get() == 2,
              "ATM10 waste decay is two mB every ten ticks");
        check.accept(c.radiationSourceDecayRate.get() == 0.9975 && c.radiationTargetDecayRate.get() == 0.9975,
              "ATM10 source and target radiation decay rates restored");
        var radiation = new RadiationSource(SNA, 100);
        radiation.decay();
        check.accept(radiation.getMagnitude() == 99.75, "actual radiation source decay applies the ATM10 factor");
        var entity = net.minecraft.world.entity.EntityType.PIG.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        var exposure = new mekanism.common.lib.radiation.capability.RadiationEntity(entity);
        exposure.set(100);
        exposure.decay();
        check.accept(exposure.getRadiation() == 99.75, "actual entity radiation decay applies the ATM10 factor");
        if (saved != null) return;
        l.setBlockAndUpdate(SNA, MekanismBlocks.SOLAR_NEUTRON_ACTIVATOR.defaultState());
        sna(l).setControlType(RedstoneControl.HIGH);
        l.setBlockAndUpdate(PUMP, MekanismBlocks.ELECTRIC_PUMP.defaultState());
        pump(l).setControlType(RedstoneControl.HIGH);
        pump(l).getComponent().addUpgrades(Upgrade.FILTER, 1);
        l.setBlockAndUpdate(PUMP.below(2), Blocks.STONE.defaultBlockState());
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) l.setBlockAndUpdate(PUMP.below().relative(d), Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(PUMP.below(), Blocks.WATER.defaultBlockState());
        l.setBlockAndUpdate(WASTE, MekanismBlocks.RADIOACTIVE_WASTE_BARREL.defaultState());
        l.setBlockAndUpdate(ISOTOPE, MekanismBlocks.RADIOACTIVE_WASTE_BARREL.defaultState());
        barrel(l, ISOTOPE).getChemicalTank().setContents(ChemicalResource.of(MekanismChemicals.POLONIUM), 100, null);
        for (int x = 0; x < 4; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 4; z++) {
            boolean wall = x == 0 || x == 3 || y == 0 || y == 4 || z == 0 || z == 3;
            l.setBlockAndUpdate(BOILER.offset(x, y, z), wall ? MekanismBlocks.BOILER_CASING.defaultState() : Blocks.AIR.defaultBlockState());
        }
        for (int x = 1; x <= 2; x++) for (int z = 1; z <= 2; z++) l.setBlockAndUpdate(BOILER.offset(x, 2, z), MekanismBlocks.PRESSURE_DISPERSER.defaultState());
        l.setBlockAndUpdate(BOILER.offset(1, 1, 1), MekanismBlocks.SUPERHEATING_ELEMENT.defaultState());
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
            boolean wall = y == 0 || x == 0 || x == 3 || z == 0 || z == 3;
            l.setBlockAndUpdate(EVAP.offset(x, y, z), wall ? MekanismBlocks.THERMAL_EVAPORATION_BLOCK.defaultState() : Blocks.AIR.defaultBlockState());
        }
        l.setBlockAndUpdate(EVAP.offset(1, 1, 0), MekanismBlocks.THERMAL_EVAPORATION_CONTROLLER.defaultState());
    }

    boolean tick(ServerLevel l, int elapsed) {
        if (stage == 4) return true;
        var b = boiler(l);
        var e = evap(l);
        if (stage == 0) {
            if (sna(l).ticker < 40 || pump(l).ticker < 40 || !b.isFormed() || !e.isFormed()) {
                if (elapsed > 200) throw new IllegalStateException("Fuel-chain structures or machines did not start");
                return false;
            }
            var water = FluidResource.of(Fluids.WATER);
            var steam = ChemicalResource.of(MekanismChemicals.STEAM);
            var sodium = ChemicalResource.of(MekanismChemicals.SODIUM);
            check.accept(b.waterTank.capacityAsLong(water) == b.getWaterVolume() * 32000L, "formed boiler water storage matches ATM10 per-volume capacity");
            check.accept(b.superheatedCoolantTank.capacityAsLong(ChemicalResource.of(MekanismChemicals.SUPERHEATED_SODIUM)) == b.getWaterVolume() * 512000L,
                  "formed boiler heated coolant storage matches ATM10 capacity");
            check.accept(b.steamTank.capacityAsLong(steam) == 32L * 320000, "formed five-layer boiler steam storage matches ATM10 capacity");
            check.accept(b.cooledCoolantTank.capacityAsLong(sodium) == 32L * 512000, "formed five-layer boiler cooled coolant storage matches ATM10 capacity");
            check.accept(e.getHeatCapacitor().getHeatCapacity() == 4 * 600, "formed four-layer evaporation plant has 2400 J/K heat capacity");
            if (saved == null) {
                b.waterTank.setContents(water, b.waterTank.capacityAsLong(water) * 3 / 4, null);
                b.steamTank.setContents(steam, b.steamTank.capacityAsLong(steam) * 3 / 4, null);
                b.cooledCoolantTank.setContents(sodium, b.cooledCoolantTank.capacityAsLong(sodium) * 3 / 4, null);
            } else {
                check.accept(b.waterTank.amountAsLong() == saved.get("boilerWater").getAsLong(), "boiler water above old capacity survives fresh JVM");
                check.accept(b.steamTank.amountAsLong() == saved.get("boilerSteam").getAsLong(), "boiler steam above old capacity survives fresh JVM");
                check.accept(b.cooledCoolantTank.amountAsLong() == saved.get("boilerSodium").getAsLong(), "boiler cooled coolant above old capacity survives fresh JVM");
                check.accept(sna(l).inputTank.amountAsLong() == saved.get("activatorInput").getAsLong() && sna(l).outputTank.amountAsLong() == saved.get("polonium").getAsLong(),
                      "neutron activation input and output survive fresh JVM while paused");
                check.accept(e.outputTank.amountAsLong() == saved.get("brine").getAsLong(), "evaporation brine survives fresh JVM");
                check.accept(pump(l).fluidTank.amountAsLong() == saved.get("heavyWater").getAsLong() && pump(l).getUpgrades(Upgrade.FILTER) == 1,
                      "filtered pump heavy water and upgrade survive fresh JVM");
            }
            check.accept(barrel(l, ISOTOPE).getChemicalTank().amountAsLong() == 100, "blacklisted polonium does not decay in a real waste barrel");
            l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack(), "time set noon");
            l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack(), "weather clear");
            stage = 1;
            return false;
        }
        if (stage == 1) {
            activatorBefore = sna(l).outputTank.amountAsLong();
            evapBefore = e.outputTank.amountAsLong();
            sna(l).inputTank.setContents(ChemicalResource.of(MekanismChemicals.NUCLEAR_WASTE), 10000, null);
            activatorInput = 10000;
            e.inputTank.setContents(FluidResource.of(Fluids.WATER), 100000, null);
            evapInput = 100000;
            e.getHeatCapacitor().setHeat(3000 * e.getHeatCapacitor().getHeatCapacity(), null);
            pump(l).fluidTank.setContents(FluidResource.EMPTY, 0, null);
            pump(l).getEnergyContainer().setEnergy(pump(l).getEnergyContainer().getCapacityAsLong(), null);
            barrel(l, WASTE).getChemicalTank().setContents(ChemicalResource.of(MekanismChemicals.NUCLEAR_WASTE), 100, null);
            sna(l).setControlType(RedstoneControl.DISABLED);
            pump(l).setControlType(RedstoneControl.DISABLED);
            stage = 2;
            return false;
        }
        ticks++;
        if (ticks == 1) {
            int activated = (int) (256 * ClimateAccess.peak(l, SNA) * WorldUtils.getSunBrightness(l, SNA));
            check.accept(activated > 64, "sunlit activator fixture distinguishes ATM10 rate from old 64 mB/t basis");
            check.accept(sna(l).outputTank.amountAsLong() - activatorBefore == activated && activatorInput - sna(l).inputTank.amountAsLong() == activated * 10L,
                  "one actual sunlit tick converts exact ATM10-rate nuclear waste to polonium");
            sna(l).setControlType(RedstoneControl.HIGH);
            int evaporated = (int) ((Math.min(3000, e.getTemperature()) - HeatAPI.AMBIENT_TEMP) * 1.85 * 4 / 18);
            check.accept(e.outputTank.amountAsLong() - evapBefore == evaporated && evapInput - e.inputTank.amountAsLong() == evaporated * 10L,
                  "one actual heated evaporation tick conserves ten water per brine at ATM10 rate");
            check.accept(evaporated > 1000, "heated plant fixture distinguishes restored evaporation rate");
            e.inputTank.setContents(FluidResource.EMPTY, 0, null);
        }
        check.accept(barrel(l, WASTE).getChemicalTank().amountAsLong() == 100 - 2 * (ticks / 10), "actual radioactive waste barrel obeys two mB per ten ticks: " + ticks);
        check.accept(barrel(l, ISOTOPE).getChemicalTank().amountAsLong() == 100, "polonium decay blacklist holds during measured tick " + ticks);
        if (ticks < 20) return false;
        check.accept(pump(l).fluidTank.amountAsLong() == 50 && pump(l).fluidTank.resource().is(MekanismFluids.HEAVY_WATER),
              "actual filtered pump extracts exactly 50 mB heavy water from one source");
        pump(l).setControlType(RedstoneControl.HIGH);
        barrel(l, WASTE).getChemicalTank().setContents(ChemicalResource.EMPTY, 0, null);
        stage = 4;
        return true;
    }

    Map<String, Object> snapshot(ServerLevel l) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("boilerWater", boiler(l).waterTank.amountAsLong());
        r.put("boilerSteam", boiler(l).steamTank.amountAsLong());
        r.put("boilerSodium", boiler(l).cooledCoolantTank.amountAsLong());
        r.put("activatorInput", sna(l).inputTank.amountAsLong());
        r.put("polonium", sna(l).outputTank.amountAsLong());
        r.put("brine", evap(l).outputTank.amountAsLong());
        r.put("heavyWater", pump(l).fluidTank.amountAsLong());
        r.put("isotope", barrel(l, ISOTOPE).getChemicalTank().amountAsLong());
        return r;
    }
}
