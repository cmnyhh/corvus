package dev.dronepal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.dronepal.DroneEntity;
import dev.dronepal.Dronepal;
import java.io.Reader;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class DroneRenderer extends EntityRenderer<DroneEntity> {
    private static final ResourceLocation GEO = ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "geo/drone.geo.json");
    private static final java.util.Map<String,ResourceLocation> PAINTS = dev.dronepal.PaintCans.PALETTE.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(name -> name, name -> ResourceLocation.fromNamespaceAndPath(
                    Dronepal.ID, "textures/entity/drone_" + name + ".png")));
    private static final ResourceLocation ANIM = ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "animations/drone.animation.json");
    private final java.util.Map<String, List<DroneMesh.Bone>> rigs = new java.util.HashMap<>();
    private final DroneClip idle, boost, scan, damaged;
    private final GunEnvelope envelopes = new GunEnvelope();
    private final net.minecraft.client.renderer.entity.ItemRenderer items;
    private record Frame(DroneEntity entity, MultiBufferSource buffers, RenderType texture, int light,
                         org.joml.Vector3f bob, org.joml.Vector3f lean, org.joml.Vector3f sweep, float pitch, float swivel) {}
    private record Angles(float yaw, float pitch, float fold) {
        static Angles read(DroneEntity e) {
            return new Angles(dev.dronepal.DroneCombat.gimbalYaw(e), dev.dronepal.DroneCombat.aimPitch(e), dev.dronepal.DroneCombat.mountPitch(e));
        }
        Angles lerp(Angles to, float fraction) {
            return new Angles(yaw + (to.yaw-yaw)*fraction, pitch + (to.pitch-pitch)*fraction, fold + (to.fold-fold)*fraction);
        }
    }
    private record History(int tick, Angles previous, Angles current) {}
    private final java.util.Map<DroneEntity,History> history = new java.util.WeakHashMap<>();
    private Angles interpolated(DroneEntity drone, float partial) {
        var h = history.get(drone);
        if (h == null || h.tick != drone.tickCount) {
            var current = Angles.read(drone);
            var previous = h != null && h.tick + 1 == drone.tickCount ? h.current : current;
            h = new History(drone.tickCount, previous, current);
            history.put(drone,h);
        }
        return h.previous.lerp(h.current, Math.clamp(partial, 0F, 1F));
    }

    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Dronepal.ENTITY.get(), DroneRenderer::new);
    }
    DroneRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        items = ctx.getItemRenderer();
        try (Reader reader = ctx.getResourceManager().getResourceOrThrow(GEO).openAsReader();
             Reader anim = ctx.getResourceManager().getResourceOrThrow(ANIM).openAsReader()) {
            rigs.put("peregrine", DroneMesh.rig(reader));
            for (String frame : java.util.List.of("owl", "bedrock", "scrap")) {
                var location = ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "geo/" + frame + ".geo.json");
                var model = ctx.getResourceManager().getResource(location);
                if (model.isPresent()) {
                    try (Reader source = model.get().openAsReader()) { rigs.put(frame, DroneMesh.rig(source)); }
                }
            }
            var clips = DroneClip.load(anim);
            idle = require(clips, "idle", "position");
            damaged = require(clips, "damaged", "rotation");
            boost = require(clips, "boost", "rotation");
            scan = require(clips, "scan", "rotation");
        } catch (Exception e) { throw new IllegalStateException("Cannot load drone rig and clips", e); }
        shadowRadius = 0.45F;
    }
    @Override public ResourceLocation getTextureLocation(DroneEntity entity) {
        return PAINTS.getOrDefault(entity.getPaintName(), PAINTS.get("standard"));
    }
    @Override public void render(DroneEntity entity, float yaw, float partial, PoseStack poses, MultiBufferSource buffers, int light) {
        poses.pushPose();
        poses.mulPose(Axis.YP.rotationDegrees(180F - yaw));
        RenderType texture = RenderType.entityCutoutNoCull(getTextureLocation(entity));
        float time = (entity.tickCount + partial) / 20F;
        var bob = idle.sample(time % idle.duration()).div(16F);
        var lean = boost.sample(entity.getFlightBlend() * boost.duration());
        var sweep = entity.isScanActive() ? scan.sample(time % scan.duration()) : null;
        var hit = damaged.hurt(entity.hurtTime, entity.hurtDuration, partial).mul((float)Math.PI / 180F);
        poses.mulPose(new org.joml.Quaternionf().rotationZYX(hit.z,hit.y,hit.x));
        var rig = rigs.getOrDefault(entity.getFrameId(), rigs.get("peregrine"));
        Angles angles = interpolated(entity, partial);
        float deployed = 1F - Math.clamp(angles.fold / 85F, 0F, 1F);
        float pitch = envelopes.pitch(entity, rig, angles.fold) + deployed * angles.pitch;
        float swivel = deployed * Math.clamp(angles.yaw, -75F, 75F);
        Frame frame = new Frame(entity, buffers, texture, light, bob, lean, sweep, pitch, swivel);
        for (var root : rig) renderBone(root, poses, frame);
        poses.popPose();
        super.render(entity, yaw, partial, poses, buffers, light);
    }
    private static DroneClip require(java.util.Map<String,DroneClip> clips, String name, String channel) {
        DroneClip track = clips.get(name);
        if (track == null || !channel.equals(track.channel())) throw new IllegalArgumentException("Missing " + name + "." + channel);
        return track;
    }
    private void renderBone(DroneMesh.Bone bone, PoseStack poses, Frame frame) {
        var bob = frame.bob(); var lean = frame.lean(); var sweep = frame.sweep();
        poses.pushPose();
        if (bone.name().equals(idle.bone())) poses.translate(bob.x,bob.y,bob.z);
        var pivot = bone.pivot();
        var r = bone.rotation();
        float x=r.x, y=r.y, z=r.z;
        float rad = (float)Math.PI / 180F;
        if (bone.name().equals(boost.bone())) { x+=lean.x*rad; y+=lean.y*rad; z+=lean.z*rad; }
        if (sweep!=null && bone.name().equals(scan.bone())) { x+=sweep.x*rad; y+=sweep.y*rad; z+=sweep.z*rad; }
        if (bone.name().equals("gimbal")) {
            // Local forward is -Z. Yaw about the upright axle, then pitch about its rotated X axle.
            y -= frame.swivel() * rad;
            x -= frame.pitch() * rad;
        }
        if (x!=0 || y!=0 || z!=0) {
            poses.translate(pivot.x,pivot.y,pivot.z);
            poses.mulPose(new org.joml.Quaternionf().rotationZYX(z,y,x));
            poses.translate(-pivot.x,-pivot.y,-pivot.z);
        }
        PoseStack.Pose p = poses.last();
        if (!bone.vertices().isEmpty()) {
            VertexConsumer out = frame.buffers().getBuffer(frame.texture());
            for (DroneMesh.Vertex v : bone.vertices()) {
                out.addVertex(p, v.x(), v.y(), v.z()).setColor(-1).setUv(v.u(), v.v()).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(frame.light()).setNormal(p, v.nx(), v.ny(), v.nz());
            }
        }
        if (bone.name().equals("gun_mount") && !frame.entity().getMainHandItem().isEmpty()) {
            poses.pushPose();
            // THIRD_PERSON_RIGHT_HAND resolves the pack's thirdperson_hand locator.
            // Seat that grip just below the shoe. The parent gimbal rotates the
            // shoe and the held model together, keeping the same contact point.
            poses.translate(pivot.x, pivot.y - 0.055F, pivot.z);
            var capture = envelopes.begin(frame.entity(),poses.last().pose());
            if (capture==null) renderHeld(frame,poses,frame.buffers());
            else capture.draw(() -> renderHeld(frame,poses,capture.wrap(frame.buffers())));
            poses.popPose();
        }
        for (var child : bone.children()) renderBone(child, poses, frame);
        poses.popPose();
    }
    private void renderHeld(Frame f, PoseStack poses, MultiBufferSource buffers) {
        items.renderStatic(f.entity(),f.entity().getMainHandItem(),net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false,poses,buffers,f.entity().level(),f.light(),OverlayTexture.NO_OVERLAY,f.entity().getId());
    }
}
