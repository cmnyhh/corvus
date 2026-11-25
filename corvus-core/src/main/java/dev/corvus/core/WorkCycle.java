package dev.corvus.core;

/**
 * The drone's work cycle over an ore mill: one 60-tick loop, split into a scan, a drop and a reset
 * phase, plus every number both builds' clients (and the shared hover driver) are allowed to use.
 *
 * <p>This class is deliberately plain Java: no Minecraft types, no randomness, no state. Every
 * method is a pure function of the tick argument, so the 1.21.1/NeoForge build and the
 * 1.20.1/Forge build compute byte-identical motion from the same tick and the same parity test
 * ({@code CoreParityTest}) covers both. The tick argument is the <b>world</b> game time, not the
 * entity's own counter: that way a mill's sorting disc and every drone hovering over it share one
 * clock, and the client can reproduce the server's numbers without a second synced field.</p>
 *
 * <h2>The loop</h2>
 * <pre>
 *   tick   0 .............. 23 | 24 ......... 31 | 32 ....... 39 | 40 ....... 59
 *   phase  SCAN                 DROP (sinking)    DROP (rising)   RESET
 *   height HOVER                HOVER -&gt; DIP     DIP -&gt; HOVER    HOVER
 *   radius 1.20 -&gt; 0            0                0               0
 *   body   0 deg                0 -&gt; 12 deg      12 -&gt; 0 deg     0 deg
 *   lamp   cyan                 cyan -&gt; amber    amber           amber -&gt; cyan
 *   gimbal tracks the disc      down at -60 deg  sweep -20..+20   sweep -20..+20
 * </pre>
 *
 * <h2>Continuity rules</h2>
 * <p>Every function is continuous at every join <em>inside</em> the loop, and the loop itself is
 * periodic ({@code f(t + PERIOD) == f(t)}):</p>
 * <ul>
 *   <li>{@link #hoverHeight(int)} is {@link #HOVER} at tick 23 and 24 and again at 39 and 40, so the
 *       drop and the reset hand over without a step; the standing idle bob is gone, because the
 *       height is now the cycle's own.</li>
 *   <li>{@link #orbitRadius(int)} reaches exactly 0 at tick 24, so the drone is already centred when
 *       the drop starts; the offset only exists during the scan, where the angle is turning.</li>
 *   <li>{@link #orbitAngle(int)} turns one and a half laps during the scan and then finishes the
 *       remaining half lap while the radius is 0, so the bearing is back at 0 degrees (720 = 2 laps)
 *       for the tail of the drop and the whole reset and the 59 -&gt; 0 seam has no sideways jump.</li>
 *   <li>{@link #pitchDegrees(int)} is 0 at every phase boundary (0/23/24/39/40/59).</li>
 *   <li>{@link #lampBlend(int)} stays in [0, 1] and returns to 0 before the next scan, so a colour
 *       lerp never overshoots.</li>
 * </ul>
 */
public final class WorkCycle {
    /** One full work cycle, in ticks (3 s at 20 tps). {@code phase} takes the tick modulo this. */
    public static final int PERIOD = 60;
    /** End of the scan phase, in ticks into the cycle. The drop starts on this very tick. */
    public static final int SCAN_END = 24;
    /** End of the drop phase (and therefore start of the reset), in ticks into the cycle. */
    public static final int DROP_END = 40;
    /** Tick inside the drop at which the airframe is at its lowest: the middle of the 24..40 dip. */
    public static final int DIP_BOTTOM = 32;
    /** Ticks one lap of the mill's sorting disc takes. */
    public static final int DISC_PERIOD = 80;

    /** Hover height above the mill's hover centre, in blocks. */
    public static final double HOVER = 0.35D;
    /** Lowest point of the drop, in blocks above the hover centre. */
    public static final double DIP = 0.18D;
    /**
     * Radius of the scan orbit around the hover centre, in blocks.
     *
     * <p>1.2 clears the whole two-block machine (its footprint reaches 1.0 east and 0.5 south of the
     * hover centre), so the airframe visibly flies <em>around</em> the mill instead of pivoting on the
     * spot over it. This is a geometry constant, not a phase constant: the loop's timing, heights and
     * angles are untouched.</p>
     */
    public static final double ORBIT_RADIUS = 1.2D;
    /** Peak nose-down pitch of the airframe during the drop, in degrees. */
    public static final double TILT_DEG = 12.0D;
    /** Tick at which the lamp starts turning from cyan to amber: the start of the drop. */
    public static final int LAMP_SWAP_TICK = 24;

    /**
     * Ticks the enter/leave transition takes: the pose fades from the idle stance into the work
     * stance (and back out again on recall) over this many ticks, so a recall plays the exit before
     * the drone is idle again.
     */
    public static final int ENTER_TICKS = 8;

    /** Gimbal pitch while the scan tracks the disc: straight-ish down, in degrees ({@code -90} = down). */
    public static final double GIMBAL_DOWN_PITCH = -60.0D;
    /** Peak gimbal pan of the post-scan sweep, in degrees. */
    public static final double GIMBAL_SWEEP = 20.0D;

    /** Lamp colour at {@link #lampBlend} 0: the scanning cyan. */
    public static final int LAMP_CYAN = 0x2FE6FF;
    /** Lamp colour at {@link #lampBlend} 1: the depositing amber. */
    public static final int LAMP_AMBER = 0xFFA41E;

    private WorkCycle() {}

    /** The three phases of the loop. */
    public enum Phase { SCAN, DROP, RESET }

    /** Tick inside the loop for an absolute tick; never negative. */
    public static int tickInCycle(int t) {
        return Math.floorMod(t, PERIOD);
    }

    /** {@link Phase#SCAN} for 0..23, {@link Phase#DROP} for 24..39, {@link Phase#RESET} for 40..59. */
    public static Phase phase(int t) {
        int tick = tickInCycle(t);
        if (tick < SCAN_END) return Phase.SCAN;
        if (tick < DROP_END) return Phase.DROP;
        return Phase.RESET;
    }

    /**
     * Height of the airframe above the mill's hover centre, in blocks.
     *
     * <p>{@link #HOVER} during the scan and the reset; during the drop it eases down to
     * {@link #DIP} by tick {@link #DIP_BOTTOM} and rises back to {@link #HOVER} by tick 39, one tick
     * of settle before the reset. Both edges are ease-in-out (smoothstep), so the derivative is 0 at
     * each end and there is no visible kick when the dip starts or stops.</p>
     */
    public static double hoverHeight(int t) {
        int tick = tickInCycle(t);
        if (tick < SCAN_END || tick >= DROP_END) return HOVER;
        if (tick <= DIP_BOTTOM) {
            // 24 -> 32: hover -> dip, 8 ticks.
            return HOVER + (DIP - HOVER) * ease((tick - SCAN_END) / (double) (DIP_BOTTOM - SCAN_END));
        }
        // 33 -> 39: dip -> hover, finishing one tick before the reset so 39 and 40 agree exactly.
        return DIP + (HOVER - DIP) * ease((tick - DIP_BOTTOM) / (double) (DROP_END - 1 - DIP_BOTTOM));
    }

    /**
     * Radius of the scan orbit around the hover centre, in blocks.
     *
     * <p>Starts at {@link #ORBIT_RADIUS} and eases to exactly 0 by tick {@link #SCAN_END}: the drone
     * spirals in while it turns, so the drop starts over the machine instead of teleporting there.
     * The drop and the reset are centred ({@code 0}) — the radius step at the 59 -&gt; 0 seam is
     * absorbed by the hover driver's per-tick speed limit, which never teleports the entity.</p>
     */
    public static double orbitRadius(int t) {
        int tick = tickInCycle(t);
        if (tick >= SCAN_END) return 0.0D;
        return ORBIT_RADIUS * (1.0D - ease(tick / (double) SCAN_END));
    }

    /**
     * Bearing of the scan orbit, in degrees, turning clockwise.
     *
     * <p>One and a half laps at a constant rate during the scan ({@code 540 * t / 24}, 22.5 degrees
     * per tick), then the remaining half lap over 24..32 while the radius is already 0, then a hold
     * at 720 degrees. 720 is 0 modulo a full turn, so the last visible bearing of the loop equals the
     * first one: the seam at 59 -&gt; 0 is continuous in value as well as in direction, which is what
     * makes the orbit restart invisible.</p>
     */
    public static double orbitAngle(int t) {
        int tick = tickInCycle(t);
        if (tick < SCAN_END) return 540.0D * tick / SCAN_END;
        if (tick < DIP_BOTTOM) return 540.0D + 180.0D * (tick - SCAN_END) / (double) (DIP_BOTTOM - SCAN_END);
        return 720.0D;
    }

    /**
     * Nose-down pitch of the airframe, in degrees: 0 everywhere except the drop, where it rises to
     * {@link #TILT_DEG} at {@link #DIP_BOTTOM} and is back to 0 by tick 39.
     *
     * <p>Exactly 0 at every phase boundary (0, 23, 24, 39, 40 and 59), so the airframe is level
     * whenever the phase changes.</p>
     */
    public static double pitchDegrees(int t) {
        int tick = tickInCycle(t);
        if (tick < SCAN_END || tick >= DROP_END) return 0.0D;
        if (tick <= DIP_BOTTOM) {
            return TILT_DEG * ease((tick - SCAN_END) / (double) (DIP_BOTTOM - SCAN_END));
        }
        return TILT_DEG * (1.0D - ease((tick - DIP_BOTTOM) / (double) (DROP_END - 1 - DIP_BOTTOM)));
    }

    /**
     * Lamp colour blend: 0 = cyan (scanning), 1 = amber (depositing).
     *
     * <p>Starts at tick {@link #LAMP_SWAP_TICK}, reaches 1 at {@link #DIP_BOTTOM}, holds through the
     * end of the drop and eases back to 0 over 40..48 — before the reset hands over to the next
     * scan, so the value is always inside [0, 1] and never wraps a colour lerp.</p>
     */
    public static double lampBlend(int t) {
        int tick = tickInCycle(t);
        if (tick < LAMP_SWAP_TICK) return 0.0D;
        if (tick < DIP_BOTTOM) return ease((tick - LAMP_SWAP_TICK) / (double) (DIP_BOTTOM - LAMP_SWAP_TICK));
        if (tick < DROP_END) return 1.0D;
        if (tick < DROP_END + ENTER_TICKS) {
            return 1.0D - ease((tick - DROP_END) / (double) ENTER_TICKS);
        }
        return 0.0D;
    }

    /** The lamp colour for a tick: {@link #LAMP_CYAN} .. {@link #LAMP_AMBER}, RGB in the low 24 bits. */
    public static int lampColor(int t) {
        return mix(LAMP_CYAN, LAMP_AMBER, lampBlend(t));
    }

    /**
     * Angle of the mill's sorting disc, in degrees clockwise.
     *
     * <p>One lap every {@link #DISC_PERIOD} ticks, driven by world time, so the disc keeps turning
     * through all three phases and a hovering drone's gimbal can follow the blade it is reading. The
     * disc is not synced by the block entity today (there is no disc field on {@code OreMillEntity}),
     * so both builds derive it from this one function; if the machine ever syncs a real angle, read
     * that instead and keep this as the fallback.</p>
     */
    public static double discAngle(int t) {
        return 360.0D * Math.floorMod(t, DISC_PERIOD) / DISC_PERIOD;
    }

    /**
     * Bearing the gimbal should point at, in degrees clockwise (0 = model forward, i.e. south).
     *
     * <p>While the scan runs the head tracks the mill's sorting disc ({@link #discAngle}) exactly, so
     * the sensor keeps the blade it is reading in view. After the scan the lock is released: a
     * ±{@link #GIMBAL_SWEEP} pan is added on top of the disc bearing, and it is damped back to 0 over
     * the last {@link #ENTER_TICKS} ticks, which is why the 59 -&gt; 0 hand-over is continuous (at the
     * seam the gimbal bearing is exactly the disc bearing again).</p>
     *
     * <p>The body keeps orbiting clockwise at 22.5 degrees per tick ({@link #orbitAngle}) while the
     * gimbal only follows the disc at 4.5 degrees per tick ({@link #discAngle}), so the two are in
     * opposite relative phase: the head counter-rotates against the turn instead of moving with it.</p>
     */
    public static double gimbalYaw(int t) {
        return discAngle(t) + sweepYaw(t) * releaseBlend(t);
    }

    /**
     * Gimbal pitch in degrees, where {@code -90} is straight down and {@code 0} is level.
     *
     * <p>Locked down at {@link #GIMBAL_DOWN_PITCH} while the scan tracks the disc, then blended out to
     * the normal −{@link #GIMBAL_SWEEP}..+{@link #GIMBAL_SWEEP} head sweep (the drop itself still
     * looks down: the release eases in over {@link #ENTER_TICKS} ticks after tick 24). The release is
     * pulled back in over the last {@link #ENTER_TICKS} ticks, so the head arrives at −60 again for
     * the next scan and both the 39/40 and the 59/0 joins are continuous.</p>
     */
    public static double gimbalPitch(int t) {
        double release = releaseBlend(t);
        return mix(GIMBAL_DOWN_PITCH, 0.0D, release) + sweepPitch(t) * release;
    }

    /**
     * How far the gimbal has let go of the disc lock: 0 during the scan, 1 in the middle of the reset
     * (the head is free to sweep) and 0 again exactly at the loop seam.
     *
     * <p>Both {@link #gimbalYaw(int)} and {@link #gimbalPitch(int)} are built from this one factor, so
     * "head on the disc" and "head sweeping" can never be shown at the same time on the two builds.</p>
     */
    public static double releaseBlend(int t) {
        int tick = tickInCycle(t);
        if (tick < SCAN_END) return 0.0D;
        double out = ease(Math.min(1.0D, (tick - SCAN_END) / (double) ENTER_TICKS));
        // The dive back onto the disc starts halfway through the reset and takes twice as long as the
        // release, so the head does not flick when it re-acquires the blades.
        int diveStart = DROP_END + ENTER_TICKS;
        double dive = tick <= diveStart ? 0.0D
                : ease((tick - diveStart) / (double) (PERIOD - 1 - diveStart));
        return out * (1.0D - dive);
    }

    /**
     * The released head's pan, in degrees: one full ±{@link #GIMBAL_SWEEP} swing across the rest of
     * the cycle, starting and ending at 0 so it can be added to the disc bearing.
     */
    public static double sweepYaw(int t) {
        int tick = tickInCycle(t);
        if (tick < SCAN_END) return 0.0D;
        double u = (tick - SCAN_END) / (double) (PERIOD - SCAN_END);
        return GIMBAL_SWEEP * Math.sin(2.0D * Math.PI * u);
    }

    /** The released head's tilt, in degrees: the same swing as {@link #sweepYaw}, inverted in phase. */
    public static double sweepPitch(int t) {
        return -sweepYaw(t);
    }

    /** Ease-in-out on [0, 1] (smoothstep): 0 and 1 at the ends, zero derivative at both. */
    public static double ease(double u) {
        double x = Math.max(0.0D, Math.min(1.0D, u));
        return x * x * (3.0D - 2.0D * x);
    }

    /** Linear blend between two values; {@code u} is clamped to [0, 1]. */
    public static double mix(double from, double to, double u) {
        double x = Math.max(0.0D, Math.min(1.0D, u));
        return from + (to - from) * x;
    }

    /** Channel-wise blend of two packed RGB colours (no alpha), rounded. */
    public static int mix(int from, int to, double u) {
        double x = Math.max(0.0D, Math.min(1.0D, u));
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * x);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * x);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * x);
        return (r << 16) | (g << 8) | b;
    }
}
