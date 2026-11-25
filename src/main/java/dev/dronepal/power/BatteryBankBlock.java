package dev.dronepal.power;

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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Housing for {@link BatteryBankEntity}. Right-click prints the charge to the action bar. */
public final class BatteryBankBlock extends BaseEntityBlock {
    private static final MapCodec<BatteryBankBlock> CODEC = simpleCodec(BatteryBankBlock::new);
    private static final VoxelShape SHAPE = Block.box(0.45, 0, 2.3, 15.55, 11.4, 13.5);
    /**
     * Front gauge window, 0..3:
     * 0 = 0-33%, 1 = 34-66%, 2 = 67-99%, 3 = 100% (three lit segments, the fourth only at full).
     * The value lives in the block state so the model swaps without a block entity renderer.
     */
    public static final IntegerProperty CHARGE = IntegerProperty.create("charge", 0, 3);

    public BatteryBankBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(CHARGE, 0));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CHARGE);
    }
    @Override protected MapCodec<BatteryBankBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    /**
     * The model's own bounding box, not the whole cell: the housing is 15.1 x 11.4 x 11.2 units of 16, so collision
     * (and with it "blocks sight" / "suffocates") follows the art instead of an invisible cube.
     */
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Built shell, not a full cube: skylight reaches the block below through the open corners. */
    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BatteryBankEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, PowerBlocks.BANK_TYPE.get(), (level, pos, block, bank) -> bank.tick(server));
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BatteryBankEntity bank) {
            player.displayClientMessage(bank.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
