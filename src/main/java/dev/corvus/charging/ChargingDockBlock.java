package dev.corvus.charging;

import dev.corvus.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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

/**
 * The charging pad block: a flat 8/16-high slab. Right-click with coal to feed it, with a cell to dock
 * the cell, and with an empty hand to take the docked cell back.
 */
final class ChargingDockBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

    ChargingDockBlock(Properties props) { super(props); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ChargingDockEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, ChargingDockRegistry.type(), (level, pos, block, dock) -> dock.tick(server));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ChargingDockEntity dock) {
            if (held.is(Items.COAL)) {
                int accepted = dock.addCoal(held.getCount());
                held.shrink(accepted);
            } else if (!held.isEmpty() && DockEnergy.chargeable(held)) {
                dwimInsert(dock, held);
            } else if (held.isEmpty()) {
                ItemStack taken = dock.take();
                if (!taken.isEmpty()) player.setItemInHand(hand, taken);
            }
            player.displayClientMessage(dock.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Docking consumes exactly one item; the rest of the held stack stays in the player's hand. */
    private static void dwimInsert(ChargingDockEntity dock, ItemStack held) {
        ItemStack single = held.copy();
        single.setCount(1);
        if (dock.insert(single)) held.shrink(1);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof ChargingDockEntity dock) dock.dropContents();
        super.onRemove(state, level, pos, next, moving);
    }
}
