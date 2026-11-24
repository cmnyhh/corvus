package dev.dronepal;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class HudOptions {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER.comment("Show the deployed drone's power and module status in the upper left.")
            .translation("hud.dronepal.option").define("showDroneHud", true);
    public enum Markers { AUTO, LINES, PARTICLES }
    public static final ModConfigSpec.EnumValue<Markers> SCAN_MARKERS = BUILDER
            .comment("How scan targets are drawn. AUTO uses particles when a shader pack (Iris/Oculus) is active,",
                     "because custom line render types are frequently skipped by shader pipelines.")
            .defineEnum("scanMarkers", Markers.AUTO);
    static final ModConfigSpec SPEC = BUILDER.build();
    private HudOptions() {}
}
