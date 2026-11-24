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
    static final ModConfigSpec SPEC = BUILDER.build();
    /** No-throw accessor: config may be unloaded in early unit contexts. */
    public static double chargeMultiplier() {
        try { return Math.clamp(CHARGE_RATE_MULTIPLIER.get(), 0.25D, 20.0D); }
        catch (RuntimeException unloaded) { return 1.0D; }
    }
    private ServerOptions() {}
}
