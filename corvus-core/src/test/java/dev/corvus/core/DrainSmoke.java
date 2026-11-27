package dev.corvus.core;

/**
 * Printable smoke for the drain rebalance: the same arithmetic {@link DrainTest} asserts, written out
 * as the number table the report quotes.
 *
 * <p>No JUnit and no Minecraft, so it runs with a plain {@code javac}/{@code java} pair in either
 * build's toolchain:</p>
 *
 * <pre>
 * javac -d /tmp/drain corvus-core/src/main/java/dev/corvus/core/HardwareRules.java \
 *                       corvus-core/src/test/java/dev/corvus/core/DrainSmoke.java
 * java -cp /tmp/drain dev.corvus.core.DrainSmoke
 * </pre>
 */
public final class DrainSmoke {
    /** Module table after the rebalance: family, idle FE/t, active FE/t (the four the loadout uses). */
    private static final String[] FAMILIES = {"combat_mk1", "scan_mk1", "carry_mk1", "light_mk1"};
    private static final double[] IDLE = {3, 0, 0, 0};
    private static final double[] ACTIVE = {14, 6, 5, 2};

    /** Batteries after the rebalance: id, capacity FE, receive FE/t. */
    private static final String[] CELLS = {"cvs_bat_r1", "omd_bat_100", "omd_bat_200", "rn_bat_g1",
            "ahi_bat_mk1", "cvs_bat_r2", "omd_bat_300", "rn_bat_g2", "ahi_bat_mk2", "ahi_bat_mk3"};
    private static final long[] CAPACITY = {300_000L, 360_000L, 700_000L, 450_000L, 500_000L, 600_000L,
            1_200_000L, 900_000L, 1_200_000L, 2_400_000L};
    private static final int[] RECEIVE = {800, 600, 800, 675, 1_200, 1_000, 1_000, 900, 1_600, 2_000};

    private DrainSmoke() {}

    public static void main(String[] args) {
        double multiplier = HardwareRules.DEFAULT_DRAIN_MULTIPLIER;
        double baseline = HardwareRules.baselinePerTick(true);
        int[] online = {1, 1, 1, 1};

        System.out.println("drainMultiplier = " + multiplier + " (clamp "
                + HardwareRules.MIN_DRAIN_MULTIPLIER + ".." + HardwareRules.MAX_DRAIN_MULTIPLIER + ")");
        System.out.println("deployed baseline = " + baseline + " FE/t");
        System.out.println();
        System.out.printf("%-12s %8s %8s %12s %12s%n", "module", "idle/t", "active/t", "idle (x0.6)",
                "active (x0.6)");
        for (int i = 0; i < FAMILIES.length; i++) {
            System.out.printf("%-12s %8.1f %8.1f %12.2f %12.2f%n", FAMILIES[i], IDLE[i], ACTIVE[i],
                    IDLE[i] * multiplier, ACTIVE[i] * multiplier);
        }

        double off = HardwareRules.drainPerTick(IDLE, ACTIVE, new int[] {0, 0, 0, 0}, new int[] {0, 0, 0, 0},
                true, multiplier);
        double standby = HardwareRules.drainPerTick(IDLE, ACTIVE, online, new int[] {0, 0, 0, 0}, true, multiplier);
        double cruise = HardwareRules.drainPerTick(IDLE, ACTIVE, online, new int[] {0, 0, 0, 1}, true, multiplier);
        double engaged = HardwareRules.drainPerTick(IDLE, ACTIVE, online, new int[] {1, 1, 1, 1}, true, multiplier);
        double before = 0;
        for (double watts : ACTIVE) before += watts;

        System.out.println();
        System.out.printf("%-34s %10s %10s %10s%n", "loadout", "FE/t", "FE/s", "before FE/t");
        line("every module switched off (all off)", off, before);
        line("standing by, combat armed", standby, before);
        line("cruising (lamp on)", cruise, before);
        line("scanning + engaged + hauling + lamp", engaged, before);

        System.out.println();
        System.out.printf("%-14s %10s %8s %12s %12s %12s%n", "cell", "capacity", "recv/t",
                "off HH:MM", "standby HH:MM", "cruise HH:MM");
        for (int i = 0; i < CELLS.length; i++) {
            System.out.printf("%-14s %10d %8d %12s %12s %12s%n", CELLS[i], CAPACITY[i], RECEIVE[i],
                    eta(CAPACITY[i], off), eta(CAPACITY[i], standby), eta(CAPACITY[i], cruise));
        }

        long smallest = CAPACITY[0];
        System.out.println();
        System.out.printf("smallest cell %d FE: all-off %s h, cruise %s h (target: >= 3 h / >= 1 h)%n",
                smallest, hours(smallest, off), hours(smallest, cruise));
        System.out.printf("reference rn_bat_g1 450,000 FE: all-off %s h, cruise %s h%n",
                hours(450_000L, off), hours(450_000L, cruise));
    }

    private static void line(String label, double perTick, double beforePerTick) {
        System.out.printf("%-34s %10.2f %10d %10.2f%n", label, perTick,
                HardwareRules.drainForWindow(perTick, 20), beforePerTick);
    }

    /** HH:MM from empty at a steady draw; unlike the HUD helper this has no "full/charging" dash. */
    private static String eta(long capacity, double perTick) {
        long seconds = (long) (capacity / (perTick * 20.0D));
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 3_600L, seconds % 3_600L / 60L);
    }

    private static String hours(long capacity, double perTick) {
        double seconds = capacity / (perTick * 20.0D);
        return String.format(java.util.Locale.ROOT, "%.2f", seconds / 3_600.0D);
    }
}
