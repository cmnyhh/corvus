package dev.corvus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/** Reads the Curios {@code drone} slot. The controller stays in its original slot; we never move it. */
public final class DroneCurios {
    public static final String SLOT = "drone";

    private DroneCurios() {}

    public static ItemStack equipped(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player)
                .resolve()
                .flatMap(inventory -> inventory.getStacksHandler(SLOT))
                .map(handler -> {
                    var stacks = handler.getStacks(); // functional slots only, never cosmetic slots
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack stack = stacks.getStackInSlot(i);
                        if (stack.is(Corvus.DRONE.get())) return stack;
                    }
                    return ItemStack.EMPTY;
                })
                .orElse(ItemStack.EMPTY);
    }

    public static boolean slotPresent(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player)
                .resolve()
                .flatMap(inventory -> inventory.getStacksHandler(SLOT))
                .map(handler -> handler.getStacks().getSlots() > 0)
                .orElse(false);
    }
}
