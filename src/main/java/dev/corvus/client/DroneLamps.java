package dev.corvus.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.corvus.Corvus;
import dev.corvus.DroneEntity;
import dev.corvus.LightSync;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * The client half of the lamp: an additive glow around a projection that the server says is lit.
 *
 * <p><b>Why not the light engine.</b> The 1.21 build mixes into
 * {@code LevelRenderer.getLightColor(BlockAndTintGetter, BlockState, BlockPos)} and returns a brighter
 * packed lightmap value for blocks near a drone. That hook has to be declared as a mixin config, and in
 * this port {@code META-INF/mods.toml} (which carries {@code [[mixins]]}) and {@code build.gradle} are
 * owned by another agent and are frozen for the TaCZ work — so a <em>block light engine</em> change is
 * not available to this file. What is available on a plain Forge 1.20.1 client is
 * {@link RenderLevelStageEvent}, and that is what this class uses: no block is placed, no light section is
 * dirtied, nothing is written to the world.</p>
 *
 * <p>What it draws, per lit projection (see {@link LightSync.Lamp}):</p>
 * <ul>
 *   <li>a camera-facing additive halo whose radius tracks the module's {@code radius} parameter,</li>
 *   <li>a brighter core disc, so the body itself reads as the source,</li>
 *   <li>a soft pool on the first solid surface below, so the ground around the drone is visibly lighter.</li>
 * </ul>
 *
 * <p>Brightness is the profile's {@code light_level}/15 and the colour is the module's {@code tint}; the
 * scrap headlamp's flicker bit dims the halo on a 10-step cycle. A dark lamp is simply absent from the
 * cache ({@link LightSync#prune}), so an empty battery, a switched-off lamp and a recalled drone all
 * produce no glow at all.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class DroneLamps {
    private static final int SEGMENTS = 20;
    /** Halo radius in blocks (the module's radius broadens the ground pool, not the halo). */
    private static final double HALO = 0.85D;
    private static final double CORE = 0.32D;
    /** Vertical search for the surface the pool is drawn on. */
    private static final int FLOOR_SCAN = 8;

    private DroneLamps() {}

    /** Drops lamp entries whose entity is gone; a recalled projection must not leave a glow behind. */
    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            LightSync.forgetAll();
            return;
        }
        if (client.level.getGameTime() % 20 != 0) return;
        LightSync.prune(id -> client.level.getEntity(id) != null);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        boolean any = false;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof DroneEntity && LightSync.lamp(entity.getId()) != null) {
                any = true;
                break;
            }
        }
        if (!any) return;
        Vec3 eye = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        Matrix4f matrix = pose.last().pose();
        beginAdditive();
        long time = client.level.getGameTime();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof DroneEntity drone)) continue;
            LightSync.Lamp lamp = LightSync.lamp(drone.getId());
            if (lamp == null || !lamp.lit()) continue;
            drawHalo(client, event.getCamera(), matrix, drone, lamp, time);
        }
        endAdditive();
        pose.popPose();
    }

    private static void drawHalo(Minecraft client, Camera camera, Matrix4f matrix, DroneEntity drone,
                                 LightSync.Lamp lamp, long time) {
        float strength = flicker(lamp, drone.getId(), time) / 15F;
        int red = (lamp.rgb() >> 16) & 0xFF;
        int green = (lamp.rgb() >> 8) & 0xFF;
        int blue = lamp.rgb() & 0xFF;
        Vec3 center = drone.getBoundingBox().getCenter();
        // Camera-facing basis, so the halo is a disc that always faces the player.
        Vec3 look = new Vec3(camera.getLookVector());
        Vec3 right = look.cross(new Vec3(0D, 1D, 0D));
        if (right.lengthSqr() < 1.0E-6D) right = new Vec3(1D, 0D, 0D);
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();
        double broad = HALO * (0.75D + 0.25D * Mth.clamp(lamp.radius() / 16D, 0D, 1D));
        fan(matrix, center, right, up, broad, red, green, blue, 0.30F * strength);
        fan(matrix, center, right, up, CORE, red, green, blue, 0.65F * strength);
        // Ground pool on the first solid surface below the drone.
        BlockPos base = drone.blockPosition();
        for (int depth = 1; depth <= FLOOR_SCAN; depth++) {
            BlockPos floor = base.below(depth);
            BlockState state = client.level.getBlockState(floor);
            if (state.isAir() || !state.isSolidRender(client.level, floor)) continue;
            Vec3 pool = new Vec3(center.x, floor.getY() + 1.015D, center.z);
            double radius = Math.min(1.4D + 0.6D * lamp.radius(), 9.0D);
            Vec3 flat = new Vec3(1D, 0D, 0D);
            Vec3 flat2 = new Vec3(0D, 0D, 1D);
            fan(matrix, pool, flat, flat2, radius, red, green, blue, 0.32F * strength);
            fan(matrix, pool, flat, flat2, radius * 0.45D, red, green, blue, 0.34F * strength);
            break;
        }
    }

    /** The flicker bit dims the profile's brightness on a short cycle; a steady lamp is untouched. */
    private static int flicker(LightSync.Lamp lamp, int entityId, long time) {
        int brightness = Math.max(1, lamp.brightness());
        if (!lamp.flicker()) return brightness;
        return (time / 3 + entityId) % 10 == 0 ? Math.max(1, brightness - 5) : brightness;
    }

    /** One additive triangle fan: bright centre, alpha fading to zero at {@code radius}. */
    private static void fan(Matrix4f matrix, Vec3 center, Vec3 u, Vec3 v, double radius,
                            int red, int green, int blue, float alpha) {
        if (alpha <= 0.004F) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, (float) center.x, (float) center.y, (float) center.z)
                .color(red, green, blue, Mth.clamp(alpha, 0F, 1F)).endVertex();
        for (int step = 0; step <= SEGMENTS; step++) {
            double angle = Math.PI * 2D * step / SEGMENTS;
            double a = Math.cos(angle) * radius;
            double b = Math.sin(angle) * radius;
            buffer.vertex(matrix,
                            (float) (center.x + u.x * a + v.x * b),
                            (float) (center.y + u.y * a + v.y * b),
                            (float) (center.z + u.z * a + v.z * b))
                    .color(red, green, blue, 0)
                    .endVertex();
        }
        Tesselator.getInstance().end();
    }

    private static void beginAdditive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
    }

    private static void endAdditive() {
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
