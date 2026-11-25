package dev.corvus.core;

/**
 * The drone's once-per-second power settlement.
 *
 * <p>Every second the drone pays for what its modules consumed and then banks what its solar panel
 * made. The order matters and used to differ between the builds: subtracting the drain from the
 * <b>starting</b> charge and then adding the charge back lets a panel revive a drone that would
 * otherwise have hit zero for that tick, while adding first would let a drone fly a whole second on
 * energy it had not earned yet. The shared rule is <b>drain first, then add</b>:</p>
 *
 * <pre>
 *   afterDrain = clamp(charge - drainPerTick * 20, 0, capacity)
 *   result     = clamp(afterDrain + solarPerTick * 20, 0, capacity)
 * </pre>
 *
 * <p>Because the intermediate value is clamped at 0, a drain larger than the charge can never put the
 * drone into debt: it arrives at 0 and the panel's production for that same second is banked in full.
 * Because the final value is clamped at capacity, a full drone cannot overcharge, and because drain
 * is applied first, solar can never pay for a module in the same second it is switched on — a
 * deliberate, testable ordering rather than an accident.</p>
 */
public final class Power {
    /** Game ticks per second; the drone settles once per this many ticks. */
    public static final int TICKS_PER_SECOND = 20;

    private Power() {}

    /**
     * Charge after one second of drain followed by one second of solar.
     *
     * @param charge       charge at the start of the second; negative values count as 0
     * @param capacity     battery capacity; {@code <= 0} means "no battery" and returns 0
     * @param drainPerTick FE/t the online modules consume; negative values count as 0
     * @param solarPerTick FE/t the panel produces; negative values count as 0
     * @return charge at the end of the second, always within {@code [0, capacity]}
     */
    public static long settleSecond(long charge, int capacity, int drainPerTick, int solarPerTick) {
        if (capacity <= 0) return 0L;
        long cap = capacity;
        long start = Math.min(cap, Math.max(0L, charge));
        long drain = (long) Math.max(0, drainPerTick) * TICKS_PER_SECOND;
        long gain = (long) Math.max(0, solarPerTick) * TICKS_PER_SECOND;
        long afterDrain = Math.max(0L, start - drain);
        return Math.min(cap, afterDrain + gain);
    }
}
