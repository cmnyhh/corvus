package dev.corvus.power.grid;

import dev.corvus.DroneItem;
import dev.corvus.Hardware;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Bridges the wireless grid and a deployed drone's controller battery (1.20.1).
 *
 * <p>The drone's power system runs once per second. During that second the grid allocates its share
 * of the tower's 500 FE/t and banks it; at the boundary this class withdraws the banked FE, pays the
 * module drain first and writes the rest into the battery through {@link Hardware#setEnergy}. The
 * delivered amount was already reduced by {@code 0.95^hops} in {@link TowerGridService}, so the
 * battery only ever receives what the supplying tower actually spent. It works in every deployed
 * state — hovering, working over an ore mill, parked next to a charging dock — because it reads no
 * state at all, and a full battery asks the grid for nothing.</p>
 *
 * <h2>The one-second request</h2>
 * <p>A socket asks for exactly what the next settlement can put into the cell: the module drain plus
 * the deficit ({@code capacity - energy}) in FE per second, and its credit cap is that same number.
 * The allocator turns it into {@code ceil(need / 20)} FE per tick and stops banking once the whole
 * request is covered, so a topping-off drone never strands paid-for FE in the bank.</p>
 *
 * <h2>Sub-FE rates</h2>
 * <p>Delivery is whole FE and the hop cost is a ratio, so the fraction of a FE a tick cannot deliver
 * is carried per device by {@link TowerGridService} (its {@code Binding}). Without that carry a 1 FE/t
 * request through one hop becomes {@code floor(1 * 0.95) = 0} on every tick: the tower pays and the
 * cell never moves. This was the "it charges to full and then never quite gets there" report.</p>
 *
 * <h2>Socket, not a serial</h2>
 * <p>Each <em>entity instance</em> owns its socket. 1.20.1 used to key the drone's grid load by the
 * airframe UUID and keep it in {@code TowerGridService.droneLoads}; a reloaded projection (chunk
 * unload, relog, dimension change) then found the <em>old</em>, already-removed entity behind that UUID,
 * the service pruned the load as invalid on every allocation and the drone never charged again for the
 * rest of the session. A per-instance socket cannot go stale, and the service drops it once the entity
 * is gone.</p>
 *
 * <h2>Unloaded, out of range, or in another dimension</h2>
 * <p>Policy: <b>nothing is settled while the projection is not loaded</b>, and that is not silent. The
 * settlement only runs from the entity's own tick, so an unloaded drone cannot collect a grant; its
 * banked-but-unwithdrawn credit is dropped with the binding instead of being paid twice. On re-deploy
 * the socket is new, the bank starts at zero and charging resumes on the next allocation. The HUD says
 * so: {@code DroneHud} only prints a grid line for a controller whose drone it can actually see, and
 * falls back to "off grid" when the projection is gone.</p>
 *
 * <h2>Serial check</h2>
 * <p>The controller handed to the airframe must carry this airframe's serial. A foreign controller is
 * refused and the socket is unregistered, so the old airframe cannot keep drawing a share of the tower
 * for a cell it no longer owns.</p>
 */
public final class DroneCharger {
    /**
     * @param delivered    FE taken out of the drone's grid bank this second
     * @param drainPaid    part of {@code delivered} that paid for module drain
     * @param charged      part of {@code delivered} written into the battery
     * @param online       a tower covers the drone (even when the battery is full)
     * @param full         the battery is at capacity after this transaction
     * @param ratePerSecond {@code charged}, i.e. the current charging rate
     * @param queued       on grid, not full, and nothing was delivered this second (the tower had no
     *                     budget left for this drone); the HUD shows this as "on grid · queued"
     */
    public record Result(int delivered, int drainPaid, int charged, boolean online, boolean full,
            int ratePerSecond, boolean queued) {}

    /** One entity instance's grid socket; the entity is weak so a discarded drone is never pinned. */
    private static final class Socket implements GridLoad {
        private final WeakReference<Entity> drone;
        private BlockPos last;
        private int perSecond;

        Socket(Entity drone) {
            this.drone = new WeakReference<>(drone);
            this.last = drone.blockPosition();
        }

        @Override public BlockPos gridPos() {
            Entity entity = drone.get();
            if (entity != null) last = entity.blockPosition();
            return last;
        }

        @Override public int gridPriority() { return GridLink.PRIORITY_DRONE; }

        @Override public int gridDemandFe() {
            return perSecond <= 0 ? 0 : Math.max(1, (perSecond + 19) / 20);
        }

        @Override public int gridCreditCapFe() { return Math.max(0, perSecond); }

        @Override public boolean gridValid() {
            Entity entity = drone.get();
            return entity != null && !entity.isRemoved() && entity.isAlive();
        }
    }

    private static final Map<Entity, Socket> SOCKETS = new WeakHashMap<>();

    private DroneCharger() {}

    public static Result service(ServerLevel level, Entity drone, ItemStack controller, int drainPerSecond) {
        if (level == null || level.isClientSide || drone == null || controller.isEmpty()) {
            return new Result(0, 0, 0, false, false, 0, false);
        }
        int capacity = Hardware.capacity(controller);
        boolean online = GridLink.covered(level, drone.blockPosition());
        if (capacity <= 0) return new Result(0, 0, 0, online, false, 0, false);
        UUID serial = DroneItem.serial(controller);
        if (serial != null && !serial.equals(drone.getUUID())) {
            Socket stale = SOCKETS.get(drone);
            if (stale != null) GridLink.unregisterLoad(level, stale);
            return new Result(0, 0, 0, online, Hardware.energy(controller) >= capacity, 0, false);
        }

        int energy = Hardware.energy(controller);
        int deficit = Math.max(0, capacity - energy);
        int drain = Math.max(0, drainPerSecond);
        int need = drain + deficit;

        Socket socket = socket(drone);
        socket.perSecond = need;
        int delivered = 0;
        if (need > 0) {
            // Register before the draw so the first settlement after a reload has something to collect.
            GridLink.registerLoad(level, socket);
            delivered = GridLink.withdraw(level, socket, need);
        }
        int drainPaid = Math.min(drain, delivered);
        int charged = Math.min(delivered - drainPaid, deficit);
        if (charged > 0) Hardware.setEnergy(controller, energy + charged);
        int after = Hardware.energy(controller);
        boolean full = after >= capacity;
        return new Result(delivered, drainPaid, charged, online, full, charged,
                online && !full && delivered == 0);
    }

    private static Socket socket(Entity drone) {
        Socket socket = SOCKETS.get(drone);
        if (socket == null) {
            if (SOCKETS.size() > 64) SOCKETS.values().removeIf(existing -> !existing.gridValid());
            socket = new Socket(drone);
            SOCKETS.put(drone, socket);
        }
        return socket;
    }
}
