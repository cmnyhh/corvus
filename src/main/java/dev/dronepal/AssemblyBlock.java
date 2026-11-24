package dev.dronepal;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

final class AssemblyBlock extends BaseEntityBlock {
    private static final MapCodec<AssemblyBlock> CODEC = simpleCodec(AssemblyBlock::new);
    AssemblyBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<AssemblyBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new AssemblyStation(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, SurvivalWorks.STATION.get(), (world, pos, block, station) -> station.tick());
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AssemblyStation station) {
            player.openMenu(new SimpleMenuProvider((id, inv, owner) -> new AssemblyMenu(id, inv, station), getName()));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState old, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!old.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof AssemblyStation station) station.dropContents();
        super.onRemove(old, level, pos, next, moving);
    }
}
