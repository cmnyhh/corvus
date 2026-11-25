package dev.corvus.power.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Middle / top segment of a 1.20.1 power tower. No block entity, no energy and no loot of its own:
 * breaking either part destroys the bottom segment with drops once, and the bottom's removal clears
 * the remaining part.
 */
public final class PowerTowerPartBlock extends Block {
    public enum Part { MID, TOP }

    /** Lamp band on the top segment: 0 = red (empty), 1 = amber (half), 2 = green (full). */
    public static final IntegerProperty LAMP = IntegerProperty.create("lamp", 0, 2);
    private static final VoxelShape MID_SHAPE = Block.box(1.65D, 0.0D, 1.65D, 14.35D, 15.8D, 14.35D);
    private static final VoxelShape TOP_SHAPE = Block.box(3.3D, 0.0D, 3.3D, 12.7D, 14.95D, 12.7D);

    private final Part part;

    public PowerTowerPartBlock(Properties properties, Part part) {
        super(properties);
        this.part = part;
        registerDefaultState(stateDefinition.any().setValue(LAMP, 0));
    }

    public Part part() { return part; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAMP);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return part == Part.TOP ? TOP_SHAPE : MID_SHAPE;
    }

    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean movedByPiston) {
        if (!level.isClientSide && !movedByPiston && state.is(this) && !next.is(this)) {
            BlockPos main = part == Part.TOP ? pos.below(2) : pos.below();
            // If the bottom is gone already, this removal is the cascade from the bottom itself.
            if (level.getBlockState(main).is(TowerRegistry.TOWER.get())) {
                // The broken part's loot table (lootFrom the bottom) dropped the single tower already.
                // Remove the bottom without a second drop; its onRemove clears the other part.
                level.setBlock(main, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
        super.onRemove(state, level, pos, next, movedByPiston);
    }

    static boolean isMain(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(TowerRegistry.TOWER.get());
    }
}
