package dev.corvus.core;

/**
 * The power <em>interface</em> rules: the one-block {@code power_port} that lets another mod's
 * consumer draw from us, and the {@code power_cable} that carries FE from our machines to it.
 *
 * <p>Both builds compile this file, so the buffer sizes, the configurable per-tick ceilings and the
 * "who fills first" rule can never drift between 1.21.1 and 1.20.1. Nothing here touches a Minecraft
 * type: the callers hand in plain integers that their energy capabilities reported, and copy the
 * result back into their own block entities.</p>
 *
 * <p>Three rules have a history of being written twice and disagreeing:</p>
 *
 * <ul>
 *   <li>{@link #intake(int, int, int)} — how much a port may draw into its buffer this tick. The
 *       room left in the buffer and the per-tick ceiling are both real limits; the smaller one wins,
 *       so a nearly full buffer does not get a full tick's worth and a config change cannot push a
 *       buffer past its capacity.</li>
 *   <li>{@link #sequential(int, int[])} — how a single budget is shared by the consumers of one port
 *       or one cable. Fill <b>in order</b> (the same rule the battery bank's {@code pushTo} uses):
 *       the first consumer is satisfied before the second sees anything, so a 1,000 FE/t port facing
 *       two 800 FE demands gives {@code 800 + 200}, never {@code 500 + 500}. "顺序喂饱" is what makes
 *       the FE/t number in the tooltip and the FE/t actually delivered the same number.</li>
 *   <li>{@link #withinRun(int)} — one cable run is at most {@link #CABLE_LENGTH} blocks. Beyond that
 *       the run stops carrying power instead of pretending to be an infinitely long wire; the status
 *       line says so.</li>
 * </ul>
 */
public final class PowerIo {
    /** Buffer inside a power port, FE. Small on purpose: a port is a socket, not a battery. */
    public static final int PORT_BUFFER = 8_000;
    /** Default of the {@code portOutput} config entry: FE/t the port may take in and give out. */
    public static final int PORT_OUTPUT_DEFAULT = 1_000;
    /** Lowest value {@code portOutput} may be configured to. */
    public static final int PORT_OUTPUT_MIN = 100;
    /** Highest value {@code portOutput} may be configured to. */
    public static final int PORT_OUTPUT_MAX = 4_000;
    /**
     * Default FE/t a cable network may move, shared by every endpoint of that network. The live value is
     * the {@code cableThroughput} config entry; this is the default both builds ship
     * ({@link EnergyNet#THROUGHPUT_DEFAULT}) and the number the tooltip falls back to.
     */
    public static final int CABLE_RATE = EnergyNet.THROUGHPUT_DEFAULT;
    /**
     * Longest source-to-sink path, in blocks, that still carries power
     * ({@link EnergyNet#MAX_LENGTH}); the cable status line uses it to report a dead run.
     */
    public static final int CABLE_LENGTH = EnergyNet.MAX_LENGTH;
    /** Hard limit on the cells one cable scan may visit, so a pathological build cannot stall a tick. */
    public static final int CABLE_SCAN_LIMIT = 96;

    private PowerIo() {}

    /**
     * Clamps a configured port output into the advertised {@code 100..4000 FE/t} range.
     *
     * <p>Used for the config value, for the tooltip and for the per-tick windows alike, so an edited
     * file cannot make the block report one number and deliver another.</p>
     *
     * @param configured value read from the config; any integer
     * @return {@code 100..4000}
     */
    public static int portOutput(int configured) {
        return Math.max(PORT_OUTPUT_MIN, Math.min(PORT_OUTPUT_MAX, configured));
    }

    /**
     * FE a port takes from its neighbours in one tick: the free room, capped by the tick ceiling.
     *
     * @param stored   FE already in the port's buffer; negative counts as 0, above capacity counts as full
     * @param capacity buffer capacity ({@link #PORT_BUFFER} unless a test overrides it)
     * @param rate     per-tick ceiling, normally {@link #portOutput(int)} of the config value
     * @return {@code >= 0}, never more than the free room and never more than {@code rate}
     */
    public static int intake(int stored, int capacity, int rate) {
        if (capacity <= 0 || rate <= 0) return 0;
        int held = Math.min(Math.max(0, stored), capacity);
        return Math.min(rate, capacity - held);
    }

    /**
     * Fills one budget into the demands <b>in array order</b> and reports what each demand received.
     *
     * <p>The first demand takes as much as it asked for (or whatever is left), the second sees only
     * the remainder, and so on. This is the rule for several external consumers pulling on the same
     * port and for several ports pulling through the same cable run: nobody is starved by an even
     * split, and the sum can never exceed the budget.</p>
     *
     * @param budget  FE available; {@code <= 0} yields an all-zero plan
     * @param demands FE each consumer asked for; negative entries count as 0
     * @return a fresh array of the same length, {@code 0 <= result[i] <= max(0, demands[i])},
     *         {@code sum(result) <= max(0, budget)}
     * @throws IllegalArgumentException when {@code demands} is {@code null}
     */
    public static int[] sequential(int budget, int[] demands) {
        if (demands == null) throw new IllegalArgumentException("demands must not be null");
        int[] taken = new int[demands.length];
        int left = Math.max(0, budget);
        for (int i = 0; i < demands.length && left > 0; i++) {
            int give = Math.min(left, Math.max(0, demands[i]));
            if (give <= 0) continue;
            taken[i] = give;
            left -= give;
        }
        return taken;
    }

    /**
     * Whether a cable run of {@code distance} blocks still carries power.
     *
     * @param distance block distance from the drawing cell to the source, one-based; {@code <= 0}
     *                 (the consumer sits next to the first cable) is inside the run
     * @return true for {@code 0..32}, false for anything longer
     */
    public static boolean withinRun(int distance) {
        return distance >= 0 && distance <= CABLE_LENGTH;
    }

    /**
     * What is left of a per-tick ceiling after {@code usedThisTick} FE already moved.
     *
     * <p>Both windows (a port's output, a cable run's throughput) are per game tick. The remainder is
     * clamped at 0 so a second caller in the same tick gets nothing instead of a negative allowance.</p>
     *
     * @param rate         the ceiling for one tick
     * @param usedThisTick FE already moved this tick; negative counts as 0
     * @return {@code 0..rate}
     */
    public static int remainder(int rate, int usedThisTick) {
        if (rate <= 0) return 0;
        return Math.max(0, rate - Math.max(0, usedThisTick));
    }
}
