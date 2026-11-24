package dev.dronepal.client;

import dev.dronepal.Dronepal;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Draw the complete authored sprite; the UV rectangle always covers the whole image. */
final class GuiIcons {
    private static final Map<String,ResourceLocation> TEXTURES = new HashMap<>();

    static void draw(GuiGraphics g,String icon,int x,int y,int size) {
        var texture = TEXTURES.computeIfAbsent(icon, name -> ResourceLocation.fromNamespaceAndPath(Dronepal.ID,"textures/gui/"+name+".png"));
        g.blit(texture,x,y,0,0,size,size,size,size);
    }
    static String mode(String value) {
        return switch(value) {
            case "passive","hostile_only","retaliate","assist","aggressive" -> "wheel_"+value;
            default -> "wheel_engage";
        };
    }
    private GuiIcons() {}
}
