package dev.dronepal.mill;

import dev.corvus.core.WorkCycle;
import dev.dronepal.DialogueServer;
import dev.dronepal.DroneEntity;
import dev.dronepal.Hardware;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * The drone half of the ore-mill feature: the work order, the hover over the machine and the battery
 * contract (20 FE/t on top of the machine, stop at 15%, resume at 40%).
 *
 * <p>The machine owns the assignment ({@link OreMillEntity#assignedDrone()}); the drone owns the target
 * block. Both are checked on both sides every tick, so recalling the drone or breaking the mill ends the
 * job without a leftover worker.</p>
 */
public final class DroneWork {
    /**
     * Most blocks the airframe may travel in one tick while on station.
     *
     * <p>Above the orbit's own tangential speed ({@code ORBIT_RADIUS * 22.5 deg} ≈ 0.47 blocks per
     * tick at the shared 60-tick cycle), so a drone that is already on station snaps onto the exact
     * circle point every tick instead of trailing behind it; small enough that joining the station -
     * or the smoothed radius at the loop seam - is a glide, never a teleport.</p>
     */
    private static final double ORBIT_STEP = 0.6D;

    private DroneWork() {}

    public static boolean order(ServerPlayer player, String intent) {
        DroneEntity drone = Hardware.active(player);
        if (drone == null) { message(player, "no_drone"); return false; }
        if (intent.equals("order.idle")) {
            stop(drone);
            message(player, "idle");
            DialogueServer.event(player, "idle");
            return true;
        }
        if (intent.equals("order.work")) return work(player, drone);
        return false;
    }

    private static boolean work(ServerPlayer player, DroneEntity drone) {
        ItemStack controller = drone.controller();
        if (controller.isEmpty()) { message(player, "no_drone"); return false; }
        if (Hardware.tier(controller, "scan") <= 0) {
            // Without the scan module the drone cannot read an ore at all, so there is nothing to order.
            message(player, "no_scan");
            DialogueServer.event(player, "refuse");
            return false;
        }
        OreMillEntity mill = nearest(player.serverLevel(), player.blockPosition(), OreMillRules.WORK_RANGE);
        if (mill == null) {
            message(player, "no_mill");
            DialogueServer.event(player, "no_mill");
            return false;
        }
        mill.assignDrone(drone.getUUID(), player.getUUID());
        drone.assignWork(mill.getBlockPos());
        message(player, "assigned", mill.getBlockPos().toShortString());
        DialogueServer.event(player, "work");
        return true;
    }

    /** The GUI button: assign the caller's own drone to this exact machine, within 32 blocks. */
    public static boolean callToMill(ServerPlayer player, OreMillEntity mill) {
        DroneEntity drone = Hardware.active(player);
        if (drone == null) { message(player, "no_drone"); return false; }
        if (drone.position().distanceToSqr(mill.getBlockPos().getCenter()) > OreMillRules.WORK_RANGE * OreMillRules.WORK_RANGE) {
            message(player, "out_of_range");
            return false;
        }
        mill.assignDrone(drone.getUUID(), player.getUUID());
        drone.assignWork(mill.getBlockPos());
        ItemStack controller = drone.controller();
        boolean scanning = !controller.isEmpty() && Hardware.tier(controller, "scan") > 0;
        message(player, scanning ? "assigned" : "no_scan", mill.getBlockPos().toShortString());
        DialogueServer.event(player, scanning ? "work" : "refuse");
        return true;
    }

    public static void stop(DroneEntity drone) {
        if (drone.workTarget() != null && drone.level() instanceof ServerLevel world
                && world.getBlockEntity(drone.workTarget()) instanceof OreMillEntity mill) {
            mill.clearDrone(drone.getUUID());
        }
        drone.clearWork();
    }

    /** True while the drone is hovering over its mill; returns false once the order is over. */
    public static boolean chase(DroneEntity drone, ServerPlayer player) {
        BlockPos pos = drone.workTarget();
        if (pos == null) { drone.working(false); return false; }
        if (!(drone.level() instanceof ServerLevel world)) return false;
        if (!(world.getBlockEntity(pos) instanceof OreMillEntity mill) || !mill.isAssigned(drone.getUUID())
                || drone.controller().isEmpty()) {
            drone.clearWork();
            return false;
        }
        if (drone.blockPosition().distSqr(pos) > 64 * 64) { drone.clearWork(); return false; }
        // The station is the shared 60-tick work cycle (dev.corvus.core.WorkCycle, also used by the
        // client pose): the drone orbits the machine during the scan, sinks straight down for the
        // drop and hovers over the centre for the reset. The numbers all come from corvus-core, so
        // the 1.20.1 build flies the identical path; nothing here decides ore, power or water.
        //
        // The entity itself is placed ON the circle - centre = the mill's hover centre (the seam
        // between the two halves), radius = WorkCycle.orbitRadius(t) - so the XZ position is the
        // orbit, not a heading. The old follow-lerp is gone: it turned a moving target into a
        // 0.1-block wobble, which read in game as a drone spinning on the spot.
        int cycle = (int) world.getGameTime();
        // The core's radius snaps from 0 to ORBIT_RADIUS at the loop seam (DROP/RESET are centred, the
        // scan orbits). Averaging the last three samples - stateless, so every drone and both builds
        // agree - turns that step into a gliding three-tick move outward without touching the phases.
        double radius = (WorkCycle.orbitRadius(cycle) + WorkCycle.orbitRadius(cycle - 1)
                + WorkCycle.orbitRadius(cycle - 2)) / 3.0D;
        double bearing = Math.toRadians(WorkCycle.orbitAngle(cycle));
        Vec3 centre = mill.hoverCenter();
        Vec3 target = new Vec3(centre.x + Math.cos(bearing) * radius,
                centre.y + WorkCycle.hoverHeight(cycle),
                centre.z + Math.sin(bearing) * radius);
        int tick = WorkCycle.tickInCycle(cycle);
        if (WorkCycle.phase(cycle) == WorkCycle.Phase.SCAN && tick % 8 == 0) {
            // A thin down-facing light column from the gimbal onto the disc, every 8 ticks.
            for (int i = 0; i < 3; i++) {
                world.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                        drone.getX(), drone.getY() - 0.18 - i * 0.2, drone.getZ(), 1, 0.02, 0.0, 0.02, 0.0);
            }
        }
        if (WorkCycle.phase(cycle) == WorkCycle.Phase.DROP && tick == WorkCycle.SCAN_END) {
            // The drop opens with a dust ring on the machine's top face.
            double top = pos.getY() + 1.02;
            for (int i = 0; i < 10; i++) {
                double slice = Math.PI * 2 * i / 10;
                world.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD,
                        centre.x + Math.cos(slice) * 0.55, top, centre.z + Math.sin(slice) * 0.55,
                        1, 0.0, 0.02, 0.0, 0.01);
            }
        }
        // On station the entity sits exactly on the circle: per tick the target moves 0.47 blocks
        // (22.5 degrees of a 1.2 block radius), inside the cap, so this is a snap and the hull never
        // trails the orbit. Flying in from somewhere else - the owner's shoulder, a recall, the
        // smoothed radius at the seam - is a capped 0.6 block/tick glide instead of a teleport.
        Vec3 delta = target.subtract(drone.position());
        double distance = delta.length();
        Vec3 next = distance <= ORBIT_STEP ? target : drone.position().add(delta.scale(ORBIT_STEP / distance));
        double speed = next.subtract(drone.position()).horizontalDistance();
        drone.setDeltaMovement(Vec3.ZERO);
        drone.setFlight((int)Math.round(15 * Math.min(speed / 0.22, 1)));
        drone.setPos(next);
        // Facing = the tangent of the circle, i.e. the way the airframe is travelling. In Minecraft's
        // yaw convention (0 = south, 90 = west) a point at bearing b moves along yaw b, so no extra
        // 90 degrees is needed; DROP/RESET keep turning with the core so the seam stays continuous.
        drone.faceHeading((float) WorkCycle.orbitAngle(cycle));
        drone.working(true);
        return true;
    }

    /**
     * Runs once a second with the rest of the drone power settlement: the work draw is
     * {@value OreMillRules#DRONE_FE_PER_TICK} FE/t, i.e. 400 FE per settlement, and the 15%/40%
     * hysteresis lives on the entity so a stalled drone stays stalled until it is really recharged.
     */
    public static void second(DroneEntity drone, ItemStack controller) {
        if (drone.workTarget() == null || controller.isEmpty()) return;
        int capacity = Hardware.capacity(controller);
        if (capacity <= 0) return;
        int energy = Hardware.energy(controller);
        if (energy > 0) Hardware.setEnergy(controller, Math.max(0, energy - OreMillRules.DRONE_FE_PER_TICK * 20));
        double charge = (double)Hardware.energy(controller) / capacity;
        if (charge < OreMillRules.DRONE_STOP_CHARGE) drone.setWorkStalled(true);
        else if (charge >= OreMillRules.DRONE_RESUME_CHARGE) drone.setWorkStalled(false);
    }

    /** Closest mill in loaded chunks around {@code center}, within {@code range} blocks. */
    static OreMillEntity nearest(ServerLevel world, BlockPos center, int range) {
        OreMillEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        int minX = (center.getX() - range) >> 4, maxX = (center.getX() + range) >> 4;
        int minZ = (center.getZ() - range) >> 4, maxZ = (center.getZ() + range) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, ?> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof OreMillEntity mill)) continue;
                    double distance = mill.getBlockPos().distToCenterSqr(center.getX() + .5, center.getY() + .5, center.getZ() + .5);
                    if (distance <= (double)range * range && distance < bestDistance) {
                        best = mill;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.dronepal.ore_mill." + key, args), true);
    }
}
