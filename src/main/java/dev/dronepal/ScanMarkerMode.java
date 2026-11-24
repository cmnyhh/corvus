package dev.dronepal;

/** Marker rendering choice, kept separate from the client renderer so it can be tested server-side. */
public final class ScanMarkerMode {
    public static HudOptions.Markers resolve(HudOptions.Markers configured, boolean shaders) {
        return configured == HudOptions.Markers.AUTO
                ? (shaders ? HudOptions.Markers.PARTICLES : HudOptions.Markers.LINES)
                : configured;
    }
    private ScanMarkerMode() {}
}
