package dev.dronepal;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class ArchiveGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 680)
    public static void flightLogSurvivesRecallAndRepair(GameTestHelper test) {
        var world = test.getLevel();
        var player = GameTestPlayers.join(test);
        var here = test.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(here.getX() + .5, here.getY(), here.getZ() + .5);
        var controller = Dronepal.DRONE.toStack();
        CuriosTestSlots.set(player, controller);
        var serial = DroneItem.ensureSerial(controller);
        var archive = DroneArchive.get(world);
        test.assertTrue(archive.entry(serial) == null, "unused controller has archive");
        test.assertTrue(DroneItem.deploy(player, controller, serial), "initial deployment failed");
        var first = archive.entry(serial);
        test.assertTrue(first.frame().equals(Frames.id(controller)) && first.rebuilds() == 0, "first flight details wrong");
        test.runAfterDelay(27, () -> {
            CuriosTestSlots.set(player, net.minecraft.world.item.ItemStack.EMPTY);
            DroneLifecycle.recall(player);
            var landed = archive.entry(serial);
            test.assertTrue(landed.ticks() >= 25 && landed.ticks() <= 28, "active ticks not flushed at recall: " + landed.ticks());
            test.runAfterDelay(5, () -> {
                test.assertTrue(archive.entry(serial).ticks() == landed.ticks(), "idle time counted");
                CuriosTestSlots.set(player, controller);
                test.assertTrue(DroneItem.deploy(player, controller, serial), "relaunch failed");
                var drone = (DroneEntity) world.getEntity(serial);
                drone.hurt(drone.damageSources().genericKill(), Float.MAX_VALUE);
                test.assertTrue(archive.entry(serial).awaitingRepair() && archive.entry(serial).rebuilds() == 0, "repair counted too early");
                test.runAfterDelay(602, () -> {
                    var restored = archive.entry(serial);
                    test.assertTrue(restored.firstTick() == first.firstTick() && restored.rebuilds() == 1 && !restored.awaitingRepair(), "rebuild count or commissioning changed");
                    var copy = DroneArchive.load(archive.save(new CompoundTag(), world.registryAccess()), world.registryAccess());
                    test.assertTrue(restored.equals(copy.entry(serial)), "archive failed save/load");
                    try {
                        test.assertTrue(world.getServer().getCommands().getDispatcher().execute("dronepal memory", player.createCommandSourceStack()) == 1, "archive command failed");
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) { throw new AssertionError(ex); }
                    test.succeed();
                });
            });
        });
    }
}
