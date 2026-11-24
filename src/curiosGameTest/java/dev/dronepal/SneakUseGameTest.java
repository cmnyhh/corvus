package dev.dronepal;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Sneak-use must open the panel even when another mod consumes or bypasses the use event. */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class SneakUseGameTest {
    @GameTest(template = "curios_test_room")
    public static void sneakUseOpensPanelAndCancels(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var controller = Frames.create("peregrine");
        CuriosTestSlots.set(player, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = (DroneEntity) player.serverLevel().getEntity(DroneItem.serial(controller));
        test.assertTrue(drone != null, "no drone");
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var event = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, drone);
        NeoForge.EVENT_BUS.post(event);
        test.assertTrue(event.isCanceled(), "sneak-use was not cancelled");
        test.assertTrue(player.containerMenu instanceof TerminalMenu, "sneak-use did not open the panel");
        player.closeContainer();
        // A mod that calls the entity directly (no event) must still reach the same panel.
        var result = drone.mobInteract(player, InteractionHand.MAIN_HAND);
        test.assertTrue(result == net.minecraft.world.InteractionResult.SUCCESS
                && player.containerMenu instanceof TerminalMenu, "direct entity interaction bypassed the panel");
        test.succeed();
    }

    @GameTest(template = "curios_test_room")
    public static void strangerCannotOpen(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        var stranger = GameTestPlayers.join(test);
        var controller = Frames.create("peregrine");
        CuriosTestSlots.set(owner, controller);
        DroneLifecycle.reconcile(owner, controller);
        var drone = (DroneEntity) owner.serverLevel().getEntity(DroneItem.serial(controller));
        test.assertTrue(drone != null, "no drone");
        stranger.setShiftKeyDown(true);
        stranger.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        test.assertTrue(!TerminalMenus.openTerminal(stranger, drone), "stranger opened the panel");
        test.assertTrue(!(stranger.containerMenu instanceof TerminalMenu), "stranger got a panel");
        test.succeed();
    }
}
