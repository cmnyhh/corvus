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

    /** Server-wide scale on every module draw and on the deployment baseline. */
    public static final double DEFAULT_DRAIN_MULTIPLIER = 0.6D;
    /** Lower clamp of {@code drainMultiplier}; below this a drone is effectively free to fly. */
    public static final double MIN_DRAIN_MULTIPLIER = 0.25D;
    /** Upper clamp of {@code drainMultiplier}; the pre-rebalance cost is about 1 / 0.6 = 1.67x. */
    public static final double MAX_DRAIN_MULTIPLIER = 3.0D;
    /** FE/t a deployed airframe costs before any module is switched on (it is still holding itself up). */
    public static final double DEPLOYED_BASELINE_WATTS = 0.5D;

    /** Same ceiling the module parser has always applied to a data pack's wattage. */
    private static final int WATTS_CEILING = 10_000;

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
     * Total FE/t drawn by the online modules, at one fixed wattage each.
     *
     * <p>This is the pre-idle-split rule and is kept for callers that only know one number per module
     * (the brown-out ranking, the tooltips, the older parity assertions). Everything that bills a
     * running airframe goes through {@link #drainPerTick(double[], double[], int[], int[], boolean,
     * double)}, which knows the standby/active split and the deployment baseline.</p>
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
     * Standby draw of a family whose data file does not declare {@code drain_idle}.
     *
     * <p>The idle/active split only bills a module while it is doing work; a data pack written before
     * the split still carries the old always-on {@code drain_watts}, so the stand-by half has to have a
     * sane default per family. Combat keeps a small trickle (the weapon bus stays armed), the link
     * channel keeps 1 FE/t to stay open, and everything else coasts at 0.</p>
     *
     * @param family module family ({@code combat}, {@code scan}, {@code carry}, {@code medic},
     *               {@code light}, {@code link}, {@code solar}); unknown or null reads 0
     * @return the default {@code drain_idle} in FE/t
     */
    public static int idleDefault(String family) {
        if (family == null) return 0;
        return switch (family) {
            case "combat" -> 3;
            case "link" -> 1;
            // scan, carry, medic, light and solar are worth nothing while they are not doing work.
            default -> 0;
        };
    }

    /**
     * The standby draw of one module definition.
     *
     * @param declared the file's {@code drain_idle}, or {@code null} when the pack predates the field
     * @param family   the module family, used for the fallback
     * @return the declared value, or {@link #idleDefault(String)}; clamped to {@code 0..10000}
     */
    public static int idleWatts(Integer declared, String family) {
        int value = declared == null ? idleDefault(family) : declared;
        return clampWatts(value);
    }

    /**
     * The in-use draw of one module definition.
     *
     * <p>Compatibility rule: a data pack that only has the old {@code drain_watts} bills that number
     * while the module works, which is exactly what it used to bill all the time.</p>
     *
     * @param declaredActive the file's {@code drain_active}, or {@code null} when absent
     * @param legacyWatts    the file's {@code drain_watts}, or {@code null} when absent
     * @return the in-use draw in FE/t, clamped to {@code 0..10000}
     * @throws IllegalArgumentException when the definition declares neither field
     */
    public static int activeWatts(Integer declaredActive, Integer legacyWatts) {
        Integer value = declaredActive != null ? declaredActive : legacyWatts;
        if (value == null) {
            throw new IllegalArgumentException("module declares neither drain_active nor drain_watts");
        }
        return clampWatts(value);
    }

    private static int clampWatts(int value) {
        return value < 0 ? 0 : Math.min(value, WATTS_CEILING);
    }

    /** FE/t a deployed airframe costs with every module switched off; 0 when it is not deployed. */
    public static double baselinePerTick(boolean deployed) {
        return deployed ? DEPLOYED_BASELINE_WATTS : 0.0D;
    }

    /**
     * Clamp of the server-wide {@code drainMultiplier} config knob.
     *
     * @param multiplier any value; NaN reads as {@link #DEFAULT_DRAIN_MULTIPLIER}
     * @return the multiplier inside {@code [0.25, 3.0]}
     */
    public static double clampMultiplier(double multiplier) {
        if (Double.isNaN(multiplier)) return DEFAULT_DRAIN_MULTIPLIER;
        if (multiplier < MIN_DRAIN_MULTIPLIER) return MIN_DRAIN_MULTIPLIER;
        if (multiplier > MAX_DRAIN_MULTIPLIER) return MAX_DRAIN_MULTIPLIER;
        return multiplier;
    }

    /**
     * FE/t the airframe draws right now: every online module at its standby or in-use rate, plus the
     * deployment baseline, all scaled once by the server's {@code drainMultiplier}.
     *
     * <p>One rounding point, at the end: the arrays are doubles because the per-module vendor
     * {@code drain_multiplier} (OmniDrive 0.85, Rustnail 1.3, ...) is fractional, and the global
     * multiplier is fractional too. The caller converts this per-tick figure to whole FE for one
     * settlement window through {@link #drainForWindow(double, int)}.</p>
     *
     * @param idleWatts   standby FE/t per module (already carrying the vendor multiplier)
     * @param activeWatts in-use FE/t per module (already carrying the vendor multiplier)
     * @param onlineFlags one flag per module, non-zero = online; a module that is off draws nothing
     * @param activeFlags one flag per module, non-zero = doing work this second
     * @param deployed    true while the airframe is projected, which pays the baseline even with every
     *                    module switched off
     * @param multiplier  the server's {@code drainMultiplier}; clamped by {@link #clampMultiplier}
     * @return the scaled FE/t draw, never negative
     * @throws IllegalArgumentException when an array is {@code null} or the lengths differ
     */
    public static double drainPerTick(double[] idleWatts, double[] activeWatts, int[] onlineFlags,
            int[] activeFlags, boolean deployed, double multiplier) {
        if (idleWatts == null || activeWatts == null || onlineFlags == null || activeFlags == null) {
            throw new IllegalArgumentException("the idle, active and flag arrays must not be null");
        }
        int length = idleWatts.length;
        if (activeWatts.length != length || onlineFlags.length != length || activeFlags.length != length) {
            throw new IllegalArgumentException("idle (" + length + "), active (" + activeWatts.length
                    + "), online (" + onlineFlags.length + ") and active flags (" + activeFlags.length
                    + ") must have the same length");
        }
        double total = baselinePerTick(deployed);
        for (int i = 0; i < length; i++) {
            if (onlineFlags[i] == 0) continue;
            double watts = activeFlags[i] != 0 ? activeWatts[i] : idleWatts[i];
            if (watts > 0.0D && Double.isFinite(watts)) total += watts;
        }
        return total * clampMultiplier(multiplier);
    }

    /**
     * Whole FE for one settlement window: {@code round(perTick x ticks)}, floored at 0.
     *
     * <p>This is the single rounding point of the power transaction. The 1.21.1 build rounds the
     * airframe's once-per-second bill with it, the 1.20.1 build does the same, so a fractional
     * standby cost (0.5 FE/t x 0.6 = 0.3 FE/t) cannot be rounded away twice or twice too often.</p>
     *
     * @param perTick FE/t, usually the result of {@link #drainPerTick(double[], double[], int[],
     *                int[], boolean, double)}
     * @param ticks   ticks in the window; {@code <= 0} bills nothing
     * @return the whole FE for the window, saturated at {@link Integer#MAX_VALUE}
     */
    public static int drainForWindow(double perTick, int ticks) {
        if (!(perTick > 0.0D) || ticks <= 0) return 0;
        double total = perTick * ticks;
        if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.round(total);
    }

    /**
     * Remaining endurance as {@code HH:MM}, for the HUD's drain line.
     *
     * <p>There is no honest answer while the cell is being filled (a "remaining" that grows is noise),
     * when it is already full, or when nothing is being spent, so those three states print an em dash
     * rather than a made-up number.</p>
     *
     * @param energyFe   charge now, in FE
     * @param capacityFe battery capacity, in FE
     * @param netPerTick <em>net</em> FE/t being spent (drain minus what solar/the grid put back)
     * @param charging   true while the airframe is on a grid or a pad that is putting FE in
     * @return {@code HH:MM}, or {@code "—"} when it cannot be known
     */
    public static String remainingHhMm(long energyFe, long capacityFe, double netPerTick, boolean charging) {
        if (charging || capacityFe <= 0L || energyFe >= capacityFe || !(netPerTick > 0.0D)) return "\u2014";
        double seconds = Math.max(0L, energyFe) / (netPerTick * 20.0D);
        if (!Double.isFinite(seconds)) return "\u2014";
        long whole = (long) Math.floor(seconds);
        long hours = whole / 3_600L;
        long minutes = whole % 3_600L / 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes);
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
