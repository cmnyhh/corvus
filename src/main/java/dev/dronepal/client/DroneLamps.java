package dev.dronepal.client;

import dev.dronepal.DroneEntity;
import dev.dronepal.Dronepal;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Immutable light snapshots for chunk meshing workers. Never touches the world's light engine. */
@EventBusSubscriber(modid=Dronepal.ID,value=Dist.CLIENT)
public final class DroneLamps {
    private record Lamp(BlockPos pos,int radius,int level) {}
    private static volatile Lamp[] sources=new Lamp[0];
    private static Map<Integer,Lamp> previous=Map.of();
    private static ClientLevel world;
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        if (mc.level!=world) { world=mc.level; previous=Map.of(); sources=new Lamp[0]; }
        if (world==null || world.getGameTime()%5!=0) return;
        Map<Integer,Lamp> next=new HashMap<>();
        for (var entity : world.entitiesForRendering()) if (entity instanceof DroneEntity d && d.lightProfile()!=0) {
            int profile=d.lightProfile(), brightness=profile&15;
            if ((profile&512)!=0 && (world.getGameTime()/5+d.getId())%11==0) brightness=Math.max(1,brightness-4);
            next.put(d.getId(),new Lamp(d.blockPosition(),(profile>>4)&31,brightness));
        }
        if (previous.equals(next)) return;
        sources=next.values().toArray(Lamp[]::new);
        for (var e : previous.entrySet()) if (!e.getValue().equals(next.get(e.getKey()))) dirty(mc,e.getValue());
        for (var e : next.entrySet()) if (!e.getValue().equals(previous.get(e.getKey()))) dirty(mc,e.getValue());
        previous=next;
    }
    private static void dirty(Minecraft mc,Lamp lamp) {
        BlockPos p=lamp.pos; int r=lamp.radius;
        mc.levelRenderer.setBlocksDirty(p.getX()-r,p.getY()-r,p.getZ()-r,p.getX()+r,p.getY()+r,p.getZ()+r);
    }
    public static int sample(BlockPos p) {
        int max=0;
        for (Lamp l : sources) {
            double distance=l.pos.distSqr(p);
            if (distance<l.radius*l.radius) max=Math.max(max,(int)Math.ceil(l.level*(1-Math.sqrt(distance)/l.radius)));
        }
        return max;
    }
}
