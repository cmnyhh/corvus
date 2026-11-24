package dev.corvus.client;

import dev.corvus.Corvus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * One-line scan read-out: how many marks are showing and how long they have left.
 *
 * <p>This is the "HUD readout + expiry" half of the scan feature: the numbers come straight from
 * {@link ScanDisplay#readout()}, which returns null once the snapshot expires, so the line disappears
 * by itself when the server's marks run out.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ScanHud {
    private static final int COLOR = 0xff7fe6dc;

    private ScanHud() {}

    @SubscribeEvent
    static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "scan_readout",
                (gui, graphics, partialTick, screenWidth, screenHeight) -> render(graphics));
    }

    private static void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || client.screen != null || client.player == null) return;
        int[] readout = ScanDisplay.readout();
        if (readout == null) return;
        String text = "扫描 " + readout[0] + " 矿 / " + readout[1] + " 敌对 · " + readout[2] + "s";
        var font = client.font;
        int width = font.width(text);
        int left = (graphics.guiWidth() - width) / 2;
        int top = graphics.guiHeight() - 70;
        graphics.fill(left - 4, top - 3, left + width + 4, top + font.lineHeight + 3, 0x88202a2e);
        graphics.drawString(font, text, left, top, COLOR, false);
    }
}
