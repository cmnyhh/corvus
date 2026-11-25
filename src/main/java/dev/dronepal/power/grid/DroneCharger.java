package dev.dronepal.power.grid;

import dev.dronepal.Hardware;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Bridges the wireless grid and a deployed drone's controller battery.
 *
 * <p>The drone's power system runs once per second. During that second the grid allocates its share
 * of the tower's 500 FE/t and banks it; at the boundary this class withdraws the banked FE, uses the
 * first part to pay for the module drain, and writes the rest straight into the battery NBT through
 * {@link Hardware#setEnergy} (the {@code Hardware}/{@code Batteries} path). The delivered amount was
 * already reduced by {@code 0.95^hops} in {@link TowerGridService}, so the battery only ever receives
 * what the supplying tower actually spent.</p>
 *
 * <p>It works in all deployed states — hovering, working over an ore mill, or parked next to a
 * charging dock — because it does not care about state, only about the drone being alive with a
 * controller in hand. A full battery asks the grid for nothing.</p>
 */
public final class DroneCharger {
    /**
     * @param delivered    FE handed to this drone for the whole second
     * @param drainPaid    part of {@code delivered} that paid for module drain
     * @param charged      part of {@code delivered} written into the battery
     * @param online       a tower covers the drone (even when the battery is full)
     * @param full         the battery is at capacity after this transaction
     * @param ratePerSecond {@code charged}, i.e. the current charging rate
     */
    public record Result(int delivered, int drainPaid, int charged, boolean online, boolean full, int ratePerSecond) {}

    private DroneCharger() {}

    public static Result service(ServerLevel level, Entity drone, ItemStack controller, int drainPerSecond) {
        if (level == null || level.isClientSide || drone == null || controller.isEmpty()) {
            return new Result(0, 0, 0, false, false, 0);
        }
        int capacity = Hardware.capacity(controller);
        int energy = Hardware.energy(controller);
        if (capacity <= 0) return new Result(0, 0, 0, GridLink.droneAttached(level, drone), false, 0);

        int drain = Math.max(0, drainPerSecond);
        int deficit = Math.max(0, capacity - energy);
        int request = drain + deficit;
        int delivered = GridLink.drawDrone(level, drone, request);
        int drainPaid = Math.min(drain, delivered);
        int charged = Math.min(delivered - drainPaid, capacity - energy);
        if (charged > 0) Hardware.setEnergy(controller, energy + charged);
        boolean online = GridLink.droneAttached(level, drone);
        return new Result(delivered, drainPaid, charged, online, Hardware.energy(controller) >= capacity, charged);
    }
}
