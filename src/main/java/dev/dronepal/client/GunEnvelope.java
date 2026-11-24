package dev.dronepal.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dronepal.DroneEntity;
import java.util.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** World units, measured along authored -Z forward. All samples are client-only. */
public final class GunEnvelope {
    public record Sample(float gunMinZ,float gunMaxZ,float bodyMinZ,float bodyMaxZ,float overhang,float maxPitch,int vertices) {}
    private static final Map<DroneEntity, Sample> published = new WeakHashMap<>();
    public static Sample last(DroneEntity drone) { return published.get(drone); }
    private final Map<List<DroneMesh.Bone>,float[]> bodies = new IdentityHashMap<>();
    private final Map<DroneEntity,Entry> entries = new WeakHashMap<>();
    private record Entry(ItemStack gun,List<DroneMesh.Bone> rig,int sampledAt,Sample sample) {}
    private static void measureBody(DroneMesh.Bone b,Matrix4f parent,float[] bounds) {
        var p=b.pivot(); var r=b.rotation();
        var m=new Matrix4f(parent).translate(p).rotateZYX(r.z,r.y,r.x).translate(-p.x,-p.y,-p.z);
        if (b.name().equals("gun_mount")) bounds[2]=m.transformPosition(new Vector3f(p)).z;
        if (b.name().equals("body")) for (var v:b.vertices()) {
            float z=m.transformPosition(new Vector3f(v.x(),v.y(),v.z())).z;
            bounds[0]=Math.min(bounds[0],z); bounds[1]=Math.max(bounds[1],z);
        }
        for(var child:b.children()) measureBody(child,m,bounds);
    }
    static Sample solve(float min,float max,float bodyMin,float bodyMax,int vertices) {
        // Each exposed end counts independently; unused clearance at one end cannot offset the other.
        float excess=Math.max(0,bodyMin-min)+Math.max(0,max-bodyMax);
        float span=bodyMax-bodyMin;
        float cap=vertices==0?65F:Math.max(25F,85F*span/(span+excess));
        return new Sample(min,max,bodyMin,bodyMax,excess,cap,vertices);
    }
    float pitch(DroneEntity drone,List<DroneMesh.Bone> rig,float requested) {
        ItemStack gun=drone.getMainHandItem();
        if(gun.isEmpty()) { entries.remove(drone); published.remove(drone); return requested; }
        Entry current=entries.get(drone);
        if(current==null || current.rig()!=rig || !ItemStack.isSameItemSameComponents(gun,current.gun())) {
            float[] b=bodies.computeIfAbsent(rig,roots->{
                float[] v={Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,0};
                for(var root:roots) measureBody(root,new Matrix4f(),v);
                if(v[1]-v[0]<0.05F) { v[0]=-.29F; v[1]=.29F; }
                return v;
            });
            float front=b[0]-b[2],back=b[1]-b[2];
            current=new Entry(gun.copy(),rig,-200,solve(front,back,front,back,0));
            entries.put(drone,current); published.put(drone,current.sample());
        }
        return requested*current.sample().maxPitch()/85F;
    }
    Capture begin(DroneEntity drone,Matrix4f mount) {
        Entry e=entries.get(drone);
        if(e==null || e.sample().vertices()>0 || drone.tickCount-e.sampledAt()<40) return null;
        return new Capture(drone,e,new Matrix4f(mount).invert());
    }
    final class Capture {
        final DroneEntity drone; final Entry entry; final Matrix4f inverse;
        int vertices; float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        Capture(DroneEntity d,Entry e,Matrix4f i){drone=d;entry=e;inverse=i;}
        MultiBufferSource wrap(MultiBufferSource original) {
            return type -> new VertexConsumerWrapper(original.getBuffer(type)) {
                @Override public VertexConsumer addVertex(float x,float y,float z) {
                    float local=inverse.m02()*x+inverse.m12()*y+inverse.m22()*z+inverse.m32();
                    if(Float.isFinite(local)){min=Math.min(min,local);max=Math.max(max,local);vertices++;}
                    return super.addVertex(x,y,z);
                }
            };
        }
        void draw(Runnable render) {
            if (net.neoforged.fml.ModList.get().isLoaded("tacz") && Immediate.AVAILABLE && dev.dronepal.CombatSlots.isGun(drone.getMainHandItem())) {
                // The external VBO backend bypasses MultiBufferSource. This public context selects
                // the same pack's immediate third-person mesh just for the measured draw.
                com.tacz.guns.client.renderer.item.DisplayItemRenderContext.render(render);
            } else render.run();
            finish();
        }
        void finish() {
            Sample prev=entry.sample();
            Sample s=vertices>=4 && max-min>.05F && max-min<12F ? solve(min,max,prev.bodyMinZ(),prev.bodyMaxZ(),vertices) : prev;
            entries.put(drone,new Entry(entry.gun(),entry.rig(),drone.tickCount,s)); published.put(drone,s);
        }
    }
    private static final class Immediate {
        static final boolean AVAILABLE=available();
        private static boolean available() {
            try {
                Class.forName("com.tacz.guns.client.renderer.item.DisplayItemRenderContext", false, GunEnvelope.class.getClassLoader())
                        .getMethod("render",Runnable.class);
                return true;
            } catch (ReflectiveOperationException | LinkageError ignored) { return false; }
        }
    }
}
