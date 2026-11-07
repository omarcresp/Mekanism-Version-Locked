package dev.atm11poc;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.radiation.IRadiationManager;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.registries.MekanismChemicals;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.content.fission.FissionReactorMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.fission.TileEntityFissionReactorCasing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Real coolant loss and destructive meltdown, confined to disposable, distant fixtures. */
final class ReactorSafetyProbe {
    private static final BlockPos RECOVERY = new BlockPos(24, 80, 600);
    private static final BlockPos MELTDOWN = new BlockPos(200, 80, 800);
    private final BiConsumer<Boolean, String> check;
    private JsonObject saved;
    private int recoveryStage;
    private int meltdownStage;
    private int shutdownTicks;
    private int explosionTick;
    private int damagedTicks;
    private long shutdownFuel;
    private double shutdownDamage;
    private double shutdownTemperature;
    private double peakDamage;
    private double postMeltdownDamage;
    private double postMeltdownTemperature;
    private double fallout;
    private FissionReactorMultiblockData failedReactor;

    ReactorSafetyProbe(BiConsumer<Boolean, String> check) { this.check = check; }

    void prepare(ServerLevel level, JsonObject saved) {
        this.saved = saved;
        for (BlockPos base : new BlockPos[]{RECOVERY, MELTDOWN}) {
            for (int x = (base.getX() - 16) >> 4; x <= (base.getX() + 20) >> 4; x++) {
                for (int z = (base.getZ() - 16) >> 4; z <= (base.getZ() + 20) >> 4; z++) level.setChunkForced(x, z, true);
            }
            if (saved == null) build(level, base, false);
        }
        check.accept(MekanismGeneratorsConfig.generators.fissionMeltdownsEnabled.get()
                    && MekanismGeneratorsConfig.generators.fissionMeltdownChance.get() == 0.001
                    && MekanismGeneratorsConfig.generators.fissionPostMeltdownDamage.get() == 75,
              "safety: ATM10 enabled meltdowns, natural chance and post-meltdown damage remain active");
    }

    private void build(ServerLevel level, BlockPos base, boolean repair) {
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
            BlockPos pos = base.offset(x, y, z);
            boolean wall = x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4;
            // Preserve surviving casing inventories and their multiblock cache during repair.
            if (!repair || !(level.getBlockEntity(pos) instanceof TileEntityFissionReactorCasing)) {
                level.setBlockAndUpdate(pos, wall ? GeneratorsBlocks.FISSION_REACTOR_CASING.defaultState() : Blocks.AIR.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(base.offset(2, 1, 2), GeneratorsBlocks.FISSION_FUEL_ASSEMBLY.defaultState());
        level.setBlockAndUpdate(base.offset(2, 2, 2), GeneratorsBlocks.FISSION_FUEL_ASSEMBLY.defaultState());
        level.setBlockAndUpdate(base.offset(2, 3, 2), GeneratorsBlocks.CONTROL_ROD_ASSEMBLY.defaultState());
        level.setBlockAndUpdate(base.offset(0, 2, 2), GeneratorsBlocks.FISSION_REACTOR_PORT.defaultState());
    }

    private FissionReactorMultiblockData reactor(ServerLevel level, BlockPos base) {
        // An explosion may remove any particular casing, so locate a surviving controller.
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
            if (level.getBlockEntity(base.offset(x, y, z)) instanceof TileEntityFissionReactorCasing tile && tile.getMultiblock().isFormed()) return tile.getMultiblock();
        }
        return null;
    }

    private void chargeFuel(ServerLevel level, BlockPos base, int amount) {
        var handler = level.getCapability(Capabilities.CHEMICAL.block(), base.offset(0, 2, 2), Direction.WEST);
        check.accept(handler != null, "safety: physical input port exposes fuel capability");
        try (var tx = Transaction.openRoot()) {
            check.accept(handler.insert(ChemicalResource.of(MekanismChemicals.FISSILE_FUEL), amount, tx) == amount,
                  "safety: fissile fuel enters physical input port");
            tx.commit();
        }
    }

    private void restoreCoolant(ServerLevel level, FissionReactorMultiblockData r) {
        var water = FluidResource.of(Fluids.WATER);
        var handler = level.getCapability(Capabilities.FLUID.block(), RECOVERY.offset(0, 2, 2), Direction.WEST);
        check.accept(handler != null, "safety: emergency cooling uses physical input port");
        try (var tx = Transaction.openRoot()) {
            int amount = (int) r.coolantTank.getFluidTank().capacityAsLong(water);
            check.accept(handler.insert(water, amount, tx) == amount, "safety: emergency coolant charge fully accepted");
            tx.commit();
        }
    }

    boolean tick(ServerLevel level, int elapsed, boolean ignitionComplete) {
        boolean recovery = recovery(level, elapsed, ignitionComplete);
        boolean meltdown = meltdown(level, elapsed, ignitionComplete);
        return recovery && meltdown;
    }

    private boolean recovery(ServerLevel level, int elapsed, boolean ignitionComplete) {
        var r = reactor(level, RECOVERY);
        if (r == null) {
            if (elapsed > 200) throw new IllegalStateException("Recovery reactor did not form");
            return false;
        }
        if (recoveryStage == 0) {
            if (saved != null) {
                var prior = saved.getAsJsonObject("recovery");
                check.accept(!r.isActive() && r.rateLimit == 2, "safety: damaged reactor shutdown and burn setting survive fresh JVM");
                check.accept(r.fuelTank.amountAsLong() == prior.get("fuel").getAsLong()
                            && r.wasteTank.amountAsLong() == prior.get("waste").getAsLong(),
                      "safety: damaged shutdown retains exact fuel and waste across JVMs");
                check.accept(r.reactorDamage > 0 && Math.abs(r.reactorDamage - prior.get("damage").getAsDouble()) < 0.02,
                      "safety: nonzero reactor damage persists across fresh JVM");
                shutdownFuel = r.fuelTank.amountAsLong();
                peakDamage = prior.get("peakDamage").getAsDouble();
                shutdownDamage = prior.get("shutdownDamage").getAsDouble();
                shutdownTemperature = prior.get("shutdownTemperature").getAsDouble();
                recoveryStage = 5;
            } else if (ignitionComplete) {
                chargeFuel(level, RECOVERY, 5000);
                check.accept(r.coolantTank.getFluidTank().isEmpty(), "safety: coolant-loss fixture starts dry");
                r.setRateLimit(2);
                r.setActive(true);
                recoveryStage = 1;
            }
            return false;
        }
        peakDamage = Math.max(peakDamage, r.reactorDamage);
        if (recoveryStage == 1 && r.reactorDamage >= 20) {
            check.accept(r.isActive() && r.heatCapacitor.getTemperature() > 1200,
                  "safety: coolant loss naturally crosses 1200 K and accumulates damage while burning");
            r.setActive(false);
            shutdownFuel = r.fuelTank.amountAsLong();
            shutdownDamage = r.reactorDamage;
            shutdownTemperature = r.heatCapacitor.getTemperature();
            recoveryStage = 2;
        } else if (recoveryStage == 2 && ++shutdownTicks == 10) {
            check.accept(r.fuelTank.amountAsLong() == shutdownFuel && r.lastBurnRate == 0,
                  "safety: manual emergency shutdown stops fuel burning for ten hot ticks");
            check.accept(r.reactorDamage > shutdownDamage && r.heatCapacitor.getTemperature() > 1200,
                  "safety: residual heat continues damaging a shut down dry reactor");
            restoreCoolant(level, r);
            recoveryStage = 3;
        } else if (recoveryStage == 3 && r.heatCapacitor.getTemperature() < 1200) {
            check.accept(r.heatedCoolantTank.amountAsLong() > 0 && r.reactorDamage > 0 && r.reactorDamage < 100,
                  "safety: restored coolant produces steam and prevents meltdown before damage threshold");
            recoveryStage = 4;
        } else if (recoveryStage == 5) {
            damagedTicks++;
            if (r.reactorDamage == 0) {
                check.accept(damagedTicks > 1 && r.fuelTank.amountAsLong() == shutdownFuel && !r.isActive(),
                      "safety: damaged reactor repairs naturally while shut down without consuming fuel");
                r.setActive(true);
                check.accept(r.isActive() && !r.isForceDisabled(), "safety: repaired reactor accepts restart");
                recoveryStage = 6;
                shutdownTicks = 0;
                shutdownFuel = r.fuelTank.amountAsLong();
            }
        } else if (recoveryStage == 6 && ++shutdownTicks == 20) {
            r.setActive(false);
            check.accept(r.fuelTank.amountAsLong() == shutdownFuel - 40 && r.reactorDamage == 0,
                  "safety: repaired reactor burns exactly 40 mB in 20 cooled ticks without new damage");
            recoveryStage = 7;
        }
        return recoveryStage == 4 || recoveryStage == 7;
    }

    private boolean meltdown(ServerLevel level, int elapsed, boolean ignitionComplete) {
        var r = reactor(level, MELTDOWN);
        if (meltdownStage == 0) {
            if (r == null) return false;
            if (saved != null) {
                var prior = saved.getAsJsonObject("meltdown");
                check.accept(!r.isActive() && r.fuelTank.isEmpty() && r.wasteTank.isEmpty(),
                      "safety: rebuilt melted reactor remains inactive and empty after JVM restart");
                check.accept(r.reactorDamage > 0 && Math.abs(r.reactorDamage - prior.get("damage").getAsDouble()) < 0.02,
                      "safety: post-meltdown damage survives JVM restart");
                check.accept(IRadiationManager.INSTANCE.getRadiationLevel(level, MELTDOWN.offset(2, 2, 2)) > IRadiationManager.INSTANCE.baselineRadiation(),
                      "safety: real meltdown fallout survives JVM restart");
                postMeltdownDamage = prior.get("postMeltdownDamage").getAsDouble();
                postMeltdownTemperature = prior.get("postMeltdownTemperature").getAsDouble();
                fallout = prior.get("fallout").getAsDouble();
                meltdownStage = 4;
            } else {
                check.accept(IRadiationManager.INSTANCE.getRadiationLevel(level, MELTDOWN.offset(2, 2, 2)) == IRadiationManager.INSTANCE.baselineRadiation(),
                      "safety: destructive fixture has no preexisting fallout");
                chargeFuel(level, MELTDOWN, 20000);
                r.setRateLimit(2);
                r.setActive(true);
                failedReactor = r;
                meltdownStage = 1;
            }
        } else if (meltdownStage == 1 && !failedReactor.isActive()) {
            fallout = IRadiationManager.INSTANCE.getRadiationLevel(level, MELTDOWN.offset(2, 2, 2));
            check.accept(failedReactor.reactorDamage == 75 && failedReactor.fuelTank.isEmpty() && failedReactor.wasteTank.isEmpty(),
                  "safety: natural meltdown clears radioactive contents, shuts down and resets damage to 75");
            postMeltdownDamage = failedReactor.reactorDamage;
            postMeltdownTemperature = failedReactor.heatCapacitor.getTemperature();
            check.accept(postMeltdownTemperature < 350 && fallout > IRadiationManager.INSTANCE.baselineRadiation(),
                  "safety: natural meltdown resets heat and releases real world radiation");
            check.accept(r == null, "safety: natural meltdown physically destroys reactor formation");
            explosionTick = elapsed;
            meltdownStage = 2;
        } else if (meltdownStage == 2 && elapsed > explosionTick + 200 && ignitionComplete) {
            build(level, MELTDOWN, true);
            meltdownStage = 3;
        } else if (meltdownStage == 3 && r != null) {
            check.accept(!r.isActive() && r.reactorDamage > 70 && r.reactorDamage <= 75,
                  "safety: physical rebuild restores damaged inactive reactor cache");
            check.accept(r.fuelTank.isEmpty() && r.wasteTank.isEmpty(), "safety: physical rebuild cannot resurrect irradiated fuel or waste");
            meltdownStage = 4;
        }
        return meltdownStage == 4;
    }

    Map<String, Object> snapshot(ServerLevel level) {
        var recovery = new LinkedHashMap<String, Object>(state(reactor(level, RECOVERY)));
        recovery.put("peakDamage", peakDamage);
        recovery.put("shutdownDamage", shutdownDamage);
        recovery.put("shutdownTemperature", shutdownTemperature);
        recovery.put("repairTicks", damagedTicks);
        var meltdown = new LinkedHashMap<String, Object>(state(reactor(level, MELTDOWN)));
        meltdown.put("postMeltdownDamage", postMeltdownDamage);
        meltdown.put("postMeltdownTemperature", postMeltdownTemperature);
        meltdown.put("fallout", fallout);
        return Map.of("recovery", recovery, "meltdown", meltdown);
    }

    private Map<String, Object> state(FissionReactorMultiblockData r) {
        return Map.of("fuel", r.fuelTank.amountAsLong(), "waste", r.wasteTank.amountAsLong(),
              "water", r.coolantTank.getFluidTank().amountAsLong(), "steam", r.heatedCoolantTank.amountAsLong(),
              "active", r.isActive(), "temperature", r.heatCapacitor.getTemperature(), "damage", r.reactorDamage);
    }
}
