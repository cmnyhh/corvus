package dev.corvus.mill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A six-way water pipe. The blockstate carries one boolean per face; the pump walks the connected pipes
 * (up to {@link WaterPipeNetwork#MAX_LENGTH} blocks) and splits its 200 mB/t across every machine at the
 * far end. No water is stored here, so nothing decays or clogs: a pipe is either connected or not.
 *
 * <p>Right-clicking with the drone terminal prints the live flow, the number of downstream machines and
 * the segment length, which is also how an over-long run announces itself.</p>
 */
public final class WaterPipeBlock extends Block {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    private static final VoxelShape CORE = Block.box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape NORTH_SHAPE = Block.box(5, 5, 0, 11, 11, 5);
    private static final VoxelShape SOUTH_SHAPE = Block.box(5, 5, 11, 11, 11, 16);
    private static final VoxelShape WEST_SHAPE = Block.box(0, 5, 5, 5, 11, 11);
    private static final VoxelShape EAST_SHAPE = Block.box(11, 5, 5, 16, 11, 11);
    private static final VoxelShape UP_SHAPE = Block.box(5, 11, 5, 11, 16, 11);
    private static final VoxelShape DOWN_SHAPE = Block.box(5, 0, 5, 11, 5, 11);

    WaterPipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = CORE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, NORTH_SHAPE);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, SOUTH_SHAPE);
        if (state.getValue(WEST)) shape = Shapes.or(shape, WEST_SHAPE);
        if (state.getValue(EAST)) shape = Shapes.or(shape, EAST_SHAPE);
        if (state.getValue(UP)) shape = Shapes.or(shape, UP_SHAPE);
        if (state.getValue(DOWN)) shape = Shapes.or(shape, DOWN_SHAPE);
        return shape;
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return updated(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide) refreshAround(level, pos, state);
    }

    @Override public void neighborChanged(BlockState state, Level level, BlockPos pos, Block from, BlockPos fromPos,
                                          boolean moving) {
        if (!level.isClientSide) {
            BlockState next = updated(level, pos, state);
            if (next != state) level.setBlock(pos, next, Block.UPDATE_ALL);
        }
    }

    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (level instanceof Level world) {
            BlockState next = updated(world, pos, state);
            if (next != state) return next;
        }
        return state;
    }

    private static void refreshAround(Level level, BlockPos pos, BlockState state) {
        if (state != updated(level, pos, state)) level.setBlock(pos, updated(level, pos, state), Block.UPDATE_ALL);
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            BlockState other = level.getBlockState(neighbour);
            if (other.is(OreMillRegistry.PIPE.get())) {
                BlockState next = updated(level, neighbour, other);
                if (next != other) level.setBlock(neighbour, next, Block.UPDATE_ALL);
            }
        }
    }

    /** Recomputes all six booleans from the neighbours. */
    static BlockState updated(Level level, BlockPos pos, BlockState state) {
        return state
                .setValue(NORTH, connects(level, pos, Direction.NORTH))
                .setValue(EAST, connects(level, pos, Direction.EAST))
                .setValue(SOUTH, connects(level, pos, Direction.SOUTH))
                .setValue(WEST, connects(level, pos, Direction.WEST))
                .setValue(UP, connects(level, pos, Direction.UP))
                .setValue(DOWN, connects(level, pos, Direction.DOWN));
    }

    private static boolean connects(Level level, BlockPos pos, Direction side) {
        BlockPos neighbour = pos.relative(side);
        BlockState state = level.getBlockState(neighbour);
        if (state.is(OreMillRegistry.PIPE.get()) || state.is(OreMillRegistry.PUMP.get())
                || state.is(OreMillRegistry.MILL.get()) || state.is(OreMillRegistry.SIDE.get())) return true;
        return level.getBlockEntity(neighbour) != null
                && level.getBlockEntity(neighbour).getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER,
                side.getOpposite()).isPresent();
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && held.is(dev.corvus.Corvus.DRONE.get())) {
            player.displayClientMessage(WaterPipeNetwork.describe(level, pos), true);
            return InteractionResult.sidedSuccess(false);
        }
        return InteractionResult.PASS;
    }
}
