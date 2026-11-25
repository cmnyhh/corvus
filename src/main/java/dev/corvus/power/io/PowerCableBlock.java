package dev.corvus.power.io;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The FE cable: six-sided connection state, five placeholder models (straight, corner, tee, cross,
 * cap), no storage to speak of and no ticker.
 *
 * <p>Mirror of the 1.21.1 block. The blockstate carries one boolean per face, exactly like the water
 * pipe, so the model set and the connection mask stay a resource-pack concern.</p>
 */
public final class PowerCableBlock extends BaseEntityBlock {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final Map<Direction, BooleanProperty> SIDES = Map.of(
            Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH,
            Direction.WEST, WEST, Direction.UP, UP, Direction.DOWN, DOWN);

    /** All 64 connection masks, indexed by {@link #mask(BlockState)}; built once. */
    private static final VoxelShape[] SHAPES = new VoxelShape[64];

    static {
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = Block.box(4, 4, 4, 12, 12, 12);
            for (Direction side : Direction.values()) {
                if ((mask & (1 << side.ordinal())) == 0) continue;
                shape = Shapes.or(shape, arm(side));
            }
            SHAPES[mask] = shape;
        }
    }

    PowerCableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    /** Collision follows the conduit: centre plus one stub per connected face. */
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[mask(state)];
    }

    private static int mask(BlockState state) {
        int mask = 0;
        for (Direction side : Direction.values()) {
            if (state.getValue(SIDES.get(side))) mask |= 1 << side.ordinal();
        }
        return mask;
    }

    private static VoxelShape arm(Direction side) {
        return switch (side) {
            case NORTH -> Block.box(4, 4, 0, 12, 12, 4);
            case SOUTH -> Block.box(4, 4, 12, 12, 12, 16);
            case WEST -> Block.box(0, 4, 4, 4, 12, 12);
            case EAST -> Block.box(12, 4, 4, 16, 12, 12);
            case DOWN -> Block.box(4, 0, 4, 12, 4, 12);
            case UP -> Block.box(4, 12, 4, 12, 16, 12);
        };
    }

    /**
     * The connection mask a player placement produces at {@code pos}. Public so a test can put the mask
     * on blocks it placed with {@code setBlock} (which never runs {@link #getStateForPlacement}).
     */
    public BlockState connectionState(LevelAccessor level, BlockPos pos) {
        BlockState state = defaultBlockState();
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            state = state.setValue(SIDES.get(side), PowerIoSources.connectable(level.getBlockState(neighbour)));
        }
        return state;
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return connectionState(context.getLevel(), context.getClickedPos());
    }

    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return state.setValue(SIDES.get(direction), PowerIoSources.connectable(neighbour));
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PowerCableEntity(pos, state);
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof PowerCableEntity cable) {
            cable.readStatus(server, player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Status component for the smoke test and the client-side hover path. */
    public static Component status(int flow, int downstream, int length) {
        return Component.translatable("message.dronepal.power_cable.status", flow, downstream, length);
    }
}
