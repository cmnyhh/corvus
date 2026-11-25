package dev.corvus.mill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The ore mill's main block. A horizontal two-block machine: this half carries the block entity and the
 * inventory, the {@link OreMillSideBlock} carries the second cube. {@code facing} is the direction of
 * the side block, chosen from the placer's look direction.
 */
public final class OreMillBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    OreMillBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Where the side half goes for a given front: the model's +X half, i.e. the front's right hand. */
    public static BlockPos sidePos(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING).getClockWise());
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OreMillEntity(pos, state);
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Front faces the player, the side half goes on the front's right hand: the Blockbench
        // ore_mill is the left half of the 32-unit machine and ore_mill_side the right one.
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos side = context.getClickedPos().relative(facing.getClockWise());
        if (!context.getLevel().getBlockState(side).canBeReplaced()) return null;
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                                      net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        placeSide(level, pos, state);
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        placeSide(level, pos, state);
    }

    private static void placeSide(Level level, BlockPos pos, BlockState state) {
        BlockPos side = sidePos(state, pos);
        if (level.getBlockState(side).canBeReplaced()) {
            // Same facing value on both halves, so one y-rotation table drives both blockstates.
            level.setBlock(side, OreMillRegistry.SIDE.get().defaultBlockState()
                    .setValue(OreMillSideBlock.FACING, state.getValue(FACING)), Block.UPDATE_ALL);
        }
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
                                                                          BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, OreMillRegistry.TYPE.get(),
                (level, pos, block, mill) -> mill.tick(server));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof OreMillEntity mill) {
            // A water bucket is the manual top-up path: 1,000 mB and an empty bucket back.
            ItemStack held = player.getItemInHand(hand);
            if (held.is(Items.WATER_BUCKET) && mill.addWater(1_000) > 0) {
                player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                player.displayClientMessage(Component.translatable("message.dronepal.mill_water_added"), true);
                return InteractionResult.sidedSuccess(false);
            }
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new OreMillMenu(id, inventory, mill),
                    Component.translatable("block.huhu_dronepal.ore_mill")));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void onRemove(BlockState old, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!old.is(next.getBlock()) && !level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof OreMillEntity mill) mill.dropContents();
            BlockPos side = sidePos(old, pos);
            if (level.getBlockState(side).is(OreMillRegistry.SIDE.get())) level.removeBlock(side, false);
        }
        super.onRemove(old, level, pos, next, moving);
    }
}
