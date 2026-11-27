package dev.dronepal;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ServerOptions {
    enum Protection { always, creative_owner, never }
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    static final ModConfigSpec.EnumValue<Protection> INVULNERABILITY = BUILDER
        .comment("Protect drones from ordinary damage. Bypass damage such as /kill remains effective.")
        .defineEnum("invulnerability", Protection.creative_owner);
    static final ModConfigSpec.DoubleValue CHARGE_RATE_MULTIPLIER = BUILDER
        .comment("Multiplies every battery's receive rate and the charging dock's cable input.",
                 "Raise it for packs whose power grid delivers tens of thousands of FE per tick.",
                 "Coal burning is intentionally left alone so it stays an emergency trickle.")
        .defineInRange("chargeRateMultiplier", 1.0D, 0.25D, 20.0D);
    static final ModConfigSpec.IntValue AUTO_SCAN_SECONDS = BUILDER
        .comment("Seconds between automatic scans by a deployed, powered scanner.")
        .defineInRange("autoScanSeconds", 15, 5, 300);
    static final ModConfigSpec.DoubleValue DRAIN_MULTIPLIER = BUILDER
        .comment("Scale on every module's draw (standby and in-use) and on the deployed airframe's 0.5 FE/t baseline.",
                 "0.25 = 4x the endurance, 1.0 = the pre-rebalance cost, 3.0 = 3x the cost (0.25-3.0, default 0.6).",
                 "It multiplies the whole settled bill once a second, so it never changes the relative cost of two modules.",
                 "The HUD's FE/s figure and the remaining-time estimate already include it.")
        .defineInRange("drainMultiplier", dev.corvus.core.HardwareRules.DEFAULT_DRAIN_MULTIPLIER,
                dev.corvus.core.HardwareRules.MIN_DRAIN_MULTIPLIER,
                dev.corvus.core.HardwareRules.MAX_DRAIN_MULTIPLIER);
    static final ModConfigSpec.IntValue BURNER_OUTPUT = BUILDER
        .comment("Burner generator output in FE per tick. Any furnace fuel is worth burnTicks x 2.5 FE,",
                 "so one coal lump feeds 4,000 FE regardless of this rate; the buffer is always 100,000 FE.")
        .defineInRange("burnerOutput", 40, 10, 400);
    static final ModConfigSpec.IntValue SOLAR_OUTPUT = BUILDER
        .comment("Solar generator peak output in FE per tick, measured in clear daylight.",
                 "Rain and thunderstorms halve it; night and a covered sky produce nothing. Buffer is 50,000 FE.")
        .defineInRange("solarOutput", 60, 10, 200);
    static final ModConfigSpec.IntValue BATTERY_BANK_CAPACITY = BUILDER
        .comment("Battery bank buffer in FE. Input and output are capped at 2,000 FE/t each, lossless.",
                 "Existing banks keep the energy they hold when this changes; only the ceiling moves.")
        .defineInRange("batteryBankCapacity", 500_000, 1_000, 10_000_000);
    static final ModConfigSpec.IntValue PORT_OUTPUT = BUILDER
        .comment("FE per tick a power port takes from its neighbours and hands to other mods (100-4000).",
                 "The port buffers 8,000 FE; this is also the ceiling another mod's consumer sees.",
                 "A cable run carries at most 2,000 FE/t regardless of this value.")
        .defineInRange("portOutput", 1_000, 100, 4_000);
    static final ModConfigSpec.IntValue CABLE_THROUGHPUT = BUILDER
        .comment("Total FE per tick one cable network may move (100-8000, default 2,000).",
                 "A network is every power_cable block connected to the same run. Cables are general",
                 "conductors: any of our FE blocks that touches a cable is an endpoint, and the network",
                 "moves power source -> sink each tick by priority (tower, battery, ore mill, dock, port).",
                 "A run longer than 32 blocks between source and sink carries nothing.")
        .defineInRange("cableThroughput", dev.corvus.core.EnergyNet.THROUGHPUT_DEFAULT,
                dev.corvus.core.EnergyNet.THROUGHPUT_MIN, dev.corvus.core.EnergyNet.THROUGHPUT_MAX);
    // ---- wireless power grid (power_tower) ----
    static final ModConfigSpec.IntValue TOWER_RADIUS = BUILDER
        .comment("Coverage radius of a power tower in blocks (8-64). Two towers at most this far apart",
                 "form one grid, and a device must be inside this radius to attach to a tower.",
                 "The pale coverage ring is shown while holding the deployment terminal (G toggles it).")
        .defineInRange("towerRadius", dev.corvus.core.Grid.DEFAULT_RADIUS,
                dev.corvus.core.Grid.MIN_RADIUS, dev.corvus.core.Grid.MAX_RADIUS);
    static final ModConfigSpec.IntValue TOWER_THROUGHPUT = BUILDER
        .comment("Output ceiling of one tower in FE/t (100-2,000). Every device directly attached to",
                 "that tower shares this budget: drones first, then ore mills, then charging docks.",
                 "A device that gets only part of its demand keeps running at a lower rate.")
        .defineInRange("towerThroughput", dev.corvus.core.Grid.DEFAULT_THROUGHPUT,
                dev.corvus.core.Grid.MIN_THROUGHPUT, dev.corvus.core.Grid.MAX_THROUGHPUT);
    static final ModConfigSpec.DoubleValue TOWER_EFFICIENCY = BUILDER
        .comment("Delivery ratio of one tower-to-tower hop (0.85-1.0). A device drawing through N",
                 "towers receives efficiency^N of the FE the supplying tower spends; more than five",
                 "hops is out of reach.")
        .defineInRange("towerEfficiency", dev.corvus.core.Grid.LINK_EFFICIENCY,
                dev.corvus.core.Grid.MIN_EFFICIENCY, dev.corvus.core.Grid.MAX_EFFICIENCY);
    static final ModConfigSpec SPEC = BUILDER.build();
    /** No-throw accessor: config may be unloaded in early unit contexts. */
    public static double chargeMultiplier() {
        try { return Math.clamp(CHARGE_RATE_MULTIPLIER.get(), 0.25D, 20.0D); }
        catch (RuntimeException unloaded) { return 1.0D; }
    }
    /** Server-wide drain scale (0.25..3.0); the default is 0.6, see {@link #DRAIN_MULTIPLIER}. */
    public static double drainMultiplier() {
        try { return dev.corvus.core.HardwareRules.clampMultiplier(DRAIN_MULTIPLIER.get()); }
        catch (RuntimeException unloaded) { return dev.corvus.core.HardwareRules.DEFAULT_DRAIN_MULTIPLIER; }
    }
    public static int burnerOutput() {
        try { return Math.clamp(BURNER_OUTPUT.get(), 10, 400); }
        catch (RuntimeException unloaded) { return 40; }
    }
    public static int solarOutput() {
        try { return Math.clamp(SOLAR_OUTPUT.get(), 10, 200); }
        catch (RuntimeException unloaded) { return 60; }
    }
    public static int batteryBankCapacity() {
        try { return Math.clamp(BATTERY_BANK_CAPACITY.get(), 1_000, 10_000_000); }
        catch (RuntimeException unloaded) { return 500_000; }
    }
    public static int portOutput() {
        try { return Math.clamp(PORT_OUTPUT.get(), 100, 4_000); }
        catch (RuntimeException unloaded) { return 1_000; }
    }
    /** Per-network cable throughput, FE/t; clamped to the shared 100..8000 range even if the file is junk. */
    public static int cableThroughput() {
        try { return dev.corvus.core.EnergyNet.clampThroughput(CABLE_THROUGHPUT.get()); }
        catch (RuntimeException unloaded) { return dev.corvus.core.EnergyNet.THROUGHPUT_DEFAULT; }
    }
    /** Grid link/coverage radius; clamped to the documented 8..64 even if the file holds junk. */
    public static int towerRadius() {
        try { return dev.corvus.core.Grid.clampRadius(TOWER_RADIUS.get()); }
        catch (RuntimeException unloaded) { return dev.corvus.core.Grid.DEFAULT_RADIUS; }
    }
    /** Per-tower shared output budget, FE/t. */
    public static int towerThroughput() {
        try { return dev.corvus.core.Grid.clampThroughput(TOWER_THROUGHPUT.get()); }
        catch (RuntimeException unloaded) { return dev.corvus.core.Grid.DEFAULT_THROUGHPUT; }
    }
    /** Per-hop delivery ratio, 0.85..1.0. */
    public static double towerEfficiency() {
        try { return dev.corvus.core.Grid.clampEfficiency(TOWER_EFFICIENCY.get()); }
        catch (RuntimeException unloaded) { return dev.corvus.core.Grid.LINK_EFFICIENCY; }
    }
    /** Active pull from the six neighbours; a shared rule constant, not a config knob. */
    public static int towerPullRate() { return dev.corvus.core.Grid.TOWER_PULL_RATE; }
    private ServerOptions() {}
}
