package dev.corvus.core;

/**
 * Frame numbers: hit points, per-hit damage mitigation and the follow-loop step.
 *
 * <p>These three are the frame/movement figures both builds read from {@code frames/*.json} and then
 * apply in their own code. The arithmetic is identical and easy to drift (a {@code round} here, a
 * missing clamp there), so it lives here.</p>
 *
 * <p>Pure arithmetic, no Minecraft types: callers pass plain doubles and keep their own vector math.</p>
 */
public final class FrameStats {
    /** Fraction of the remaining distance the follow loop covers per tick at {@code accel_response = 1}. */
    public static final double FOLLOW_BASE = 0.2D;
    /** Upper bound on {@code accel_response}; above this the drone would snap to the station. */
    public static final double MAX_ACCEL = 5.0D;
    /** Hard ceiling on a frame's hit points, matching the validated {@code max_health} range. */
    public static final double MAX_HEALTH = 1024.0D;

    private FrameStats() {}

    /**
     * Effective maximum health of a frame after a multiplier (paint, chip, upgrade).
     *
     * @param baseHp     the frame's {@code max_health} value; {@code <= 0} is treated as 1 because a
     *                   frame must never spawn dead
     * @param multiplier scale applied to the base value; clamped to {@code [0, 10]}, non-finite values
     *                   treated as 1 (no change)
     * @return {@code baseHp x multiplier}, at least 1.0 and at most {@link #MAX_HEALTH}; never NaN
     */
    public static double maxHealth(int baseHp, double multiplier) {
        double base = baseHp <= 0 ? 1.0D : Math.min(MAX_HEALTH, baseHp);
        double scale = Double.isFinite(multiplier) ? Math.min(10.0D, Math.max(0.0D, multiplier)) : 1.0D;
        double result = base * scale;
        if (!Double.isFinite(result)) result = MAX_HEALTH;
        return Math.min(MAX_HEALTH, Math.max(1.0D, result));
    }

    /**
     * Damage that actually lands after a frame's mitigation, capped per hit.
     *
     * <p>{@code mult} is the fraction that gets <b>through</b>: 1.0 is an unarmoured frame, 0.5 halves
     * the hit, 0 blocks it. It is clamped to {@code [0, 1]}, so a data-pack value above 1 cannot make
     * a frame take more damage than the hit, and a negative value cannot heal it. The per-hit cap
     * ({@code max_damage_per_hit} in the frame JSON) is applied <b>after</b> mitigation: a frame with a
     * 12 damage cap takes at most 12 from one hit, however hard the hit was.</p>
     *
     * @param amount incoming damage; {@code <= 0} or non-finite returns 0
     * @param mult   damage multiplier in {@code [0, 1]}; non-finite treated as 1
     * @param cap    per-hit ceiling; {@code <= 0} or non-finite means "no cap"
     * @return damage in {@code [0, amount]}, never negative
     */
    public static double mitigate(double amount, double mult, double cap) {
        if (!Double.isFinite(amount) || amount <= 0.0D) return 0.0D;
        double scale = Double.isFinite(mult) ? Math.min(1.0D, Math.max(0.0D, mult)) : 1.0D;
        double landed = amount * scale;
        if (Double.isFinite(cap) && cap > 0.0D) landed = Math.min(landed, cap);
        return Math.max(0.0D, landed);
    }

    /**
     * One follow-loop step towards the station, repeated {@code tick} times.
     *
     * <p>The per-tick rule is the one the drone already used:
     * {@code current + (target - current) x clamp(0.2 x accel, 0, 1)} — a fractional lerp, not a
     * constant speed, so the drone eases into its station instead of overshooting it. Clamping the
     * fraction to {@code [0, 1]} means an {@code accel_response} of 5 or more arrives in a single tick
     * and can never overshoot, and {@code accel <= 0} leaves the position untouched.</p>
     *
     * @param current start coordinate on one axis
     * @param target  station coordinate on the same axis
     * @param accel   the frame's {@code accel_response}; non-finite treated as 1
     * @param tick    number of steps to apply; {@code <= 0} returns {@code current} unchanged
     * @return the coordinate after {@code tick} steps; the distance to {@code target} shrinks
     *         monotonically (it is multiplied by {@code 1 - fraction} each step), so the value
     *         converges to {@code target} without ever crossing it
     */
    public static double followStep(double current, double target, double accel, int tick) {
        if (tick <= 0) return current;
        double raw = Double.isFinite(accel) ? accel : 1.0D;
        double fraction = Math.min(1.0D, Math.max(0.0D, FOLLOW_BASE * Math.min(MAX_ACCEL, Math.max(0.0D, raw))));
        double value = current;
        for (int i = 0; i < tick; i++) {
            value += (target - value) * fraction;
        }
        return value;
    }
}
