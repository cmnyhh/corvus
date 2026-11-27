package dev.corvus.core;

/**
 * The cable network as a <em>general conductor</em>: every one of our FE blocks that touches a cable is
 * an endpoint, and each tick one allocation decides who gives and who takes.
 *
 * <p>This replaces the "a cable run only feeds a {@code power_port}" model, in which a battery, a
 * generator and a wireless tower on the same run could not see each other at all. The rules live here,
 * once, and both builds (1.21.1/NeoForge and 1.20.1/Forge) compile this file — the loader-side
 * {@code EnergyLink} only walks the world, asks its block entities for plain integers and moves the FE
 * the plan names.</p>
 *
 * <h2>One network, one budget, one plan</h2>
 *
 * <p>A network is a connected component of cable blocks. Every cell of the component may reach any
 * other cell, but <b>a source only feeds a sink that is at most {@link #MAX_LENGTH} cells away</b>
 * through the component ({@link #withinRun(int)}); a longer path carries nothing, which is what stops
 * a 40-block run from acting like an infinite wire. Per tick exactly one plan is made for the whole
 * component — no per-block pull loop, so the same FE cannot be handed out twice — and the plan's total
 * is capped by the network budget, {@code cableThroughput} FE/t (default
 * {@link #THROUGHPUT_DEFAULT}, advertised range {@link #THROUGHPUT_MIN}..{@link #THROUGHPUT_MAX}).</p>
 *
 * <h2>Who is served first</h2>
 *
 * <p>Sink priorities are fixed constants, served in ascending order:</p>
 *
 * <pre>
 *   {@link #PRIORITY_TOWER} 0   a wireless tower's buffer (keep the grid alive first)
 *   {@link #PRIORITY_BATTERY} 1  a battery bank charging
 *   {@link #PRIORITY_MILL} 2    an ore mill
 *   {@link #PRIORITY_DOCK} 3    a charging dock
 *   {@link #PRIORITY_PORT} 4    a power port feeding another mod
 * </pre>
 *
 * <p>Inside one priority level the remaining budget is shared <b>in proportion to each sink's
 * remaining deficit</b> (not evenly, and not first-come): 510 FE over two sinks that still want 20 and
 * 1,000 FE is 10 + 500, never 255 + 255 — a sink that asks for less cannot be over-filled and gets the
 * same share of the shortfall as its peers. Whole FE only; the leftover of the integer division is
 * handed out one FE at a time in index order.</p>
 *
 * <h2>No self-loop, nothing created</h2>
 *
 * <p>A battery bank is both source and sink: it may charge from the network <em>and</em> discharge into
 * it, but it can never pay for its own grant. {@link #allocate} pairs every granted FE with a
 * <b>different</b> endpoint's offer, so a lone block offering into its own demand transfers nothing
 * rather than rotating energy through itself (which would look like a full network while moving no
 * energy anywhere). Every plan is balanced: {@code sum(spent) == sum(granted)} and
 * {@code sum(granted) <= budget}, so a loader that applies it literally can neither create nor destroy
 * FE — the smoke tests assert exactly that.</p>
 */
public final class EnergyNet {
    /** Default network through end, FE/t ({@code cableThroughput}). */
    public static final int THROUGHPUT_DEFAULT = 2_000;
    /** Lowest configurable network throughput, FE/t. */
    public static final int THROUGHPUT_MIN = 100;
    /** Highest configurable network throughput, FE/t. */
    public static final int THROUGHPUT_MAX = 8_000;
    /** Longest source-to-sink path through the component, in cells; longer does not carry. */
    public static final int MAX_LENGTH = 32;
    /** Hard cap on the cells one network may hold, so a pathological build cannot stall a tick. */
    public static final int CELL_LIMIT = 2_048;

    /** Sink priority: a wireless tower's buffer, refilled first. */
    public static final int PRIORITY_TOWER = 0;
    /** Sink priority: a battery bank charging. */
    public static final int PRIORITY_BATTERY = 1;
    /** Sink priority: an ore mill. */
    public static final int PRIORITY_MILL = 2;
    /** Sink priority: a charging dock. */
    public static final int PRIORITY_DOCK = 3;
    /** Sink priority: a power port feeding another mod; served last. */
    public static final int PRIORITY_PORT = 4;

    private EnergyNet() {}

    /**
     * One allocation: what each endpoint gives ({@code spent}) and what each endpoint takes
     * ({@code granted}), index-aligned with the arrays handed to {@link #allocate}.
     *
     * <p>Both arrays are fresh copies owned by the caller; the constructor copies nothing, so a plan is
     * cheap and safe to read but should not be mutated after it leaves {@code allocate}.</p>
     */
    public static final class Plan {
        /** An empty plan: no endpoints, nothing moved. */
        public static final Plan EMPTY = new Plan(new int[0], new int[0], 0);

        private final int[] granted;
        private final int[] spent;
        private final int total;

        private Plan(int[] granted, int[] spent, int total) {
            this.granted = granted;
            this.spent = spent;
            this.total = total;
        }

        /** FE handed to endpoint {@code index} this tick. */
        public int granted(int index) {
            return index >= 0 && index < granted.length ? granted[index] : 0;
        }

        /** FE taken from endpoint {@code index} this tick, always from somebody else's grant. */
        public int spent(int index) {
            return index >= 0 && index < spent.length ? spent[index] : 0;
        }

        /** Endpoints the plan covers (same length as the arrays {@link #allocate} was given). */
        public int size() { return granted.length; }

        /** Total FE this plan moves: equal to the sum of {@link #granted} and of {@link #spent}. */
        public int total() { return total; }

        /** Whether nothing moved at all. */
        public boolean isEmpty() { return total <= 0; }
    }

    /**
     * Clamps a configured network throughput to the advertised {@link #THROUGHPUT_MIN}..
     * {@link #THROUGHPUT_MAX} range, so a hand-edited config file cannot make the block report one
     * number and move another.
     */
    public static int clampThroughput(int configured) {
        return Math.max(THROUGHPUT_MIN, Math.min(THROUGHPUT_MAX, configured));
    }

    /**
     * Whether a path of {@code distance} cells still carries power.
     *
     * @param distance source-to-sink cell count; {@code 0} (the sink touches a cell next to the source)
     *                 is inside the run
     * @return true for {@code 0..32}, false for anything longer
     */
    public static boolean withinRun(int distance) {
        return distance >= 0 && distance <= MAX_LENGTH;
    }

    /**
     * Total FE/t that {@code count} networks may move between them.
     *
     * <p>Each component has its own {@link #THROUGHPUT_DEFAULT} window; this is the aggregate a level
     * may move when it holds {@code count} components, used by the loader to keep the sum of all
     * networks in one dimension bounded (and to print an honest total in the status line). A
     * non-positive count has nothing to carry, and the product saturates rather than overflowing.</p>
     *
     * @param count number of cable networks; {@code <= 0} yields 0
     * @return {@code count * THROUGHPUT_DEFAULT}, clamped to {@link Integer#MAX_VALUE}
     */
    public static int limit(int count) {
        if (count <= 0) return 0;
        long total = (long) count * THROUGHPUT_DEFAULT;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Makes one plan for one network.
     *
     * <p>Every array is index-aligned and may describe a block that is both source and sink (a battery
     * bank, a tower). The plan never asks an endpoint to pay for its own grant, never exceeds
     * {@code budget}, never exceeds an offer, and never over-fills a demand.</p>
     *
     * <p>The budget is first capped by the offers that are actually there and by the total demand, so a
     * network with 300 FE in its buffers never reports a 2,000 FE plan. Priorities are then served in
     * ascending order; within one level the remaining budget is shared in proportion to each sink's
     * remaining deficit. A grant that only the sink itself could cover is not granted at all — the FE
     * stays where it is (that is the anti-self-loop rule) — and the loop continues with the budget that
     * is left, so one unpayable sink does not starve the others.</p>
     *
     * @param offers     FE each endpoint could give this tick; negative counts as 0
     * @param demands    FE each endpoint could take this tick; negative counts as 0
     * @param priorities sink priority per endpoint, lower served first; ignored where demand is 0
     * @param budget     network ceiling for this tick, normally {@code cableThroughput}
     * @return a balanced plan; {@link Plan#EMPTY} for {@code null} or mismatched arrays
     */
    public static Plan allocate(int[] offers, int[] demands, int[] priorities, int budget) {
        if (offers == null || demands == null || priorities == null) return Plan.EMPTY;
        int count = offers.length;
        if (count == 0 || demands.length != count || priorities.length != count) return Plan.EMPTY;

        int[] offer = new int[count];
        int[] demand = new int[count];
        long supply = 0L;
        long wanted = 0L;
        for (int i = 0; i < count; i++) {
            offer[i] = Math.max(0, offers[i]);
            demand[i] = Math.max(0, demands[i]);
            supply += offer[i];
            wanted += demand[i];
        }
        long room = Math.max(0, budget);
        long total = Math.min(Math.min(supply, room), wanted);
        int[] granted = new int[count];
        int[] spent = new int[count];
        if (total <= 0) return new Plan(granted, spent, 0);

        // Sources are drawn down in index order, so "who paid" is deterministic and testable.
        int[] left = offer.clone();
        long budgetLeft = total;

        // Distinct priority levels, ascending (at most a handful; insertion sort keeps it allocation free).
        int[] levels = new int[count];
        int levelCount = 0;
        for (int value : priorities) {
            boolean seen = false;
            for (int i = 0; i < levelCount; i++) if (levels[i] == value) { seen = true; break; }
            if (seen) continue;
            int at = levelCount++;
            while (at > 0 && levels[at - 1] > value) { levels[at] = levels[at - 1]; at--; }
            levels[at] = value;
        }

        for (int l = 0; l < levelCount && budgetLeft > 0; l++) {
            int level = levels[l];
            boolean progress = true;
            while (budgetLeft > 0 && progress) {
                progress = false;
                long deficits = 0L;
                for (int i = 0; i < count; i++) {
                    if (priorities[i] == level && granted[i] < demand[i]) deficits += demand[i] - granted[i];
                }
                if (deficits <= 0L) break;

                // Proportional share of what is left, in whole FE, largest-remainder style.
                int[] share = new int[count];
                long assigned = 0L;
                for (int i = 0; i < count; i++) {
                    if (priorities[i] != level || granted[i] >= demand[i]) continue;
                    share[i] = (int) (budgetLeft * (demand[i] - granted[i]) / deficits);
                    assigned += share[i];
                }
                long rest = budgetLeft - assigned;
                for (int i = 0; i < count && rest > 0; i++) {
                    if (priorities[i] != level || granted[i] >= demand[i]) continue;
                    if (share[i] >= demand[i] - granted[i]) continue;
                    share[i]++;
                    rest--;
                }

                for (int sink = 0; sink < count && budgetLeft > 0; sink++) {
                    if (priorities[sink] != level) continue;
                    int want = Math.min(share[sink], Math.min(demand[sink] - granted[sink], (int) budgetLeft));
                    if (want <= 0) continue;
                    int paid = 0;
                    for (int source = 0; source < count && paid < want; source++) {
                        if (source == sink) continue; // no self-loop: a block never pays itself
                        if (left[source] <= 0) continue;
                        int take = Math.min(want - paid, left[source]);
                        left[source] -= take;
                        spent[source] += take;
                        paid += take;
                    }
                    if (paid <= 0) continue;
                    granted[sink] += paid;
                    budgetLeft -= paid;
                    progress = true;
                }
            }
        }

        int moved = 0;
        for (int i = 0; i < count; i++) moved += granted[i];
        return new Plan(granted, spent, moved);
    }
}
