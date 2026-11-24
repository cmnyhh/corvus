package dev.corvus.client;

import dev.corvus.Corvus;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Draws the complete authored sprite; the UV rectangle always covers the whole image. */
final class GuiIcons {
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    private GuiIcons() {}

    static void draw(GuiGraphics g, String icon, int x, int y, int size) {
        ResourceLocation texture = TEXTURES.computeIfAbsent(icon,
                name -> new ResourceLocation(Corvus.ID, "textures/gui/" + name + ".png"));
        g.blit(texture, x, y, 0.0F, 0.0F, size, size, size, size);
    }

    /** Sprite name for a wheel mode label, shared with the radial menu. */
    static String mode(String value) {
        return switch (value) {
            case "passive", "hostile_only", "retaliate", "assist", "aggressive" -> "wheel_" + value;
            default -> "wheel_engage";
        };
    }
}
