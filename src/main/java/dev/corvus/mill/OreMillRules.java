package dev.corvus.mill;

import java.util.List;

/**
 * Every tunable number of the ore mill in one place, copied literally from the 1.21.1 build.
 *
 * <p>Manual operation takes {@value #MANUAL_TICKS} ticks (6 s) per ore and yields one concentrate plus a
 * {@value #MANUAL_BYPRODUCT} by-product roll. A drone on station works in {@value #DRONE_TICKS} ticks
 * (1.5 s), doubles the concentrate for common ores and rolls {@value #DRONE_BYPRODUCT}. One item always
 * costs {@value #FE_PER_ITEM} FE; the machine accepts at most {@value #FE_PER_TICK} FE/t from its
 * neighbours and buffers {@value #BUFFER} FE. The tank holds {@value #WATER_CAPACITY} mB and each ore
 * needs {@value #WATER_PER_ITEM} mB ({@value #WATER_PER_ITEM_DRONE} mB under a drone). A water pump next
 * door pushes {@value #PUMP_RATE} mB/t into its neighbours and buffers {@value #PUMP_BUFFER} mB.</p>
 */
public final class OreMillRules {
    public static final int BUFFER = 60_000;
    /** Input cap, and the number the GUI prints as "20 FE/t". */
    public static final int FE_PER_TICK = 20;
    public static final int FE_PER_ITEM = 1_200;

    public static final int MANUAL_TICKS = 120;
    public static final int DRONE_TICKS = 30;

    public static final double MANUAL_BYPRODUCT = 0.20D;
    public static final double DRONE_BYPRODUCT = 0.35D;
    public static final double SIEVE_BYPRODUCT_BONUS = 0.15D;

    public static final double RARE_CHANCE = 0.12D;
    public static final double SIEVE_RARE_CHANCE = 0.18D;

    public static final int WATER_CAPACITY = 4_000;
    public static final int WATER_PER_ITEM = 250;
    public static final int WATER_PER_ITEM_DRONE = 300;
    public static final int WATER_PER_TICK = 0;

    public static final int PUMP_RATE = 200;
    public static final int PUMP_BUFFER = 8_000;

    public static final double COOLING_SPEED = 1.5D;
    public static final double COOLING_POWER = 1.4D;

    /** The drone's own hover/work draw, on top of whatever the machine consumes. */
    public static final int DRONE_FE_PER_TICK = 20;
    public static final double DRONE_STOP_CHARGE = 0.15D;
    public static final double DRONE_RESUME_CHARGE = 0.40D;
    public static final int WORK_RANGE = 32;

    public static final List<String> FRAME_IDS = List.of("peregrine", "owl", "bedrock", "scrap");
    private static final double[] FRAME_SPEED = {1.0D, 1.25D, 1.5D, 0.75D};

    private OreMillRules() {}

    public static double frameSpeed(String frameId) {
        int index = FRAME_IDS.indexOf(frameId);
        return index < 0 ? 1.0D : FRAME_SPEED[index];
    }

    /** Without a carry module the drone only manages half speed. */
    public static double droneFactor(String frameId, boolean carry, boolean cooling) {
        return frameSpeed(frameId) * (carry ? 1.0D : 0.5D) * (cooling ? COOLING_SPEED : 1.0D);
    }

    public static int ticksPerItem(boolean drone, String frameId, boolean carry, boolean cooling) {
        double factor = drone ? droneFactor(frameId, carry, cooling) : (cooling ? COOLING_SPEED : 1.0D);
        int base = drone ? DRONE_TICKS : MANUAL_TICKS;
        return Math.max(1, (int) Math.round(base / factor));
    }

    public static int energyPerItem(boolean cooling) {
        return (int) Math.round(FE_PER_ITEM * (cooling ? COOLING_POWER : 1.0D));
    }

    public static int waterPerItem(boolean drone) {
        return drone ? WATER_PER_ITEM_DRONE : WATER_PER_ITEM;
    }

    public static double byproductChance(boolean drone, boolean sieve) {
        double base = drone ? DRONE_BYPRODUCT : MANUAL_BYPRODUCT;
        return Math.min(1.0D, sieve ? base + SIEVE_BYPRODUCT_BONUS : base);
    }

    public static double rareChance(boolean sieve) {
        return sieve ? SIEVE_RARE_CHANCE : RARE_CHANCE;
    }

    /** Common ores double under a drone; the three rare families never do. */
    public static int concentrateCount(OreKind kind, boolean drone) {
        return kind.rare() ? 1 : drone ? 2 : 1;
    }
}
