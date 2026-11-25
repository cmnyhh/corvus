package dev.corvus.core;

/**
 * Battery-bank gauge and the "who gets the power" rule used when one source feeds several receivers.
 *
 * <p>Two rules with a history of drift between the builds:</p>
 *
 * <ul>
 *   <li>{@link #window(int, int)} — the coarse 0..3 model segment. The 1.21.1 bank truncated the
 *       percentage (66.7 % read as 66 % → segment 1), the 1.20.1 bank rounded it (67 % → segment 2),
 *       so the two models switched segment at different charges. The shared rule rounds, because a
 *       gauge that reads "67 %" must also show the third of four segments. The segment itself is
 *       reported <b>as-is</b> ({@code 0..3}, the block-state value) by both builds: the 1.21.1 status
 *       line used to add one for a "x/4" display while 1.20.1 printed the raw segment, so the same
 *       charge read "3/4" on one side and "2/4" on the other.</li>
 *   <li>{@link #pushTo(int, int[], int[])} — how a push budget is divided between neighbours. The
 *       1.21.1 push split evenly ({@code share}/{@code extra}), the 1.20.1 link filled neighbours in
 *       face order (first one takes everything). The shared rule is <b>fill in order</b>: 40 FE
 *       across two empty receivers is {@code [40, 0]}, and a receiver that is full does not halve
 *       anybody else's share — the leftover simply stays with the sender, which is what the 1.20.1
 *       link always did and what a "feed the pad first" base design expects.</li>
 * </ul>
 *
 * <p>Pure arithmetic, no Minecraft types: both builds pass plain arrays of what their energy
 * capabilities report.</p>
 */
public final class Bank {
    /** Number of gauge segments (0..3), i.e. the {@code charge} block-state range. */
    public static final int WINDOWS = 4;
    /** Highest gauge segment: a display domain of {@code 0..3}, not {@code 1..4}. */
    public static final int LAST_WINDOW = WINDOWS - 1;
    /** FE a generator offers a neighbour just to find out whether it can take anything at all. */
    public static final int PROBE_FE = 1;

    private Bank() {}

    /**
     * Fill percentage, rounded to the nearest whole percent.
     *
     * @param charge   stored FE; values below 0 count as 0, values above {@code capacity} are clamped
     * @param capacity configured capacity; {@code <= 0} yields 0 instead of dividing by zero
     * @return {@code 0..100}
     */
    public static int percent(int charge, int capacity) {
        if (capacity <= 0 || charge <= 0) return 0;
        long c = Math.min((long) charge, (long) capacity);
        return (int) Math.min(100L, Math.round(c * 100.0D / capacity));
    }

    /**
     * Model segment for a charge level: 0 = 0..33 %, 1 = 34..66 %, 2 = 67..99 %, 3 = 100 %.
     *
     * <p>The boundaries are applied to the <b>rounded</b> percentage, so 2 of 3 cells (66.7 %) reads
     * as 67 % and selects segment 2, matching what the status line prints.</p>
     *
     * @param charge   stored FE; {@code <= 0} yields 0
     * @param capacity configured capacity; {@code <= 0} yields 0
     * @return {@code 0..3}
     */
    public static int window(int charge, int capacity) {
        int percent = percent(charge, capacity);
        if (percent >= 100) return LAST_WINDOW;
        if (percent >= 67) return 2;
        if (percent >= 34) return 1;
        return 0;
    }

    /**
     * Fills {@code budget} into the targets <b>in array order</b>, and reports what each one took.
     *
     * <p>The first target is offered as much as it has room for; whatever is left goes to the second,
     * and so on. A full target (or one with a non-positive capacity) is skipped and consumes nothing,
     * so the next target still sees the whole remaining budget. When every target is full the result
     * is all zeros and the caller keeps its energy.</p>
     *
     * <p>The sum of the result is at most {@code budget} and at most the total free room, and every
     * entry is {@code >= 0} and never more than that target's own free room — a target with 10 FE of
     * room can never be credited 20. Unlike an even split, a small first target does <b>not</b> shrink
     * the second one's share: 40 FE into rooms {@code [10, 1000]} is {@code [10, 30]}.</p>
     *
     * @param budget     FE available to give away; {@code <= 0} returns an all-zero array
     * @param capacities per-target capacity, same length as {@code stored}
     * @param stored     per-target stored FE, same length as {@code capacities}; values below 0 count
     *                   as 0 and values above the target capacity count as full
     * @return a fresh array of the same length: the FE each target receives, in fill order
     * @throws IllegalArgumentException when either array is {@code null} or the lengths differ
     */
    public static int[] pushTo(int budget, int[] capacities, int[] stored) {
        if (capacities == null || stored == null) {
            throw new IllegalArgumentException("capacities and stored must not be null");
        }
        if (capacities.length != stored.length) {
            throw new IllegalArgumentException(
                    "capacities (" + capacities.length + ") and stored (" + stored.length + ") must have the same length");
        }
        int[] received = new int[capacities.length];
        int left = budget;
        if (left <= 0) return received;
        for (int i = 0; i < capacities.length && left > 0; i++) {
            int offer = Math.min(left, freeRoom(capacities[i], stored[i]));
            if (offer <= 0) continue;
            received[i] = offer;
            left -= offer;
        }
        return received;
    }

    /**
     * Total FE a {@link #pushTo} plan moves: the sum of its entries.
     *
     * @param plan the array {@link #pushTo} returned; {@code null} counts as "nothing moved"
     * @return {@code 0} for a {@code null}/empty plan, else the sum of the non-negative entries
     */
    public static int planned(int[] plan) {
        if (plan == null) return 0;
        long total = 0L;
        for (int amount : plan) total += Math.max(0, amount);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Whether a neighbour that was probed with {@link #PROBE_FE} FE would accept it.
     *
     * <p>This is the "is anybody actually able to take power <em>now</em>" test a burner uses before
     * it lights new fuel. It is deliberately a one-FE simulated offer instead of "a capability exists":
     * a full receiver still exposes the capability, and burning a coal lump into a full base would be
     * fuel destroyed for nothing. {@code receiveEnergy(1, true)} is the platform side of the probe; this
     * method is the shared rule for what to do with its answer.</p>
     *
     * @param simulatedAccept the FE a neighbour reported for a simulated {@link #PROBE_FE} offer
     * @return true when at least one whole FE would be accepted
     */
    public static boolean canTakeProbe(int simulatedAccept) {
        return simulatedAccept >= PROBE_FE;
    }

    /** Free room in one target, with both ends clamped so a bad NBT value cannot go negative. */
    private static int freeRoom(int capacity, int stored) {
        if (capacity <= 0) return 0;
        int held = Math.min(Math.max(0, stored), capacity);
        return capacity - held;
    }
}
