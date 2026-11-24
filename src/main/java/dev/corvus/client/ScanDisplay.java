package dev.corvus.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.corvus.Corvus;
import dev.corvus.HudOptions;
import dev.corvus.ScanMarkerMode;
import dev.corvus.ScanSnapshot;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

/**
 * Draws the owner's last scan: cyan boxes for ore, orange-red boxes for hostiles.
 *
 * <p>Two rendering paths, chosen by {@link ScanMarkerMode}: depth-independent lines (default) and, when
 * a shader pack is active or the config asks for it, vanilla dust particles. Shader packs routinely
 * skip custom line render types, so {@code AUTO} switches to particles rather than silently drawing
 * nothing.</p>
 *
 * <p>The snapshot cleans itself up: {@link #visible()} drops the state once the level changes or
 * {@code expiresAt} passes, so a lost "clear" packet cannot leave stale markers behind.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class ScanDisplay {
    private static ClientLevel world;
    private static ScanSnapshot snapshot;
    private static int particleClock;

    private ScanDisplay() {}

    /** Iris and Oculus both expose this API; absence simply means "no shader pack". */
    static boolean shadersActive() {
        if (!ModList.get().isLoaded("iris") && !ModList.get().isLoaded("oculus")) return false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(instance));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    static HudOptions.Markers mode() {
        return ScanMarkerMode.resolve(HudOptions.scanMarkers(), shadersActive());
    }

    public static void accept(ScanSnapshot update) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(update.dimension())) return;
        if (update.expiresAt() <= level.getGameTime() || update.isEmpty()) {
            if (snapshot != null && snapshot.serial().equals(update.serial())) clear();
            return;
        }
        world = level;
        snapshot = update;
    }

    static void clear() {
        snapshot = null;
        world = null;
    }

    private static boolean visible() {
        if (snapshot == null) return false;
        if (world != Minecraft.getInstance().level || world.getGameTime() >= snapshot.expiresAt()) {
            clear();
            return false;
        }
        return true;
    }

    /** Read-out for the HUD line: remaining seconds and both counts, or null when hidden. */
    public static int[] readout() {
        if (!visible()) return null;
        long remaining = Math.max(0, snapshot.expiresAt() - world.getGameTime());
        return new int[]{snapshot.ores().size(), snapshot.hostiles().size(), (int) ((remaining + 19) / 20)};
    }

    @SubscribeEvent
    static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        visible();
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /**
     * Shader packs routinely skip custom line render types, so targets are marked with vanilla dust
     * particles instead: those go through the normal particle pipeline and survive any shader pack.
     */
    private static void tickParticles() {
        if (++particleClock % 4 != 0) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        for (ScanSnapshot.Ore ore : snapshot.ores()) ring(level, Vec3.atCenterOf(ore.pos()), 0.24, .33f, .91f, .88f);
        for (ScanSnapshot.Contact contact : snapshot.hostiles()) {
            Entity mob = world.getEntity(contact.entityId());
            if (mob != null && mob.getUUID().equals(contact.uuid()) && !mob.isAlive()) continue;
            Vec3 base = mob != null
                    ? mob.position().add(0, mob.getBbHeight() * .5, 0)
                    : Vec3.atBottomCenterOf(contact.pos()).add(0, .8, 0);
            ring(level, base, .55, 1f, .51f, .38f);
        }
    }

    private static void ring(ClientLevel level, Vec3 centre, double radius, float r, float g, float b) {
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2 * i / 6;
            level.addParticle(new DustParticleOptions(new Vector3f(r, g, b), 1.1f),
                    centre.x + Math.cos(angle) * radius, centre.y, centre.z + Math.sin(angle) * radius, 0, 0, 0);
        }
    }

    @SubscribeEvent
    static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !visible()
                || Minecraft.getInstance().options.hideGui) return;
        if (mode() == HudOptions.Markers.PARTICLES) {
            tickParticles();
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        for (ScanSnapshot.Ore ore : snapshot.ores()) {
            LevelRenderer.renderLineBox(pose, lines, new AABB(ore.pos()).inflate(.015), .33f, .91f, .88f, 1f);
        }
        float partial = event.getPartialTick();
        for (ScanSnapshot.Contact contact : snapshot.hostiles()) {
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
            LevelRenderer.renderLineBox(pose, lines, box.inflate(.04), 1f, .51f, .38f, 1f);
        }
        pose.popPose();
        // Vanilla lines() keeps depth testing on; markers must be readable through terrain.
        boolean depthWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            buffers.endBatch(RenderType.lines());
        } finally {
            if (depthWasEnabled) RenderSystem.enableDepthTest();
        }
    }

    /** Test hook for the smoke harness: what the client currently believes it should draw. */
    public static String debugState() {
        if (snapshot == null) return "none";
        return snapshot.ores().size() + " ores, " + snapshot.hostiles().size() + " hostiles, mode=" + mode()
                + ", shaders=" + shadersActive();
    }
}
