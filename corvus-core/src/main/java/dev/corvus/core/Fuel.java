package dev.corvus.core;

/**
 * Furnace fuel to FE, one rule for every fuel slot in the mod.
 *
 * <p>An item is worth {@code burnTicks x }{@value #FE_PER_TICK} FE, where {@code burnTicks} is the
 * vanilla furnace burn time. The multiplier preserves the historical coal contract (coal 1,600 t =
 * 4,000 FE) and scales the rest the way players expect: coal block 16,000 t = 40,000 FE, blaze rod
 * 2,400 t = 6,000 FE, lava bucket 20,000 t = 50,000 FE.</p>
 *
 * <p>This class is pure arithmetic on purpose: it has no Minecraft types, so the same code compiles
 * and is tested by both the 1.21.1 and the 1.20.1 build. Call sites keep their own platform types
 * ({@code ItemStack#getBurnTime} vs {@code ForgeHooks#getBurnTime}) and only pass the tick count.</p>
 *
 * <h2>Rounding</h2>
 * <p>{@code burnTicks x 2.5} can land on a half (any odd tick count: 1 t = 2.5 FE, 3 t = 7.5 FE).
 * The half always rounds <b>up</b> ({@link Math#round(double)} semantics, i.e. half-up for positive
 * values): 1 t = 3 FE, 3 t = 8 FE, 5 t = 13 FE. Rounding is applied to the whole item at once, never
 * per tick, so a long burn cannot drift.</p>
 */
public final class Fuel {
    /** FE produced per tick of vanilla burn time. */
    public static final double FE_PER_TICK = 2.5D;

    private Fuel() {}

    /**
     * FE stored in one item with the given vanilla burn time.
     *
     * @param burnTicks vanilla burn time in ticks; {@code <= 0} means "not a fuel"
     * @return whole FE for one item; {@code 0} for a non-fuel, never negative, never above
     *         {@link Long#MAX_VALUE} (an {@code int} input cannot overflow a {@code long})
     */
    public static long fuelToFe(int burnTicks) {
        if (burnTicks <= 0) return 0L;
        // Half-up: Math.round adds 0.5 and floors, so 2.5 -> 3 and 7.5 -> 8.
        return Math.round(burnTicks * FE_PER_TICK);
    }

    /**
     * FE still owed for a partly burnt item: its full value minus what has already been paid out.
     *
     * <p>Generators hand out FE a tick at a time, so when a slot is emptied, dismantled, reloaded or
     * refunded the caller needs the exact remainder instead of re-deriving it. Keeping the remainder
     * here means a partly burnt lava bucket can never leak energy (paying out less than
     * {@link #fuelToFe}) or duplicate it (paying out more).</p>
     *
     * @param burnTicks  vanilla burn time of the item, as passed to {@link #fuelToFe}
     * @param feReleased FE already handed out for that item; negative values are treated as 0
     * @return the remainder clamped to {@code [0, Integer.MAX_VALUE]}: {@code 0} when the item is
     *         fully paid (or overpaid), never negative
     */
    public static int flushRemainder(int burnTicks, long feReleased) {
        long owed = fuelToFe(burnTicks) - Math.max(0L, feReleased);
        if (owed <= 0L) return 0;
        return (int) Math.min(Integer.MAX_VALUE, owed);
    }

    /**
     * Whole-item convenience: the same value as {@link #fuelToFe} narrowed to {@code int}, clamped
     * instead of wrapping. Use this when the caller stores fuel value in an {@code int} field (the
     * 1.21.1/1.20.1 fuel buffers both do) and the item has not been burnt yet.
     */
    public static int flushRemainder(int burnTicks) {
        return (int) Math.min(Integer.MAX_VALUE, fuelToFe(burnTicks));
    }
}
