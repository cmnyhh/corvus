package dev.dronepal;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class BackpackGameTest {
    @GameTest(template = "curios_test_room")
    public static void handAndLegacyBackpackCannotDeploy(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        var target = test.absolutePos(new BlockPos(2, 1, 2));
        owner.teleportTo(target.getX(), target.getY(), target.getZ());
        var controller = new ItemStack(Dronepal.DRONE.get());
        owner.setItemInHand(InteractionHand.MAIN_HAND, controller);
        controller.use(test.getLevel(), owner, InteractionHand.MAIN_HAND);
        test.assertTrue(DroneItem.serial(controller) == null, "hand use assigned a serial");
        var serial = DroneItem.ensureSerial(controller);
        owner.getPersistentData().putUUID(DroneItem.ACTIVE, serial); // Legacy backpack deployment.
        test.assertTrue(!DroneItem.deploy(owner, controller, serial), "unequipped controller deployed");
        test.assertTrue(DroneRemote.status(owner, 1).enabled() == 0, "remote accepted legacy backpack");
        test.runAfterDelay(5, () -> {
            test.assertTrue(test.getLevel().getEntity(serial) == null && !owner.getPersistentData().hasUUID(DroneItem.ACTIVE), "legacy intent rebuilt drone");
            test.assertTrue(owner.getMainHandItem() == controller && controller.getCount() == 1, "controller lost");
            test.succeed();
        });
    }
}
