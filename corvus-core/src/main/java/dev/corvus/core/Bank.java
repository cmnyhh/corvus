package dev.corvus.core;

/**
 * Battery-bank gauge and the even split used when one source feeds several receivers.
 *
 * <p>Two rules with a history of drift between the builds:</p>
 *
 * <ul>
 *   <li>{@link #window(int, int)} — the coarse 0..3 model segment. The 1.21.1 bank truncated the
 *       percentage (66.7 % read as 66 % → segment 1), the 1.20.1 bank rounded it (67 % → segment 2),
 *       so the two models switched segment at different charges. The shared rule rounds, because a
 *       gauge that reads "67 %" must also show the third of four segments.</li>
 *   <li>{@link #pushSplit(int, int[], int[])} — how a push budget is divided between neighbours. The
 *       1.21.1 push split evenly ({@code share}/{@code extra}), the 1.20.1 link filled neighbours in
 *       face order (first one takes everything). The shared rule is the even split, with the
 *       remainder handed out one FE at a time in index order.</li>
 * </ul>
 *
 * <p>Pure arithmetic, no Minecraft types: both builds pass plain arrays of what their energy
 * capabilities report.</p>
 */
public final class Bank {
    /** Number of gauge segments (0..3), i.e. the {@code charge} block-state range. */
    public static final int WINDOWS = 4;

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
        if (percent >= 100) return 3;
        if (percent >= 67) return 2;
        if (percent >= 34) return 1;
        return 0;
    }

    /**
     * Splits {@code budget} FE evenly between every target that has room, and reports what each one
     * actually took.
     *
     * <p>Targets with {@code stored >= capacity} (or a non-positive capacity) are not eligible and
     * receive 0; they do not consume a share and are not counted in the divisor. The budget divides
     * evenly between the eligible targets, and the remainder is handed out <b>one FE at a time in
     * array order</b>: budget 41 across two empty targets is {@code [21, 20]}, not {@code [20, 21]}.
     * A share larger than the target's free room is truncated — the excess is <b>not</b> redistributed
     * to the remaining targets, because one call must never pretend to have offered more than the
     * caller's budget.</p>
     *
     * @param budget     FE available to give away; {@code <= 0} returns an all-zero array
     * @param capacities per-target capacity, same length as {@code stored}; negative values are
     *                   treated as 0
     * @param stored     per-target stored FE, same length as {@code capacities}; values below 0 count
     *                   as 0 and values above the target capacity count as full
     * @return a fresh array of the same length: the FE each target actually received, always
     *         {@code >= 0} and never more than that target's free room
     * @throws IllegalArgumentException when either array is {@code null} or the lengths differ
     */
    public static int[] pushSplit(int budget, int[] capacities, int[] stored) {
        if (capacities == null || stored == null) {
            throw new IllegalArgumentException("capacities and stored must not be null");
        }
        if (capacities.length != stored.length) {
            throw new IllegalArgumentException(
                    "capacities (" + capacities.length + ") and stored (" + stored.length + ") must have the same length");
        }
        int[] received = new int[capacities.length];
        if (budget <= 0 || capacities.length == 0) return received;

        int eligible = 0;
        for (int i = 0; i < capacities.length; i++) {
            if (freeRoom(capacities[i], stored[i]) > 0) eligible++;
        }
        if (eligible == 0) return received;

        int share = budget / eligible;
        int extra = budget % eligible;
        for (int i = 0; i < capacities.length; i++) {
            int free = freeRoom(capacities[i], stored[i]);
            if (free <= 0) continue;
            int offer = share + (extra > 0 ? 1 : 0);
            if (extra > 0) extra--;
            received[i] = Math.min(offer, free);
        }
        return received;
    }

    /** Free room in one target, with both ends clamped so a bad NBT value cannot go negative. */
    private static int freeRoom(int capacity, int stored) {
        if (capacity <= 0) return 0;
        int held = Math.min(Math.max(0, stored), capacity);
        return capacity - held;
    }
}
