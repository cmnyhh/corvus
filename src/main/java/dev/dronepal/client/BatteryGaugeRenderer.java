package dev.dronepal.client;

import dev.dronepal.BatteryItem;
import dev.dronepal.Dronepal;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT)
public record BatteryGaugeRenderer(BatteryItem.Gauge gauge) implements ClientTooltipComponent {
    private static final int TRACK = 96;
    @SubscribeEvent static void register(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(BatteryItem.Gauge.class, BatteryGaugeRenderer::new);
    }
    @Override public int getHeight() { return 10; }
    @Override public int getWidth(Font font) { return TRACK + 4; }
    @Override public void renderImage(Font font, int x, int y, GuiGraphics g) {
        g.fill(x, y + 1, x + TRACK + 2, y + 8, 0xff93a4aa);
        g.fill(x + TRACK + 2, y + 3, x + TRACK + 4, y + 6, 0xff93a4aa);
        g.fill(x + 1, y + 2, x + TRACK + 1, y + 7, 0xff20292d);
        int width = gauge.pixels(TRACK);
        if (width > 0) g.fill(x + 1, y + 2, x + 1 + width, y + 7, 0xff000000 | gauge.color());
    }
}
