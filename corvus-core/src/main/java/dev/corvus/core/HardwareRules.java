package dev.corvus.core;

/**
 * The three hardware rules that decide whether a module runs and what it costs.
 *
 * <p>They are here as one class because they interact: the player switch ({@link #online}), the
 * brown-out cut ({@link #brownout}) and the drain sum ({@link #drainPerTick}) all read the same set of
 * installed modules. The 1.20.1 build used to answer "online" from the charge alone, so a module the
 * player had switched off in the wheel still drew power and a switched-off scanner still re-armed its
 * automatic sweep. The shared rule is {@code online = !userDisabled && powered}.</p>
 *
 * <p>Pure arithmetic, no Minecraft types: both builds pass their own per-module numbers.</p>
 */
public final class HardwareRules {
    /** Denominator of the brown-out threshold: below {@code capacity / 5} the hungriest module is cut. */
    public static final int BROWNOUT_DIVISOR = 5;

    private HardwareRules() {}

    /**
     * Is the module allowed to run right now?
     *
     * @param userDisabled true when the player switched the module off in the wheel
     * @param powered      true when the frame has charge, the module is fitted and no fault is active
     * @return false if either input is false; the player switch always wins, so a disabled module
     *         neither draws power nor speaks
     */
    public static boolean online(boolean userDisabled, boolean powered) {
        return !userDisabled && powered;
    }

    /**
     * Total FE/t drawn by the online modules.
     *
     * <p>Summed as the plain sum of the module wattages, not as {@code round(sum x mult) x 20}: the
     * 1.21.1 build rounded each module's <em>per-second</em> draw and then summed, the 1.20.1 build
     * summed and rounded once, which drifted by up to 10 FE per second with two fractional
     * multipliers. Rounding is the caller's job at the point where per-tick watts become the
     * per-second charge; this method only totals whole watts.</p>
     *
     * @param wattByModule {@code drain_watts} per installed module, any order
     * @param onlineFlags  one flag per module, non-zero = online; same length as {@code wattByModule}
     * @return the sum of the online wattages, negative wattages treated as 0, saturated at
     *         {@link Integer#MAX_VALUE}; 0 when no module is online
     * @throws IllegalArgumentException when either array is {@code null} or the lengths differ
     */
    public static int drainPerTick(int[] wattByModule, int[] onlineFlags) {
        if (wattByModule == null || onlineFlags == null) {
            throw new IllegalArgumentException("wattByModule and onlineFlags must not be null");
        }
        if (wattByModule.length != onlineFlags.length) {
            throw new IllegalArgumentException(
                    "wattByModule (" + wattByModule.length + ") and onlineFlags (" + onlineFlags.length
                            + ") must have the same length");
        }
        long total = 0L;
        for (int i = 0; i < wattByModule.length; i++) {
            if (onlineFlags[i] == 0) continue;
            total += Math.max(0, wattByModule[i]);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Is the frame in brown-out (the state in which the hungriest module is cut)?
     *
     * <p>The threshold is integer division, exactly as both builds compared it:
     * {@code energy < capacity / 5}. A 500,000 FE frame browns out below 100,000 FE; at exactly
     * 100,000 it does not. The strict {@code <} is deliberate — the cut point is the 20 % line, not
     * one FE above it.</p>
     *
     * @param energy   current charge, in FE
     * @param capacity battery capacity, in FE; {@code <= 0} means "no battery", never a brown-out
     * @return true when the charge is strictly below one fifth of capacity; a negative charge counts
     *         as below the threshold
     */
    public static boolean brownout(long energy, long capacity) {
        return capacity > 0L && energy < capacity / BROWNOUT_DIVISOR;
    }
}
