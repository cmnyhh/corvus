package dev.dronepal;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

public final class CuriosTestSlots {
    public static void set(ServerPlayer player, ItemStack stack) {
        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow()
                .getStacks().setStackInSlot(0, stack);
    }
}
