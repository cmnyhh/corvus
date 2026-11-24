package dev.dronepal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class HardwareGameTest {
    static ItemStack module(String family, int tier) {
        ItemStack result = Hardware.MODULE.toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, result, tag -> {
            tag.putString("module_family", family);
            tag.putInt("module_tier", tier);
        });
        return result;
    }
    @GameTest(template = "curios_test_room")
    public static void moduleProducts(GameTestHelper test) {
        var products = new java.util.HashSet<net.minecraft.resources.ResourceLocation>();
        for (String family : java.util.List.of("combat", "scan", "medic", "carry", "solar", "light", "link")) {
            for (int tier = 1; tier <= 3; tier++) {
                ItemStack original = Hardware.moduleStack(family + "_mk" + tier);
                var definition = Hardware.definition(original);
                test.assertTrue(definition != null && products.add(definition.id()), "duplicate or missing original " + family);
                test.assertTrue(definition == Hardware.definition(module(family, tier)), "legacy item did not resolve to original");
            }
            if (!family.equals("link")) {
                ItemStack original = module(family, 1), rust = Hardware.moduleStack(family + "_rust_g1");
                var def = Hardware.definition(rust);
                test.assertTrue(def != null && products.add(def.id()) && def.vendor().equals("rust"), "missing Rustnail product " + family);
                test.assertTrue(def.wheelLabel().equals(Hardware.definition(original).wheelLabel()), "missing inherited wheel action");
                test.assertTrue(!rust.getHoverName().equals(original.getHoverName()), "product labels collided");
                test.assertTrue(Hardware.installError(rust, java.util.List.of(original)).equals("duplicate"), "allowed two vendors of one family");
                var saved = rust.save(test.getLevel().registryAccess());
                var restored = ItemStack.parseOptional(test.getLevel().registryAccess(), (net.minecraft.nbt.CompoundTag) saved);
                test.assertTrue(Hardware.definition(restored) == def, "save/load lost Rustnail identity");
            }
        }
        test.assertTrue(products.size() == 27, "expected 27 unique module products");
        test.assertTrue(Hardware.definition(module("carry", 1)).draw(20) == 85, "OmniDrive economy trait missing");
        test.assertTrue(Hardware.definition(module("scan", 1)).trait("scan_radius_multiplier", 1) == 1.2, "Corvus range trait missing");
        test.assertTrue(Hardware.definition(module("carry", 1)).integer("slots") == 9 && Hardware.definition(Hardware.moduleStack("carry_rust_g1")).integer("slots") == 12, "cargo capacities collapsed");
        ItemStack forged = module("scan", 1);
        CustomData.update(DataComponents.CUSTOM_DATA, forged, tag -> tag.putString("module_product", "huhu_dronepal:unknown_rust"));
        test.assertTrue(Hardware.installError(forged, java.util.List.of()).equals("invalid"), "unknown explicit product fell back to legacy family");
        test.succeed();
    }
    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void loadoutAndPower(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var controller = Dronepal.DRONE.toStack();
        var inventory = player.getInventory();
        CuriosTestSlots.set(player, controller);
        inventory.setItem(1, Hardware.BATTERIES.getFirst().toStack());
        inventory.setItem(2, module("scan", 1));
        inventory.setItem(3, module("medic", 1));
        inventory.setItem(4, module("scan", 2));
        inventory.setItem(5, module("light", 3));
        inventory.setItem(6, module("carry", 2));
        for (int slot : new int[]{1, 2, 3, 5}) test.assertTrue(Hardware.installFromInventory(player, controller, slot).isEmpty(), "install failed for slot " + slot);
        test.assertTrue(Hardware.installFromInventory(player, controller, 4).equals("duplicate"), "accepted a duplicate family");
        test.assertTrue(Hardware.installFromInventory(player, controller, 6).equals("full"), "accepted a fourth module");
        test.assertTrue(inventory.getItem(4).getCount() == 1 && inventory.getItem(6).getCount() == 1, "rejected installation lost an item");
        player.setHealth(10);
        DroneLifecycle.reconcile(player, controller);
        test.assertTrue(Hardware.controller(player) == controller, "equipped controller did not deploy");
        test.assertTrue(!Hardware.execute(player, "scan.ore"), "MK1 performed an ore scan");
        test.assertTrue(Hardware.execute(player, "scan.hostile") && !Hardware.execute(player, "scan"), "scan trigger/cooldown failed");
        test.assertTrue(!Hardware.execute(player, "order.heal"), "Alba kit healed without supplies");
        inventory.setItem(7, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "medical_supply")), 2));
        test.assertTrue(Hardware.execute(player, "order.heal"), "medic failed to start");
        test.assertTrue(inventory.getItem(7).getCount() == 1, "medical cycle did not consume one dressing");
        test.runAfterDelay(22, () -> {
            test.assertTrue(player.getHealth() > 10, "medic did not heal");
            int charge = Hardware.energy(controller);
            test.assertTrue(charge < Hardware.capacity(controller) && charge > 0, "battery failed to drain");
            test.assertTrue(Hardware.energy(controller.copy()) == charge, "controller copy lost charge");
            test.assertTrue(Hardware.removeToInventory(player, controller, 0).isEmpty(), "failed to uninstall battery");
            ItemStack cell = inventory.items.stream().filter(s -> s.is(Hardware.BATTERIES.getFirst().get())).findFirst().orElseThrow();
            test.assertTrue(Hardware.energy(cell) == charge && Hardware.energy(controller) == 0, "uninstall reset or duplicated charge");
            test.assertTrue(!Hardware.execute(player, "scan.hostile"), "unpowered scanner responded");
            test.succeed();
        });
    }
}
