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

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ServerOptions() {}

    /** No-throw accessor: the config may be unloaded in early load or unit contexts. */
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

    public static Protection protection() {
        try {
            return INVULNERABILITY.get();
        } catch (RuntimeException unloaded) {
            return Protection.creative_owner;
        }
    }
}
