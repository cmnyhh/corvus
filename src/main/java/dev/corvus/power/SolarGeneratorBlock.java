package dev.corvus.power;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The solar generator block: a plain cube that reports what it is currently producing on right-click.
 *
 * <p>It has no inventory and no face to orient, so there is nothing to configure — the sky decides.</p>
 */
public final class SolarGeneratorBlock extends BaseEntityBlock {
    /** The model's own bounding box (15 x 11.73 x 15 units of 16) - see getShape. */
    private static final VoxelShape SHAPE = Block.box(0.5, 0, 0.5, 15.5, 11.73, 15.5);

    /** Which way the panel faces; kept so the model can carry a distinct front. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    SolarGeneratorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, net.minecraft.core.Direction.NORTH)
                .setValue(SolarGeneratorEntity.SUN, 0));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SolarGeneratorEntity.SUN);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    /**
     * The model's own bounding box, not the whole cell: collision and, with it, "blocks sight" /
     * "suffocates" follow the art instead of an invisible cube around it.
     */
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Built shell, not a full cube: skylight still reaches the block underneath. */
    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }


    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SolarGeneratorEntity(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, PowerRegistry.SOLAR_TYPE.get(), (level, pos, block, solar) -> solar.tick(server));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SolarGeneratorEntity solar) {
            player.displayClientMessage(solar.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
