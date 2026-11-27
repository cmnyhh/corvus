package dev.dronepal.power.grid;

import dev.dronepal.DroneItem;
import dev.dronepal.Hardware;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
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
 *
 * <h2>The one-second request</h2>
 * <p>A socket asks for exactly what the next settlement can put into the cell: the module drain plus
 * the deficit ({@code capacity - energy}), in FE per second, and its credit cap is the same number.
 * The allocator turns that into {@code ceil(need / 20)} FE per tick and stops banking once the whole
 * request is covered, so a topping-off drone never leaves paid-for FE stranded in the bank (the old
 * {@code max(need, 40)} floor did exactly that and threw up to 40 FE away per top-off).</p>
 *
 * <h2>Sub-FE rates</h2>
 * <p>Nothing here floors a rate: the tower delivers whole FE and keeps the rounding remainder per
 * device inside {@link TowerGridService} (see its {@code Binding#carried}), so a 1 FE/t request at one
 * hop arrives as 0.95 FE/t on average instead of {@code floor(0.95) = 0}. That carry is what makes a
 * large cell topped off through a weak link actually reach full.</p>
 *
 * <h2>Socket, not a serial</h2>
 * <p>Each <em>entity instance</em> gets its own {@link Socket} instead of the service keeping one grid
 * load per serial: when a drone is unloaded (entity discarded, player relog, dimension change) and
 * respawned from the same controller, the new instance must not inherit a grid binding that was
 * registered for the discarded one. The old binding is pruned by the service once its entity is
 * removed, so a reload also starts from an empty bank instead of paying twice.</p>
 *
 * <h2>Unloaded, out of range, or in another dimension</h2>
 * <p>Policy: <b>nothing is settled while the projection is not loaded</b>, and that is not silent. The
 * settlement can only run from the entity's own tick, so an unloaded drone (chunk unloaded, owner
 * offline, or the owner walked through a portal — {@code DroneEntity.tick} discards the projection
 * when it is in another dimension than its owner) cannot collect a grant; the service prunes its
 * binding in the same tick, which drops any banked-but-unwithdrawn credit rather than paying it out
 * twice. On re-deploy the socket is new, the bank starts at zero, and charging resumes on the next
 * allocation. The HUD is told the truth at the moment the projection goes away:
 * {@code DroneEntity.remove} publishes one final sample with {@code online = false}, so the status
 * line reads "off grid" instead of freezing on "charging", and the panel hides itself 2.2 s later.</p>
 *
 * <h2>Serial check</h2>
 * <p>The controller in the airframe must carry this airframe's serial. A controller swapped into
 * another drone (or a stale copy of the old one) is refused <em>and</em> unregistered from the grid, so
 * the old airframe cannot keep drawing a share of the tower for a cell it no longer owns.</p>
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

    /**
     * One entity instance's grid socket. The entity is held weakly: the socket must never keep a
     * discarded drone alive, and the service drops the load as soon as the entity is gone.
     */
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

        /**
         * Room left in the bank: exactly what the next settlement will withdraw. Capping at the
         * request (instead of a fixed minimum) is what keeps a top-off from banking FE the cell can
         * never absorb.
         */
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
        // The serial is the airframe identity. A controller that was swapped into another airframe (or
        // a stale copy of the old one) must never charge this drone, and the old serial must not keep
        // a share of the tower either.
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
            // Register before the draw: the allocator polls this socket in the same tick, so the first
            // settlement after a reload has something to collect instead of throwing the second away.
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
