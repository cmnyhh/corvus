package dev.corvus.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wireless-grid contract table, compiled and run by both builds.
 *
 * <p>These are the exact numbers the two loaders' smoke suites assert against the real world
 * (tower network hop cost, 5-hop reach, the 500 FE/t shared budget and its priority order), so a
 * change to the shared rule shows up here before it shows up in a game.</p>
 */
class GridCoreTest {

    @Test
    @DisplayName("grid: hops cost 0.95^n, three hops are 0.857")
    void hopCosts() {
        assertEquals(1.0D, Grid.hopsCost(0), 1e-12, "direct connection keeps 100%");
        assertEquals(1.0D, Grid.hopsCost(-2), 1e-12, "negative hop count is a direct connection");
        assertEquals(0.95D, Grid.hopsCost(1), 1e-12, "one hop");
        assertEquals(0.95D * 0.95D, Grid.hopsCost(2), 1e-12, "two hops");
        assertEquals(0.857375D, Grid.hopsCost(3), 1e-12, "three hops = 0.95^3");
        assertEquals(0.86D, Math.round(Grid.hopsCost(3) * 100.0D) / 100.0D, 1e-12, "rounded to two decimals");
        assertEquals(0.7737809375D, Grid.hopsCost(5), 1e-12, "five hops = 0.95^5");
        assertEquals(0.85D * 0.85D * 0.85D, Grid.hopsCost(3, 0.85D), 1e-12, "config efficiency is the base");
        assertEquals(1.0D, Grid.hopsCost(3, 1.5D), 1e-12, "efficiency above 1 is clamped (no free energy)");
        assertEquals(0.614125D, Grid.hopsCost(3, 0.1D), 1e-12, "efficiency below 0.85 is clamped");
    }

    @Test
    @DisplayName("grid: five hops reach, six do not")
    void reach() {
        for (int hops = 0; hops <= 5; hops++) assertTrue(Grid.reachable(hops), "hop " + hops + " must be usable");
        assertFalse(Grid.reachable(6), "six hops is out of range");
        assertFalse(Grid.reachable(-1), "negative hop count is not a route");
        assertEquals(0, Grid.delivered(1_000, 6, 0.95D), "six-hop delivery is zero");
        assertEquals(950, Grid.delivered(1_000, 1, 0.95D), "one-hop delivery");
        assertEquals(857, Grid.delivered(1_000, 3, 0.95D), "three-hop delivery");
    }

    @Test
    @DisplayName("grid: one tower's 500 FE/t is shared by priority, partial grants instead of off")
    void priorityBudget() {
        int[] demands = {200, 200, 200};
        int[] priorities = {Grid.PRIORITY_DRONE, Grid.PRIORITY_MILL, Grid.PRIORITY_DOCK};
        assertArrayEquals(new int[] {200, 200, 100}, Grid.allocate(500, demands, priorities),
                "drone 200 + mill 200 + dock the remaining 100 (slowed, not off)");
        assertEquals(500, sum(Grid.allocate(500, demands, priorities)), "the whole budget is used");

        // Demand above the budget: equal split of what is left inside a priority level.
        int[] wants = {400, 400, 400};
        int[] same = {2, 2, 2};
        assertArrayEquals(new int[] {167, 167, 166}, Grid.allocate(500, wants, same), "water-fill share");
        assertArrayEquals(new int[] {400, 100, 0}, Grid.allocate(500, wants, priorities),
                "drone satisfied, mill takes what is left, dock gets nothing");
        assertArrayEquals(new int[] {0, 0, 0}, Grid.allocate(0, demands, priorities), "zero budget");
        assertArrayEquals(new int[] {0, 0, 0}, Grid.allocate(-10, demands, priorities), "negative budget");
        assertArrayEquals(new int[0], Grid.allocate(500, new int[0], new int[0]), "no devices -> no grants");
        assertEquals(0, Grid.allocate(500, new int[] {1}, new int[0]).length, "mismatched arrays are rejected");
    }

    @Test
    @DisplayName("grid: config ranges are clamped, not trusted")
    void configClamps() {
        assertEquals(8, Grid.clampRadius(0));
        assertEquals(64, Grid.clampRadius(999));
        assertEquals(32, Grid.clampRadius(32));
        assertEquals(100, Grid.clampThroughput(0));
        assertEquals(2_000, Grid.clampThroughput(50_000));
        assertEquals(500, Grid.clampThroughput(500));
        assertEquals(0.85D, Grid.clampEfficiency(0.1D), 1e-12);
        assertEquals(1.0D, Grid.clampEfficiency(4.0D), 1e-12);
        assertEquals(0.95D, Grid.clampEfficiency(0.95D), 1e-12);
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
