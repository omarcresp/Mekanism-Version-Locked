package dev.atm11poc;

import java.util.Map;
import java.util.function.BiConsumer;
import mekanism.api.AutomationType;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.laser.TileEntityLaserAmplifier;
import mekanism.generators.common.content.fusion.FusionReactorMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.registries.GeneratorsChemicals;
import mekanism.generators.common.registries.GeneratorsItems;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Physical laser ignition, electrical/thermal conversion, and saved fractional output. */
final class FusionProbe {
    private static final BlockPos START = new BlockPos(80, 80, 24);
    private static final BlockPos LASER = START.offset(-3, 2, 2);
    private static final BlockPos SOURCE = LASER.offset(0, 0, -3);
    private static final int IGNITION_FE = 400000000;
    private final BiConsumer<Boolean, String> check;
    private boolean reopen;
    private boolean supplied;
    private boolean complete;
    private int thermalPulses;
    private int operatingTicks;
    private long initialDeuterium;
    private long initialTritium;
    private double ambient;
    private int suppliedFE;
    private boolean ignited;

    FusionProbe(BiConsumer<Boolean, String> check) { this.check = check; }

    private static final class Builder extends GeneratorsBuilders.FusionReactorBuilder {
        void place(Level level) { build(level, START, false); }
    }

    void prepare(ServerLevel level, boolean reopen) {
        this.reopen = reopen;
        level.setChunkForced(4, 1, true);
        level.setChunkForced(5, 1, true);
        if (!reopen) {
            new Builder().place(level);
            level.setBlockAndUpdate(START.offset(0, 2, 2), GeneratorsBlocks.LASER_FOCUS_MATRIX.defaultState());
            level.setBlockAndUpdate(LASER, Attribute.setFacing(MekanismBlocks.LASER_AMPLIFIER.defaultState(), Direction.EAST));
            level.setBlockAndUpdate(SOURCE, Attribute.setFacing(MekanismBlocks.LASER.defaultState(), Direction.SOUTH));
        }
    }

    private FusionReactorMultiblockData reactor(ServerLevel level) {
        return ((TileEntityFusionReactorController) level.getBlockEntity(START.offset(2, 4, 2))).getMultiblock();
    }

    private void thermalPulse(FusionReactorMultiblockData reactor) {
        try (var tx = Transaction.openRoot()) {
            // Equal plasma/case temperatures prevent internal heat transfer. The case
            // then loses 30 J to air; at 4% efficiency this generates one whole Joule.
            reactor.setPlasmaTemp(ambient + 90, tx);
            reactor.getHeatCapacitor().setHeat((ambient + 90) * reactor.getHeatCapacitor().getHeatCapacity(), tx);
            tx.commit();
        }
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        var reactor = reactor(level);
        if (!reactor.isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Real fusion structure did not form within 200 ticks");
            return false;
        }
        if (!supplied) {
            ambient = reactor.getHeatCapacitor().getTemperature();
            check.accept(mekanism.common.config.MekanismConfig.usage.laser.get() == 20000, "ATM10 powered laser consumes 20000 FE per firing tick");
            check.accept(reactor.energyContainer().getCapacityAsLong() == 400000000, "ATM10 fusion buffer is 400 million FE");
            check.accept(reactor.deuteriumTank.capacityAsLong(GeneratorsChemicals.DEUTERIUM.asResource()) == 500,
                  "ATM10 fusion fuel tank holds 500 mB");
            if (reopen) {
                check.accept(!reactor.isBurning() && reactor.getInjectionRate() == 4, "fusion shutdown and injection rate survive JVM restart");
                check.accept(reactor.energyContainer().getAmountAsLong() == 12345, "fusion output energy survives JVM restart exactly");
                check.accept(Math.abs(reactor.getGenerationRemainder() - 0.4) < 1e-9, "fractional fusion output survives JVM restart");
                check.accept(reactor.getHeatCapacitor().getHeatCapacity() == 1, "fusion case heat capacity survives JVM restart");
            } else {
                check.accept(!reactor.isBurning(), "fusion begins unignited");
                double initial = reactor.getPlasmaTemp();
                try (var tx = Transaction.openRoot()) {
                    reactor.addTemperatureFromEnergyInput(400000000, tx);
                    reactor.setGenerationRemainder(0.8, tx);
                    check.accept(Math.abs(reactor.getPlasmaTemp() - initial - 100000000) < 1e-6,
                          "400 million FE supplies one billion Joules for cold-plasma heating");
                }
                check.accept(reactor.getPlasmaTemp() == initial && reactor.getGenerationRemainder() == 0,
                      "aborted fusion energy and fractional state roll back together");
            }
            thermalPulse(reactor);
            supplied = true;
            return false;
        }
        if (thermalPulses < (reopen ? 2 : 3)) {
            thermalPulses++;
            if (thermalPulses < (reopen ? 2 : 3)) { thermalPulse(reactor); return false; }
            check.accept(reactor.energyContainer().getAmountAsLong() == (reopen ? 12346 : 1),
                  "whole Joules accumulate into FE without discarding each tick's fraction");
            check.accept(Math.abs(reactor.getGenerationRemainder() - 0.2) < 1e-8,
                  "fusion conversion retains the exact remaining fifth of one FE");
            if (reopen) { complete = true; return true; }
            var hohlraum = ContainerType.CHEMICAL.getFilledVariant(GeneratorsItems.HOHLRAUM, GeneratorsChemicals.FUSION_FUEL, null);
            reactor.getInventorySlots().getFirst().setContents(ItemResource.of(hohlraum), 1, null);
            reactor.deuteriumTank.setContents(GeneratorsChemicals.DEUTERIUM.asResource(), 500, null);
            reactor.tritiumTank.setContents(GeneratorsChemicals.TRITIUM.asResource(), 500, null);
            initialDeuterium = reactor.deuteriumTank.amountAsLong();
            initialTritium = reactor.tritiumTank.amountAsLong();
            reactor.setInjectionRate(4);
            var laser = (TileEntityLaserAmplifier) level.getBlockEntity(LASER);
            check.accept(laser.getDirection() == Direction.EAST, "real laser amplifier points through the focus matrix");
            laser.setMinThresholdFromPacket(IGNITION_FE);
            laser.setMaxThresholdFromPacket(IGNITION_FE);
            supplyLaser(level);
            level.getServer().tickRateManager().requestGameToSprint(20500);
            check.accept(level.getServer().tickRateManager().isSprinting(),
                  "isolated server accelerates normal laser charging ticks");
            return false;
        }
        if (!ignited) {
            if (suppliedFE < IGNITION_FE) supplyLaser(level);
            if (!reactor.isBurning()) return false;
            check.accept(suppliedFE == IGNITION_FE, "normal laser source receives exactly 400 million FE for ignition");
            check.accept(((TileEntityLaserAmplifier) level.getBlockEntity(LASER)).getEnergyContainer().isEmpty() &&
                  ((mekanism.common.tile.laser.TileEntityLaser) level.getBlockEntity(SOURCE)).getEnergyContainer().isEmpty(),
                  "laser source and amplifier transfer the full electrical budget through real beams");
            ignited = true;
            return false;
        }
        if (++operatingTicks < 100) return false;
        check.accept(((TileEntityLaserAmplifier) level.getBlockEntity(LASER)).getEnergyContainer().isEmpty(), "laser amplifier transfers its full stored pulse");
        check.accept(reactor.getInventorySlots().getFirst().isEmpty(), "physical laser ignition consumes the filled hohlraum");
        check.accept(reactor.isBurning(), "fusion sustains burning for 100 ticks after physical laser ignition");
        check.accept(reactor.deuteriumTank.amountAsLong() < initialDeuterium && reactor.tritiumTank.amountAsLong() < initialTritium,
              "fusion injects both deuterium and tritium");
        check.accept(initialDeuterium - reactor.deuteriumTank.amountAsLong() == initialTritium - reactor.tritiumTank.amountAsLong(),
              "fusion consumes equal quantities of deuterium and tritium");
        check.accept(reactor.energyContainer().getAmountAsLong() > 1, "fusion generates stored FE after physical ignition");
        level.getServer().tickRateManager().stopSprinting();
        complete = true;
        return true;
    }

    private void supplyLaser(ServerLevel level) {
        var source = (mekanism.common.tile.laser.TileEntityLaser) level.getBlockEntity(SOURCE);
        int amount = Math.min(20000, IGNITION_FE - suppliedFE);
        try (var tx = Transaction.openRoot()) {
            int inserted = source.getEnergyContainer().insert(amount, tx, AutomationType.EXTERNAL);
            if (inserted != amount) throw new IllegalStateException("Laser source rejected its bounded electrical supply");
            tx.commit();
            suppliedFE += inserted;
        }
    }

    Map<String, Object> shutdownForSave(ServerLevel level) {
        var reactor = reactor(level);
        reactor.deuteriumTank.setContents(mekanism.api.chemical.ChemicalResource.EMPTY, 0, null);
        reactor.tritiumTank.setContents(mekanism.api.chemical.ChemicalResource.EMPTY, 0, null);
        reactor.fuelTank.setContents(mekanism.api.chemical.ChemicalResource.EMPTY, 0, null);
        reactor.setBurning(false);
        try (var tx = Transaction.openRoot()) {
            reactor.setPlasmaTemp(ambient, tx);
            reactor.getHeatCapacitor().setHeat(ambient * reactor.getHeatCapacitor().getHeatCapacity(), tx);
            reactor.setGenerationRemainder(0.4, tx);
            reactor.energyContainer().setEnergy(12345, tx);
            tx.commit();
        }
        return Map.of("energy", reactor.energyContainer().getAmountAsLong(), "fraction", reactor.getGenerationRemainder(),
              "heatCapacity", reactor.getHeatCapacitor().getHeatCapacity(), "burning", reactor.isBurning(), "injectionRate", reactor.getInjectionRate());
    }
}
