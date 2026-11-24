package dev.corvus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The assembly bench block. 1.20.1 blocks carry no {@code MapCodec}; the shape, the ticker and the
 * "drop everything when broken" rule are the whole class.
 */
final class AssemblyBlock extends BaseEntityBlock {
    AssemblyBlock(Properties properties) { super(properties); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new AssemblyStation(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, SurvivalWorks.STATION.get(), (world, pos, block, station) -> station.tick());
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AssemblyStation station) {
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new AssemblyMenu(id, inventory, station),
                    net.minecraft.network.chat.Component.translatable("block.huhu_dronepal.assembly_bench")));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void onRemove(BlockState old, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!old.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof AssemblyStation station) {
            station.dropContents();
        }
        super.onRemove(old, level, pos, next, moving);
    }
}
