package dev.corvus.power.grid.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.corvus.Corvus;
import dev.corvus.ServerOptions;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Pale coverage rings around loaded towers, only while the player holds the deployment terminal and
 * has toggled the overlay on (G). Nothing is drawn otherwise.
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class TowerGridOverlay {
    private static final int SEGMENTS = 72;
    private static final double MAX_DISTANCE = 160.0D;
    private static final float R = 0.55F, G = 0.88F, B = 1.0F, A = 0.32F;

    private TowerGridOverlay() {}

    @SubscribeEvent
    static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        while (TowerGridKeys.TOGGLE.consumeClick()) {
            TowerGridKeys.toggle();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(
                        "message.dronepal.grid_overlay." + (TowerGridKeys.shown() ? "on" : "off")), true);
            }
        }
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!TowerGridKeys.shown() || minecraft.level == null || minecraft.player == null) return;
        if (minecraft.options.hideGui || !holdsTerminal(minecraft)) return;
        Collection<BlockPos> towers = TowerClientState.towers(minecraft.level);
        if (towers.isEmpty()) return;
        int radius = ServerOptions.towerRadius();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer vertices = buffers.getBuffer(RenderType.lines());
        for (BlockPos tower : towers) {
            Vec3 center = Vec3.atCenterOf(tower);
            if (center.distanceToSqr(camera) > MAX_DISTANCE * MAX_DISTANCE) continue;
            ring(pose.last().pose(), vertices, center.x, tower.getY() + 1.45D, center.z, radius);
            line(pose.last().pose(), vertices, center.x, tower.getY() + 0.2D, center.z,
                    center.x, tower.getY() + 3.2D, center.z);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static boolean holdsTerminal(Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null) return false;
        return player.getMainHandItem().is(Corvus.DRONE.get()) || player.getOffhandItem().is(Corvus.DRONE.get());
    }

    private static void ring(org.joml.Matrix4f matrix, VertexConsumer vertices, double x, double y, double z, int radius) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = (Math.PI * 2.0D) * i / SEGMENTS;
            double a1 = (Math.PI * 2.0D) * (i + 1) / SEGMENTS;
            line(matrix, vertices, x + Math.cos(a0) * radius, y, z + Math.sin(a0) * radius,
                    x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius);
        }
    }

    private static void line(org.joml.Matrix4f matrix, VertexConsumer vertices,
                             double x0, double y0, double z0, double x1, double y1, double z1) {
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length <= 0.0F) return;
        nx /= length; ny /= length; nz /= length;
        vertices.vertex(matrix, (float) x0, (float) y0, (float) z0).color(R, G, B, A).normal(nx, ny, nz).endVertex();
        vertices.vertex(matrix, (float) x1, (float) y1, (float) z1).color(R, G, B, A).normal(nx, ny, nz).endVertex();
    }
}
