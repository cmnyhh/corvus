package dev.dronepal.mill;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The water pump; right-click reports the buffer and whether it can see water.
 *
 * <p>The Blockbench pump is a 14 x 13.02 x 14 unit housing with feet, so it is registered with
 * {@code noOcclusion()} and collides with its own bounding box: a full cell would both cull the face
 * of whatever block it stands on and let the player walk on invisible air.</p>
 */
public final class WaterPumpBlock extends BaseEntityBlock {
    private static final MapCodec<WaterPumpBlock> CODEC = simpleCodec(WaterPumpBlock::new);
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 13.02, 15);

    public WaterPumpBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<WaterPumpBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    /** Open frame of a pump body: skylight should still reach the block below. */
    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new WaterPumpEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof ServerLevel server)) return null;
        return createTickerHelper(type, OreMillRegistry.WATER_PUMP_TYPE.get(), (world, pos, block, pump) -> pump.tick(server));
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WaterPumpEntity pump) {
            player.displayClientMessage(pump.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
