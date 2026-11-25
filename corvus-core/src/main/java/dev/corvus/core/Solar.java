package dev.corvus.core;

/**
 * Solar panel daylight rule: is the panel producing, and how much.
 *
 * <p>Both builds used to spell this out twice, with different answers (the 1.20.1 panel called
 * {@code Level#isNight}, which a rainy afternoon can satisfy, and only halved on rain; the 1.21.1
 * panel used the raw day clock and halved on rain or thunder). This class is the single answer:</p>
 *
 * <ul>
 *   <li>{@link State#OFF} — no output: night, a fixed-time dimension, or a covered sky;</li>
 *   <li>{@link State#DIM} — half output: daylight under an open sky with rain or thunder;</li>
 *   <li>{@link State#BRIGHT} — full output: clear daylight under an open sky.</li>
 * </ul>
 *
 * <p>Day is passed in as a boolean so the caller decides <b>how</b> to read the clock. The rule both
 * builds converge on is the raw day clock, not {@code Level#isDay()}: the latter folds in
 * {@code skyDarken}, and rain darkens the sky enough to read as night, which would switch a panel off
 * when it should only be halved. A fixed-time dimension has no day/night cycle and therefore counts
 * as night — the caller passes {@code false} for that.</p>
 *
 * <h2>Rounding</h2>
 * <p>Dim output is {@code floor(peak / 2)}: a 61 FE/t peak gives 30 FE/t, not 31. No minimum of 1 is
 * applied — a peak of 0 or 1 really does give 0 in the rain, because inventing 1 FE/t out of a
 * 0 FE/t panel would be energy from nowhere.</p>
 */
public final class Solar {
    /**
     * Panel state. The {@link #code()} values are the ones the block state and the model use, so a
     * renderer can switch on 0/1/2 without a second table.
     */
    public enum State {
        /** Night, fixed-time dimension or covered sky: no output. */
        OFF(0),
        /** Daylight but raining or thundering: half output. */
        DIM(1),
        /** Clear daylight with an open sky: full output. */
        BRIGHT(2);

        private final int code;

        State(int code) {
            this.code = code;
        }

        /** 0 = off, 1 = dim, 2 = bright — the block-state value. */
        public int code() {
            return code;
        }

        /**
         * Inverse of {@link #code()}.
         *
         * @throws IllegalArgumentException when the code is outside {@code 0..2}; a corrupt block state
         *         should fail loudly instead of silently reading as "off"
         */
        public static State fromCode(int code) {
            for (State state : values()) {
                if (state.code == code) return state;
            }
            throw new IllegalArgumentException("Unknown solar state code " + code + " (expected 0..2)");
        }
    }

    private Solar() {}

    /**
     * Panel state for this instant.
     *
     * @param daytimeByWorldClock true when the world clock says day (and the dimension has a day/night
     *                            cycle); false at night or in a fixed-time dimension
     * @param canSeeSky           true when the sky is visible from one block above the panel
     * @param raining             true while the level is raining
     * @param thundering          true while the level is thundering (halves output on its own, even
     *                            when {@code raining} is false)
     * @return {@link State#OFF} for night or a covered sky (checked first, so weather never overrides
     *         "no light"), otherwise {@link State#DIM} when raining or thundering, else
     *         {@link State#BRIGHT}
     */
    public static State state(boolean daytimeByWorldClock, boolean canSeeSky, boolean raining, boolean thundering) {
        if (!daytimeByWorldClock) return State.OFF;
        if (!canSeeSky) return State.OFF;
        return raining || thundering ? State.DIM : State.BRIGHT;
    }

    /**
     * Output in FE/t for one tick, before the buffer is considered.
     *
     * @param peak  configured clear-sky output in FE/t; {@code <= 0} means "no panel"
     * @param state current state, {@code null} treated as {@link State#OFF}
     * @return {@code 0} when off or the peak is not positive, {@code peak} when bright, and
     *         {@code floor(peak / 2)} when dim (60 -> 30, 61 -> 30, 1 -> 0); never negative
     */
    public static int ratePerTick(int peak, State state) {
        if (state == null || state == State.OFF || peak <= 0) return 0;
        if (state == State.BRIGHT) return peak;
        return peak / 2; // int division == floor for non-negative peaks
    }
}
