package dev.dronepal;

import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

final class EquippedTestDrone {
    static void equip(GameTestHelper test, ServerPlayer player, ItemStack controller,
                      BiConsumer<ItemStack, DroneEntity> run) {
        var slots = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow().getStacks();
        test.assertTrue(slots.insertItem(0, controller, false).isEmpty(), "functional drone slot rejected controller");
        test.startSequence().thenExecuteAfter(5, () -> {
            var equipped = slots.getStackInSlot(0);
            var serial = DroneItem.serial(equipped);
            var entity = serial == null ? null : player.serverLevel().getEntity(serial);
            test.assertTrue(entity instanceof DroneEntity, "equipping controller did not deploy drone");
            run.accept(equipped, (DroneEntity) entity);
        });
    }
}
