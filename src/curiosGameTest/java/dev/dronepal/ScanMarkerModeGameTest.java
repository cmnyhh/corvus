package dev.dronepal;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal") @PrefixGameTestTemplate(false)
public final class ScanMarkerModeGameTest {
    @GameTest(template = "curios_test_room")
    public static void autoPicksParticlesOnlyUnderShaders(GameTestHelper test) {
        test.assertTrue(ScanMarkerMode.resolve(HudOptions.Markers.AUTO, false) == HudOptions.Markers.LINES, "vanilla should draw lines");
        test.assertTrue(ScanMarkerMode.resolve(HudOptions.Markers.AUTO, true) == HudOptions.Markers.PARTICLES, "shaders should fall back to particles");
        test.assertTrue(ScanMarkerMode.resolve(HudOptions.Markers.LINES, true) == HudOptions.Markers.LINES, "explicit lines must be respected");
        test.assertTrue(ScanMarkerMode.resolve(HudOptions.Markers.PARTICLES, false) == HudOptions.Markers.PARTICLES, "explicit particles must be respected");
        test.succeed();
    }
}
