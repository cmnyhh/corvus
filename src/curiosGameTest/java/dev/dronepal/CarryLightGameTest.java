package dev.dronepal;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal") @PrefixGameTestTemplate(false)
public final class CarryLightGameTest {
    @GameTest(template="curios_test_room",timeoutTicks=65)
    public static void factoryCargoNine(GameTestHelper t) { lifecycle(t,"carry_mk1",9,"light_mk1",(8<<4)|12); }
    @GameTest(template="curios_test_room",timeoutTicks=65)
    public static void rustCargoTwelve(GameTestHelper t) { lifecycle(t,"carry_rust_g1",12,"light_rust_g1",512|(9<<4)|14); }
    static void lifecycle(GameTestHelper t,String product,int capacity,String lamp,int expected) {
        var p=GameTestPlayers.join(t);
        var pos=t.absolutePos(new BlockPos(2,1,2));
        p.teleportTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        var hold=Dronepal.DRONE.toStack();
        CuriosTestSlots.set(p, hold);
        Hardware.save(hold,List.of(Hardware.moduleStack(product),Hardware.moduleStack(lamp),Hardware.BATTERIES.getFirst().toStack()));
        var serial=DroneItem.ensureSerial(hold);
        DroneLifecycle.reconcile(p, hold);
        t.assertTrue(Hardware.controller(p) == hold, "initial slot deployment failed");
        var drone=(DroneEntity)t.getLevel().getEntity(serial);
        List<ItemEntity> drops=new ArrayList<>();
        // Ordered pickups fix the identity of slots 10-12, independently of entity iteration order.
        for(int i=1;i<=capacity+1;i++) {
            final int id=i;
            var stack=new ItemStack(Items.PAPER);
            CustomData.update(DataComponents.CUSTOM_DATA,stack,tag -> tag.putInt("ticket",id));
            var drop=new ItemEntity(t.getLevel(),drone.getX(),drone.getY(),drone.getZ(),stack);
            drop.setTarget(p.getUUID()); drop.setNoPickUpDelay();
            t.getLevel().addFreshEntity(drop); drops.add(drop);
            CarryLight.second(drone,p,hold);
            t.assertTrue(drop.isRemoved()==(id<=capacity),"capacity boundary at ticket "+id);
        }
        t.assertTrue(drone.lightProfile()==expected,"incorrect powered lamp profile");
        drops.getLast().discard();
        CuriosTestSlots.set(p, ItemStack.EMPTY);
        DroneLifecycle.reconcile(p, ItemStack.EMPTY);
        t.assertTrue(Hardware.controller(p).isEmpty(), "unequipping did not recall");
        var restored=ItemStack.parse(p.registryAccess(),hold.save(p.registryAccess())).orElseThrow();
        CuriosTestSlots.set(p, restored);
        t.runAfterDelay(2,() -> {
            t.assertTrue(Hardware.controller(p) == restored, "reequipping did not redeploy");
            // Disable automatic pickup: this must not prevent manual unloading.
            CustomData.update(DataComponents.CUSTOM_DATA,restored,tag -> tag.putBoolean("dronepal_off_carry",true));
            t.assertTrue(Hardware.execute(p,"order.fetch"),"owner cannot unload after redeploy");
            int[] receipts=new int[capacity+2];
            for(var stack:p.getInventory().items) if(stack.is(Items.PAPER)) {
                int id=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getInt("ticket");
                if(id>0 && id<receipts.length) receipts[id]+=stack.getCount();
            }
            for(int i=1;i<=capacity;i++) t.assertTrue(receipts[i]==1,"missing/duplicate ticket "+i);
            t.assertTrue(receipts[capacity+1]==0 && !Hardware.execute(p,"order.fetch"),"overflow or second delivery created items");
            var current=(DroneEntity)t.getLevel().getEntity(serial);
            CustomData.update(DataComponents.CUSTOM_DATA,restored,tag -> tag.putBoolean("dronepal_off_light",true));
            CarryLight.second(current,p,restored);
            t.assertTrue(current.lightProfile()==0,"disabled lamp still active");
            t.succeed();
        });
    }
}
