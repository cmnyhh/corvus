package dev.corvus;

/**
 * Marker rendering choice, kept out of the client renderer so the resolve rule can be exercised
 * server-side (and in the smoke harness) without a GL context.
 *
 * <p>Shader packs (Iris/Oculus) routinely skip custom line render types, so {@code AUTO} falls back
 * to dust particles whenever a pack is active. Ported unchanged from 1.21; the configured value now
 * comes from {@link HudOptions#scanMarkers()}, which is a no-throw reader.</p>
 */
public final class ScanMarkerMode {
    public static HudOptions.Markers resolve(HudOptions.Markers configured, boolean shaders) {
        if (configured == null) configured = HudOptions.Markers.AUTO;
        return configured == HudOptions.Markers.AUTO
                ? (shaders ? HudOptions.Markers.PARTICLES : HudOptions.Markers.LINES)
                : configured;
    }

    /** Convenience overload: reads the client config with a no-throw fallback. */
    public static HudOptions.Markers current(boolean shaders) {
        return resolve(HudOptions.scanMarkers(), shaders);
    }

    private ScanMarkerMode() {}
}
