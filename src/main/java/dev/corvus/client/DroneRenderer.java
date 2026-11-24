package dev.corvus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.corvus.Corvus;
import dev.corvus.DroneEntity;
import dev.corvus.Frames;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the authored Bedrock rig with all six 1.21 animation clips, the gimbal pose and the held gun.
 *
 * <p>Clip roles, by authored bone/track:</p>
 * <ul>
 *   <li>{@code idle} — {@code root.position}, 2 s loop: the hover bob</li>
 *   <li>{@code boost} — {@code root.rotation}: forward lean, sampled by the server's flight blend</li>
 *   <li>{@code scan} — {@code sensor.rotation}, 3 s loop: the sensor sweep while a scan is active</li>
 *   <li>{@code damaged} — {@code root.rotation}: recoil, driven by the engine's hurt timer</li>
 *   <li>{@code stow} / {@code deploy} — {@code gimbal.rotation}: the fold curve in each direction</li>
 * </ul>
 *
 * <p>The gimbal carries yaw (about the upright axle) and pitch (about its rotated X axle) from the
 * synched combat pose. While folded, the two state clips provide the authored easing instead of a
 * linear lerp, so the mount moves the way the animation author intended. The held item is drawn with
 * the standard third-person hand transform at the {@code gun_mount} pivot and is deliberately not
 * rescaled.</p>
 *
 * <p>Deferred: the 1.21 {@code GunEnvelope} measurement (which shrinks the maximum stow pitch when a
 * long gun would clip through the body). Until a gun backend exists there is nothing to measure.</p>
 */
public final class DroneRenderer extends EntityRenderer<DroneEntity> {
    private static final ResourceLocation GEO = new ResourceLocation(Corvus.ID, "geo/drone.geo.json");
    private static final ResourceLocation ANIM = new ResourceLocation(Corvus.ID, "animations/drone.animation.json");
    private static final Map<String, ResourceLocation> TEXTURES = textures();
    /** Full fold angle, in degrees: the authored stow clip ends here. */
    private static final float FOLD_LIMIT = 85F;
    private static final float RADIANS = (float) Math.PI / 180F;

    private record Frame(DroneEntity entity, MultiBufferSource buffers, RenderType texture, int light,
                         Vector3f bob, Vector3f lean, Vector3f sweep, float foldX, float pitch, float swivel) {}

    private final Map<String, List<DroneMesh.Bone>> rigs = new HashMap<>();
    private final ItemRenderer items;
    private final DroneClip idle;
    private final DroneClip boost;
    private final DroneClip scan;
    private final DroneClip damaged;
    private final DroneClip stow;
    private final DroneClip deploy;
    private final Map<DroneEntity, Float> lastFold = new WeakHashMap<>();

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        items = context.getItemRenderer();
        rigs.put("peregrine", load(context, GEO));
        for (String frame : Frames.IDS) {
            if ("peregrine".equals(frame)) continue;
            ResourceLocation geo = new ResourceLocation(Corvus.ID, "geo/" + frame + ".geo.json");
            // A frame without authored geometry falls back to the peregrine rig instead of crashing.
            if (context.getResourceManager().getResource(geo).isPresent()) rigs.put(frame, load(context, geo));
        }
        try (Reader reader = context.getResourceManager().getResourceOrThrow(ANIM).openAsReader()) {
            Map<String, DroneClip> clips = DroneClip.load(reader);
            idle = require(clips, "idle", "position");
            boost = require(clips, "boost", "rotation");
            scan = require(clips, "scan", "rotation");
            damaged = require(clips, "damaged", "rotation");
            stow = require(clips, "stow", "rotation");
            deploy = require(clips, "deploy", "rotation");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load drone clips", failure);
        }
        this.shadowRadius = 0.45F;
    }

    private static List<DroneMesh.Bone> load(EntityRendererProvider.Context context, ResourceLocation geo) {
        try (Reader reader = context.getResourceManager().getResourceOrThrow(geo).openAsReader()) {
            return DroneMesh.rig(reader);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load drone rig " + geo, failure);
        }
    }

    private static DroneClip require(Map<String, DroneClip> clips, String name, String channel) {
        DroneClip track = clips.get(name);
        if (track == null || !channel.equals(track.channel())) {
            throw new IllegalArgumentException("Missing " + name + "." + channel);
        }
        return track;
    }

    private static Map<String, ResourceLocation> textures() {
        Map<String, ResourceLocation> map = new HashMap<>();
        for (String paint : Frames.PAINTS) {
            map.put(paint, new ResourceLocation(Corvus.ID, "textures/entity/drone_" + paint + ".png"));
        }
        return Map.copyOf(map);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return TEXTURES.getOrDefault(entity.getPaintName(), TEXTURES.get("standard"));
    }

    @Override
    public void render(DroneEntity entity, float entityYaw, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int packedLight) {
        List<DroneMesh.Bone> rig = rigs.getOrDefault(entity.getFrameId(), rigs.get("peregrine"));
        if (rig == null) return;
        poses.pushPose();
        poses.mulPose(Axis.YP.rotationDegrees(180F - entityYaw));
        RenderType type = RenderType.entityCutoutNoCull(getTextureLocation(entity));
        float time = (entity.tickCount + partialTick) / 20F;
        Vector3f bob = idle.sample(mod(time, idle.duration())).div(16F);
        Vector3f lean = boost.sample(entity.getFlightBlend() * boost.duration());
        Vector3f sweep = entity.isScanActive() ? scan.sample(mod(time, scan.duration())) : null;
        Vector3f hit = damaged.hurt(entity.hurtTime, entity.hurtDuration, partialTick).mul(RADIANS);
        poses.mulPose(new Quaternionf().rotationZYX(hit.z, hit.y, hit.x));

        float fold = clamp(entity.mountPitch(), 0F, FOLD_LIMIT);
        float progress = fold / FOLD_LIMIT;
        float foldX = foldCurve(entity, progress);
        float deployed = 1F - progress;
        float pitch = deployed * clamp(entity.aimPitch(), -85F, 85F);
        float swivel = deployed * clamp(entity.aimYaw(), -75F, 75F);
        Frame frame = new Frame(entity, buffers, type, packedLight, bob, lean, sweep, foldX, pitch, swivel);
        for (DroneMesh.Bone root : rig) renderBone(root, poses, frame);
        poses.popPose();
        super.render(entity, entityYaw, partialTick, poses, buffers, packedLight);
    }

    /**
     * The authored fold curve for the current direction of travel: {@code deploy} while opening,
     * {@code stow} while closing. Both end at 0/85 degrees, so the pose stays continuous at either end.
     */
    private float foldCurve(DroneEntity entity, float progress) {
        Float previous = lastFold.put(entity, progress);
        boolean opening = previous != null && progress < previous;
        DroneClip clip = opening ? deploy : stow;
        float fraction = opening ? 1F - progress : progress;
        return Math.abs(clip.progress(fraction).x);
    }

    private static float mod(float value, float modulus) {
        return modulus <= 0 ? 0 : ((value % modulus) + modulus) % modulus;
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }

    private void renderBone(DroneMesh.Bone bone, PoseStack poses, Frame frame) {
        poses.pushPose();
        if (bone.name().equals(idle.bone())) poses.translate(frame.bob.x, frame.bob.y, frame.bob.z);
        Vector3f pivot = bone.pivot();
        Vector3f rotation = bone.rotation();
        float x = rotation.x;
        float y = rotation.y;
        float z = rotation.z;
        if (bone.name().equals(boost.bone())) {
            x += frame.lean.x * RADIANS;
            y += frame.lean.y * RADIANS;
            z += frame.lean.z * RADIANS;
        }
        if (frame.sweep != null && bone.name().equals(scan.bone())) {
            x += frame.sweep.x * RADIANS;
            y += frame.sweep.y * RADIANS;
            z += frame.sweep.z * RADIANS;
        }
        if (bone.name().equals("gimbal")) {
            // Local forward is -Z. Yaw about the upright axle, then pitch about its rotated X axle.
            y -= frame.swivel * RADIANS;
            x -= (frame.pitch + frame.foldX) * RADIANS;
        }
        if (x != 0F || y != 0F || z != 0F) {
            poses.translate(pivot.x, pivot.y, pivot.z);
            poses.mulPose(new Quaternionf().rotationZYX(z, y, x));
            poses.translate(-pivot.x, -pivot.y, -pivot.z);
        }
        if (!bone.vertices().isEmpty()) {
            PoseStack.Pose pose = poses.last();
            VertexConsumer out = frame.buffers().getBuffer(frame.texture());
            for (DroneMesh.Vertex vertex : bone.vertices()) {
                out.vertex(pose.pose(), vertex.x(), vertex.y(), vertex.z())
                        .color(255, 255, 255, 255)
                        .uv(vertex.u(), vertex.v())
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(frame.light())
                        .normal(pose.normal(), vertex.nx(), vertex.ny(), vertex.nz())
                        .endVertex();
            }
        }
        if (bone.name().equals("gun_mount") && !frame.entity().getMainHandItem().isEmpty()) {
            poses.pushPose();
            // THIRD_PERSON_RIGHT_HAND resolves the pack's thirdperson_hand locator. Seat that grip
            // just below the shoe; the parent gimbal rotates shoe and gun together, keeping contact.
            poses.translate(pivot.x, pivot.y - 0.055F, pivot.z);
            renderHeld(frame, poses);
            poses.popPose();
        }
        for (DroneMesh.Bone child : bone.children()) renderBone(child, poses, frame);
        poses.popPose();
    }

    /** No scale is applied: the gun is drawn with the vanilla third-person hand transform as-is. */
    private void renderHeld(Frame frame, PoseStack poses) {
        ItemStack stack = frame.entity().getMainHandItem();
        items.renderStatic(frame.entity(), stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, poses,
                frame.buffers(), frame.entity().level(), frame.light(), OverlayTexture.NO_OVERLAY,
                frame.entity().getId());
    }
}
