package dev.corvus.power;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The battery bank block: no GUI, right-click prints stored over capacity. */
public final class BatteryBankBlock extends BaseEntityBlock {
    /** The model's own bounding box (15.1 x 11.4 x 11.2 units of 16) - see getShape. */
    private static final VoxelShape SHAPE = Block.box(0.45, 0, 2.3, 15.55, 11.4, 13.5);

    BatteryBankBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BatteryBankEntity.CHARGE, 0));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BatteryBankEntity.CHARGE);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    /**
     * The model's own bounding box, not the whole cell: collision and, with it, "blocks sight" /
     * "suffocates" follow the art instead of an invisible cube around it.
     */
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Built shell, not a full cube: skylight still reaches the block underneath. */
    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }


    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BatteryBankEntity(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (!(world instanceof ServerLevel server)) return null;
        return createTickerHelper(type, PowerRegistry.BANK_TYPE.get(), (level, pos, block, bank) -> bank.tick(server));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BatteryBankEntity bank) {
            player.displayClientMessage(bank.status(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
