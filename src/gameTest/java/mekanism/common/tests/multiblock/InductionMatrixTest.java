package mekanism.common.tests.multiblock;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.content.matrix.MatrixEnergyContainer;
import mekanism.common.content.matrix.MatrixMultiblockData;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.multiblock.TileEntityInductionCasing;
import mekanism.common.tile.multiblock.TileEntityInductionCell;
import mekanism.common.tile.multiblock.TileEntityInductionProvider;
import mekanism.common.util.EnumUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;
import org.jspecify.annotations.Nullable;

@ForEachTest(groups = "multiblock.induction_matrix")
public class InductionMatrixTest {

    private static final String MATRIX_PARTS = MekanismTests.MODID + ":induction_matrix_parts";
    //Note: Our template is lazy so that we ensure the blocks are registered
    @RegisterStructureTemplate(MATRIX_PARTS)
    public static final Supplier<StructureTemplate> MATRIX_PARTS_TEMPLATE = StructureTemplateBuilder.lazy(4, 1, 1, builder -> builder
          .set(0, 0, 0, MekanismBlocks.INDUCTION_CASING.defaultState())
          .set(1, 0, 0, MekanismBlocks.BASIC_INDUCTION_CELL.defaultState())
          .set(2, 0, 0, MekanismBlocks.BASIC_INDUCTION_PROVIDER.defaultState())
          .set(3, 0, 0, MekanismBlocks.BASIC_ENERGY_CUBE.defaultState())
    );

    @GameTest(template = MATRIX_PARTS)
    @TestHolder(description = "Tests to make sure that an induction matrix does not output more energy than it has stored.")
    public static void testOutputLimitedByStored(final MekGameTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  MatrixEnergyContainer matrix = createMatrix(helper).energyContainer();
                  IEnergyContainer cube = helper.getBlockEntity(3, 0, 0, TileEntityEnergyCube.class).energyContainer();
                  List<BlockCapabilityCache<EnergyHandler, @Nullable Direction>> targets = getCubeTargets(helper);
                  matrix.tick(targets, null, null, null);
                  helper.assertValueEqual(cube.getAmountAsLong(), 0L, "Energy output by an empty matrix");
                  insert(matrix, 1_000);
                  matrix.tick(targets, null, null, null);
                  helper.assertValueEqual(cube.getAmountAsLong(), 1_000L, "Energy output by a matrix storing 1000 FE");
                  helper.assertValueEqual(matrix.getAmountAsLong(), 0L, "Energy left in the matrix");
              })
              .thenSucceed();
    }

    private static MatrixMultiblockData createMatrix(MekGameTestHelper helper) {
        MatrixMultiblockData matrix = new MatrixMultiblockData(helper.getBlockEntity(0, 0, 0, TileEntityInductionCasing.class));
        matrix.setFormedForce(true);
        matrix.addCell(helper.getBlockEntity(1, 0, 0, TileEntityInductionCell.class));
        matrix.addProvider(helper.getBlockEntity(2, 0, 0, TileEntityInductionProvider.class));
        return matrix;
    }

    private static List<BlockCapabilityCache<EnergyHandler, @Nullable Direction>> getCubeTargets(MekGameTestHelper helper) {
        //Target every side so that the test doesn't depend on which sides of the cube are configured to accept energy
        BlockPos cube = helper.absolutePos(new BlockPos(3, 0, 0));
        return Arrays.stream(EnumUtils.DIRECTIONS)
              .map(side -> Capabilities.ENERGY.createCache(helper.getLevel(), cube, side))
              .toList();
    }

    private static void insert(MatrixEnergyContainer matrix, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            matrix.insert(amount, transaction, AutomationType.EXTERNAL);
            transaction.commit();
        }
    }
}
