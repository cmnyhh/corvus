package dev.corvus.power.io;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The one-block供能接口: a socket our machines' energy leaves through.
 *
 * <p>Mirror of the 1.21.1 block. The facing is the <b>output</b> side — the art draws a connector
 * sticking out of it — so the block pulls from the other five faces only. The energy capability is
 * offered on <b>every</b> face, because another mod's cable decides for itself which side to attach to.</p>
 *
 * <p>No GUI: right-click prints charge, the configured FE/t and how many supply blocks touch it. All
 * numbers come from {@link dev.corvus.core.PowerIo}.</p>
 */
public final class PowerPortBlock extends BaseEntityBlock {
    /** Which way the outlet points. The model's socket and the visual front follow it. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Housing (1..15 x 0..14) plus the connector on the facing side; cached per facing. */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.NORTH, Shapes.or(Block.box(1, 0, 1, 15, 14, 15), Block.box(5, 5, 0, 11, 11, 2)),
            Direction.EAST, Shapes.or(Block.box(1, 0, 1, 15, 14, 15), Block.box(14, 5, 5, 16, 11, 11)),
            Direction.SOUTH, Shapes.or(Block.box(1, 0, 1, 15, 14, 15), Block.box(5, 5, 14, 11, 11, 16)),
            Direction.WEST, Shapes.or(Block.box(1, 0, 1, 15, 14, 15), Block.box(0, 5, 5, 2, 11, 11)));

    PowerPortBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    /** Front faces the player who placed it, the same convention the three generators use. */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** The model's own bounding box: the housing plus the plug, never an invisible full cube. */
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** Built shell, not a full cube: skylight still reaches the block underneath. */
    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PowerPortEntity(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return null;
        return createTickerHelper(type, PowerIoRegistry.PORT_TYPE.get(), (world, pos, block, port) -> port.tick(server));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PowerPortEntity port) {
            player.displayClientMessage(port.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
