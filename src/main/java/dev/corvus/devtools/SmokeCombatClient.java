package dev.corvus.devtools;

import com.mojang.blaze3d.platform.NativeImage;
import dev.corvus.Corvus;
import dev.corvus.Hardware;
import dev.corvus.ModuleSync;
import dev.corvus.client.CommsSubtitles;
import dev.corvus.client.ScanDisplay;
import java.io.File;
import java.io.IOException;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client half of the combat/scan/dialogue acceptance run: it takes the two screenshots that prove the
 * layer is visible, at ticks the server script (see {@link SmokeCombat}) has already prepared.
 *
 * <ul>
 *   <li>tick 165 — scan markers + the spoken subtitle (scan finished at ~tick 142);</li>
 *   <li>tick 248 — the mute phase: the chip has been pulled and the server log shows the suppression,
 *       so this frame only proves the markers are still drawn; any line still on screen is the one
 *       spoken before the pull (the overlay clock pauses while a GUI screen is open).</li>
 * </ul>
 *
 * <p>Dev-only: active with {@code -Dcorvus.smoke=1}. The world shot of the phase-1 harness lands at
 * tick 150, a few ticks before the subtitle packet arrives, which is why this takes its own frames.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class SmokeCombatClient {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.smoke"));
    private static final Logger LOGGER = LoggerFactory.getLogger("corvus-smoke-combat");

    private static final int SUBTITLE_SHOT_AT = 165;
    private static final int CLOSE_SCREEN_AT = 244;
    private static final int MUTE_SHOT_AT = 258;

    private static int ticks;
    private static boolean subtitleShot;
    private static boolean muteShot;
    private static boolean screenClosed;

    private SmokeCombatClient() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        ticks++;
        if (ticks == 2 && client.options.getCameraType() == CameraType.FIRST_PERSON) {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        if (ticks == SUBTITLE_SHOT_AT && !subtitleShot) {
            subtitleShot = true;
            LOGGER.info("[smoke-c] shot 04: scan={} subtitle={}", ScanDisplay.debugState(), CommsSubtitles.debugState());
            LOGGER.info("[smoke-c] module sync: received={} hardwareSynced={} scanMk2Radius={}",
                    ModuleSync.synced().size(), Hardware.synced(),
                    ModuleSync.synced().containsKey(new net.minecraft.resources.ResourceLocation("huhu_dronepal", "scan_mk2"))
                            ? String.valueOf(ModuleSync.synced()
                                    .get(new net.minecraft.resources.ResourceLocation("huhu_dronepal", "scan_mk2"))
                                    .integer("radius"))
                            : "missing");
            grab("04-scan-markers-and-subtitle.png");
        }
        if (ticks == CLOSE_SCREEN_AT && !screenClosed) {
            screenClosed = true;
            // Closes the terminal/inventory screen the other harness opens, so the next frames render
            // the world and its overlays again (a screenshot captures the last rendered frame).
            client.setScreen(null);
        }
        if (ticks == MUTE_SHOT_AT && !muteShot) {
            muteShot = true;
            LOGGER.info("[smoke-c] shot 05 (mute run): markers still drawn, no NEW line was sent during the"
                    + " mute phase (see the server log); scan={} subtitle={}", ScanDisplay.debugState(),
                    CommsSubtitles.debugState());
            grab("05-scan-markers-mute-run.png");
        }
    }

    private static void grab(String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.error("[smoke-c] cannot create {}", directory.getAbsolutePath());
            return;
        }
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            LOGGER.info("[smoke-c] screenshot written: {} ({}x{})", target.getAbsolutePath(),
                    image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            LOGGER.error("[smoke-c] screenshot failed: {}", failure.toString());
        } finally {
            image.close();
        }
    }
}
