package dev.corvus.mill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;

/**
 * The mill's side block: a full cube, no block entity, no item and no loot. Its {@code facing} points
 * back at the main block, so a pipe touching the side can still reach the main machine's tank and a
 * player clicking the side opens the main panel.
 *
 * <p>Breaking either half breaks the pair and drops the main item once. The machine used to be two
 * blocks tall; that third block is gone, so an old save simply loses the unknown block instead of
 * throwing while a block entity loads.</p>
 *
 * <p>{@code facing} is the machine's front (the same value the body carries), not the direction
 * toward the body: the side half always sits on the front's right hand, which is what lets both
 * halves share one y-rotation table in their blockstates.</p>
 */
final class OreMillSideBlock extends Block {
    /** The machine's front; the body is one step counter-clockwise from here. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    OreMillSideBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** This half's {@code facing} is the machine's front (same value as the body): the body is one
     *  step counter-clockwise from the front, because the side half sits on the front's right hand. */
    static BlockPos mainPos(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING).getCounterClockWise());
    }

    @Override public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return level.getBlockState(mainPos(state, pos)).is(OreMillRegistry.MILL.get());
    }

    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return direction == state.getValue(FACING) && !canSurvive(state, level, pos)
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : state;
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        BlockPos main = mainPos(state, pos);
        if (level.getBlockState(main).is(OreMillRegistry.MILL.get())) {
            return level.getBlockState(main).use(level, player, hand,
                    new BlockHitResult(hit.getLocation(), hit.getDirection(), main, hit.isInside()));
        }
        return InteractionResult.PASS;
    }

    @Override public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos main = mainPos(state, pos);
            if (level.getBlockState(main).is(OreMillRegistry.MILL.get())) level.destroyBlock(main, !player.isCreative());
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && !level.isClientSide) {
            BlockPos main = mainPos(state, pos);
            if (level.getBlockState(main).is(OreMillRegistry.MILL.get())) level.destroyBlock(main, true);
        }
        super.onRemove(state, level, pos, next, moving);
    }

    // Pipes and the pump resolve the main block entity explicitly (see WaterPipeNetwork.scan and
    // WaterPumpEntity.push), so the side block needs no capability provider of its own.
}
