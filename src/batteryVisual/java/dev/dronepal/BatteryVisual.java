package dev.dronepal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;

@EventBusSubscriber(modid="huhu_dronepal", value=Dist.CLIENT)
public final class BatteryVisual {
    private static boolean started;
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (started || mc.level == null || mc.player == null || mc.getOverlay() != null || mc.level.getGameTime() < 10) return;
        started = true;
        mc.options.guiScale().set(2);
        mc.resizeDisplay();
        mc.setScreen(new Sheet(mc));
    }
    private static final class Sheet extends Screen {
        private final ItemStack[] cells = new ItemStack[4];
        private int frames;
        Sheet(Minecraft mc) {
            super(Component.literal("Battery hover check"));
            for (int i=0;i<4;i++) cells[i]=Hardware.BATTERIES.getFirst().toStack();
            int capacity=Hardware.capacity(cells[0]);
            Hardware.setEnergy(cells[0],0);
            Hardware.setEnergy(cells[1],capacity/2);
            Hardware.setEnergy(cells[3],capacity/4);
            var saved=(CompoundTag)cells[3].save(mc.level.registryAccess());
            saved.putString("id","huhu_dronepal:battery_mk1");
            cells[3]=ItemStack.parseOptional(mc.level.registryAccess(),saved);
        }
        @Override public void render(GuiGraphics g,int mx,int my,float delta) {
            g.fill(0,0,width,height,0xff292c30);
            String[] labels={"Empty","Half","Full","Legacy Mk1"};
            for(int i=0;i<cells.length;i++) {
                int x=15+(i%2)*width/2,y=20+(i/2)*height/2;
                g.drawString(font,labels[i],x,y,0xffdddddd,false);
                g.renderItem(cells[i],x,y+24);
                g.renderItemDecorations(font,cells[i],x,y+24);
                g.renderTooltip(font,cells[i],x+24,y+38);
            }
            if (++frames==12) {
                g.flush();
                try(var image=Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    Path path=Path.of(System.getProperty("dronepal.battery.screenshot"));
                    Files.createDirectories(path.getParent()); image.writeToFile(path);
                } catch(java.io.IOException e) { throw new IllegalStateException(e); }
                minecraft.execute(minecraft::stop);
            }
        }
    }
}
