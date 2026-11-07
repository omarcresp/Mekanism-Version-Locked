package dev.atm11poc;

import java.util.function.BiConsumer;
import java.util.Map;
import com.google.gson.JsonObject;
import mekanism.api.chemical.ChemicalResource;
import mekanism.common.registries.MekanismChemicals;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.fission.TileEntityFissionReactorCasing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** Operates a real structure in the disposable test world; never forces formation. */
final class ReactorProbe {
    private static final BlockPos START = new BlockPos(24, 80, 24);
    private final BiConsumer<Boolean, String> check;
    private boolean fueled;
    private boolean complete;
    private int operatingTicks;
    private long initialFuel;
    private long initialWaste;
    private long initialWater;
    private JsonObject saved;

    ReactorProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    void prepare(ServerLevel level, JsonObject saved) {
        this.saved = saved;
        for (int cx = 1; cx <= 2; cx++) {
            for (int cz = 1; cz <= 2; cz++) level.setChunkForced(cx, cz, true);
        }
        if (saved != null) return;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    boolean wall = x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4;
                    level.setBlockAndUpdate(START.offset(x, y, z), wall
                          ? GeneratorsBlocks.FISSION_REACTOR_CASING.defaultState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(START.offset(2, 1, 2), GeneratorsBlocks.FISSION_FUEL_ASSEMBLY.defaultState());
        level.setBlockAndUpdate(START.offset(2, 2, 2), GeneratorsBlocks.FISSION_FUEL_ASSEMBLY.defaultState());
        level.setBlockAndUpdate(START.offset(2, 3, 2), GeneratorsBlocks.CONTROL_ROD_ASSEMBLY.defaultState());
    }

    Map<String, Object> snapshot(ServerLevel level) {
        var reactor = ((TileEntityFissionReactorCasing) level.getBlockEntity(START)).getMultiblock();
        return Map.of("fuel", reactor.fuelTank.amountAsLong(), "waste", reactor.wasteTank.amountAsLong(),
              "water", reactor.coolantTank.getFluidTank().amountAsLong(), "steam", reactor.heatedCoolantTank.amountAsLong(),
              "damage", reactor.reactorDamage, "burnRate", reactor.rateLimit, "active", reactor.isActive(),
              "heatCapacity", reactor.getHeatCapacity());
    }

    boolean tick(ServerLevel level, int elapsed) {
        if (complete) return true;
        var tile = (TileEntityFissionReactorCasing) level.getBlockEntity(START);
        var reactor = tile.getMultiblock();
        if (!reactor.isFormed()) {
            if (elapsed > 200) throw new IllegalStateException("Real fission structure did not form within 200 ticks");
            return false;
        }
        if (!fueled) {
            check.accept(reactor.getMaxBurnRate() > 0, "formed fission reactor detects fuel assemblies");
            if (saved == null) {
                reactor.fuelTank.setContents(ChemicalResource.of(MekanismChemicals.FISSILE_FUEL), 1000, null);
                reactor.coolantTank.getFluidTank().setContents(FluidResource.of(Fluids.WATER), reactor.coolantTank.getFluidTank().capacityAsLong(FluidResource.of(Fluids.WATER)), null);
            } else {
                check.accept(reactor.getHeatCapacity() == saved.get("heatCapacity").getAsDouble(), "exact fission heat capacity survives JVM restart");
                check.accept(reactor.fuelTank.amountAsLong() == saved.get("fuel").getAsLong(), "exact fission fuel survives JVM restart");
                check.accept(reactor.wasteTank.amountAsLong() == saved.get("waste").getAsLong(), "exact fission waste survives JVM restart");
                check.accept(reactor.rateLimit == 1 && !reactor.isActive(), "fission burn limit and shutdown survive JVM restart");
                // Supply fresh cooling capacity for a second operating cycle after checking saved nuclear contents.
                reactor.coolantTank.getFluidTank().setContents(FluidResource.of(Fluids.WATER), reactor.coolantTank.getFluidTank().capacityAsLong(FluidResource.of(Fluids.WATER)), null);
                reactor.heatedCoolantTank.setContents(ChemicalResource.EMPTY, 0, null);
            }
            initialFuel = reactor.fuelTank.amountAsLong();
            initialWaste = reactor.wasteTank.amountAsLong();
            initialWater = reactor.coolantTank.getFluidTank().amountAsLong();
            reactor.setRateLimit(1);
            reactor.setActive(true);
            fueled = true;
            return false;
        }
        if (++operatingTicks < 100) return false;
        reactor.setActive(false);
        check.accept(reactor.fuelTank.amountAsLong() == initialFuel - 100, "100 fission ticks consume exactly 100 mB fissile fuel");
        check.accept(reactor.wasteTank.amountAsLong() == initialWaste + 100 && reactor.wasteTank.resource().equals(ChemicalResource.of(MekanismChemicals.NUCLEAR_WASTE)), "100 fission ticks produce exactly 100 mB nuclear waste");
        check.accept(reactor.coolantTank.getFluidTank().amountAsLong() < initialWater, "fission operation consumes water coolant");
        check.accept(reactor.heatedCoolantTank.amountAsLong() > 0 && reactor.heatedCoolantTank.resource().equals(ChemicalResource.of(MekanismChemicals.STEAM)), "fission operation produces heated coolant");
        check.accept(reactor.reactorDamage == 0, "cooled fission operation avoids reactor damage (damage="
              + reactor.reactorDamage + ", water=" + reactor.coolantTank.getFluidTank().amountAsLong() + ")");
        complete = true;
        return true;
    }
}
