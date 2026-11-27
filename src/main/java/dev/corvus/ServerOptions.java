package dev.corvus;

import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server gameplay knobs. Registered as {@code dronepal-server.toml}, the same file name the 1.21 build
 * uses, so an operator's notes still apply.
 */
public final class ServerOptions {
    public enum Protection { always, creative_owner, never }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.EnumValue<Protection> INVULNERABILITY = BUILDER
            .comment("Protect drones from ordinary damage. Bypass damage such as /kill remains effective.")
            .defineEnum("invulnerability", Protection.creative_owner);

    public static final ForgeConfigSpec.DoubleValue CHARGE_RATE_MULTIPLIER = BUILDER
            .comment("Multiplies every battery's receive rate and the charging dock's cable input.",
                    "Raise it for packs whose power grid delivers tens of thousands of FE per tick.",
                    "Coal burning is intentionally left alone so it stays an emergency trickle.")
            .defineInRange("chargeRateMultiplier", 1.0D, 0.25D, 20.0D);

    public static final ForgeConfigSpec.IntValue AUTO_SCAN_SECONDS = BUILDER
            .comment("Seconds between automatic scans by a deployed, powered scanner.")
            .defineInRange("autoScanSeconds", 15, 5, 300);

    public static final ForgeConfigSpec.DoubleValue DRAIN_MULTIPLIER = BUILDER
            .comment("Scale on every module's draw (standby and in-use) and on the deployed airframe's 0.5 FE/t baseline.",
                    "0.25 = 4x the endurance, 1.0 = the pre-rebalance cost, 3.0 = 3x the cost (0.25-3.0, default 0.6).",
                    "It multiplies the whole settled bill once a second, so it never changes the relative cost of two modules.")
            .defineInRange("drainMultiplier", dev.corvus.core.HardwareRules.DEFAULT_DRAIN_MULTIPLIER,
                    dev.corvus.core.HardwareRules.MIN_DRAIN_MULTIPLIER,
                    dev.corvus.core.HardwareRules.MAX_DRAIN_MULTIPLIER);

    public static final ForgeConfigSpec.IntValue BURNER_OUTPUT = BUILDER
            .comment("FE per tick a burner generator pushes into its neighbours (10-400).",
                    "Fuel value is fixed at burn ticks x 2.5, so this only changes how fast it is released.")
            .defineInRange("burnerOutput", 40, 10, 400);

    public static final ForgeConfigSpec.IntValue SOLAR_OUTPUT = BUILDER
            .comment("Peak FE per tick of a solar generator in clear daylight (10-200).",
                    "Rain or thunder halves it; night or a blocked view of the sky gives 0.")
            .defineInRange("solarOutput", 60, 10, 200);

    public static final ForgeConfigSpec.IntValue BATTERY_BANK_CAPACITY = BUILDER
            .comment("Buffer of a battery bank, in FE. Input and output are fixed at 2,000 FE/t.")
            .defineInRange("batteryBankCapacity", 500_000, 1_000, 10_000_000);

    public static final ForgeConfigSpec.IntValue PORT_OUTPUT = BUILDER
            .comment("FE per tick a power port takes from its neighbours and hands to other mods (100-4000).",
                    "The port buffers 8,000 FE; this is also the ceiling another mod's consumer sees.",
                    "A cable run carries at most 2,000 FE/t regardless of this value.")
            .defineInRange("portOutput", 1_000, 100, 4_000);

    /**
     * Total FE per tick one cable network may move. Cables are general conductors: every one of our FE
     * blocks that touches a cable is an endpoint, and the network moves power source -> sink each tick
     * by priority (tower, battery, ore mill, dock, port). 32 blocks is the longest source-to-sink path.
     */
    public static final ForgeConfigSpec.IntValue CABLE_THROUGHPUT = BUILDER
            .comment("Total FE per tick one cable network may move (100-8000, default 2,000).",
                    "A network is every power_cable block connected to the same run.",
                    "A run longer than 32 blocks between source and sink carries nothing.")
            .defineInRange("cableThroughput", dev.corvus.core.EnergyNet.THROUGHPUT_DEFAULT,
                    dev.corvus.core.EnergyNet.THROUGHPUT_MIN, dev.corvus.core.EnergyNet.THROUGHPUT_MAX);

    // ---- wireless power grid (power_tower) ----
    public static final ForgeConfigSpec.IntValue TOWER_RADIUS = BUILDER
            .comment("Coverage radius of a power tower in blocks (8-64). Two towers at most this far",
                    "apart form one grid, and a device must be inside this radius to attach.",
                    "The pale coverage ring is shown while holding the deployment terminal (G).")
            .defineInRange("towerRadius", dev.corvus.core.Grid.DEFAULT_RADIUS,
                    dev.corvus.core.Grid.MIN_RADIUS, dev.corvus.core.Grid.MAX_RADIUS);
    public static final ForgeConfigSpec.IntValue TOWER_THROUGHPUT = BUILDER
            .comment("Output ceiling of one tower in FE/t (100-2,000). Every device attached to that",
                    "tower shares this budget: drones first, then ore mills, then charging docks.",
                    "A device that gets only part of its demand keeps running at a lower rate.")
            .defineInRange("towerThroughput", dev.corvus.core.Grid.DEFAULT_THROUGHPUT,
                    dev.corvus.core.Grid.MIN_THROUGHPUT, dev.corvus.core.Grid.MAX_THROUGHPUT);
    public static final ForgeConfigSpec.DoubleValue TOWER_EFFICIENCY = BUILDER
            .comment("Delivery ratio of one tower-to-tower hop (0.85-1.0). A device drawing through N",
                    "towers receives efficiency^N of the FE the supplying tower spends; more than five",
                    "hops is out of reach.")
            .defineInRange("towerEfficiency", dev.corvus.core.Grid.LINK_EFFICIENCY,
                    dev.corvus.core.Grid.MIN_EFFICIENCY, dev.corvus.core.Grid.MAX_EFFICIENCY);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ServerOptions() {}

    /** No-throw accessor: the config may be unloaded in early load or unit contexts. */
    /** Server-wide drain scale (0.25..3.0); 0.6 by default, see {@link #DRAIN_MULTIPLIER}. */
    public static double drainMultiplier() {
        try {
            return dev.corvus.core.HardwareRules.clampMultiplier(DRAIN_MULTIPLIER.get());
        } catch (RuntimeException unloaded) {
            return dev.corvus.core.HardwareRules.DEFAULT_DRAIN_MULTIPLIER;
        }
    }

    public static double chargeMultiplier() {
        try {
            return Mth.clamp(CHARGE_RATE_MULTIPLIER.get(), 0.25D, 20.0D);
        } catch (RuntimeException unloaded) {
            return 1.0D;
        }
    }

    public static int autoScanSeconds() {
        try {
            return Mth.clamp(AUTO_SCAN_SECONDS.get(), 5, 300);
        } catch (RuntimeException unloaded) {
            return 15;
        }
    }

    /** Burner generator output in FE/t; clamped like the config so a reload cannot go out of range. */
    public static int burnerOutput() {
        try {
            return Mth.clamp(BURNER_OUTPUT.get(), 10, 400);
        } catch (RuntimeException unloaded) {
            return 40;
        }
    }

    /** Solar peak output in FE/t at clear noon. */
    public static int solarOutput() {
        try {
            return Mth.clamp(SOLAR_OUTPUT.get(), 10, 200);
        } catch (RuntimeException unloaded) {
            return 60;
        }
    }

    /** Battery bank buffer in FE. */
    public static int batteryBankCapacity() {
        try {
            return Mth.clamp(BATTERY_BANK_CAPACITY.get(), 1_000, 10_000_000);
        } catch (RuntimeException unloaded) {
            return 500_000;
        }
    }

    /** Per-network cable throughput, FE/t, clamped to the shared 100..8000 range. */
    public static int cableThroughput() {
        try {
            return dev.corvus.core.EnergyNet.clampThroughput(CABLE_THROUGHPUT.get());
        } catch (RuntimeException unloaded) {
            return dev.corvus.core.EnergyNet.THROUGHPUT_DEFAULT;
        }
    }

    /** Power port ceiling in FE/t, both for intake and for what a consumer may pull. */
    public static int portOutput() {
        try {
            return Mth.clamp(PORT_OUTPUT.get(), 100, 4_000);
        } catch (RuntimeException unloaded) {
            return 1_000;
        }
    }

    /** Grid link/coverage radius in blocks, always inside the documented 8..64 range. */
    public static int towerRadius() {
        try {
            return dev.corvus.core.Grid.clampRadius(TOWER_RADIUS.get());
        } catch (RuntimeException unloaded) {
            return dev.corvus.core.Grid.DEFAULT_RADIUS;
        }
    }

    /** Per-tower shared output budget, FE/t. */
    public static int towerThroughput() {
        try {
            return dev.corvus.core.Grid.clampThroughput(TOWER_THROUGHPUT.get());
        } catch (RuntimeException unloaded) {
            return dev.corvus.core.Grid.DEFAULT_THROUGHPUT;
        }
    }

    /** Per-hop delivery ratio, 0.85..1.0. */
    public static double towerEfficiency() {
        try {
            return dev.corvus.core.Grid.clampEfficiency(TOWER_EFFICIENCY.get());
        } catch (RuntimeException unloaded) {
            return dev.corvus.core.Grid.LINK_EFFICIENCY;
        }
    }

    /** Active pull from the six neighbours; a shared rule constant, not a config knob. */
    public static int towerPullRate() { return dev.corvus.core.Grid.TOWER_PULL_RATE; }

    /**
     * Dev-tool seam: forces the policy for the acceptance run ({@code HardwareSmoke}); {@code null}
     * reads the config. The loaded Forge config cannot be rewritten at runtime, and the three policies
     * are a player-visible contract that deserves a real assertion.
     */
    private static volatile Protection protectionOverride;

    public static void testProtection(Protection policy) {
        protectionOverride = policy;
    }

    public static Protection protection() {
        if (protectionOverride != null) return protectionOverride;
        try {
            return INVULNERABILITY.get();
        } catch (RuntimeException unloaded) {
            return Protection.creative_owner;
        }
    }
}
