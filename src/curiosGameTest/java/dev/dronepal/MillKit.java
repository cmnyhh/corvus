package dev.dronepal;

import java.util.ArrayList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/** Shared setup for the mill GameTests: controllers with a known battery, frames and modules. */
final class MillKit {
    private MillKit() {}

    static ItemStack controller(String frame, boolean scan, boolean carry, int energyPercent) {
        ItemStack controller = Frames.create(frame);
        ItemStack battery = Hardware.BATTERIES.getFirst().toStack();
        int capacity = Hardware.capacity(battery);
        CustomData.update(DataComponents.CUSTOM_DATA, battery, tag -> tag.putInt("energy", capacity * energyPercent / 100));
        var parts = new ArrayList<ItemStack>();
        parts.add(battery);
        if (scan) parts.add(Hardware.moduleStack("scan_mk1"));
        if (carry) parts.add(Hardware.moduleStack("carry_mk1"));
        controller.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(parts));
        return controller;
    }

    static int batteryEnergy(ItemStack controller) { return Hardware.energy(controller); }
    static int batteryCapacity(ItemStack controller) { return Hardware.capacity(controller); }

    /** Equips the controller, projects the drone and parks it above {@code millPos}. */
    static DroneEntity deploy(GameTestHelper test, ServerPlayer player, ItemStack controller, net.minecraft.core.BlockPos millPos) {
        CuriosTestSlots.set(player, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = (DroneEntity) test.getLevel().getEntity(DroneItem.serial(controller));
        if (drone == null) throw new AssertionError("controller did not project a drone");
        drone.setPos(millPos.getX() + .5, millPos.getY() + 2.35, millPos.getZ() + .5);
        drone.assignWork(millPos);
        return drone;
    }

    static void setBattery(ItemStack controller, int value) { Hardware.setEnergy(controller, value); }
}
