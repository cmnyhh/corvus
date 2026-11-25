package dev.dronepal.power.grid.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dronepal.Dronepal;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Pale coverage rings around every loaded tower, visible only while the player holds the deployment
 * terminal and has switched the overlay on (G). The ring radius is the configured {@code towerRadius},
 * drawn at mid-tower height, with a short cross on top so the ring's owner is unambiguous.
 *
 * <p>Nothing is drawn when the terminal is in neither hand, which is the "平时不显示" rule: the overlay
 * is an inspection tool, not a permanent minimap.</p>
 */
@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT)
public final class TowerGridOverlay {
    private static final int SEGMENTS = 72;
    private static final double MAX_DISTANCE = 160.0D;
    private static final float R = 0.55F, G = 0.88F, B = 1.0F, A = 0.32F;

    private TowerGridOverlay() {}

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
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
        int radius = dev.dronepal.ServerOptions.towerRadius();
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
            // A short vertical tick marks the tower itself inside the ring.
            line(pose.last().pose(), vertices, center.x, tower.getY() + 0.2D, center.z,
                    center.x, tower.getY() + 3.2D, center.z);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static boolean holdsTerminal(Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null) return false;
        return player.getMainHandItem().is(Dronepal.DRONE.get()) || player.getOffhandItem().is(Dronepal.DRONE.get());
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
        vertices.addVertex(matrix, (float) x0, (float) y0, (float) z0).setColor(R, G, B, A).setNormal(nx, ny, nz);
        vertices.addVertex(matrix, (float) x1, (float) y1, (float) z1).setColor(R, G, B, A).setNormal(nx, ny, nz);
    }
}
