package dev.dronepal.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dronepal.Dronepal;
import dev.dronepal.HudOptions;
import dev.dronepal.ScanSnapshot;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid=Dronepal.ID,value=Dist.CLIENT)
public final class ScanDisplay {
    private static final RenderType OUTLINE = RenderType.create("dronepal_scan", DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES, 8192, RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST).setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL).setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
            .createCompositeState(false));
    private static ClientLevel world;
    private static ScanSnapshot snapshot;
    private static int particleClock;

    /** Iris and Oculus both expose this API; absence simply means "no shader pack". */
    static boolean shadersActive() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(instance));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    private static HudOptions.Markers mode() {
        return dev.dronepal.ScanMarkerMode.resolve(HudOptions.SCAN_MARKERS.get(), shadersActive());
    }

    public static void accept(ScanSnapshot update) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(update.dimension())) return;
        if (update.expiresAt() <= level.getGameTime() || update.ores().isEmpty() && update.hostiles().isEmpty()) {
            if (snapshot != null && snapshot.serial().equals(update.serial())) clear();
            return;
        }
        world = level;
        snapshot = update;
    }
    private static void clear() { snapshot = null; world = null; }
    private static boolean visible() {
        if (snapshot == null) return false;
        if (world != Minecraft.getInstance().level || world.getGameTime() >= snapshot.expiresAt()) {
            clear();
            return false;
        }
        return true;
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) { visible(); }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    /**
     * Shader packs routinely skip custom line render types, so targets are marked with vanilla dust
     * particles instead: those go through the normal particle pipeline and survive any shader pack.
     */
    private static void tickParticles() {
        if (++particleClock % 4 != 0) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        for (var ore : snapshot.ores()) ring(level, Vec3.atCenterOf(ore.pos()), 0.24, .33f, .91f, .88f);
        float partial = 0;
        for (var contact : snapshot.hostiles()) {
            Entity mob = world.getEntity(contact.entityId());
            if (mob != null && mob.getUUID().equals(contact.uuid()) && !mob.isAlive()) continue;
            Vec3 base = mob != null ? mob.position().add(0, mob.getBbHeight() * .5, 0) : Vec3.atBottomCenterOf(contact.pos()).add(0, .8, 0);
            ring(level, base, .55, 1f, .51f, .38f);
        }
    }

    private static void ring(ClientLevel level, Vec3 centre, double radius, float r, float g, float b) {
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2 * i / 6;
            level.addParticle(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(r, g, b), 1.1f),
                    centre.x + Math.cos(angle) * radius, centre.y, centre.z + Math.sin(angle) * radius, 0, 0, 0);
        }
    }

    @SubscribeEvent static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !visible() || Minecraft.getInstance().options.hideGui) return;
        if (mode() == HudOptions.Markers.PARTICLES) { tickParticles(); return; }
        Vec3 camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var lines = buffers.getBuffer(OUTLINE);
        pose.pushPose();
        pose.translate(-camera.x,-camera.y,-camera.z);
        for (var ore : snapshot.ores()) LevelRenderer.renderLineBox(pose, lines, new AABB(ore.pos()).inflate(.015), .33f,.91f,.88f,1f);
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (var contact : snapshot.hostiles()) {
            Entity mob = world.getEntity(contact.entityId());
            AABB box;
            if (mob != null && mob.getUUID().equals(contact.uuid())) {
                if (!mob.isAlive()) continue;
                box = mob.getBoundingBox().move(mob.getPosition(partial).subtract(mob.position()));
            } else {
                Vec3 position = Vec3.atBottomCenterOf(contact.pos());
                var type = BuiltInRegistries.ENTITY_TYPE.get(contact.type());
                box = type.getDimensions().makeBoundingBox(position);
            }
            LevelRenderer.renderLineBox(pose, lines, box.inflate(.04), 1f,.51f,.38f,1f);
        }
        pose.popPose();
        // NO_DEPTH_TEST is a no-op shard. The preceding particle pass can leave depth testing enabled.
        boolean depthWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        try {
            buffers.endBatch(OUTLINE);
        } finally {
            if (depthWasEnabled) com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
    }

    private ScanDisplay() {}
}
