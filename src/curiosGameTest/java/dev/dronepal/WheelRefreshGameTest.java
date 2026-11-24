package dev.dronepal;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class WheelRefreshGameTest {
    @GameTest(template="curios_test_room")
    public static void wheelReturnsCurrentStateAndDeduplicates(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        var controller = Frames.create("peregrine");
        CuriosTestSlots.set(owner, controller);
        DroneLifecycle.reconcile(owner, CuriosDrones.equipped(owner));
        var initial = DroneRemote.query(owner, new DroneRemote.Query(10,1));
        test.assertTrue(initial != null && initial.signal() && (initial.off() & 1)==0, "initial follow state");
        var cmd = new DroneRemote.Command(initial.serial(), DroneRemote.Action.FOLLOW,10,2);
        var changed = DroneRemote.command(owner, cmd);
        test.assertTrue(changed != null && changed.revision()==2 && (changed.off() & 1)!=0 && DroneRemote.disabled(controller,"follow"), "ack precedes authoritative update");
        test.assertTrue(DroneRemote.command(owner,cmd)==null && DroneRemote.disabled(controller,"follow"), "duplicate command toggled twice");
        test.assertTrue(DroneRemote.command(owner,new DroneRemote.Command(initial.serial(),DroneRemote.Action.FOLLOW,9,3))==null, "old screen accepted");
        DroneRemote.enable(controller,"follow");
        test.runAfterDelay(6, () -> {
            var latest = DroneRemote.query(owner, new DroneRemote.Query(10,3));
            test.assertTrue(latest!=null && latest.revision()==3 && (latest.off() & 1)==0, "poll missed external change");
            test.succeed();
        });
    }
}
