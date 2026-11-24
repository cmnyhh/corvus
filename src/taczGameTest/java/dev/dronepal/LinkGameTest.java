package dev.dronepal;

import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class LinkGameTest {
    @GameTest(template="combat_test_room")
    public static void ammunitionTransfersRequirePoweredLink(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var terminal = Frames.create("peregrine");
        EquippedTestDrone.equip(test, player, terminal, (equipped, drone) -> exerciseLink(test, player, equipped));
    }

    private static void exerciseLink(GameTestHelper test, net.minecraft.server.level.ServerPlayer player, ItemStack terminal) {
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17")).build();
        player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        var right = AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:9mm")).setCount(7).build();
        var other = AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:762x39")).setCount(5).build();
        var cell = Hardware.BATTERIES.getFirst().toStack();
        java.lang.Runnable stock = () -> {
            ListTag cargo = new ListTag();
            for (var item : List.of(other, right)) cargo.add(item.save(player.registryAccess()));
            CustomData.update(DataComponents.CUSTOM_DATA, terminal, tag -> tag.put("dronepal_cargo", cargo));
        };
        stock.run();
        for (int tier : new int[]{1, 2, 3}) {
            Hardware.save(terminal, List.of(cell, Hardware.moduleStack("link_mk" + tier)));
            if (tier == 1) test.assertTrue(!Hardware.execute(player, "order.supply"), "R-1 delivered ammo");
            if (tier == 2) test.assertTrue(Hardware.execute(player, "order.supply"), "R-2 did not deliver matching ammo");
            if (tier == 3) TaczLink.second(player, terminal);
            if (tier >= 2) {
                var items = player.getInventory().items;
                test.assertTrue(items.stream().filter(s -> ItemStack.isSameItemSameComponents(s,right)).mapToInt(ItemStack::getCount).sum() == 7, "transfer lost/duplicated compatible ammo");
                test.assertTrue(items.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s,other)), "transferred incompatible ammo");
                test.assertTrue(!Hardware.execute(player, "order.supply"), "empty cargo duplicated ammo");
                items.stream().filter(s -> ItemStack.isSameItemSameComponents(s,right)).forEach(s -> s.setCount(0));
                stock.run();
            }
        }
        Hardware.setEnergy(terminal, 10);
        test.assertTrue(!Hardware.execute(player, "order.supply"), "low-power-shed link delivered");
        TaczLink.second(player, terminal);
        Hardware.save(terminal, List.of(cell));
        test.assertTrue(!Hardware.execute(player, "order.supply"), "removed link delivered");
        TaczLink.second(player, terminal);
        test.assertTrue(player.getInventory().items.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s,right)), "automatic supply ignored offline state");
        test.succeed();
    }
}
