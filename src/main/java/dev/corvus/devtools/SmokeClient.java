package dev.corvus.devtools;

import dev.corvus.Corvus;
import dev.corvus.DroneEntity;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Development-only smoke harness (client half): third-person camera, screenshots, and a creative-tab
 * shot. Active only with {@code -Dcorvus.smoke=1}; it writes to {@code -Dcorvus.artifacts}
 * (defaults to {@code artifacts/client} under the run directory).
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class SmokeClient {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.smoke"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-smoke");
    private static final int CAMERA_AT = 110;
    private static final int WORLD_SHOT_AT = 150;
    private static final int TAB_AT = 180;
    private static final int TAB_SHOT_AT = 205;
    private static final int INVENTORY_AT = 225;
    private static final int INVENTORY_SHOT_AT = 240;
    private static final int DONE_AT = 270;

    private static int ticks;
    private static boolean cameraSet;
    private static boolean worldShot;
    private static boolean tabOpened;
    private static boolean tabShot;
    private static boolean inventoryOpened;
    private static boolean inventoryShot;
    private static boolean reported;

    private SmokeClient() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        ticks++;
        if (ticks == 1) {
            // A dev run has no window focus: without this the game opens the pause menu and the
            // integrated server stops ticking, so nothing would deploy.
            minecraft.options.pauseOnLostFocus = false;
            LOGGER.info("[corvus-smoke] pauseOnLostFocus=false, quickPlay world loaded");
        }
        if (ticks == 20) logClientView(minecraft, "spawned");
        if (ticks == CAMERA_AT && !cameraSet) {
            cameraSet = true;
            if (minecraft.screen != null) minecraft.setScreen(null);
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            LOGGER.info("[corvus-smoke] camera -> {}", minecraft.options.getCameraType());
        }
        if (ticks == WORLD_SHOT_AT && !worldShot) {
            worldShot = true;
            logClientView(minecraft, "before shot");
            grab("01-third-person.png");
        }
        if (ticks == TAB_AT && !tabOpened) {
            tabOpened = true;
            openCreativeTab();
        }
        if (ticks == TAB_SHOT_AT && !tabShot && minecraft.screen instanceof CreativeModeInventoryScreen) {
            tabShot = true;
            grab("02-creative-tab.png");
        }
        if (ticks == INVENTORY_AT && !inventoryOpened) {
            inventoryOpened = true;
            if (minecraft.player != null) {
                minecraft.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(minecraft.player));
                LOGGER.info("[corvus-smoke] inventory screen opened (Curios panel shows the drone slot)");
            }
        }
        if (ticks == INVENTORY_SHOT_AT && !inventoryShot) {
            inventoryShot = true;
            LOGGER.info("[corvus-smoke] screen at inventory shot: {}",
                    minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
            if (minecraft.screen != null) grab("03-inventory-curios.png");
        }
        if (ticks == DONE_AT && !reported) {
            reported = true;
            if (minecraft.screen != null) minecraft.setScreen(null);
            LOGGER.info("[corvus-smoke] client sequence finished (ticks={}), stopping the game", ticks);
            // Shut down cleanly: saves the world, releases the session lock and lets gradle exit.
            minecraft.stop();
        }
    }

    private static void logClientView(Minecraft minecraft, String when) {
        DroneEntity seen = null;
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone) {
                seen = drone;
                break;
            }
        }
        if (seen == null) {
            LOGGER.warn("[corvus-smoke] {}: no DroneEntity client-side yet (player at {})",
                    when, minecraft.player.blockPosition());
            return;
        }
        LOGGER.info("[corvus-smoke] {}: client sees drone frame={} paint={} pos={} dist={} yaw={}",
                when, seen.getFrameId(), seen.getPaintName(), seen.position(),
                seen.distanceTo(minecraft.player), seen.getYRot());
    }

    private static void grab(String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.error("[corvus-smoke] cannot create screenshot directory {}", directory.getAbsolutePath());
            return;
        }
        // Take the screenshot ourselves instead of Screenshot.grab: grab() forces a "screenshots"
        // sub-directory, and the acceptance path is artifacts/client/<name>.png.
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            LOGGER.info("[corvus-smoke] screenshot written: {} ({}x{})",
                    target.getAbsolutePath(), image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            LOGGER.error("[corvus-smoke] screenshot failed: {}", failure.toString());
        } finally {
            image.close();
        }
    }

    private static void openCreativeTab() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            CreativeModeInventoryScreen screen = new CreativeModeInventoryScreen(
                    minecraft.player, FeatureFlags.VANILLA_SET, true);
            minecraft.setScreen(screen);
            CreativeModeTab tab = Corvus.TAB.get();
            Method select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
            select.setAccessible(true);
            select.invoke(screen, tab);
            LOGGER.info("[corvus-smoke] creative tab '{}' items={}",
                    tab.getDisplayName().getString(), tab.getDisplayItems().size());
        } catch (ReflectiveOperationException failure) {
            LOGGER.warn("[corvus-smoke] could not focus the Corvus creative tab: {}", failure.toString());
        }
    }
}
