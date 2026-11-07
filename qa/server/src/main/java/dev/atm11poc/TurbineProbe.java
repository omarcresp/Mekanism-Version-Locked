package dev.atm11poc;

import java.util.function.BiConsumer;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.registries.MekanismChemicals;
import mekanism.generators.common.registries.GeneratorsBuilders;
import mekanism.generators.common.tile.turbine.TileEntityTurbineCasing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

final class TurbineProbe {
    private static final BlockPos START = new BlockPos(48, 80, 24);
    private final BiConsumer<Boolean, String> check;
    private boolean supplied;
    private boolean complete;
    private int operatingTicks;
    private long initialSteam;

    TurbineProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    private static final class Builder extends GeneratorsBuilders.TurbineBuilder {
        void place(Level level) {
            build(level, START, false);
        }
    }

    void build(ServerLevel level) {
        for (int x = 3; x <= 4; x++) {
            for (int z = 1; z <= 2; z++) level.setChunkForced(x, z, true);
        }
        new Builder().place(level);
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        var turbine = ((TileEntityTurbineCasing) level.getBlockEntity(START)).getMultiblock();
        if (!turbine.isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Real turbine structure did not form within 200 ticks");
            return false;
        }
        if (!supplied) {
            check.accept(turbine.blades == 28 && turbine.coils > 0 && turbine.condensers > 0,
                  "formed turbine detects blades, coils and condensers");
            turbine.chemicalTank.setContents(ChemicalResource.of(MekanismChemicals.STEAM), 10000000, null);
            initialSteam = turbine.chemicalTank.amountAsLong();
            check.accept(initialSteam > 0 && turbine.energyContainer().getAmountAsLong() == 0,
                  "turbine begins with steam and no stored energy");
            supplied = true;
            return false;
        }
        if (++operatingTicks < 20) return false;
        long consumed = initialSteam - turbine.chemicalTank.amountAsLong();
        check.accept(consumed > 0, "turbine consumes steam during normal ticking");
        check.accept(turbine.energyContainer().getAmountAsLong() > 0, "turbine generates stored energy");
        check.accept(turbine.ventTank.amountAsLong() > 0 && turbine.ventTank.resource().equals(FluidResource.of(Fluids.WATER)),
              "turbine condensers recover water");
        complete = true;
        return true;
    }
}
