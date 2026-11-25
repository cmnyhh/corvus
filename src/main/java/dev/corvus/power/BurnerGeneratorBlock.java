package dev.corvus.power;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
 * The burner generator block.
 *
 * <p>Interaction is the charging pad's vocabulary: right-click with any furnace fuel to load the slot,
 * sneak-right-click with an empty hand to take the fuel back, anything else prints the status line.
 * There is no GUI, which keeps the block a single cube with a single model.</p>
 */
public final class BurnerGeneratorBlock extends BaseEntityBlock {
    /** The model's own bounding box (12 x 16 x 12.2 units of 16) - see getShape. */
    private static final VoxelShape SHAPE = Block.box(2, 0, 1.8, 14, 16, 14);

    /** Which way the machine faces; the model rotates its front panel with it. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    BurnerGeneratorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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
        return new BurnerGeneratorEntity(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, PowerRegistry.BURNER_TYPE.get(), (level, pos, block, burner) -> burner.tick(server));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BurnerGeneratorEntity burner) {
            if (FuelPower.fuel(held)) {
                held.shrink(burner.addFuel(held));
            } else if (held.isEmpty() && player.isShiftKeyDown()) {
                ItemStack taken = burner.takeFuel();
                if (!taken.isEmpty()) player.setItemInHand(hand, taken);
            }
            player.displayClientMessage(burner.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof BurnerGeneratorEntity burner) {
            burner.dropContents();
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
