package dev.dronepal;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class CuriosSlotGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 100)
    public static void equipRecallAndReequip(GameTestHelper test) {
        ServerPlayer player = GameTestPlayers.join(test);
        BlockPos here = test.absolutePos(new BlockPos(2, 1, 2));
        player.teleportTo(here.getX() + 0.5, here.getY(), here.getZ() + 0.5);
        var slots = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow().getStacks();
        ItemStack controller = new ItemStack(Dronepal.DRONE.get());
        test.assertTrue(slots.getSlots() == 1 && slots.isItemValid(0, controller), "expected one functional slot accepting controller");
        test.assertTrue(slots.insertItem(0, controller, false).isEmpty(), "equip rejected");
        test.runAfterDelay(5, () -> {
            UUID serial = DroneItem.serial(slots.getStackInSlot(0));
            var first = serial == null ? null : test.getLevel().getEntity(serial);
            test.assertTrue(first instanceof DroneEntity && count(player) == 1, "expected one deployed drone, serial=" + serial + ", entity=" + first + ", count=" + count(player)
                    + ", spectator=" + player.isSpectator() + ", playerTicks=" + player.tickCount + ", state=" + player.getPersistentData());
            test.runAfterDelay(25, () -> {
                test.assertTrue(test.getLevel().getEntity(serial) == first && count(player) == 1, "reconciliation replaced or duplicated drone");
                ItemStack removed = slots.extractItem(0, 1, false);
                test.assertTrue(serial.equals(DroneItem.serial(removed)) && removed.getCount() == 1, "lost original controller");
                player.getInventory().setItem(0, removed);
                test.runAfterDelay(3, () -> {
                    test.assertTrue(count(player) == 0 && test.getLevel().getEntity(serial) == null, "unequip failed to recall");
                    test.assertTrue(slots.insertItem(0, player.getInventory().removeItemNoUpdate(0), false).isEmpty(), "re-equip rejected");
                    test.runAfterDelay(5, () -> {
                        test.assertTrue(count(player) == 1 && test.getLevel().getEntity(serial) instanceof DroneEntity, "re-equip changed identity or entity count");
                        test.assertTrue(slots.getStackInSlot(0).getCount() == 1 && player.getInventory().isEmpty(), "controller duplicated");
                        test.succeed();
                    });
                });
            });
        });
    }

    private static int count(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(DroneEntity.class, player.getBoundingBox().inflate(16),
                drone -> player.getUUID().equals(drone.owner)).size();
    }
}
