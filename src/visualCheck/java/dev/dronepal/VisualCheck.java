package dev.dronepal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;

@EventBusSubscriber(modid="huhu_dronepal", value=Dist.CLIENT)
public final class VisualCheck {
    private static final boolean TACZ = net.neoforged.fml.ModList.get().isLoaded("tacz");
    private static boolean started;
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (!started && mc.level != null && mc.player != null && mc.getOverlay() == null && mc.level.getGameTime()>10) {
            started=true;
            mc.setScreen(new Sheet(mc));
        }
    }
    private static final class OptionalGun {
        static net.minecraft.world.item.ItemStack create(int col) {
            String id=java.util.List.of("m4a1","glock_17","rpg7","glock_17").get(col);
            return com.tacz.guns.api.item.builder.GunItemBuilder.create().setId(net.minecraft.resources.ResourceLocation.parse("tacz:"+id)).build();
        }
    }
    static final class Sheet extends Screen {
        private final DroneEntity[] drones = new DroneEntity[TACZ ? 12 : 8];
        private int rendered;
        private static final String[] TRACK_LABEL = {"front", "right", "left", "rear", "high", "low", "moving A", "moving B"};
        private static final float[] BODY = {0,0,0,105,0,0,12,24};
        private static final float[] SWIVEL = {0,65,-65,75,0,0,24,36};
        private static final float[] ELEVATION = {0,0,0,0,-32,36,-8,-15};
        private void trackingSheet(GuiGraphics g) {
            for (int i=0;i<8;i++) {
                var drone=drones[i];
                if (rendered == 8) {
                    drone.setFrame("peregrine");
                    drone.setPaint("standard");
                    drone.setItemSlot(EquipmentSlot.MAINHAND, OptionalGun.create(0));
                }
                float progress = i >= 6 ? Math.clamp((rendered - 8) / 5F, 0F, 1F) : 1F;
                float yaw=SWIVEL[i] * progress, pitch=ELEVATION[i] * progress;
                drone.getEntityData().set(DroneEntity.MOUNT_PITCH,(byte)0);
                drone.getEntityData().set(DroneEntity.AIM_YAW,yaw);
                drone.getEntityData().set(DroneEntity.AIM_PITCH,pitch);
                drone.tickCount=rendered;
                int w=width/4,h=height/2,x=i%4*w,y=i/4*h;
                g.fill(x+1,y+1,x+w-1,y+h-1,0xff3c4249);
                g.drawString(font,TRACK_LABEL[i],x+4,y+5,0xffeeeeee,false);
                g.drawString(font,(int)BODY[i]+" / "+(int)yaw+" / "+(int)pitch,x+4,y+18,0xffcedce8,false);
                var q=new org.joml.Quaternionf().rotationZ((float)Math.PI).rotateX(-.48f).rotateY(1.2f-(float)Math.toRadians(BODY[i]));
                InventoryScreen.renderEntityInInventory(g,x+w*.5f,y+h*.59f,Math.min(w*.32f,h*.48f),new org.joml.Vector3f(),q,null,drone);
            }
        }
        Sheet(Minecraft mc) {
            super(Component.literal("Dronepal visual check"));
            for(int i=0;i<drones.length;i++) {
                String id=Frames.IDS.get(i%4);
                var drone=new DroneEntity(Dronepal.ENTITY.get(),mc.level);
                drone.setFrame(id); drone.setPaint(Frames.byId(id).defaultPaint());
                if(TACZ || i>=4) drone.setItemSlot(EquipmentSlot.MAINHAND, TACZ ? OptionalGun.create(i%4) : Items.SPYGLASS.getDefaultInstance());
                if(TACZ) drone.getEntityData().set(DroneEntity.MOUNT_PITCH,(byte)(i/4==0 ? 0 : i/4==1 ? 42 : 85));
                drones[i]=drone;
            }
        }
        @Override public void render(GuiGraphics g,int mx,int my,float delta) {
            g.fill(0,0,width,height,0xff282c31);
            if (!(TACZ && rendered >= 8)) for(int i=0;i<drones.length;i++) {
                int col=i%4,row=i/4,w=width/4,h=height/(TACZ?3:2),x=col*w,y=row*h;
                g.fill(x+1,y+1,x+w-1,y+h-1,0xff3c4249);
                String caption = Frames.IDS.get(col);
                if (TACZ) {
                    var sample = dev.dronepal.client.GunEnvelope.last(drones[i]);
                    float requested = DroneCombat.mountPitch(drones[i]);
                    float actual = sample == null ? requested : sample.maxPitch() * requested / 85F;
                    caption += " " + Math.round(requested) + "/" + Math.round(actual);
                    if (rendered == 7) {
                        System.out.println("DRONEPAL_PROFILE " + caption + " " + sample);
                        if (sample == null || sample.vertices() <= 0) throw new AssertionError("Missing TaCZ mesh: " + caption);
                        if (row > 0) {
                            var base = dev.dronepal.client.GunEnvelope.last(drones[col]);
                            if (Math.abs(sample.gunMinZ()-base.gunMinZ()) > 0.002F || Math.abs(sample.gunMaxZ()-base.gunMaxZ()) > 0.002F)
                                throw new AssertionError("Model bounds changed with pose: " + caption);
                        }
                    }
                } else if (row > 0) caption += " + mount";
                g.drawString(font,caption,x+5,y+5,0xffeeeeee,false);
                var q=new org.joml.Quaternionf().rotationZ((float)Math.PI).rotateX(TACZ?0:row==0?-.26f:.24f).rotateY(TACZ?(float)Math.PI/2:.52f);
                InventoryScreen.renderEntityInInventory(g,x+w*.5f,y+h*.59f,Math.min(w*(TACZ?.30f:.44f),h*.48f),new org.joml.Vector3f(),q,null,drones[i]);
            }
            if (TACZ && rendered >= 8) trackingSheet(g);
            g.flush();
            ++rendered;
            if (rendered == 8 || (TACZ && rendered == 16)) {
                try(var image=Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    Path dir=Path.of(System.getProperty("dronepal.visual.dir")); Files.createDirectories(dir);
                    image.writeToFile(dir.resolve(rendered == 16 ? "gimbal-tracking-client.png" : TACZ ? "mount-grip-side-profile.png" : "four-frames-in-client.png"));
                    System.out.println("DRONEPAL_VISUAL_CAPTURE " + dir);
                } catch(Exception e) { throw new IllegalStateException(e); }
                if (!TACZ || rendered == 16) minecraft.execute(minecraft::stop);
            }
        }
    }
}
