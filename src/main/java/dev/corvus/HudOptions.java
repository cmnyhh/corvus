package dev.corvus;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client display knobs, registered as the {@code client} config ({@code dronepal-client.toml}). */
public final class HudOptions {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Show the deployed drone's power and module status in the upper left.")
            .translation("hud.dronepal.option")
            .define("showDroneHud", true);

    public enum Markers { AUTO, LINES, PARTICLES }

    public static final ForgeConfigSpec.EnumValue<Markers> SCAN_MARKERS = BUILDER
            .comment("How scan targets are drawn. AUTO uses particles when a shader pack (Iris/Oculus)",
                    "is active, because custom line render types are frequently skipped by shader pipelines.")
            .defineEnum("scanMarkers", Markers.AUTO);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private HudOptions() {}

    /** No-throw accessor: the client config is unavailable on a dedicated server. */
    public static Markers scanMarkers() {
        try {
            return SCAN_MARKERS.get();
        } catch (RuntimeException unloaded) {
            return Markers.AUTO;
        }
    }

    public static boolean hudEnabled() {
        try {
            return ENABLED.get();
        } catch (RuntimeException unloaded) {
            return true;
        }
    }
}
