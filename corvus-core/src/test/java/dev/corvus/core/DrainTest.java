package dev.corvus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The idle/active drain table, compiled and run by <b>both</b> builds (1.21.1/NeoForge and
 * 1.20.1/Forge) from {@code corvus-core/src/test}.
 *
 * <p>The complaint this pins down is "the drone burns its whole cell while it is doing nothing": every
 * module used to draw its {@code drain_watts} from the moment it was fitted, whether or not it was
 * being used. The rule now is one standby rate and one in-use rate per module, plus a small deployment
 * baseline, all scaled once by the server's {@code drainMultiplier}.</p>
 *
 * <p>The last test is the endurance claim itself, expressed as arithmetic ({@code capacity / net FE per
 * second}) so a balance change cannot pass by waiting in real time. It is deliberately computed for the
 * <em>smallest</em> cell in the game after the rebalance (CVS R-1, 300,000 FE), so a "typical loadout"
 * endurance figure quoted for one battery is true for every battery at or above it.</p>
 */
class DrainTest {

    /** Smallest cell after the rebalance: CVS R-1, 300,000 FE (was 150,000). */
    private static final long SMALLEST_CELL = 300_000L;
    /** One settlement window, the granularity the power transaction actually bills at. */
    private static final int SECOND = 20;

    // ------------------------------------------------------------------ (1) deployment baseline

    @Test
    @DisplayName("drain: an idle deployed airframe costs 0.5 FE/t, scaled by the multiplier")
    void idleBaselineIsHalfAWatt() {
        double noModules = HardwareRules.drainPerTick(new double[0], new double[0], new int[0], new int[0],
                true, HardwareRules.DEFAULT_DRAIN_MULTIPLIER);
        assertEquals(0.3D, noModules, 1e-9D, "0.5 FE/t x 0.6 = 0.3 FE/t with every module switched off");
        assertEquals(0.5D, HardwareRules.baselinePerTick(true), 1e-9D, "the baseline itself is 0.5 FE/t");
        assertEquals(0.0D, HardwareRules.baselinePerTick(false), 1e-9D, "nothing is paid while not deployed");
        assertEquals(0.0D, HardwareRules.drainPerTick(new double[0], new double[0], new int[0], new int[0],
                false, HardwareRules.DEFAULT_DRAIN_MULTIPLIER), 1e-9D, "an undeployed airframe is free");

        // Every module fitted and online but switched off in the wheel (onlineFlags = 0): still 0.3.
        double[] idle = {3D, 0D, 0D, 0D, 2D};
        double[] active = {14D, 6D, 5D, 4D, 2D};
        assertEquals(0.3D, HardwareRules.drainPerTick(idle, active, new int[] {0, 0, 0, 0, 0},
                new int[] {0, 0, 0, 0, 0}, true, 0.6D), 1e-9D, "switched-off modules draw nothing");
        assertEquals(6, HardwareRules.drainForWindow(0.3D, SECOND), "0.3 FE/t for one second is 6 FE");
    }

    // ------------------------------------------------------------------ (2) scan: standby 0, pulse > 0

    @Test
    @DisplayName("drain: a scanner costs nothing on standby and its active rate only during the sweep")
    void scanPaysOnlyWhileScanning() {
        double[] idle = {0D};
        double[] active = {6D};
        assertEquals(0.0D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {0}, false, 1.0D),
                1e-9D, "scan_mk1 standby is 0 FE/t");
        assertEquals(6.0D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {1}, false, 1.0D),
                1e-9D, "scan_mk1 during a pulse is its drain_active");
        assertEquals(3.6D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {1}, false, 0.6D),
                1e-9D, "and the configured multiplier still applies");
        assertEquals(72, HardwareRules.drainForWindow(3.6D, SECOND), "3.6 FE/t for 20 ticks");

        // A switched-off scanner is not charged even if it is somehow flagged as sweeping.
        assertEquals(0.0D, HardwareRules.drainPerTick(idle, active, new int[] {0}, new int[] {1}, false, 1.0D),
                1e-9D, "the wheel switch beats the active flag");
    }

    // ------------------------------------------------------------------ (3) combat: 3 standby, active in combat

    @Test
    @DisplayName("drain: a combat bus stands by at 3 FE/t and bills its active rate while engaged")
    void combatStandbyAndEngaged() {
        double[] idle = {3D};
        double[] active = {14D};
        assertEquals(1.8D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {0}, false, 0.6D),
                1e-9D, "combat_mk1 standby is 3 FE/t x 0.6");
        assertEquals(3.0D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {0}, false, 1.0D),
                1e-9D, "standby without the multiplier");
        assertEquals(14.0D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {1}, false, 1.0D),
                1e-9D, "engaged bills drain_active, not standby + active");
        assertEquals(8.4D, HardwareRules.drainPerTick(idle, active, new int[] {1}, new int[] {1}, false, 0.6D),
                1e-9D, "engaged at the default multiplier");
        assertEquals(36, HardwareRules.drainForWindow(1.8D, SECOND), "3 x 0.6 FE/t for one second");
    }

    // ------------------------------------------------------------------ (4) legacy data packs

    @Test
    @DisplayName("drain: a pack with only drain_watts bills it as active and takes the family standby default")
    void legacyPackFallsBack() {
        assertEquals(14, HardwareRules.activeWatts(null, 14), "drain_active falls back to drain_watts");
        assertEquals(9, HardwareRules.activeWatts(9, 14), "an explicit drain_active wins");
        assertThrows(IllegalArgumentException.class, () -> HardwareRules.activeWatts(null, null),
                "a definition with no wattage at all is a data error");

        assertEquals(0, HardwareRules.idleDefault("scan"), "scan standby default");
        assertEquals(0, HardwareRules.idleDefault("carry"), "carry standby default");
        assertEquals(0, HardwareRules.idleDefault("medic"), "medic standby default");
        assertEquals(0, HardwareRules.idleDefault("light"), "light standby default");
        assertEquals(0, HardwareRules.idleDefault("solar"), "solar standby default");
        assertEquals(3, HardwareRules.idleDefault("combat"), "combat standby default");
        assertEquals(1, HardwareRules.idleDefault("link"), "link stands by at 1 FE/t to keep the channel");
        assertEquals(0, HardwareRules.idleDefault(null), "an unknown family coasts");

        assertEquals(3, HardwareRules.idleWatts(null, "combat"), "absent drain_idle takes the default");
        assertEquals(5, HardwareRules.idleWatts(5, "combat"), "a declared drain_idle wins");
        assertEquals(0, HardwareRules.idleWatts(-4, "combat"), "a negative wattage cannot refund FE");
        assertEquals(10_000, HardwareRules.idleWatts(99_999, "scan"), "clamped to the parser ceiling");

        // The whole legacy module, run through the shared table: standby = default, active = drain_watts.
        String family = "combat";
        int legacy = 14;
        double standby = HardwareRules.idleWatts(null, family);
        double inUse = HardwareRules.activeWatts(null, legacy);
        assertEquals(1.8D, HardwareRules.drainPerTick(new double[] {standby}, new double[] {inUse},
                new int[] {1}, new int[] {0}, false, 0.6D), 1e-9D, "legacy standby 3 FE/t x 0.6");
        assertEquals(8.4D, HardwareRules.drainPerTick(new double[] {standby}, new double[] {inUse},
                new int[] {1}, new int[] {1}, false, 0.6D), 1e-9D, "legacy active 14 FE/t x 0.6");
    }

    // ------------------------------------------------------------------ (5) multiplier bounds

    @Test
    @DisplayName("drain: the multiplier clamps to 0.25..3.0 and NaN reads as the 0.6 default")
    void multiplierBounds() {
        assertEquals(0.25D, HardwareRules.clampMultiplier(0.25D), 1e-9D, "0.25 is the floor and is kept");
        assertEquals(3.0D, HardwareRules.clampMultiplier(3.0D), 1e-9D, "3.0 is the ceiling and is kept");
        assertEquals(0.25D, HardwareRules.clampMultiplier(0.01D), 1e-9D, "below the floor");
        assertEquals(3.0D, HardwareRules.clampMultiplier(99D), 1e-9D, "above the ceiling");
        assertEquals(0.6D, HardwareRules.clampMultiplier(Double.NaN), 1e-9D, "NaN is not a multiplier");
        assertEquals(3.0D, HardwareRules.clampMultiplier(Double.POSITIVE_INFINITY), 1e-9D, "infinity clamps high");
        assertEquals(0.25D, HardwareRules.clampMultiplier(Double.NEGATIVE_INFINITY), 1e-9D, "and low");
        assertEquals(0.6D, HardwareRules.DEFAULT_DRAIN_MULTIPLIER, 1e-9D, "the shipped default");

        double[] idle = {3D, 0D, 0D, 0D};
        double[] active = {14D, 6D, 5D, 2D};
        int[] online = {1, 1, 1, 1};
        int[] use = {0, 0, 0, 0};
        assertEquals(0.75D, HardwareRules.drainPerTick(idle, active, online, use, false, 0.25D), 1e-9D,
                "3 FE/t standby at the floor multiplier");
        assertEquals(9.0D, HardwareRules.drainPerTick(idle, active, online, use, false, 3.0D), 1e-9D,
                "3 FE/t standby at the ceiling multiplier");
        assertEquals(15, HardwareRules.drainForWindow(0.75D, SECOND), "0.75 FE/t x 20 = 15 FE");
        assertEquals(180, HardwareRules.drainForWindow(9.0D, SECOND), "9 FE/t x 20 = 180 FE");
    }

    // ------------------------------------------------------------------ window rounding

    @Test
    @DisplayName("drain: one settlement window rounds once and never invents FE")
    void windowRounding() {
        assertEquals(0, HardwareRules.drainForWindow(0.3D, 0), "a zero-length window bills nothing");
        assertEquals(0, HardwareRules.drainForWindow(0.3D, -20), "a negative window bills nothing");
        assertEquals(0, HardwareRules.drainForWindow(0D, 20), "a free second");
        assertEquals(0, HardwareRules.drainForWindow(-5D, 20), "a negative rate cannot refund");
        assertEquals(1, HardwareRules.drainForWindow(0.03D, 20), "0.6 FE rounds up to the one FE it costs");
        assertEquals(10, HardwareRules.drainForWindow(0.5D, 20), "0.5 FE/t is 10 FE a second");
        assertEquals(Integer.MAX_VALUE, HardwareRules.drainForWindow(1e12D, 20), "saturated, not wrapped");
        assertEquals(19_440, HardwareRules.drainForWindow(972D, 20), "972 FE/t is not a real airframe, but it adds up");
    }

    // ------------------------------------------------------------------ HUD endurance line

    @Test
    @DisplayName("hud: remaining time is HH:MM, and an em dash while charging, full or free")
    void remainingTime() {
        assertEquals("00:01", HardwareRules.remainingHhMm(3_600L, 10_000L, 3D, false),
                "3,600 FE at 3 FE/t (60 FE/s) is one minute");
        assertEquals("03:00", HardwareRules.remainingHhMm(1_080_000L, 1_200_000L, 5D, false),
                "1,080,000 FE at 5 FE/t (100 FE/s) is three hours");
        assertEquals("\u2014", HardwareRules.remainingHhMm(450_000L, 450_000L, 6D, false), "a full cell");
        assertEquals("\u2014", HardwareRules.remainingHhMm(300_000L, 450_000L, 6D, true), "while charging");
        assertEquals("\u2014", HardwareRules.remainingHhMm(300_000L, 450_000L, 0D, false), "nothing being spent");
        assertEquals("\u2014", HardwareRules.remainingHhMm(300_000L, 0L, 6D, false), "no battery");
        assertEquals("\u2014", HardwareRules.remainingHhMm(300_000L, 450_000L, -3D, false),
                "a net gain has no endurance figure");
        assertEquals("00:00", HardwareRules.remainingHhMm(1L, 450_000L, 100D, false), "under a minute reads 00:00");
    }

    // ------------------------------------------------------------------ the endurance claim

    @Test
    @DisplayName("smoke: the typical loadout lasts over 3 h switched off and over 1 h cruising")
    void typicalLoadoutEndurance() {
        // combat_mk1 + scan_mk1 + carry_mk1 + light_mk1 (the four families a beginner fits first).
        double[] idle = {HardwareRules.idleWatts(null, "combat"), 0D, 0D, 0D};
        double[] active = {14D, 6D, 5D, 2D};
        int[] online = {1, 1, 1, 1};

        // (a) Every module switched off in the wheel: only the deployment baseline is billed.
        double idlePerTick = HardwareRules.drainPerTick(idle, active, new int[] {0, 0, 0, 0},
                new int[] {0, 0, 0, 0}, true, HardwareRules.DEFAULT_DRAIN_MULTIPLIER);
        long idleSeconds = enduranceSeconds(SMALLEST_CELL, idlePerTick);
        assertTrue(idleSeconds >= 3L * 3_600L, "switched-off standby is only " + hours(idleSeconds) + " h");

        // (b) Daily cruise: the scanner and the hold are idle, the weapon bus stands by and the lamp is
        // on (the worst honest cruise, i.e. flying at night with the light on).
        double cruisePerTick = HardwareRules.drainPerTick(idle, active, online, new int[] {0, 0, 0, 1},
                true, HardwareRules.DEFAULT_DRAIN_MULTIPLIER);
        long cruiseSeconds = enduranceSeconds(SMALLEST_CELL, cruisePerTick);
        assertTrue(cruiseSeconds >= 3_600L, "a day of cruising is only " + minutes(cruiseSeconds) + " min");

        // The two figures the report quotes, for the reference cell (Rustnail G1, 450,000 FE).
        double burnedPerSecondBefore = (14D + 6D + 5D + 2D) * 20D;   // 540 FE/s, every module always on
        assertEquals(27D, burnedPerSecondBefore / 20D, 1e-9D, "the old always-on loadout was 27 FE/t");
        assertTrue(cruisePerTick < 27D / 4D, "cruising must be at least four times cheaper than the old always-on draw");
        assertTrue(idlePerTick < cruisePerTick, "an all-off drone must be cheaper than a cruising one");
        assertFalse(HardwareRules.remainingHhMm(450_000L, 450_000L, 0D, true).isEmpty(), "the HUD always prints something");
    }

    /** Seconds until empty for a cell of {@code capacity} FE spending {@code perTick} FE/t. */
    private static long enduranceSeconds(long capacity, double perTick) {
        return HardwareRules.drainForWindow(perTick, SECOND) <= 0 ? Long.MAX_VALUE
                : (long) (capacity / (perTick * 20.0D));
    }

    private static String hours(long seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", seconds / 3_600.0D);
    }

    private static String minutes(long seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", seconds / 60.0D);
    }
}
