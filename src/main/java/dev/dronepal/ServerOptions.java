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
    static final ModConfigSpec SPEC = BUILDER.build();
    /** No-throw accessor: config may be unloaded in early unit contexts. */
    public static double chargeMultiplier() {
        try { return Math.clamp(CHARGE_RATE_MULTIPLIER.get(), 0.25D, 20.0D); }
        catch (RuntimeException unloaded) { return 1.0D; }
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
    private ServerOptions() {}
}
