package dev.corvus.client;

import dev.corvus.Corvus;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Fixed-position subtitles for drone comms: a queue of at most eight lines, paged four at a time, drawn
 * in a cut-corner box above the hotbar.
 *
 * <p>1.20.1 has no {@code RegisterGuiLayersEvent}/{@code VanillaGuiLayers}; the overlay is registered
 * above {@link VanillaGuiOverlay#HOTBAR} on the MOD bus instead. Everything else matches the 1.21
 * renderer, including the per-page lifetime and the persona accent colours.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CommsSubtitles {
    private record Message(Component speaker, int accent, Component body) {}

    private static final ArrayDeque<Message> queue = new ArrayDeque<>();
    private static Message current;
    private static ClientLevel level;
    private static long start;
    private static int bodyWidth;
    private static int page;
    private static List<FormattedCharSequence> lines = List.of();

    private CommsSubtitles() {}

    public static void accept(Component speaker, String persona, Component text) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || text == null || text.getString().isBlank()) return;
        if (level != client.level) clear();
        level = client.level;
        int accent = switch (persona == null ? "" : persona) {
            case "tactical" -> 0xffecc383;
            case "chatty" -> 0xffffa6bc;
            default -> 0xff87d7cd;
        };
        if (queue.size() >= 8) queue.removeFirst();
        queue.addLast(new Message(speaker.copy(), accent, text.copy()));
        if (current == null) next();
    }

    private static void next() {
        current = queue.pollFirst();
        start = level == null ? 0 : level.getGameTime();
        bodyWidth = 0;
        page = 0;
    }

    private static void clear() {
        queue.clear();
        current = null;
        level = null;
    }

    /** Test hook: lines waiting plus whether one is on screen. */
    public static String debugState() {
        return current == null ? "idle(" + queue.size() + " queued)" : "showing:" + current.body.getString();
    }

    @SubscribeEvent
    static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "comms_subtitle",
                (gui, graphics, partialTick, screenWidth, screenHeight) -> render(graphics));
    }

    private static void render(GuiGraphics graphics) {
        if (current == null) return;
        Minecraft client = Minecraft.getInstance();
        if (level != client.level) {
            clear();
            return;
        }
        var font = client.font;
        int width = clamp(graphics.guiWidth() * 2 / 3, 150, 280);
        if (bodyWidth != width) {
            bodyWidth = width;
            lines = font.split(current.body, width - 18);
        }
        int pages = Math.max(1, (lines.size() + 3) / 4);
        page = Math.min(page, pages - 1);
        int from = page * 4;
        int to = Math.min(from + 4, lines.size());
        int ticks = clamp(45 + (to - from) * 24, 80, 150);
        if (level.getGameTime() - start >= ticks) {
            if (++page >= pages) next();
            else start = level.getGameTime();
            return;
        }
        if (client.screen != null || client.options.hideGui) return;
        int content = font.width(current.speaker) + 32;
        for (int index = from; index < to; index++) content = Math.max(content, font.width(lines.get(index)) + 18);
        width = clamp(content, 88, width);
        int height = 22 + (to - from) * (font.lineHeight + 2);
        int left = (graphics.guiWidth() - width) / 2;
        int top = Math.max(4, graphics.guiHeight() - 83 - height);
        cut(graphics, left + 1, top + 2, width, height, 0x5520262a);
        cut(graphics, left, top, width, height, 0xff53646a);
        cut(graphics, left + 1, top + 1, width - 2, height - 2, 0xf3232e35);
        graphics.fill(left + 3, top + 4, left + 5, top + 14, current.accent);
        String counter = pages > 1 ? (page + 1) + "/" + pages : "";
        int labelWidth = width - 17 - font.width(counter);
        graphics.drawString(font, font.plainSubstrByWidth(current.speaker.getString(), labelWidth),
                left + 9, top + 4, current.accent, false);
        if (!counter.isEmpty()) {
            graphics.drawString(font, counter, left + width - 6 - font.width(counter), top + 4, 0xffb5c4c9, false);
        }
        graphics.fill(left + 8, top + 16, left + width - 7, top + 17, 0xff405057);
        for (int index = from; index < to; index++) {
            graphics.drawString(font, lines.get(index), left + 9, top + 20 + (index - from) * (font.lineHeight + 2),
                    0xfff3f3e9, false);
        }
    }

    private static void cut(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x + 2, y, x + width - 2, y + 1, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, color);
        graphics.fill(x, y + 2, x + width, y + height - 2, color);
        graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, color);
        graphics.fill(x + 2, y + height - 1, x + width - 2, y + height, color);
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }
}
