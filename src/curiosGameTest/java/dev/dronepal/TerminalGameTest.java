package dev.dronepal;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class TerminalGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void barksSwitchIsOwnerOnly(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        BlockPos here = test.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(here.getX()+.5, here.getY(), here.getZ()+.5);
        var equipped = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow().getStacks();
        equipped.insertItem(0, new ItemStack(Dronepal.DRONE.get()), false);
        // Joined up front for the same reason as below: never join a player from inside a callback.
        var guest = GameTestPlayers.join(test);
        test.runAfterDelay(5, () -> {
            ItemStack controller = equipped.getStackInSlot(0);
            var drone = (DroneEntity)test.getLevel().getEntity(DroneItem.serial(controller));
            test.assertTrue(drone != null, "no owned drone");
            player.setShiftKeyDown(true);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, drone));
            player.setShiftKeyDown(false);
            test.assertTrue(player.containerMenu instanceof TerminalMenu, "terminal did not open");
            TerminalMenu menu = (TerminalMenu)player.containerMenu;
            test.assertTrue(menu.barksOn(), "barks must default to on");
            test.assertTrue(menu.clickMenuButton(player, TerminalMenu.BUTTON_BARKS), "the bark switch refused the owner");
            test.assertTrue(!menu.barksOn() && DroneRemote.disabled(controller, "barks"), "the bark switch did not reach the terminal");
            // A personality switch must not double as a module switch.
            test.assertTrue(!DroneRemote.disabled(controller, "link") && !DroneRemote.disabled(controller, "follow")
                    && !DroneRemote.disabled(controller, "carry"), "the bark switch touched a module key");
            var reloaded = ItemStack.parse(player.registryAccess(), controller.save(player.registryAccess())).orElseThrow();
            test.assertTrue(DroneRemote.disabled(reloaded, "barks"), "the bark switch did not survive serialization");
            guest.teleportTo(player.getX(), player.getY(), player.getZ());
            test.assertTrue(!menu.clickMenuButton(guest, TerminalMenu.BUTTON_BARKS), "another player toggled the switch");
            test.assertTrue(DroneRemote.disabled(controller, "barks"), "a foreign toggle changed the terminal");
            player.teleportTo(here.getX()+15, here.getY(), here.getZ());
            test.assertTrue(!menu.clickMenuButton(player, TerminalMenu.BUTTON_BARKS), "a remote toggle was accepted");
            player.teleportTo(here.getX()+.5, here.getY(), here.getZ()+.5);
            test.assertTrue(menu.clickMenuButton(player, TerminalMenu.BUTTON_BARKS) && menu.barksOn(), "the owner could not switch barks back on");
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 100)
    public static void configureOwnedDrone(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        BlockPos here = test.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(here.getX()+.5, here.getY(), here.getZ()+.5);
        var equipped = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow().getStacks();
        equipped.insertItem(0, new ItemStack(Dronepal.DRONE.get()), false);
        // Joined up front: joining registers one callback per remaining tick, which must not happen
        // from inside a delayed callback (vanilla iterates that map and a rehash breaks it).
        var guest = GameTestPlayers.join(test);
        test.runAfterDelay(5, () -> {
            ItemStack controller = equipped.getStackInSlot(0);
            UUID id = DroneItem.serial(controller);
            DroneEntity drone = (DroneEntity)test.getLevel().getEntity(id);
            test.assertTrue(drone != null, "no owned drone");
            player.setShiftKeyDown(true);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, drone));
            test.assertTrue(player.containerMenu instanceof TerminalMenu, "empty-hand crouch did not open terminal");
            TerminalMenu menu = (TerminalMenu)player.containerMenu;
            var inv = player.getInventory();
            inv.setItem(9, Hardware.BATTERIES.get(2).toStack());
            int expectedFE = Hardware.capacity(inv.getItem(9));
            menu.clicked(TerminalMenu.firstInventorySlot(), 0, ClickType.PICKUP, player);
            test.assertTrue(menu.getCarried().is(Hardware.BATTERIES.get(2).get()) && Hardware.contents(controller).isEmpty(), "pickup must use real cursor");
            menu.clicked(1, 0, ClickType.PICKUP, player);
            test.assertTrue(menu.getSlot(1).getItem().isEmpty() && !menu.getCarried().isEmpty(), "illegal module target accepted battery");
            menu.clicked(4, 0, ClickType.PICKUP, player);
            test.assertTrue(inv.getItem(9).isEmpty() && menu.energy() == expectedFE && menu.capacity() == expectedFE && expectedFE > 65535, "battery transfer or full FE range failed");
            inv.setItem(1, new ItemStack(Items.STONE));
            menu.clicked(4, 1, ClickType.SWAP, player);
            test.assertTrue(Hardware.contents(controller).size() == 1 && menu.getCarried().isEmpty(), "swap moved ghost battery");
            for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.STONE, 64));
            menu.clicked(4, 0, ClickType.QUICK_MOVE, player);
            test.assertTrue(Hardware.contents(controller).size() == 1 && menu.feedback().equals("inventory_full"), "full inventory lost the battery");
            inv.setItem(9, ItemStack.EMPTY);
            player.teleportTo(here.getX()+15, here.getY(), here.getZ());
            menu.clicked(4, 0, ClickType.PICKUP, player);
            test.assertTrue(!menu.stillValid(player) && Hardware.contents(controller).size() == 1 && inv.getItem(9).isEmpty(), "remote interaction changed the terminal");
            player.teleportTo(here.getX()+.5, here.getY(), here.getZ()+.5);
            guest.teleportTo(player.getX(),player.getY(),player.getZ());
            guest.setShiftKeyDown(true);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(guest, InteractionHand.MAIN_HAND, drone));
            test.assertTrue(!(guest.containerMenu instanceof TerminalMenu), "another player opened the terminal");
            menu.clicked(4, 0, ClickType.PICKUP, player);
            test.assertTrue(menu.getCarried().is(Hardware.BATTERIES.get(2).get()), "battery not on cursor");
            player.closeContainer();
            test.assertTrue(Hardware.contents(controller).isEmpty() && inv.getItem(9).is(Hardware.BATTERIES.get(2).get()), "battery removal did not conserve one cell");
            test.succeed();
        });
    }
}
