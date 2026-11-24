package dev.dronepal;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/** The physical controller stays in its original slot. */
final class CuriosDrones {
    static ItemStack equipped(ServerPlayer player) {
        var handler = CuriosApi.getCuriosInventory(player).flatMap(inv -> inv.getStacksHandler("drone"));
        if (handler.isPresent()) {
            var stacks = handler.get().getStacks(); // Functional slots only, never cosmetic slots.
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack stack = stacks.getStackInSlot(i);
                if (stack.is(Dronepal.DRONE.get())) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

}
