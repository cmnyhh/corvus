package dev.dronepal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.dronepal.client.DroneWheel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Real-client acceptance fixture for the V command wheel. It injects the same key state vanilla's
 * {@code KeyboardHandler} injects ({@link KeyMapping#set}), drives the three hold timings from the
 * acceptance list, and photographs every state:
 *
 * <ol>
 *   <li>50 ms tap with no controller: no wheel, no action (before the fix this flashes);</li>
 *   <li>500 ms hold with no controller: no wheel, hotbar line "no drone deployed" instead;</li>
 *   <li>50 ms tap with a deployed drone: no wheel, no action;</li>
 *   <li>300 ms hold with a deployed drone: wheel visible, aim at north, release: FOLLOW executes;</li>
 *   <li>500 ms hold, aim at the south-east (SCAN) sector, release: the sector under the cursor runs.</li>
 * </ol>
 *
 * <p>The key state is exactly what a real press/release sets, including the important side effect that
 * {@code Minecraft.setScreen(non-null)} calls {@code KeyMapping.releaseAll()} (see the timeline log).</p>
 */
@EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class WheelTimingVisual {
    private static final boolean ENABLED = "1".equals(System.getProperty("dronepal.wheelTiming"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("dronepal-wheel-timing");
    private static final InputConstants.Key V = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V);
    private static final long TAP_MS = 50;
    private static final long HOLD_MS = 300;
    private static final long LONG_HOLD_MS = 500;
    /** 0 = north, 3 = south-east: WORK is slot 8, the legacy eight sectors still own 0..7. */
    private static final int NORTH = 0, SOUTH_EAST = 3;

    private static int ticks, stage, releaseStage = -1;
    private static long stageAt;
    private static long pressedAt;
    private static int failures;
    private static final List<String> timeline = new ArrayList<>();
    private static volatile boolean followBefore, followAfter, scanBefore, scanAfter;
    private static volatile boolean droneSignal;
    private static Field mouseX, mouseY, wheelKey;
    private static KeyMapping key;

    private WheelTimingVisual() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null || mc.getOverlay() != null) return;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof PauseScreen) {
            mc.setScreen(null);
            return;
        }
        try {
            step(mc, server);
        } catch (Throwable failure) {
            LOGGER.error("[wheel-timing] fixture crashed", failure);
            check("fixture_no_crash", false, failure.toString());
            finish(mc);
        }
    }

    private static void step(Minecraft mc, MinecraftServer server) {
        long now = net.minecraft.Util.getMillis();
        switch (stage) {
            case 0 -> {
                // 140 ticks (~7 s) of warm-up: the first seconds after a world load run at a few TPS in
                // this dev client, and a "50 ms" tap must not silently become a 200 ms hold.
                if (++ticks < 140 || mc.screen != null) return;
                log("world ready after " + ticks + " ticks; clearing controllers and drones");
                server.execute(() -> clear(server));
                next(1, now);
            }
            case 1 -> {
                if (now - stageAt < 500) return;
                log("SCENARIO 1a: 50 ms tap, no drone");
                press();
                next(2, now);
            }
            case 2 -> {
                if (now - stageAt < TAP_MS) return;
                release();
                check("tap_no_drone_no_screen", mc.screen == null, "screen=" + screenName(mc));
                next(3, now);
            }
            case 3 -> {
                if (now - stageAt < 300) return;
                log("SCENARIO 1b: 500 ms hold, no drone -> must hint, not open");
                press();
                next(4, now);
            }
            case 4 -> {
                if (now - stageAt < HOLD_MS) return;
                check("hold_no_drone_no_screen", mc.screen == null, "screen=" + screenName(mc));
                capture(mc, "wheel-nodrone-hint.png");
                if (now - stageAt < LONG_HOLD_MS) return;
                release();
                next(5, now);
            }
            case 5 -> {
                if (now - stageAt < 300) return;
                server.execute(() -> equip(server));
                next(6, now);
            }
            case 6 -> {
                if (now - stageAt < 3000) return;
                log("controller worn client-side=" + controllerWorn(mc) + " server signal=" + droneSignal);
                check("controller_visible_client", controllerWorn(mc), "worn=false");
                log("SCENARIO 2a: 50 ms tap with drone, must do nothing");
                press();
                next(7, now);
            }
            case 7 -> {
                if (now - stageAt < TAP_MS) return;
                release();
                check("tap_drone_no_screen", mc.screen == null, "screen=" + screenName(mc));
                next(8, now);
            }
            case 8 -> {
                if (now - stageAt < 300) return;
                log("SCENARIO 2b: 300 ms hold, aim north, release");
                server.execute(() -> followBefore = followFlag(server));
                press();
                next(9, now);
            }
            case 9 -> {
                if (now - stageAt < HOLD_MS) return;
                check("hold_opens_wheel", mc.screen instanceof DroneWheel, "screen=" + screenName(mc));
                if (mc.screen instanceof DroneWheel) {
                    aim(mc, NORTH);
                    capture(mc, "wheel-hold-north.png");
                    log("wheel open at 300 ms; aimed at north sector; screen=" + screenName(mc));
                }
                release();
                next(10, now);
            }
            case 10 -> {
                if (now - stageAt < 600) return;
                server.execute(() -> {
                    followAfter = followFlag(server);
                    logServer("after FOLLOW release", server);
                });
                next(11, now);
            }
            case 11 -> {
                if (now - stageAt < 500) return;
                check("follow_toggled_server", followBefore != followAfter,
                        "before=" + followBefore + " after=" + followAfter);
                log("SCENARIO 3: 500 ms hold, aim south-east (SCAN), release");
                server.execute(() -> scanBefore = scanFlag(server));
                press();
                next(12, now);
            }
            case 12 -> {
                if (now - stageAt < HOLD_MS) return;
                check("hold_opens_wheel_take2", mc.screen instanceof DroneWheel, "screen=" + screenName(mc));
                if (mc.screen instanceof DroneWheel) aim(mc, SOUTH_EAST);
                if (now - stageAt < LONG_HOLD_MS) return;
                if (mc.screen instanceof DroneWheel) capture(mc, "wheel-hold-southeast.png");
                release();
                next(13, now);
            }
            case 13 -> {
                if (now - stageAt < 700) return;
                server.execute(() -> {
                    scanAfter = scanFlag(server);
                    logServer("after SCAN release", server);
                });
                next(14, now);
            }
            case 14 -> {
                if (now - stageAt < 600) return;
                check("scan_toggled_server", scanBefore != scanAfter,
                        "before=" + scanBefore + " after=" + scanAfter);
                finish(mc);
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- input

    private static void press() {
        pressedAt = net.minecraft.Util.getMillis();
        KeyMapping.set(V, true);
        log("press t=" + pressedAt + " keyDown=" + keyDown());
        timeline.add("PRESS@" + pressedAt + " screen=" + screenName(Minecraft.getInstance()));
    }

    private static void release() {
        KeyMapping.set(V, false);
        Screen screen = Minecraft.getInstance().screen;
        boolean handled = false;
        if (screen instanceof DroneWheel wheel) {
            handled = wheel.keyReleased(GLFW.GLFW_KEY_V, 0, 0);
        }
        log("release t=" + net.minecraft.Util.getMillis() + " held=" + (net.minecraft.Util.getMillis() - pressedAt)
                + " screenBefore=" + screenName(Minecraft.getInstance()) + " handled=" + handled);
        timeline.add("RELEASE@" + net.minecraft.Util.getMillis() + " heldMs="
                + (net.minecraft.Util.getMillis() - pressedAt));
    }

    /** The wheel's own KeyMapping, so the log shows the exact flag its client tick samples. */
    private static boolean keyDown() {
        try {
            if (wheelKey == null) {
                wheelKey = DroneWheel.class.getDeclaredField("KEY");
                wheelKey.setAccessible(true);
                key = (KeyMapping) wheelKey.get(null);
            }
            return key.isDown();
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    /** Places the pointer exactly in a sector centre, both for the draw path and release hit test. */
    private static void aim(Minecraft mc, int slot) {
        try {
            if (mouseX == null) {
                mouseX = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                mouseY = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                mouseX.setAccessible(true);
                mouseY.setAccessible(true);
            }
            int width = mc.screen.width, height = mc.screen.height;
            int[] point = sectorCentre(mc, slot, width, height);
            var window = mc.getWindow();
            double px = point[0] * window.getScreenWidth() / (double) width;
            double py = point[1] * window.getScreenHeight() / (double) height;
            mouseX.setDouble(mc.mouseHandler, px);
            mouseY.setDouble(mc.mouseHandler, py);
            GLFW.glfwSetCursorPos(window.getWindow(), px, py);
            log("aim slot=" + slot + " gui=" + point[0] + "," + point[1] + " window=" + px + "," + py);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Mirrors {@code WheelLayout.fit}/{@code sector} (the fixture package cannot see that record). */
    private static int[] sectorCentre(Minecraft mc, int slot, int width, int height) {
        var font = mc.font;
        int lines = font.split(Component.translatable("dronepal.wheel.hint"), Math.max(1, width - 20)).size();
        int footer = lines * (font.lineHeight + 1) + 12;
        int radius = Math.max(12, Math.min(140, Math.min(width / 2 - 6, height / 2 - footer)));
        int hole = Math.max(5, radius / 3);
        double angle = slot * 2 * Math.PI / 9;
        double distance = (radius + hole) / 2.0;
        int x = width / 2, y = height / 2;
        int px = (int) Math.round(x + Math.sin(angle) * distance);
        int py = (int) Math.round(y - Math.cos(angle) * distance);
        log("layout " + width + "x" + height + " radius=" + radius + " hole=" + hole
                + " sector=" + slot + " -> " + px + "," + py);
        return new int[] {px, py};
    }

    // ---------------------------------------------------------------- staging

    private static void clear(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        var level = player.serverLevel();
        int removed = 0;
        for (var drone : level.getEntitiesOfClass(DroneEntity.class, AABB.ofSize(player.position(), 96, 96, 96))) {
            drone.discard();
            removed++;
        }
        CuriosApi.getCuriosInventory(player).ifPresent(handler ->
                handler.getStacksHandler("drone").ifPresent(stacks ->
                        stacks.getStacks().setStackInSlot(0, ItemStack.EMPTY)));
        player.getPersistentData().remove(DroneItem.ACTIVE);
        log("cleared drones=" + removed + " controller=empty");
    }

    private static void equip(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ItemStack controller = Frames.create("peregrine");
        ItemStack battery = Hardware.BATTERIES.get(2).toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, battery,
                tag -> tag.putInt("energy", Hardware.capacity(battery)));
        var parts = new ArrayList<ItemStack>();
        parts.add(battery);
        parts.add(Hardware.moduleStack("scan_mk1"));
        controller.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(parts));
        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow()
                .getStacks().setStackInSlot(0, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = player.serverLevel().getEntity(DroneItem.serial(controller));
        droneSignal = drone instanceof DroneEntity entity && entity.isAlive();
        log("equipped controller serial=" + DroneItem.serial(controller) + " drone=" + drone
                + " signal=" + droneSignal + " followOff=" + DroneRemote.disabled(controller, "follow")
                + " scanOff=" + DroneRemote.disabled(controller, "scan"));
    }

    private static boolean followFlag(MinecraftServer server) {
        ItemStack stack = controller(server);
        boolean flag = !stack.isEmpty() && DroneRemote.disabled(stack, "follow");
        log("server followOff=" + flag);
        return flag;
    }

    private static boolean scanFlag(MinecraftServer server) {
        ItemStack stack = controller(server);
        boolean flag = !stack.isEmpty() && DroneRemote.disabled(stack, "scan");
        log("server scanOff=" + flag);
        return flag;
    }

    private static ItemStack controller(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        return CuriosApi.getCuriosInventory(player).flatMap(inv -> inv.getStacksHandler("drone"))
                .map(handler -> handler.getStacks().getStackInSlot(0)).orElse(ItemStack.EMPTY);
    }

    private static void logServer(String when, MinecraftServer server) {
        ItemStack stack = controller(server);
        log("server " + when + ": followOff=" + DroneRemote.disabled(stack, "follow")
                + " scanOff=" + DroneRemote.disabled(stack, "scan")
                + " capacity=" + Hardware.capacity(stack) + " energy=" + Hardware.energy(stack));
    }

    /** Does this client see a controller in the Curios slot? The wheel's no-drone gate uses this. */
    private static boolean controllerWorn(Minecraft mc) {
        try {
            return CuriosApi.getCuriosInventory(mc.player).flatMap(inv -> inv.getStacksHandler("drone"))
                    .map(handler -> {
                        var stacks = handler.getStacks();
                        for (int i = 0; i < stacks.getSlots(); i++) {
                            if (stacks.getStackInSlot(i).is(Dronepal.DRONE.get())) return true;
                        }
                        return false;
                    }).orElse(false);
        } catch (RuntimeException | LinkageError missing) {
            return false;
        }
    }

    // ---------------------------------------------------------------- output

    private static void next(int next, long now) {
        stage = next;
        stageAt = now;
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + name + " (" + detail + ")");
    }

    private static String screenName(Minecraft mc) {
        return mc.screen == null ? "null" : mc.screen.getClass().getSimpleName();
    }

    private static void log(String message) {
        LOGGER.info("[wheel-timing] {}", message);
    }

    private static void capture(Minecraft mc, String name) {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(System.getProperty("dronepal.wheel.dir", "artifacts/client"));
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
            log("screenshot " + dir.resolve(name) + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void finish(Minecraft mc) {
        log("DONE failures=" + failures + " timeline=" + timeline);
        if (mc.screen != null) mc.setScreen(null);
        mc.stop();
    }
}
