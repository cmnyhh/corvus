package dev.dronepal.mill;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** A water pipe: six-sided connection state, one block entity that forwards water. */
public final class WaterPipeBlock extends BaseEntityBlock {
    private static final MapCodec<WaterPipeBlock> CODEC = simpleCodec(WaterPipeBlock::new);
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final Map<Direction, BooleanProperty> SIDES = Map.of(
            Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH,
            Direction.WEST, WEST, Direction.UP, UP, Direction.DOWN, DOWN);
    private static final VoxelShape SHAPE = Block.box(4, 4, 4, 12, 12, 12);

    public WaterPipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override protected MapCodec<WaterPipeBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    static boolean connects(LevelAccessor level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof WaterPipeBlock || state.getBlock() instanceof WaterPumpBlock
                || state.is(OreMillRegistry.MILL.get()) || state.is(OreMillRegistry.MILL_SIDE.get())) return true;
        return level.getBlockEntity(pos) instanceof WaterPipeEntity;
    }

    @Nullable
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        for (Direction side : Direction.values()) {
            state = state.setValue(SIDES.get(side), connects(level, pos.relative(side), level.getBlockState(pos.relative(side))));
        }
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return state.setValue(SIDES.get(direction), connects(level, neighbourPos, neighbour));
    }

    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!level.isClientSide && stack.is(dev.dronepal.Dronepal.DRONE.get())
                && level.getBlockEntity(pos) instanceof WaterPipeEntity pipe && level instanceof net.minecraft.server.level.ServerLevel server
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            pipe.readStatus(server, serverPlayer);
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(false);
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new WaterPipeEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return null;
        return createTickerHelper(type, OreMillRegistry.WATER_PIPE_TYPE.get(), (world, pos, block, pipe) -> pipe.tick(server));
    }
}
