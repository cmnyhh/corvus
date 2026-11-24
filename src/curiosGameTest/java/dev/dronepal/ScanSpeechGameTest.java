package dev.dronepal;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal") @PrefixGameTestTemplate(false)
public final class ScanSpeechGameTest {
    @GameTest(template="curios_test_room", timeoutTicks=1600)
    public static void scanCompletionDeliversPersonalizedSubtitle(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var observer = GameTestPlayers.join(test);
        var pos = test.absolutePos(new BlockPos(2, 3, 2));
        // Separate the owner's contacts from nearby GameTest structures.
        pos = pos.above(36);
        player.teleportTo(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5);
        var controller = Frames.create("peregrine");
        var scanner = Hardware.moduleStack("scan_mk2");
        var battery = Hardware.BATTERIES.getFirst().toStack();
        var chatty = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("huhu_dronepal:chip_chatty")));
        Hardware.save(controller, List.of(chatty, scanner, battery));
        CuriosTestSlots.set(player, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = (DroneEntity)player.serverLevel().getEntity(DroneItem.serial(controller));
        var ore = pos.east(3);
        test.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
        var mob = test.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(2, 2, 2));
        mob.setPos(player.getX(), player.getY(), player.getZ()+4);
        var systems = new DroneSystems();
        test.assertTrue(systems.execute(drone, player, controller, "scan"), "manual scan failed");
        for (int i=0;i<220;i++) systems.tick(drone, player);
        var replies = DialogueGameTest.subtitles(player);
        test.assertTrue(replies.size()==1, "expected one completion: " + replies);
        var message = replies.getFirst().text().getString();
        for (String word : List.of("看这边", "东面约3格", Blocks.IRON_ORE.getName().getString(), "南面约4格", EntityType.HUSK.getDescription().getString()))
            test.assertTrue(message.contains(word), word + " missing from " + message);
        test.assertTrue(DialogueGameTest.subtitles(observer).isEmpty(), "private scan leaked");
        var scan = new ScanSnapshot(drone.getUUID(), test.getLevel().dimension().location(), 0,
            List.of(new ScanSnapshot.Ore(ore, ResourceLocation.parse("minecraft:iron_ore"))),
            List.of(new ScanSnapshot.Contact(mob.getId(), mob.getUUID(), mob.blockPosition(), ResourceLocation.parse("minecraft:husk"))));
        DialogueServer.scanComplete(player, scan, true);
        test.assertTrue(DialogueGameTest.subtitles(player).isEmpty(), "automatic duplicate");
        var empty = new ScanSnapshot(scan.serial(), scan.dimension(), 0, List.of(), List.of());
        DialogueServer.scanComplete(player, empty, true);
        test.assertTrue(DialogueGameTest.subtitles(player).isEmpty(), "automatic empty report");
        DialogueServer.scanComplete(player, empty, false);
        test.assertTrue(DialogueGameTest.subtitles(player).getFirst().text().getString().contains("没扫到目标"), "manual empty report");
        Hardware.save(controller, List.of(scanner, battery));
        DialogueServer.scanComplete(player, scan, false);
        test.assertTrue(DialogueGameTest.subtitles(player).isEmpty(), "silent chip spoke");
        // Pulling the chip out must silence everything, switch or not.
        test.assertTrue(!DroneRemote.barksEnabled(controller), "removing the chip left barks enabled");

        // The link switch mutes ammo reports and delivery only; persona barks, scanning included, must keep working.
        Hardware.save(controller, List.of(chatty, scanner, battery));
        net.minecraft.world.item.component.CustomData.update(
            net.minecraft.core.component.DataComponents.CUSTOM_DATA, controller,
            tag -> {
                tag.putBoolean("dronepal_off_link", true);
                // Silence the patrol so only the explicit call below can produce a line.
                tag.putBoolean("dronepal_off_scan", true);
            });
        var second = new ScanSnapshot(scan.serial(), scan.dimension(), 0,
            List.of(new ScanSnapshot.Ore(ore.east(1), ResourceLocation.parse("minecraft:iron_ore"))), List.of());
        // Link off must not silence the chip: assert the decision path, not packet timing across a long delay.
        test.runAfterDelay(20, () -> {
            CuriosTestSlots.set(player, controller);
            DroneLifecycle.reconcile(player, controller);
            var live = Hardware.controller(player);
            test.assertTrue(DroneRemote.barksEnabled(live), "link switch removed the chip's barks");
            var fresh = new ScanSpeech();
            test.assertTrue(fresh.describe(second, player.position(), "chatty", false, player.serverLevel().getGameTime()) != null,
                "scan report suppressed while the link switch was off");
            test.succeed();
        });
    }
}
