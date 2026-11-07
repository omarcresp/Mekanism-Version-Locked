package dev.atm11poc;

import java.util.function.BiConsumer;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.registries.GeneratorsChemicals;
import mekanism.generators.common.registries.GeneratorsItems;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Supplies ignition energy at the reactor API; laser transport is a separate check. */
final class FusionProbe {
    private static final BlockPos START = new BlockPos(80, 80, 24);
    private final BiConsumer<Boolean, String> check;
    private boolean supplied;
    private boolean complete;
    private int operatingTicks;
    private long initialDeuterium;
    private long initialTritium;

    FusionProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    private static final class Builder extends GeneratorsBuilders.FusionReactorBuilder {
        void place(Level level) {
            build(level, START, false);
        }
    }

    void build(ServerLevel level) {
        level.setChunkForced(5, 1, true);
        new Builder().place(level);
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        var reactor = ((TileEntityFusionReactorController) level.getBlockEntity(START.offset(2, 4, 2))).getMultiblock();
        if (!reactor.isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Real fusion structure did not form within 200 ticks");
            return false;
        }
        if (!supplied) {
            var hohlraum = ContainerType.CHEMICAL.getFilledVariant(GeneratorsItems.HOHLRAUM, GeneratorsChemicals.FUSION_FUEL, null);
            reactor.getInventorySlots().getFirst().setContents(ItemResource.of(hohlraum), 1, null);
            reactor.deuteriumTank.setContents(GeneratorsChemicals.DEUTERIUM.asResource(), 1000, null);
            reactor.tritiumTank.setContents(GeneratorsChemicals.TRITIUM.asResource(), 1000, null);
            initialDeuterium = reactor.deuteriumTank.amountAsLong();
            initialTritium = reactor.tritiumTank.amountAsLong();
            reactor.setInjectionRate(4);
            check.accept(!reactor.isBurning(), "fusion begins unignited with a filled hohlraum");
            try (var tx = Transaction.openRoot()) {
                reactor.addTemperatureFromEnergyInput(2000000000L, tx);
                tx.commit();
            }
            supplied = true;
            return false;
        }
        if (++operatingTicks < 100) return false;
        check.accept(reactor.getInventorySlots().getFirst().isEmpty(), "fusion ignition consumes the filled hohlraum");
        check.accept(reactor.isBurning(), "fusion sustains burning for 100 ticks after ignition");
        check.accept(reactor.deuteriumTank.amountAsLong() < initialDeuterium && reactor.tritiumTank.amountAsLong() < initialTritium,
              "fusion injects both deuterium and tritium");
        check.accept(initialDeuterium - reactor.deuteriumTank.amountAsLong() == initialTritium - reactor.tritiumTank.amountAsLong(),
              "fusion consumes equal quantities of deuterium and tritium");
        check.accept(reactor.energyContainer().getAmountAsLong() > 0, "fusion generates stored energy");
        complete = true;
        return true;
    }
}
