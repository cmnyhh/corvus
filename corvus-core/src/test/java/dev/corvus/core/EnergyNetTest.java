package dev.corvus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity table for {@link EnergyNet}: one class, compiled and run by <b>both</b> builds.
 *
 * <p>It pins the network budget (2,000 FE/t default, 100..8000), the 32-cell source-to-sink path, the
 * five sink priorities and the two rules the loaders depend on: a proportional split inside one
 * priority level, and a plan that is always balanced ({@code sum(spent) == sum(granted)}) with no
 * self-loop. The 1.21.1 GameTest and the 1.20.1 smoke assert the same behaviour through real blocks;
 * this table is the arithmetic they both run on.</p>
 */
class EnergyNetTest {

    @Test
    @DisplayName("numbers: 2,000 FE/t default, 100..8000 range, 32-cell path, five priorities")
    void numbers() {
        assertEquals(2_000, EnergyNet.THROUGHPUT_DEFAULT, "default throughput");
        assertEquals(100, EnergyNet.THROUGHPUT_MIN);
        assertEquals(8_000, EnergyNet.THROUGHPUT_MAX);
        assertEquals(32, EnergyNet.MAX_LENGTH);
        assertEquals(0, EnergyNet.PRIORITY_TOWER, "tower first");
        assertEquals(1, EnergyNet.PRIORITY_BATTERY);
        assertEquals(2, EnergyNet.PRIORITY_MILL);
        assertEquals(3, EnergyNet.PRIORITY_DOCK);
        assertEquals(4, EnergyNet.PRIORITY_PORT, "port last");
    }

    @Test
    @DisplayName("throughput: a config value outside 100..8000 is clamped, never passed through")
    void throughputClamp() {
        assertEquals(100, EnergyNet.clampThroughput(-1));
        assertEquals(100, EnergyNet.clampThroughput(0));
        assertEquals(100, EnergyNet.clampThroughput(99));
        assertEquals(100, EnergyNet.clampThroughput(100));
        assertEquals(2_000, EnergyNet.clampThroughput(2_000));
        assertEquals(8_000, EnergyNet.clampThroughput(8_000));
        assertEquals(8_000, EnergyNet.clampThroughput(8_001));
        assertEquals(8_000, EnergyNet.clampThroughput(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("limit: one network carries 2,000 FE/t, N networks N x 2,000, and 0 networks nothing")
    void limitPerNetwork() {
        assertEquals(0, EnergyNet.limit(0));
        assertEquals(0, EnergyNet.limit(-4));
        assertEquals(2_000, EnergyNet.limit(1));
        assertEquals(4_000, EnergyNet.limit(2));
        assertEquals(6_000, EnergyNet.limit(3));
        assertEquals(Integer.MAX_VALUE, EnergyNet.limit(Integer.MAX_VALUE), "the product saturates");
    }

    @Test
    @DisplayName("length: 32 cells still carries, 33 does not")
    void lengthRule() {
        assertTrue(EnergyNet.withinRun(0));
        assertTrue(EnergyNet.withinRun(32));
        assertFalse(EnergyNet.withinRun(33));
        assertFalse(EnergyNet.withinRun(40));
        assertFalse(EnergyNet.withinRun(-1));
    }

    @Test
    @DisplayName("allocate: null, empty or mismatched inputs give an empty plan instead of throwing")
    void unusableInputs() {
        assertSame(EnergyNet.Plan.EMPTY, EnergyNet.allocate(null, new int[1], new int[1], 100));
        assertSame(EnergyNet.Plan.EMPTY, EnergyNet.allocate(new int[1], null, new int[1], 100));
        assertSame(EnergyNet.Plan.EMPTY, EnergyNet.allocate(new int[1], new int[1], null, 100));
        assertSame(EnergyNet.Plan.EMPTY, EnergyNet.allocate(new int[0], new int[0], new int[0], 100));
        assertSame(EnergyNet.Plan.EMPTY, EnergyNet.allocate(new int[2], new int[1], new int[2], 100));
        assertSame(EnergyNet.Plan.EMPTY, EnergyNet.allocate(new int[2], new int[2], new int[1], 100));
    }

    @Test
    @DisplayName("allocate: no budget, no offer or no demand moves nothing")
    void nothingToDo() {
        int[] offer = {500, 0};
        int[] demand = {0, 300};
        int[] priority = {0, 1};
        assertEquals(0, EnergyNet.allocate(offer, demand, priority, 0).total(), "zero budget");
        assertEquals(0, EnergyNet.allocate(offer, demand, priority, -50).total(), "negative budget");
        assertEquals(0, EnergyNet.allocate(new int[] {0, 0}, demand, priority, 2_000).total(), "no supply");
        assertEquals(0, EnergyNet.allocate(offer, new int[] {0, 0}, priority, 2_000).total(), "no demand");
    }

    @Test
    @DisplayName("allocate: one source feeds one sink, capped by the network budget")
    void singlePair() {
        EnergyNet.Plan plan = EnergyNet.allocate(new int[] {5_000, 0}, new int[] {0, 9_000}, new int[] {0, 4}, 2_000);
        assertEquals(2_000, plan.granted(1), "sink takes the whole 2,000 FE/t window");
        assertEquals(2_000, plan.spent(0), "and the source pays exactly that");
        assertEquals(2_000, plan.total());
    }

    @Test
    @DisplayName("allocate: priority decides the order, the loser keeps what is left")
    void priorityOrder() {
        int[] offer = {10_000, 0, 0, 0};
        int[] demand = {0, 500, 500, 500};
        int[] priority = {0, EnergyNet.PRIORITY_MILL, EnergyNet.PRIORITY_TOWER, EnergyNet.PRIORITY_PORT};
        EnergyNet.Plan plan = EnergyNet.allocate(offer, demand, priority, 600);
        assertEquals(500, plan.granted(2), "tower (priority 0) is served first");
        assertEquals(100, plan.granted(1), "mill (priority 2) takes the remaining 100");
        assertEquals(0, plan.granted(3), "port (priority 4) gets nothing");
        assertEquals(600, plan.total());
    }

    @Test
    @DisplayName("allocate: inside one priority level the budget follows the deficit ratio")
    void proportionalWithinALevel() {
        int[] offer = {10_000, 0, 0};
        int[] demand = {0, 100, 300};
        int[] priority = {0, EnergyNet.PRIORITY_BATTERY, EnergyNet.PRIORITY_BATTERY};
        EnergyNet.Plan plan = EnergyNet.allocate(offer, demand, priority, 200);
        assertEquals(50, plan.granted(1), "100 of 400 deficit");
        assertEquals(150, plan.granted(2), "300 of 400 deficit");
        assertEquals(200, plan.total());

        // Proportional, not even: 510 FE over deficits 20 + 1,000 is 10 + 500. Neither sink is filled
        // beyond what it asked for, and the total is exactly the budget.
        EnergyNet.Plan mixed = EnergyNet.allocate(offer, new int[] {0, 20, 1_000},
                new int[] {0, EnergyNet.PRIORITY_BATTERY, EnergyNet.PRIORITY_BATTERY}, 510);
        assertEquals(10, mixed.granted(1), "20 of 1,020 deficit");
        assertEquals(500, mixed.granted(2), "1,000 of 1,020 deficit");
        assertEquals(510, mixed.total(), "the whole budget is handed out");
    }

    @Test
    @DisplayName("allocate: a block never pays for its own grant, so a lone battery moves nothing")
    void noSelfLoop() {
        EnergyNet.Plan alone = EnergyNet.allocate(new int[] {100}, new int[] {100}, new int[] {0}, 2_000);
        assertEquals(0, alone.total(), "offering into your own demand is not a transfer");
        assertEquals(0, alone.spent(0));
        assertEquals(0, alone.granted(0));
    }

    @Test
    @DisplayName("allocate: two batteries exchange instead of charging themselves")
    void batteriesSwapWithAPeer() {
        int[] offer = {100, 100};
        int[] demand = {200, 200};
        int[] priority = {EnergyNet.PRIORITY_BATTERY, EnergyNet.PRIORITY_BATTERY};
        EnergyNet.Plan plan = EnergyNet.allocate(offer, demand, priority, 2_000);
        assertEquals(100, plan.granted(0), "each battery takes 100 from its peer");
        assertEquals(100, plan.granted(1));
        assertEquals(100, plan.spent(0), "battery 0 paid battery 1, never itself");
        assertEquals(100, plan.spent(1), "battery 1 paid battery 0, never itself");
        assertEquals(200, plan.total());
        assertEquals(plan.total(), plan.spent(0) + plan.spent(1), "nothing created, nothing lost");
    }

    @Test
    @DisplayName("allocate: every plan is balanced and stays inside the budget and the offers")
    void balancedPlans() {
        int[] offer = {900, 4, 0, 300};
        int[] demand = {0, 700, 250, 120};
        int[] priority = {0, EnergyNet.PRIORITY_BATTERY, EnergyNet.PRIORITY_MILL, EnergyNet.PRIORITY_PORT};
        for (int budget : new int[] {0, 1, 13, 400, 1_204, 2_000, 100_000}) {
            EnergyNet.Plan plan = EnergyNet.allocate(offer, demand, priority, budget);
            int granted = 0;
            int spent = 0;
            for (int i = 0; i < 4; i++) {
                granted += plan.granted(i);
                spent += plan.spent(i);
                assertTrue(plan.granted(i) <= demand[i], "grant above demand at budget " + budget);
                assertTrue(plan.spent(i) <= offer[i], "spend above offer at budget " + budget);
            }
            assertEquals(granted, spent, "energy created or lost at budget " + budget);
            assertEquals(granted, plan.total(), "total disagrees with the grants at budget " + budget);
            assertTrue(plan.total() <= Math.max(0, budget), "plan above budget " + budget);
        }
    }

    @Test
    @DisplayName("allocate: a sink that only itself could pay does not block the next level")
    void unpayableSinkDoesNotStarveOthers() {
        // Endpoint 0 is a battery with 500 FE and 500 FE of room: it may only be paid by endpoint 1.
        // Endpoint 2 (the mill) has no offer of its own and must still be served after the battery.
        int[] offer = {500, 1_000, 0};
        int[] demand = {500, 0, 300};
        int[] priority = {EnergyNet.PRIORITY_BATTERY, 0, EnergyNet.PRIORITY_MILL};
        EnergyNet.Plan plan = EnergyNet.allocate(offer, demand, priority, 2_000);
        assertEquals(500, plan.granted(0), "the battery charges from the generator next to it");
        assertEquals(300, plan.granted(2), "the mill is not starved by the battery level");
        assertEquals(800, plan.total());
        assertEquals(500, plan.spent(1), "the generator paid the battery's charge");
        assertEquals(300, plan.spent(0), "the battery paid the mill; it never paid itself");
        assertEquals(plan.granted(0) + plan.granted(2), plan.spent(0) + plan.spent(1), "balanced plan");
    }

    @Test
    @DisplayName("allocate: negative offers and demands count as zero instead of moving energy backwards")
    void negativesAreZero() {
        EnergyNet.Plan plan = EnergyNet.allocate(new int[] {-500, 1_000}, new int[] {200, -300}, new int[] {0, 1}, 2_000);
        assertEquals(0, plan.spent(0), "a negative offer is not a source");
        assertEquals(0, plan.granted(1), "a negative demand is not a sink");
        assertEquals(200, plan.granted(0), "the real demand is still served");
        assertEquals(200, plan.spent(1), "by the real source");
        assertEquals(200, plan.total());
    }
}
