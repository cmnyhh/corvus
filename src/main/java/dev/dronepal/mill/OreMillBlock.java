package dev.dronepal.mill;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The machine's main (block-entity) half; right-click opens the mill panel, breaking spits out the
 * contents.
 *
 * <p>{@code facing} is the direction the machine's front face looks at. The machine is two blocks
 * wide and one block tall, and the art's second half always sits on the front's <em>right hand</em>
 * ({@code facing.getClockWise()}): the Blockbench {@code ore_mill} model is the left half and
 * {@code ore_mill_side} is the right one, joined along the model's +X axis (see
 * {@code art/mill-blocks/MANIFEST.md} §4.1). Both halves carry the same {@code facing} value, so one
 * y-rotation table serves both blockstates.</p>
 */
public final class OreMillBlock extends BaseEntityBlock {
    private static final MapCodec<OreMillBlock> CODEC = simpleCodec(OreMillBlock::new);
    /** The direction the machine's front looks at; the side block sits on its clockwise neighbour. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Where the side block goes for a given front: the model's +X half, i.e. the front's right. */
    public static BlockPos sidePos(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING).getClockWise());
    }

    public OreMillBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

    @Override protected MapCodec<OreMillBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new OreMillEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof ServerLevel server)) return null;
        return createTickerHelper(type, OreMillRegistry.TYPE.get(), (world, pos, block, mill) -> mill.tick(server));
    }

    @Nullable
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        return context.getLevel().getBlockState(context.getClickedPos().relative(facing.getClockWise())).canBeReplaced()
                ? defaultBlockState().setValue(FACING, facing) : null;
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (level.isClientSide) return;
        Direction facing = state.getValue(FACING);
        BlockPos side = pos.relative(facing.getClockWise());
        if (level.getBlockState(side).canBeReplaced()) {
            // Same facing value on both halves: the side model is the right half of the same machine.
            level.setBlock(side, OreMillRegistry.MILL_SIDE.get().defaultBlockState()
                    .setValue(OreMillSideBlock.FACING, facing), 3);
        }
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof OreMillEntity mill) {
            MenuProvider provider = new SimpleMenuProvider(
                    (id, inventory, ignored) -> new OreMillMenu(id, inventory, mill),
                    Component.translatable("block.huhu_dronepal.ore_mill"));
            player.openMenu(provider);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) {
            // The side block is a part, not a separate machine: remove it without a second drop.
            BlockPos side = sidePos(state, pos);
            if (level.getBlockState(side).is(OreMillRegistry.MILL_SIDE.get())) {
                level.removeBlock(side, false);
            }
            if (level.getBlockEntity(pos) instanceof OreMillEntity mill) {
                mill.dropContents();
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }

    @Nullable
    @Override public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof OreMillEntity mill
                ? new SimpleMenuProvider((id, inventory, ignored) -> new OreMillMenu(id, inventory, mill),
                        Component.translatable("block.huhu_dronepal.ore_mill"))
                : null;
    }
}
