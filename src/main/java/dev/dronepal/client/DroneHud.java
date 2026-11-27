package dev.dronepal.client;

import dev.dronepal.DroneTelemetry;
import dev.dronepal.Dronepal;
import dev.dronepal.Frames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid=Dronepal.ID, value=Dist.CLIENT)
public final class DroneHud {
    private static DroneTelemetry sample;
    private static long received, changedUntil;
    private static Component change;
    public static void receive(DroneTelemetry next) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(next.dimension())) return;
        long now = mc.level.getGameTime();
        if (sample != null && sample.serial().equals(next.serial()) && now - received < 45) {
            int changed = (sample.fitted() ^ next.fitted()) | (sample.online() ^ next.online());
            if (changed != 0) {
                int index = Integer.numberOfTrailingZeros(changed), bit = 1 << index;
                if (index < DroneTelemetry.FAMILIES.size()) {
                    var family = Component.translatable("dronepal.module." + DroneTelemetry.FAMILIES.get(index));
                    String key = (next.fitted() & bit) == 0 ? "removed" : (next.online() & bit) != 0 ? "online" : "offline";
                    change = text(key, family);
                    changedUntil = now + 60;
                }
            }
        } else change = null;
        received = now;
        sample = next;
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { sample = null; change = null; }
    @SubscribeEvent static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "drone_status"), (g,t) -> draw(g));
    }
    private static Component text(String key, Object... args) { return Component.translatable("hud.dronepal." + key, args); }
    private static void draw(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (sample == null || mc.level == null || mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()
                || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || mc.screen != null || !dev.dronepal.HudOptions.ENABLED.get()) return;
        long now = mc.level.getGameTime();
        if (now-received > 44 || now < received || !mc.level.dimension().location().toString().equals(sample.dimension())) return;
        boolean low = DroneTelemetry.low(sample.energy(), sample.capacity());
        int color = low ? 0xffff8079 : 0xffa4ddbe;
        var status = text(sample.holding() ? "holding" : "following");
        var header = Component.empty().append(Frames.name(sample.frame())).append(" · ").append(status);
        var battery = sample.capacity() == 0 ? text("no_battery") : text(low ? "low" : "charge", 100L*sample.energy()/sample.capacity());
        var rate = text("rate", DroneTelemetry.rate(sample.consumed()));
        // Five grid states, so "in coverage but not charging" has a name instead of showing a plain
        // "on grid": off grid / on grid / on grid · charging +N FE/s / on grid · full / on grid · queued.
        var grid = !sample.gridOnline() ? text("grid_offline")
                : sample.capacity() > 0 && sample.energy() >= sample.capacity() ? text("grid_full")
                : sample.gridRate() > 0 ? text("grid_charging", sample.gridRate())
                : sample.gridQueued() ? text("grid_queued")
                : text("grid_online");
        // Net drain and endurance: what the cell loses per second right now, and how long that lasts.
        // consumed already excludes what the grid paid and the panels made, so this is the real loss.
        int net = Math.max(0, sample.consumed() - sample.generated() - sample.gridRate());
        boolean charging = sample.gridRate() > 0;
        var endurance = text("remaining", dev.corvus.core.HardwareRules.remainingHhMm(
                sample.energy(), sample.capacity(), net / 20.0D, charging));
        var power = Component.empty().append(battery).append("   ").append(rate).append("   ")
                .append(text("net", net)).append(" · ").append(endurance).append("   ").append(grid);
        var modes = text("modules", Integer.bitCount(sample.online()), Integer.bitCount(sample.fitted()));
        var summary = Component.empty().append(modes);
        if ((sample.fitted() & 1) != 0) summary.append(" · ").append(Component.translatable("dronepal.engage." + sample.mode()));
        boolean updated = change != null && now < changedUntil;
        var font = mc.font;
        int width = Math.max(font.width(header), Math.max(font.width(power), font.width(summary)));
        if (updated) width = Math.max(width, font.width(change));
        int x=6, y=6;
        g.fill(x,y,x+width+23,y+(updated ? 51 : 41),0xb51c2024);
        GuiIcons.draw(g,sample.holding()?"wheel_hold":"wheel_follow",x+3,y+2,11);
        GuiIcons.draw(g,"wheel_energy",x+3,y+14,11);
        GuiIcons.draw(g,(sample.fitted() & 1) != 0 ? GuiIcons.mode(sample.mode()) : "slot_module",x+3,y+29,11);
        g.drawString(font,header,x+18,y+3,0xfff2f4f6,false);
        g.drawString(font,power,x+18,y+15,color,false);
        g.fill(x+18,y+25,x+18+width,y+27,0xff57616a);
        if (sample.capacity()>0) g.fill(x+18,y+25,x+18+(int)(width*(long)sample.energy()/sample.capacity()),y+27,color);
        g.drawString(font,summary,x+18,y+30,0xffe0e4e8,false);
        if (updated) g.drawString(font,change,x+18,y+40,0xffffdc91,false);
    }
    private DroneHud() {}
}
