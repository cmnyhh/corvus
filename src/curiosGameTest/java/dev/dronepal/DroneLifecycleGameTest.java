package dev.dronepal;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DroneLifecycleGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 640)
    public static void damageRepairAndDeduplicate(GameTestHelper test) {
        ServerPlayer owner = joined(test);
        ItemStack controller = new ItemStack(Dronepal.DRONE.get());
        CuriosTestSlots.set(owner, controller);
        UUID id = DroneItem.ensureSerial(controller);
        test.assertTrue(DroneItem.deploy(owner, controller, id), "backpack deploy failed");
        ItemStack original = controller.copy();
        DroneEntity drone = (DroneEntity) test.getLevel().getEntity(id);
        test.assertTrue(!DroneItem.deploy(owner, controller.copy(), id), "cloned serial deployed twice");
        drone.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
        drone.setGuaranteedDrop(EquipmentSlot.MAINHAND);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        float before = drone.getHealth();
        test.assertTrue(drone.hurt(drone.damageSources().generic(), 2) && drone.getHealth() < before, "nonlethal damage ignored");
        var box = drone.getBoundingBox().inflate(1);
        drone.hurt(drone.damageSources().genericKill(), Float.MAX_VALUE);
        test.assertTrue(drone.isRemoved(), "dead projection remains");
        test.assertTrue(test.getLevel().getEntitiesOfClass(ItemEntity.class, box).isEmpty(), "death dropped loot");
        test.assertTrue(test.getLevel().getEntitiesOfClass(ExperienceOrb.class, box).isEmpty(), "death dropped XP");
        test.assertTrue(!DroneItem.deploy(owner, controller.copy(), id), "copied controller bypassed repair");
        test.runAfterDelay(599, () -> test.assertTrue(test.getLevel().getEntity(id) == null, "repair finished early"));
        test.runAfterDelay(601, () -> {
            var rebuilt = test.getLevel().getEntity(id);
            test.assertTrue(rebuilt instanceof DroneEntity replacement && replacement != drone && replacement.getHealth() == replacement.getMaxHealth(), "repair failed to rebuild");
            test.assertTrue(ItemStack.isSameItemSameComponents(controller, original) && controller.getCount() == 1, "controller changed during repair");
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 400)
    public static void equippedFollowsAcrossDimensions(GameTestHelper test) {
        ServerPlayer owner = joined(test);
        ItemStack controller = new ItemStack(Dronepal.DRONE.get());
        CuriosTestSlots.set(owner, controller);
        UUID id = DroneItem.ensureSerial(controller);
        test.assertTrue(DroneItem.deploy(owner, controller, id), "initial deploy failed");
        var destination = test.getLevel().getServer().getLevel(Level.NETHER);
        // The embedded connection has no client chunk acknowledgements. Keep both the
        // owner's destination and the projection's offset destination ticking for the test.
        destination.setChunkForced(0, 0, true);
        destination.setChunkForced(0, -1, true);
        owner.setNoGravity(true);
        owner.teleportTo(destination, 0.5, 80, 0.5, 0, 0);
        test.startSequence().thenWaitUntil(() -> test.assertTrue(test.getLevel().getEntity(id) == null
                && destination.getEntity(id) instanceof DroneEntity replacement && replacement.owner.equals(owner.getUUID()),
                "expected a single projection in the destination dimension"))
            .thenExecute(() -> CuriosTestSlots.set(owner, ItemStack.EMPTY))
            .thenWaitUntil(() -> test.assertTrue(destination.getEntity(id) == null, "cleared backpack left drone alive"))
            .thenExecute(() -> {
                destination.setChunkForced(0, 0, false);
                destination.setChunkForced(0, -1, false);
            }).thenSucceed();
    }
    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void equippedRestoredAfterLogin(GameTestHelper test) {
        ServerPlayer owner = joined(test);
        ItemStack controller = new ItemStack(Dronepal.DRONE.get());
        CuriosTestSlots.set(owner, controller);
        UUID id = DroneItem.ensureSerial(controller);
        test.assertTrue(DroneItem.deploy(owner, controller, id), "initial deploy failed");
        var profile = owner.getGameProfile();
        test.getLevel().getServer().getPlayerList().remove(owner);
        test.assertTrue(test.getLevel().getEntity(id) == null, "logout retained projection");
        ServerPlayer rejoined = GameTestPlayers.join(test, profile);
        test.runAfterDelay(5, () -> {
            test.assertTrue(id.equals(DroneItem.serial(CuriosDrones.equipped(rejoined))), "relogin lost controller");
            test.assertTrue(test.getLevel().getEntity(id) instanceof DroneEntity drone && rejoined.getUUID().equals(drone.owner), "relogin lost deployed intent");
            test.succeed();
        });
    }
    private static ServerPlayer joined(GameTestHelper test) {
        ServerPlayer player = GameTestPlayers.join(test);
        BlockPos pos = test.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        return player;
    }
}
