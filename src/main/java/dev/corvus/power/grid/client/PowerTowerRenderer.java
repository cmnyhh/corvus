package dev.corvus.power.grid.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.corvus.Corvus;
import dev.corvus.power.grid.PowerTowerEntity;
import dev.corvus.power.grid.PowerTowerPartBlock;
import dev.corvus.power.grid.TowerRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Breathing top lamp: green at two-thirds charge, amber from one-third, red below. */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PowerTowerRenderer implements BlockEntityRenderer<PowerTowerEntity> {
    /** Lamp cell of the top model (7..9 / 14.5..14.95) lifted by the two segments above the BE. */
    private static final VoxelShape LAMP = Block.box(6.95D, 46.3D, 6.95D, 9.05D, 47.05D, 9.05D);

    public PowerTowerRenderer(BlockEntityRendererProvider.Context context) {}

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TowerRegistry.TYPE.get(), PowerTowerRenderer::new);
    }

    @Override public void render(PowerTowerEntity tower, float partialTick, PoseStack pose, MultiBufferSource buffer,
                                 int packedLight, int packedOverlay) {
        Level level = tower.getLevel();
        if (level == null) return;
        BlockState top = level.getBlockState(tower.getBlockPos().above(2));
        int band = top.hasProperty(PowerTowerPartBlock.LAMP) ? top.getValue(PowerTowerPartBlock.LAMP) : tower.lampBand();
        float pulse = 0.45F + 0.55F * (0.5F + 0.5F * (float) Math.sin((level.getGameTime() % 40) / 40.0D * Math.PI * 2.0D));
        float red = band == 2 ? 0.35F : 1.0F;
        float green = band == 2 ? 1.0F : band == 1 ? 0.72F : 0.18F;
        float blue = band == 2 ? 0.45F : 0.18F;
        VertexConsumer vertices = buffer.getBuffer(RenderType.debugFilledBox());
        LevelRenderer.renderVoxelShape(pose, vertices, LAMP, 0.0D, 0.0D, 0.0D, red, green, blue, 0.35F + 0.45F * pulse, false);
    }

    @Override public int getViewDistance() { return 96; }
}
