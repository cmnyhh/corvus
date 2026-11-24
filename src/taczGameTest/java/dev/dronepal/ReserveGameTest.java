package dev.dronepal;

import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public class ReserveGameTest {
    private static ItemStack ammo(String id, int count) {
        return AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:" + id)).setCount(count).build();
    }
    @GameTest(template = "combat_test_room")
    public static void reserveCapacityAndSaveRoundTrip(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        for (var frame : Map.of("owl", 4, "peregrine", 6, "scrap", 6, "bedrock", 8).entrySet()) {
            var controller = Frames.create(frame.getKey());
            // Old saves contain a single occupied stack in the controller's vanilla container component.
            Hardware.save(controller, List.of(ammo("9mm", 2)));
            controller = ItemStack.parse(player.registryAccess(), controller.save(player.registryAccess())).orElseThrow();
            for (int i = 1; i < frame.getValue(); i++) {
                player.getInventory().setItem(0, ammo("9mm", i + 2));
                test.assertTrue(Hardware.installFromInventory(player, controller, 0).isEmpty(), "early reserve limit on " + frame.getKey());
            }
            player.getInventory().setItem(0, ammo("9mm", 12));
            test.assertTrue("full".equals(Hardware.installFromInventory(player, controller, 0)), "exceeded limit on " + frame.getKey());
            test.assertTrue(player.getInventory().getItem(0).getCount() == 12, "rejected stack lost");
            var restored = ItemStack.parse(player.registryAccess(), controller.save(player.registryAccess())).orElseThrow();
            var parts = Hardware.contents(restored);
            test.assertTrue(parts.size() == frame.getValue(), "slot count changed on save");
            for (int i = 0; i < parts.size(); i++) test.assertTrue(parts.get(i).getCount() == i + 2, "stack changed on save");
        }
        test.succeed();
    }

    @GameTest(template = "combat_test_room")
    public static void crossStackReloadAndPanelReopen(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var controller = Frames.create("bedrock");
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17")).build();
        Hardware.save(controller, List.of(gun, ammo("9mm", 2), ammo("762x39", 5), ammo("9mm", 4)));
        EquippedTestDrone.equip(test, player, controller, (equipped, drone) -> {
            var reserve = drone.getCapability(Capabilities.ItemHandler.ENTITY, null);
            test.assertTrue(reserve != null && reserve.getSlots() == 8, "TaCZ cannot see expected slots");
            var logic = (AbstractGunItem)gun.getItem();
            test.assertTrue(logic.findAndExtractInventoryAmmo(reserve, gun, 5) == 5, "TaCZ failed to extract across compatible stacks");
            for (int pass = 0; pass < 2; pass++) {
                DroneCombat.flush(drone);
                var menu = new TerminalMenu(pass + 1, player.getInventory(), drone, equipped);
                menu.broadcastChanges();
                DroneCombat.refresh(drone);
                var restored = ItemStack.parse(player.registryAccess(), equipped.save(player.registryAccess())).orElseThrow();
                var stacks = Hardware.contents(restored).stream().filter(CombatSlots::isAmmo).toList();
                test.assertTrue(stacks.size() == 2 && ItemStack.matches(stacks.get(0), ammo("762x39", 5))
                        && ItemStack.matches(stacks.get(1), ammo("9mm", 1)), "panel/snapshot lost or duplicated rounds");
            }
            test.assertTrue(logic.findAndExtractInventoryAmmo(reserve, gun, 99) == 1, "spent rounds returned after reopening");
            DroneCombat.flush(drone);
            var last = Hardware.contents(equipped).stream().filter(CombatSlots::isAmmo).toList();
            test.assertTrue(last.size() == 1 && ItemStack.matches(last.getFirst(), ammo("762x39", 5)), "incompatible reserve changed");
            test.succeed();
        });
    }
}
