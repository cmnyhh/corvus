package dev.corvus.power.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Bottom segment of the three-block power tower (1.20.1). Placing it grows the middle and top segment
 * and refuses the placement when either cell is occupied; breaking any segment removes all three and
 * drops the bottom's item exactly once.
 */
public final class PowerTowerBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 15.8D, 15.0D);

    public PowerTowerBlock(Properties properties) { super(properties); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PowerTowerEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof ServerLevel server)) return null;
        return createTickerHelper(type, TowerRegistry.TYPE.get(), (world, pos, block, tower) -> tower.tick(server));
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (pos.getY() > level.getMaxBuildHeight() - 3) return null;
        if (!level.getBlockState(pos.above()).canBeReplaced(context)) return null;
        if (!level.getBlockState(pos.above(2)).canBeReplaced(context)) return null;
        return defaultBlockState();
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean movedByPiston) {
        super.onPlace(state, level, pos, previous, movedByPiston);
        if (level.isClientSide || previous.is(state.getBlock())) return;
        placePart(level, pos.above(), PowerTowerPartBlock.Part.MID);
        placePart(level, pos.above(2), PowerTowerPartBlock.Part.TOP);
    }

    private static void placePart(Level level, BlockPos pos, PowerTowerPartBlock.Part part) {
        if (level.getBlockState(pos).canBeReplaced()) {
            level.setBlock(pos, TowerRegistry.part(part).defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean movedByPiston) {
        if (!level.isClientSide && !movedByPiston && state.is(this) && !next.is(this)) {
            clearPart(level, pos.above());
            clearPart(level, pos.above(2));
        }
        super.onRemove(state, level, pos, next, movedByPiston);
    }

    private static void clearPart(Level level, BlockPos pos) {
        BlockState part = level.getBlockState(pos);
        if (part.getBlock() instanceof PowerTowerPartBlock) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PowerTowerEntity tower
                && level instanceof ServerLevel server) {
            player.displayClientMessage(tower.status(server), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
