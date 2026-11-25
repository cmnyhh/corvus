package dev.dronepal.mill;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The second half of the ore mill. The machine is two blocks wide and one block tall: the body owns the
 * block entity, this side block is only a solid face with no item and no loot. Breaking either half
 * removes the other, and only the body drops the machine.
 */
public final class OreMillSideBlock extends Block {
    private static final MapCodec<OreMillSideBlock> CODEC = simpleCodec(OreMillSideBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 16, 16);

    public OreMillSideBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<OreMillSideBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }

    /**
     * This half's {@code facing} is the machine's front (the same value the body carries), and the
     * side half sits on the front's right hand - so the body is one step counter-clockwise.
     */
    static BlockPos bodyPos(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING).getCounterClockWise());
    }

    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos body = bodyPos(state, pos);
        BlockState behind = level.getBlockState(body);
        return behind.is(OreMillRegistry.MILL.get()) && behind.getValue(OreMillBlock.FACING) == state.getValue(FACING);
    }

    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour, BlockPos neighbourPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbour, neighbourPos, moving);
        if (!level.isClientSide && !canSurvive(state, level, pos)) level.removeBlock(pos, false);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(bodyPos(state, pos)) instanceof OreMillEntity mill) {
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new OreMillMenu(id, inventory, mill),
                    Component.translatable("block.huhu_dronepal.ore_mill")));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) {
            BlockPos body = bodyPos(state, pos);
            if (level.getBlockState(body).is(OreMillRegistry.MILL.get())) level.destroyBlock(body, true);
        }
        super.onRemove(state, level, pos, next, moving);
    }

    /** Half blocks never drop themselves; the body is the item. */
    @Override protected List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        return List.of();
    }

    @Override public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
            net.minecraft.world.level.block.entity.BlockEntity blockEntity, ItemStack tool) {
        if (!level.isClientSide) {
            BlockPos body = bodyPos(state, pos);
            if (level.getBlockState(body).is(OreMillRegistry.MILL.get())) level.destroyBlock(body, true, player);
        }
    }
}
