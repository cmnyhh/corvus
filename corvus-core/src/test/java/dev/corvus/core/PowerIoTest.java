package dev.corvus.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity table for {@link PowerIo}: one class, compiled and run by <b>both</b> builds.
 *
 * <p>Every number the two {@code power/io} packages use is pinned here — the 8,000 FE port buffer, the
 * 1,000 FE/t default, the 2,000 FE/t cable ceiling, the 32-block run — plus the two behavioural rules
 * (ordered fill, clamp of a same-tick window) whose absence made the 1.21.1 and 1.20.1 power grids
 * disagree before.</p>
 */
class PowerIoTest {

    @Test
    @DisplayName("port: the advertised numbers are 8,000 FE buffer / 1,000 FE/t default / 100..4000 range")
    void portNumbers() {
        assertEquals(8_000, PowerIo.PORT_BUFFER, "buffer");
        assertEquals(1_000, PowerIo.PORT_OUTPUT_DEFAULT, "default output");
        assertEquals(100, PowerIo.PORT_OUTPUT_MIN, "minimum");
        assertEquals(4_000, PowerIo.PORT_OUTPUT_MAX, "maximum");
        assertEquals(2_000, PowerIo.CABLE_RATE, "cable ceiling");
        assertEquals(32, PowerIo.CABLE_LENGTH, "run length");
    }

    @Test
    @DisplayName("port: a config value outside 100..4000 is clamped, never passed through")
    void portOutputClamp() {
        assertEquals(100, PowerIo.portOutput(-5));
        assertEquals(100, PowerIo.portOutput(0));
        assertEquals(100, PowerIo.portOutput(99));
        assertEquals(100, PowerIo.portOutput(100));
        assertEquals(1_000, PowerIo.portOutput(1_000));
        assertEquals(4_000, PowerIo.portOutput(4_000));
        assertEquals(4_000, PowerIo.portOutput(4_001));
        assertEquals(4_000, PowerIo.portOutput(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("port: per-tick intake is the smaller of free room and ceiling")
    void portIntake() {
        assertEquals(1_000, PowerIo.intake(0, 8_000, 1_000), "empty buffer takes one tick's worth");
        assertEquals(500, PowerIo.intake(7_500, 8_000, 1_000), "only 500 FE of room");
        assertEquals(0, PowerIo.intake(8_000, 8_000, 1_000), "full buffer takes nothing");
        assertEquals(0, PowerIo.intake(9_000, 8_000, 1_000), "forged NBT above capacity counts as full");
        assertEquals(0, PowerIo.intake(-10, 0, 1_000), "no capacity");
        assertEquals(0, PowerIo.intake(0, 8_000, 0), "no rate");
    }

    @Test
    @DisplayName("fill order: 1,000 FE into two 800 FE demands is 800 + 200, never 500 + 500")
    void sequentialFillsInOrder() {
        assertArrayEquals(new int[] {800, 200}, PowerIo.sequential(1_000, new int[] {800, 800}));
        assertArrayEquals(new int[] {1_000, 0, 0}, PowerIo.sequential(1_000, new int[] {1_000, 1_000, 1_000}));
        assertArrayEquals(new int[] {300, 700}, PowerIo.sequential(1_000, new int[] {300, 700}));
        assertArrayEquals(new int[] {0, 0}, PowerIo.sequential(0, new int[] {800, 800}), "no budget");
        assertArrayEquals(new int[] {0, 0}, PowerIo.sequential(-5, new int[] {800, 800}), "negative budget");
        assertArrayEquals(new int[] {0, 10, 0}, PowerIo.sequential(10, new int[] {0, 10, 0}), "zero demand is skipped");
        assertArrayEquals(new int[] {0, 10}, PowerIo.sequential(10, new int[] {-4, 10}), "negative demand counts as 0");
        assertEquals(0, PowerIo.sequential(10, new int[0]).length, "no demands");
        assertThrows(IllegalArgumentException.class, () -> PowerIo.sequential(10, null));
    }

    @Test
    @DisplayName("fill order: the plan never exceeds the budget or a single demand")
    void sequentialNeverOverspends() {
        int[] demands = {400, 900, 50, 1_000};
        int[] plan = PowerIo.sequential(1_500, demands);
        int total = 0;
        for (int i = 0; i < plan.length; i++) {
            assertTrue(plan[i] >= 0, "negative plan entry");
            assertTrue(plan[i] <= demands[i], "plan entry " + i + " exceeds its demand");
            total += plan[i];
        }
        assertEquals(1_500, total, "the whole budget is handed out exactly once");
    }

    @Test
    @DisplayName("cable: runs up to 32 blocks carry power, anything longer does not")
    void runLength() {
        assertTrue(PowerIo.withinRun(0), "consumer next to the first cell");
        assertTrue(PowerIo.withinRun(1));
        assertTrue(PowerIo.withinRun(32), "32 blocks is still inside the run");
        assertFalse(PowerIo.withinRun(33), "33 blocks is too long");
        assertFalse(PowerIo.withinRun(40));
        assertFalse(PowerIo.withinRun(-1), "no distance is not a run");
    }

    @Test
    @DisplayName("window: a second caller in the same tick gets the remainder, never a negative allowance")
    void tickWindow() {
        assertEquals(1_000, PowerIo.remainder(1_000, 0));
        assertEquals(200, PowerIo.remainder(1_000, 800));
        assertEquals(0, PowerIo.remainder(1_000, 1_000));
        assertEquals(0, PowerIo.remainder(1_000, 1_500), "overuse clamps to 0");
        assertEquals(1_000, PowerIo.remainder(1_000, -3), "a negative used-count counts as 0");
        assertEquals(0, PowerIo.remainder(0, 0), "a zero ceiling never pays out");
    }
}
