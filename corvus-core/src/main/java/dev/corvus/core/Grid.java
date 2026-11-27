package dev.corvus.core;

/**
 * Wireless power grid rules shared by the 1.21.1 (NeoForge) and 1.20.1 (Forge) builds.
 *
 * <p>A grid is a connected component of power towers. Towers are linked when they are at most
 * {@code towerRadius} blocks apart (both versions use the same config default, 32), and each tower
 * keeps its own energy pool: linking never moves energy, it only lets a device draw from a tower that
 * is <em>hops</em> away from the tower it is attached to. Every hop costs a multiplicative
 * efficiency (default 0.95) and more than {@link #MAX_HOPS} hops is out of reach.</p>
 *
 * <p>The per-tower output budget is a shared resource: every device directly attached to a tower
 * competes for the same {@code towerThroughput} FE/t, allocated by priority (lower value first:
 * drone 0, ore mill 1, charging dock 2, power port 3). The port is last on purpose — it is the outlet
 * for other mods, so the machines of this mod are served before it, and a port that is inside a tower's
 * radius can also fall back on its cable network when the tower is empty. A device that gets a partial grant keeps working at a
 * reduced rate instead of switching off; this class only does the arithmetic, the loader-side code
 * remembers which device got which grant.</p>
 *
 * <p>This file imports nothing from Minecraft, Forge or NeoForge and is compiled into both jars.</p>
 */
public final class Grid {
    /** Longest usable path; 0 hops (the attached tower itself) is always reachable. */
    public static final int MAX_HOPS = 5;
    /** Default efficiency of one tower-to-tower hop (config range 0.85 - 1.0). */
    public static final double LINK_EFFICIENCY = 0.95;
    /** Energy buffer of one tower, FE. */
    public static final int TOWER_BUFFER = 1_000_000;
    /** Active pull from the six neighbouring FE blocks, FE/t. */
    public static final int TOWER_PULL_RATE = 2_000;
    /** Output ceiling of one tower shared by all devices attached to it, FE/t. */
    public static final int DEFAULT_THROUGHPUT = 500;
    /** Link distance between two towers and the displayed coverage radius, blocks. */
    public static final int DEFAULT_RADIUS = 32;

    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 64;
    public static final int MIN_THROUGHPUT = 100;
    public static final int MAX_THROUGHPUT = 2_000;
    public static final double MIN_EFFICIENCY = 0.85;
    public static final double MAX_EFFICIENCY = 1.0;

    /** Device priorities: a lower number is served first. */
    public static final int PRIORITY_DRONE = 0;
    public static final int PRIORITY_MILL = 1;
    public static final int PRIORITY_DOCK = 2;
    /** Serviced after the mod's own machines: a {@code power_port} feeding another mod. */
    public static final int PRIORITY_PORT = 3;

    private Grid() {}

    /**
     * Delivery ratio over {@code hops} links at the default 0.95 efficiency.
     *
     * <p>0 hops -> 1.0; 1 -> 0.95; 3 -> 0.857375 (the smoke tests allow 0.86 +/- 0.01); 6 is not
     * reachable at all, but the arithmetic still answers 0.735... rather than throwing.</p>
     */
    public static double hopsCost(int hops) {
        return hopsCost(hops, LINK_EFFICIENCY);
    }

    /**
     * Delivery ratio over {@code hops} links at {@code efficiency} per hop.
     *
     * <p>{@code hops <= 0} is a direct connection and keeps 100% (a negative hop count is a caller
     * bug, not a penalty). The efficiency is clamped to the documented config range, so a forged
     * config value can neither create energy (&gt; 1) nor burn all of it (&lt; 0.85).</p>
     */
    public static double hopsCost(int hops, double efficiency) {
        if (hops <= 0) return 1.0;
        double base = clamp(efficiency, MIN_EFFICIENCY, MAX_EFFICIENCY);
        return Math.pow(base, hops);
    }

    /** Whether a path of {@code hops} links may carry power at all. */
    public static boolean reachable(int hops) {
        return hops >= 0 && hops <= MAX_HOPS;
    }

    /** FE actually handed to a device when the tower spends {@code supply} FE over {@code hops}. */
    public static int delivered(int supply, int hops, double efficiency) {
        if (supply <= 0 || !reachable(hops)) return 0;
        return (int) Math.floor(supply * hopsCost(hops, efficiency));
    }

    /**
     * Splits one tower's per-tick budget across the devices attached to it.
     *
     * <p>Priorities are served in ascending order (0 = drone first). Inside one priority level the
     * remaining budget is water-filled equally: a device that wants less than its share leaves the
     * rest to its peers, and when the budget finally runs out every peer gets the same partial
     * amount, which callers turn into a slower machine rather than an off switch. Grants are whole FE
     * and never exceed the device's own demand.</p>
     *
     * @param budget     FE/t the tower may deliver this tick (negative is treated as 0)
     * @param demands    FE/t each device asked for; index order is the tie-breaker
     * @param priorities priority per device, lower served first; must match {@code demands} length
     * @return granted FE per device (same length); an empty array when the inputs are unusable
     */
    public static int[] allocate(int budget, int[] demands, int[] priorities) {
        if (demands == null || priorities == null || demands.length != priorities.length) {
            return new int[0];
        }
        int count = demands.length;
        int[] grants = new int[count];
        int remaining = Math.max(0, budget);
        if (remaining == 0 || count == 0) return grants;

        // Distinct priorities, ascending. There are at most a handful of device classes, so the
        // insertion sort keeps this allocation free of collections and boxing.
        int[] levels = new int[count];
        int levelCount = 0;
        for (int priority : priorities) {
            boolean seen = false;
            for (int i = 0; i < levelCount; i++) if (levels[i] == priority) { seen = true; break; }
            if (seen) continue;
            int at = levelCount++;
            while (at > 0 && levels[at - 1] > priority) { levels[at] = levels[at - 1]; at--; }
            levels[at] = priority;
        }

        for (int l = 0; l < levelCount && remaining > 0; l++) {
            int level = levels[l];
            // Water-fill this priority level until the budget or every demand is satisfied.
            boolean progress = true;
            while (remaining > 0 && progress) {
                progress = false;
                int active = 0;
                for (int i = 0; i < count; i++) {
                    if (priorities[i] == level && grants[i] < demands[i]) active++;
                }
                if (active == 0) break;
                int share = remaining / active;
                if (share <= 0) share = 1; // hand out the last few FE one at a time, index order
                for (int i = 0; i < count && remaining > 0; i++) {
                    if (priorities[i] != level || grants[i] >= demands[i]) continue;
                    int give = Math.min(share, Math.min(demands[i] - grants[i], remaining));
                    if (give <= 0) continue;
                    grants[i] += give;
                    remaining -= give;
                    progress = true;
                }
            }
        }
        return grants;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Clamps a configured radius to the documented 8..64 range. */
    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    /** Clamps a configured throughput to the documented 100..2000 FE/t range. */
    public static int clampThroughput(int throughput) {
        return Math.max(MIN_THROUGHPUT, Math.min(MAX_THROUGHPUT, throughput));
    }

    /** Clamps a configured per-hop efficiency to 0.85..1.0. */
    public static double clampEfficiency(double efficiency) {
        return clamp(efficiency, MIN_EFFICIENCY, MAX_EFFICIENCY);
    }
}
