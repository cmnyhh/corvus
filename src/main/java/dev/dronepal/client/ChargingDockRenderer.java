package dev.dronepal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.dronepal.Dronepal;
import dev.dronepal.charging.ChargingDockEntity;
import dev.dronepal.charging.ChargingDockRegistry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * Live hologram above the charging dock.
 * <p>
 * The docked cell (or the controller holding it) floats and slowly turns over the pad, and a
 * camera-facing panel lists the buffer charge, the cell's own charge and the coal left. Every value
 * is copied verbatim from {@link ChargingDockEntity#getUpdateTag}, so what is drawn is the server's
 * state and not a client-side guess.
 * <p>
 * Drawn only within {@value #VIEW_RANGE} blocks (see {@link #shouldRender}); the frustum test is done
 * by the block entity dispatcher against {@link #getRenderBoundingBox(ChargingDockEntity)}.
 */
@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ChargingDockRenderer implements BlockEntityRenderer<ChargingDockEntity> {
    /** Blocks past which the hologram is not drawn at all. */
    private static final double VIEW_RANGE = 16.0;
    /** The pad is 8/16 high, so the item centre and label sit above that top face. */
    private static final float ITEM_Y = 0.86F;
    private static final float ITEM_SCALE = 0.5F;
    private static final float BOB_HEIGHT = 0.03F;
    private static final float SPIN_DEGREES_PER_TICK = 2.0F;
    private static final float PANEL_Y = 1.55F;
    /** World scale of one font pixel. */
    private static final float TEXT_SCALE = 0.0125F;
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;
    private static final int BAR_HEIGHT = 4;
    private static final int BAR_TOP_GAP = 2;
    private static final int BAR_BOTTOM_GAP = 3;
    private static final int NAME_WIDTH = 104;
    private static final int PANEL_BORDER = 0xC01A2228;
    private static final int PANEL_FILL = 0xA6000000;
    private static final int BAR_TRACK = 0xCC3A464E;
    /**
     * Billboard-space depth in font pixels. The camera-relative billboard has +z pointing at the viewer
     * (that is why vanilla draws drop shadows at z = 0 and the glyphs at z + 0.03), so the backdrop has
     * to use negative depth. The quads of one batch are also sorted far to near before upload, which
     * puts the backdrop first, the track second and the bar fill last.
     */
    private static final float BACKDROP_Z = -4.0F;
    private static final float BAR_TRACK_Z = -2.0F;
    private static final float BAR_FILL_Z = -1.0F;

    private final ItemRenderer items;
    private final Font font;

    public ChargingDockRenderer(BlockEntityRendererProvider.Context context) {
        this.items = context.getItemRenderer();
        this.font = context.getFont();
    }

    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ChargingDockRegistry.type(), ChargingDockRenderer::new);
    }

    @Override public int getViewDistance() { return (int) VIEW_RANGE; }

    @Override public boolean shouldRender(ChargingDockEntity dock, Vec3 camera) {
        return camera.distanceToSqr(Vec3.atCenterOf(dock.getBlockPos())) < VIEW_RANGE * VIEW_RANGE;
    }

    /** Covers the floating item and the label so the frustum test culls them as one unit. */
    @Override public AABB getRenderBoundingBox(ChargingDockEntity dock) {
        return new AABB(dock.getBlockPos()).expandTowards(0.0, 1.9, 0.0).inflate(1.2);
    }

    @Override
    public void render(ChargingDockEntity dock, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Level level = dock.getLevel();
        if (level == null) return;
        float time = level.getGameTime() + partialTick;
        ItemStack target = dock.targetStack();
        if (!target.isEmpty()) {
            pose.pushPose();
            pose.translate(0.5F, ITEM_Y + Mth.sin(time * 0.06F) * BOB_HEIGHT, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(time * SPIN_DEGREES_PER_TICK));
            pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            items.renderStatic(target, ItemDisplayContext.FIXED, light, overlay, pose, buffers, level, dock.getBlockPos().hashCode());
            pose.popPose();
        }
        drawPanel(dock, pose, buffers);
    }

    private void drawPanel(ChargingDockEntity dock, PoseStack pose, MultiBufferSource buffers) {
        List<Row> rows = rows(dock);
        int textWidth = 0;
        for (Row row : rows) textWidth = Math.max(textWidth, font.width(row.text()));
        int width = textWidth + PADDING * 2;
        int height = rows.size() * LINE_HEIGHT + BAR_TOP_GAP + BAR_HEIGHT + BAR_BOTTOM_GAP + PADDING * 2;
        pose.pushPose();
        pose.translate(0.5F, PANEL_Y, 0.5F);
        pose.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        // Y is flipped so font pixel coordinates grow downwards. The backdrop, bar and glyphs share one
        // batch source, so all quads are written before the first glyph request flushes that batch; the
        // glyphs of that later batch plus polygon offset keep the text on top of the panel.
        pose.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
        Matrix4f matrix = pose.last().pose();
        float left = -width / 2.0F;
        float right = width / 2.0F;
        float top = -height / 2.0F;
        float bottom = height / 2.0F;
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        quad(quads, matrix, left - 1, top - 1, right + 1, bottom + 1, BACKDROP_Z - 1.0F, PANEL_BORDER);
        quad(quads, matrix, left, top, right, bottom, BACKDROP_Z, PANEL_FILL);
        drawBar(quads, matrix, left + PADDING, top + PADDING + LINE_HEIGHT + BAR_TOP_GAP, right - PADDING, dock.storedEnergy(), dock.buffer());
        float y = top + PADDING;
        for (int i = 0; i < rows.size(); i++) {
            font.drawInBatch(rows.get(i).text(), left + PADDING, y, rows.get(i).color(), true, matrix, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            y += LINE_HEIGHT;
            if (i == 0) y += BAR_TOP_GAP + BAR_HEIGHT + BAR_BOTTOM_GAP;
        }
        pose.popPose();
    }

    private static void drawBar(VertexConsumer quads, Matrix4f matrix, float x0, float y0, float x1, int value, int max) {
        quad(quads, matrix, x0, y0, x1, y0 + BAR_HEIGHT, BAR_TRACK_Z, BAR_TRACK);
        if (max <= 0 || value <= 0) return;
        float part = Mth.clamp(value / (float) max, 0F, 1F);
        quad(quads, matrix, x0, y0, x0 + (x1 - x0) * part, y0 + BAR_HEIGHT, BAR_FILL_Z, barColor(part));
    }

    private static int barColor(float part) {
        if (part < 0.2F) return 0xFFE0715F;
        return part < 0.6F ? 0xFFE6B44C : 0xFF6FD08A;
    }

    private List<Row> rows(ChargingDockEntity dock) {
        List<Row> rows = new ArrayList<>(4);
        rows.add(new Row(group(dock.storedEnergy()) + " / " + group(dock.buffer()) + " FE", 0xFFFFFFFF));
        ItemStack target = dock.targetStack();
        if (!target.isEmpty()) {
            int capacity = Math.max(0, dock.targetCapacity());
            int charge = Math.max(0, dock.targetEnergy());
            String percent = capacity > 0 ? Math.round(charge * 100.0F / capacity) + "%" : "--";
            rows.add(new Row(fit(target.getHoverName().getString(), NAME_WIDTH) + "  " + percent, 0xFF9CD8FF));
            rows.add(new Row(group(charge) + " / " + group(capacity) + " FE", 0xFFB5C2CC));
        }
        ItemStack fuel = dock.fuelStack();
        if (!fuel.isEmpty()) {
            String label = fit(fuel.getHoverName().getString(), NAME_WIDTH) + " ×" + dock.coalCount();
            rows.add(new Row(label, 0xFFF0B45E));
        } else {
            rows.add(new Row(chinese() ? "无燃料" : "No fuel", 0xFFAEB6BE));
        }
        return rows;
    }

    private static boolean chinese() {
        return Minecraft.getInstance().getLanguageManager().getSelected().startsWith("zh");
    }

    /** Trims an item name to the label width; vanilla Font keeps CJK glyphs intact. */
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    private static String group(int value) {
        String digits = Integer.toString(Math.max(0, value));
        StringBuilder out = new StringBuilder(digits.length() + 4);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(',');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    private static void quad(VertexConsumer quads, Matrix4f matrix, float x0, float y0, float x1, float y1, float z, int argb) {
        quads.addVertex(matrix, x0, y1, z).setColor(argb);
        quads.addVertex(matrix, x1, y1, z).setColor(argb);
        quads.addVertex(matrix, x1, y0, z).setColor(argb);
        quads.addVertex(matrix, x0, y0, z).setColor(argb);
    }

    private record Row(String text, int color) {}
}
