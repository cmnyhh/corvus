package dev.corvus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counters for the TaCZ player-candidate mixin ({@code dev.corvus.mixin.TaczPlayerCollisionMixin}) — the acceptance run's only way to tell "the rule
 * held" from "no round was ever resolved against a player".
 *
 * <p>Deliberately free of TaCZ types: the dev smoke reads it whether or not the gun backend is on
 * the runtime classpath, and keeping it clean means loading this class never drags in a target the
 * mixin is only conditionally applied to. It also lives outside {@code dev.corvus.mixin} on
 * purpose: Mixin refuses to load a class from a declared mixin package as a normal class, so
 * calling one from injected code throws {@code IllegalClassLoadError}.</p>
 */
public final class TaczPlayerGuard {
    private static final Logger LOG = LoggerFactory.getLogger("corvus-tacz-guard");

    /** Every player candidate the mixin refused, whether or not the ray actually crossed its box. */
    private static int filtered;
    /** Subset of {@link #filtered} whose box really was on the scanned segment. */
    private static int onPath;
    /** Per-player {@code [refused, boxOnRay]}. */
    private static final Map<UUID, int[]> perPlayer = new HashMap<>();
    /** First few refusals, for the log. */
    private static final List<String> trace = new CopyOnWriteArrayList<>();
    /** Players whose first refusal has been logged; later ones are summarised by the counters. */
    private static final java.util.Set<UUID> announced = new java.util.HashSet<>();

    private TaczPlayerGuard() {}

    /** Called by the mixin for every player candidate it drops. Never throws, never decides anything. */
    public static void filtered(DroneEntity drone, Player player, Vec3 start, Vec3 end) {
        boolean crossing = player.getBoundingBox().inflate(0.2D).clip(start, end).isPresent();
        synchronized (TaczPlayerGuard.class) {
            filtered++;
            if (crossing) onPath++;
            int[] counts = perPlayer.computeIfAbsent(player.getUUID(), key -> new int[2]);
            counts[0]++;
            if (crossing) counts[1]++;
        }
        String target = drone.getTarget() == null ? "none"
                : drone.getTarget().getName().getString() + "/" + drone.getTarget().getUUID();
        String line = player.getName().getString() + " uuid=" + player.getUUID()
                + " boxOnRay=" + crossing + " droneTarget=" + target + " mode="
                + Engagement.mode(drone.controller()) + " dist=" + String.format("%.2f", Math.sqrt(drone.distanceToSqr(player)))
                + " los=" + drone.hasLineOfSight(player) + " invulnerable=" + player.isInvulnerable()
                + " attackable=" + player.isAttackable() + " spectator=" + player.isSpectator();
        if (trace.size() < 12) trace.add(line);
        boolean first;
        synchronized (TaczPlayerGuard.class) {
            first = announced.add(player.getUUID());
        }
        if (first) LOG.info("[tacz-guard] first refusal for a player: {}", line);
    }

    public static synchronized int filtered() {
        return filtered;
    }

    /** Refusals where the player's box genuinely intersected the ray: the real "was in the way". */
    public static synchronized int onPath() {
        return onPath;
    }

    public static synchronized int filteredFor(UUID player) {
        int[] counts = perPlayer.get(player);
        return counts == null ? 0 : counts[0];
    }

    public static synchronized int onPathFor(UUID player) {
        int[] counts = perPlayer.get(player);
        return counts == null ? 0 : counts[1];
    }

    public static List<String> trace() {
        return List.copyOf(trace);
    }

    public static synchronized void reset() {
        filtered = 0;
        onPath = 0;
        perPlayer.clear();
        trace.clear();
    }
}
