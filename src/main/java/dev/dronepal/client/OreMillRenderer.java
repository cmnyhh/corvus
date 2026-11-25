package dev.dronepal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.corvus.core.WorkCycle;
import dev.dronepal.Dronepal;
import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillRegistry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * The mill's sorting disc, as the model has it: one thin glowing ring.
 *
 * <p>The Blockbench model carries the disc itself - four sliced plate elements ({@code disc_r0..r3}),
 * a hub ({@code disc_hub}) and the 26-segment pit wall ({@code pitrim_*}) - so a client overlay must
 * not draw blades on top of it. The old overlay drew four brass blades around a hub at the <em>main
 * block's</em> top centre, while the model's disc is centred on the <em>seam</em> between the two
 * halves and sunk 1.1 units below the deck: the blades hovered half a block off the machine and above
 * the plate. They are gone; what is left is this ring, placed from the model's own numbers:</p>
 *
 * <ul>
 *   <li>centre {@code (16, 8)} in the main block's units - the seam midpoint, i.e. local
 *       {@code (1.0, 0.5)} in blocks, because the model is the left half and {@code ore_mill_side} the
 *       right one ({@code art/mill-blocks/MANIFEST.md} section 4.1);</li>
 *   <li>plate top {@code y = 14.10} units = {@code 0.88125} blocks (the deck is 15.2, the pit 1.1 deep);</li>
 *   <li>radius {@code 5.6} units = {@code 0.35} blocks - the pit wall's inner edge, so the ring runs
 *       just inside the rim the plate sits in, concentric with the hub.</li>
 * </ul>
 *
 * <p>It turns on the same clock the drone's gimbal reads ({@link WorkCycle#discAngle}), which is why
 * {@link WorkCycle#gimbalYaw} equals the disc angle during the scan; the ring's colour follows the
 * shared phase lamp ({@link WorkCycle#lampColor}). Drawn with {@link RenderType#debugQuads()} (position
 * + colour, unlit), so it needs no model or texture file of its own and the block's cube model,
 * collision and lighting stay untouched. Presentation only: the block entity keeps no disc state and
 * the mill rules never read it.</p>
 */
@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OreMillRenderer implements BlockEntityRenderer<OreMillEntity> {
    /** Seam midpoint, in the main block's local space: x = 16/16 (the side half starts here), z = 8/16. */
    private static final float DISC_X = 1.0F;
    private static final float DISC_Z = 0.5F;
    /** Plate top 14.10/16, plus a hair so the ring wins the depth test against the plate. */
    private static final float DISC_Y = 14.10F / 16.0F + 0.004F;
    /**
     * Radius of the ring: 5.0 units, i.e. the 1.0-unit gap between the plate's outer edge (4.5) and the
     * pit wall's inner face (5.6). A ring drawn on 5.6 itself would be half swallowed by the 0.32-unit
     * thick wall and read as a few dots from above; 5.0 sits in the open gap, concentric with the hub.
     */
    private static final float RING_RADIUS = 5.0F / 16.0F;
    private static final float RING_HALF_WIDTH = 0.2F / 16.0F;
    /** Segments around the ring: under one unit of arc each, fine enough to read as a circle up close. */
    private static final int RING_SEGMENTS = 48;
    private static final float VIEW_RANGE = 48.0F;

    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(OreMillRegistry.type(), OreMillRenderer::new);
    }

    public OreMillRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public int getViewDistance() { return (int) VIEW_RANGE; }

    @Override
    public void render(OreMillEntity mill, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Level level = mill.getLevel();
        if (level == null) return;
        int cycle = (int) level.getGameTime();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        // The colour is the shared work-phase lamp, so the ring reports the same phase the drone does.
        int argb = 0xFF000000 | WorkCycle.lampColor(cycle);
        // A ring is rotation-invariant, so it needs no angle of its own: only the colour follows the
        // phase. The turn stays a server-side fact the drone's gimbal reads (WorkCycle#discAngle).
        pose.pushPose();
        pose.translate(DISC_X, DISC_Y, DISC_Z);
        ring(quads, pose.last().pose(), RING_RADIUS, RING_HALF_WIDTH, argb);
        pose.popPose();
    }

    /**
     * A flat annulus in the local XZ plane, emitted with both windings so it shows from above and below
     * (the pit opens upward, but a player may look in from any side).
     */
    private static void ring(VertexConsumer quads, Matrix4f matrix, float radius, float halfWidth, int argb) {
        float inner = radius - halfWidth;
        float outer = radius + halfWidth;
        for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            double a0 = Math.PI * 2.0D * segment / RING_SEGMENTS;
            double a1 = Math.PI * 2.0D * (segment + 1) / RING_SEGMENTS;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            vertex(quads, matrix, c0 * inner, s0 * inner, argb);
            vertex(quads, matrix, c0 * outer, s0 * outer, argb);
            vertex(quads, matrix, c1 * outer, s1 * outer, argb);
            vertex(quads, matrix, c1 * inner, s1 * inner, argb);
            vertex(quads, matrix, c1 * inner, s1 * inner, argb);
            vertex(quads, matrix, c1 * outer, s1 * outer, argb);
            vertex(quads, matrix, c0 * outer, s0 * outer, argb);
            vertex(quads, matrix, c0 * inner, s0 * inner, argb);
        }
    }

    private static void vertex(VertexConsumer quads, Matrix4f matrix, float x, float z, int argb) {
        quads.addVertex(matrix, x, 0F, z).setColor(argb);
    }

}
