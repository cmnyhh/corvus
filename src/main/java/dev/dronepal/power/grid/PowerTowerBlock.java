package dev.dronepal.power.grid;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
 * Bottom segment of the three-block power tower; it owns the block entity and the energy pool.
 *
 * <p>Placing the bottom segment also places {@code power_tower_mid} and {@code power_tower_top}, and
 * the placement is refused outright when either cell is occupied (so a rejected placement never eats
 * the item). Breaking the bottom drops exactly one tower; breaking the middle or top forwards to the
 * bottom (see {@link PowerTowerPartBlock}) so all three disappear and the drop still happens once.</p>
 */
public final class PowerTowerBlock extends BaseEntityBlock {
    private static final MapCodec<PowerTowerBlock> CODEC = simpleCodec(PowerTowerBlock::new);
    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 15.8D, 15.0D);

    public PowerTowerBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<PowerTowerBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    /** The art's own footprint, like the other power machines, so the ground face is not culled. */
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PowerTowerEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof ServerLevel server)) return null;
        return createTickerHelper(type, TowerRegistry.TYPE.get(), (world, pos, block, tower) -> tower.tick(server));
    }

    /** Three cells must be free; otherwise placement fails before the stack is consumed. */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (pos.getY() > level.getMaxBuildHeight() - 3) return null;
        if (!level.getBlockState(pos.above()).canBeReplaced(context)) return null;
        if (!level.getBlockState(pos.above(2)).canBeReplaced(context)) return null;
        return defaultBlockState();
    }

    /** Any placement path (player, structure, command) grows the two upper segments. */
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean movedByPiston) {
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

    /** Breaking the bottom removes the two upper segments without a second drop. */
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean movedByPiston) {
        if (!level.isClientSide && !movedByPiston && state.is(this) && !next.is(this)) {
            clearPart(level, pos.above());
            clearPart(level, pos.above(2));
        }
        super.onRemove(state, level, pos, next, movedByPiston);
    }

    private static void clearPart(Level level, BlockPos pos) {
        BlockState part = level.getBlockState(pos);
        if (part.getBlock() instanceof PowerTowerPartBlock) level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PowerTowerEntity tower
                && level instanceof ServerLevel server) {
            player.displayClientMessage(tower.status(server), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Used by the top segment: is this the bottom of the tower that owns {@code partPos}? */
    static BlockPos mainPos(BlockPos partPos, int above) { return partPos.below(above); }

    static Direction facingToward(BlockPos from, BlockPos to) { return Direction.getNearest(to.getX() - from.getX(), 0, to.getZ() - from.getZ()); }
}
