package dev.dronepal;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class FollowAimGameTest {
    @GameTest(template="curios_test_room",timeoutTicks=100)
    public static void aimAtHoveringDrone(GameTestHelper test) {
        var p=GameTestPlayers.join(test);
        var at=Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(2,2,2)));
        p.teleportTo(at.x,at.y,at.z);
        p.setNoGravity(true);
        var slot=CuriosApi.getCuriosInventory(p).orElseThrow().getStacksHandler("drone").orElseThrow().getStacks();
        slot.insertItem(0,Dronepal.DRONE.toStack(),false);
        test.runAfterDelay(6,()->{
            var drone=(DroneEntity)test.getLevel().getEntity(DroneItem.serial(slot.getStackInSlot(0)));
            test.assertTrue(drone!=null && drone.isPickable(),"drone must be a pickable entity");
            Vec3 start=drone.position();
            p.setYRot(p.getYRot()+173);
            test.runAfterDelay(4,()->{
                test.assertTrue(drone.position().distanceTo(start)<.015,"standing head turn moved the drone");
                p.setShiftKeyDown(true);
                p.setPos(p.position().add(.25,0,0));
                test.runAfterDelay(4,()->{
                    test.assertTrue(drone.position().distanceTo(start)<.015,"sneaking displaced the aiming target");
                    Vec3 eye=p.getEyePosition(), aim=drone.getBoundingBox().getCenter().subtract(eye).normalize().scale(3);
                    var hit=ProjectileUtil.getEntityHitResult(p,eye,eye.add(aim),p.getBoundingBox().expandTowards(aim).inflate(1), e->e.isPickable()&&!e.isSpectator(),9);
                    test.assertTrue(hit!=null && hit.getEntity()==drone,"crosshair ray missed drone");
                    p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(drone,true,InteractionHand.MAIN_HAND));
                    test.assertTrue(p.containerMenu instanceof TerminalMenu,"entity interaction packet did not open terminal");
                    p.setShiftKeyDown(false);
                    p.setPos(p.position().add(0,0,.25));
                    test.runAfterDelay(4,()->{
                        test.assertTrue(p.containerMenu instanceof TerminalMenu && drone.position().distanceTo(start)<.015,"open terminal did not maintain station");
                        p.closeContainer();
                        p.setPos(p.position().add(0,0,1.25));
                        test.runAfterDelay(8,()->{
                            test.assertTrue(drone.position().distanceTo(start)>.25,"walking did not resume follow");
                            test.succeed();
                        });
                    });
                });
            });
        });
    }
}
