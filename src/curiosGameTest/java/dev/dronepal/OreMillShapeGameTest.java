package dev.dronepal;

import dev.dronepal.mill.OreMillBlock;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.power.PowerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The mill is two blocks wide: placing it adds the side half, breaking either half drops the machine
 * once.
 *
 * <p>The side half is the art's right-hand cell - with {@code facing=north} it is placed to the
 * <em>east</em>, because the {@code ore_mill} model is the left half of the 32-unit machine and
 * {@code ore_mill_side} the right one ({@code art/mill-blocks/MANIFEST.md} §4.1). Both halves then
 * carry the same {@code facing} value, which is what lets one y-rotation table drive both
 * blockstates.</p>
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class OreMillShapeGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void placementAddsTheHeadAndBreakDropsOnce(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(2, 1, 2));
        clearRoomAround(level, pos);
        level.setBlockAndUpdate(pos, OreMillRegistry.MILL.get().defaultBlockState());
        var side = pos.east();
        test.assertTrue(level.getBlockState(side).is(OreMillRegistry.MILL_SIDE.get()),
                "mill side block was not placed to the front's right; self=" + level.getBlockState(pos)
                        + " east=" + level.getBlockState(side));
        test.assertTrue(level.getBlockState(side).getValue(dev.dronepal.mill.OreMillSideBlock.FACING)
                        == level.getBlockState(pos).getValue(dev.dronepal.mill.OreMillBlock.FACING),
                "the two halves must share one facing so both blockstates rotate together");
        level.destroyBlock(pos, true);
        test.assertTrue(level.getBlockState(side).isAir(), "breaking the mill left the side block behind");
        long dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .map(ItemEntity::getItem).filter(stack -> stack.is(OreMillRegistry.MILL_ITEM.get())).count();
        test.assertTrue(dropped == 1, "mill dropped " + dropped + " times");
        test.succeed();
    }

    /**
     * The occlusion regression the users reported: placing a machine on stone made the stone's top face
     * vanish, because the machine claimed to be a full occluder while its model is a built shell. Each
     * housing must therefore answer "no" to the occlusion questions, and the renderer must still want to
     * draw the face of the block underneath.
     */
    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void machinesKeepTheGroundFaceBelowThem(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(2, 1, 2));
        clearRoomAround(level, pos);
        Block[] machines = {OreMillRegistry.MILL.get(), OreMillRegistry.WATER_PUMP.get(),
                PowerBlocks.BURNER.get(), PowerBlocks.SOLAR.get(), PowerBlocks.BANK.get()};
        for (Block machine : machines) {
            BlockPos stone = pos.below();
            level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, machine.defaultBlockState());
            BlockState state = level.getBlockState(pos);
            test.assertFalse(state.canOcclude(), machine + " still claims to occlude its neighbours");
            test.assertFalse(state.isSolidRender(level, pos), machine + " still renders as a solid block");
            test.assertTrue(Block.shouldRenderFace(level.getBlockState(stone), level, stone, Direction.UP, pos),
                    "the stone under " + machine + " lost its top face");
            if (machine == OreMillRegistry.MILL.get()) {
                BlockState side = level.getBlockState(OreMillBlock.sidePos(state, pos));
                test.assertTrue(side.is(OreMillRegistry.MILL_SIDE.get()), "mill side half missing");
                test.assertFalse(side.canOcclude(), "the mill's side half still occludes its neighbours");
            }
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
        }
        test.succeed();
    }

    private static void clearRoomAround(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
            level.setBlockAndUpdate(pos.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void breakingTheHeadBreaksTheBodyOnce(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(2, 1, 2));
        clearRoomAround(level, pos);
        level.setBlockAndUpdate(pos, OreMillRegistry.MILL.get().defaultBlockState());
        level.destroyBlock(pos.east(), true);
        test.assertTrue(level.getBlockState(pos).isAir(), "breaking the side block left the body behind");
        long dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .map(ItemEntity::getItem).filter(stack -> stack.is(OreMillRegistry.MILL_ITEM.get())).count();
        test.assertTrue(dropped == 1, "head break dropped the mill " + dropped + " times");
        test.succeed();
    }
}
