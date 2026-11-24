package dev.dronepal.client;

import dev.dronepal.Dronepal;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid=Dronepal.ID,value=Dist.CLIENT)
public final class CommsSubtitles {
    private record Message(Component speaker, int accent, Component body) {}
    private static final ArrayDeque<Message> queue = new ArrayDeque<>();
    private static Message current;
    private static ClientLevel level;
    private static long start;
    private static int bodyWidth, page;
    private static List<FormattedCharSequence> lines = List.of();

    public static void accept(Component speaker, String persona, Component text) {
        var client = Minecraft.getInstance();
        if (client.level == null || text.getString().isBlank()) return;
        if (level != client.level) clear();
        level = client.level;
        int accent = switch (persona) {
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
        start = level.getGameTime();
        bodyWidth = 0;
        page = 0;
    }
    private static void clear() { queue.clear(); current=null; level=null; }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut e) { clear(); }

    @SubscribeEvent static void register(RegisterGuiLayersEvent e) {
        e.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(Dronepal.ID,"comms_subtitle"), (g,time) -> render(g));
    }
    private static void render(GuiGraphics g) {
        if (current == null) return;
        Minecraft client = Minecraft.getInstance();
        if (level != client.level) { clear(); return; }
        var font = client.font;
        int width = Math.clamp(g.guiWidth()*2/3,150,280);
        if (bodyWidth != width) { bodyWidth=width; lines=font.split(current.body,width-18); }
        int pages = Math.max(1, Math.ceilDiv(lines.size(),4));
        page = Math.min(page,pages-1);
        int from=page*4, to=Math.min(from+4,lines.size());
        int ticks=Math.clamp(45+(to-from)*24,80,150);
        if (level.getGameTime()-start >= ticks) {
            if (++page >= pages) next(); else start=level.getGameTime();
            return;
        }
        if (client.screen != null || client.options.hideGui) return;
        int content=font.width(current.speaker)+32;
        for(int i=from;i<to;i++) content=Math.max(content,font.width(lines.get(i))+18);
        width=Math.clamp(content,88,width);
        int height=22+(to-from)*(font.lineHeight+2);
        int left=(g.guiWidth()-width)/2;
        int top=Math.max(4,g.guiHeight()-83-height);
        cut(g,left+1,top+2,width,height,0x5520262a);
        cut(g,left,top,width,height,0xff53646a);
        cut(g,left+1,top+1,width-2,height-2,0xf3232e35);
        g.fill(left+3,top+4,left+5,top+14,current.accent);
        String counter=pages>1?(page+1)+"/"+pages:"";
        int labelWidth=width-17-font.width(counter);
        g.drawString(font,font.plainSubstrByWidth(current.speaker.getString(),labelWidth),left+9,top+4,current.accent,false);
        if(!counter.isEmpty()) g.drawString(font,counter,left+width-6-font.width(counter),top+4,0xffb5c4c9,false);
        g.fill(left+8,top+16,left+width-7,top+17,0xff405057);
        for(int i=from;i<to;i++) g.drawString(font,lines.get(i),left+9,top+20+(i-from)*(font.lineHeight+2),0xfff3f3e9,false);
    }
    private static void cut(GuiGraphics g,int x,int y,int width,int height,int color) {
        g.fill(x+2,y,x+width-2,y+1,color);
        g.fill(x+1,y+1,x+width-1,y+2,color);
        g.fill(x,y+2,x+width,y+height-2,color);
        g.fill(x+1,y+height-2,x+width-1,y+height-1,color);
        g.fill(x+2,y+height-1,x+width-2,y+height,color);
    }
    private CommsSubtitles() {}
}
