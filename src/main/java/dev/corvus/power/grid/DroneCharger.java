package dev.corvus.power.grid;

import dev.corvus.Hardware;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Bridges the wireless grid and a deployed drone's controller battery (1.20.1).
 *
 * <p>Pays module drain first and writes the rest into the battery through
 * {@link Hardware#setEnergy}; the delivered amount was already reduced by {@code 0.95^hops} in
 * {@link TowerGridService}. Works in every deployed state, and a full battery asks for nothing.</p>
 */
public final class DroneCharger {
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
        int delivered = GridLink.drawDrone(level, drone, drain + deficit);
        int drainPaid = Math.min(drain, delivered);
        int charged = Math.min(delivered - drainPaid, capacity - energy);
        if (charged > 0) Hardware.setEnergy(controller, energy + charged);
        return new Result(delivered, drainPaid, charged, GridLink.droneAttached(level, drone),
                Hardware.energy(controller) >= capacity, charged);
    }
}
