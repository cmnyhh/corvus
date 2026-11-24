package dev.dronepal.charging;

import com.mojang.serialization.MapCodec;
import dev.dronepal.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

final class ChargingDockBlock extends BaseEntityBlock {
    private static final MapCodec<ChargingDockBlock> CODEC = simpleCodec(ChargingDockBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

    ChargingDockBlock(Properties props) { super(props); }
    @Override protected MapCodec<ChargingDockBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ChargingDockEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, ChargingDockRegistry.TYPE.get(), (level, pos, block, dock) -> dock.tick(server));
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!held.is(Items.COAL) && !DockEnergy.chargeable(held)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ChargingDockEntity dock) {
            if (held.is(Items.COAL)) held.consume(dock.addCoal(held.getCount()), player);
            else dock.insert(held);
            player.displayClientMessage(dock.status(), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ChargingDockEntity dock) {
            if (player.getMainHandItem().isEmpty()) {
                ItemStack taken = dock.take();
                if (!taken.isEmpty()) player.setItemInHand(InteractionHand.MAIN_HAND, taken);
            }
            player.displayClientMessage(dock.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof ChargingDockEntity dock) dock.dropContents();
        super.onRemove(state, level, pos, next, moving);
    }
}
