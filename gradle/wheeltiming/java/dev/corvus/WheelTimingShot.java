package dev.corvus;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.corvus.client.DroneWheel;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * 1.20.1 twin of the 1.21.1 {@code WheelTimingVisual}. It reproduces the real key path for this loader:
 * {@link KeyMapping#set} plus {@link InputEvent.Key} (which is what opens the wheel here), then the
 * release through {@code Screen.keyReleased}. The 500 ms hold is the regression: this build's
 * {@code clientTick} watchdog used {@code KEY.isDown()}, which is cleared by
 * {@code Minecraft.setScreen}'s {@code KeyMapping.releaseAll()}, so the wheel closes one tick after it
 * opens.
 *
 * <p>Dev-only: compiled only when {@code gradle/wheel-timing.init.gradle} adds this directory, and
 * inert unless {@code -Dcorvus.wheelTiming=1}.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class WheelTimingShot {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.wheelTiming"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-wheel-timing");
    private static final InputConstants.Key V = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V);
    private static final long TAP_MS = 50, HOLD_MS = 300, LONG_HOLD_MS = 500;
    private static final int NORTH = 0, SOUTH_EAST = 3;

    private static int ticks, stage;
    private static long stageAt, pressedAt;
    private static int failures;
    private static final List<String> timeline = new ArrayList<>();
    private static volatile boolean followBefore, followAfter, scanBefore, scanAfter;
    private static volatile boolean droneSignal;
    private static Field mouseX, mouseY, wheelKey;
    private static KeyMapping key;

    private WheelTimingShot() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
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
                if (++ticks < 40 || mc.screen != null) return;
                log("world ready; clearing controllers and drones");
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
                capture(mc, "wheel-nodrone-hint-1.20.1.png");
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
                    capture(mc, "wheel-hold-north-1.20.1.png");
                    log("wheel open at 300 ms; aimed at north sector; screen=" + screenName(mc));
                }
                release();
                next(10, now);
            }
            case 10 -> {
                if (now - stageAt < 700) return;
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
                if (mc.screen instanceof DroneWheel) capture(mc, "wheel-hold-southeast-1.20.1.png");
                release();
                next(13, now);
            }
            case 13 -> {
                if (now - stageAt < 2500) return;
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

    // ---------------------------------------------------------------- input, in KeyboardHandler order

    private static void press() {
        pressedAt = net.minecraft.Util.getMillis();
        KeyMapping.set(V, true);
        MinecraftForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_PRESS, 0));
        log("press t=" + pressedAt + " keyDown=" + keyDown() + " screen=" + screenName(Minecraft.getInstance()));
        timeline.add("PRESS@" + pressedAt);
    }

    private static void release() {
        KeyMapping.set(V, false);
        Screen screen = Minecraft.getInstance().screen;
        boolean handled = false;
        if (screen instanceof DroneWheel wheel) handled = wheel.keyReleased(GLFW.GLFW_KEY_V, 0, 0);
        MinecraftForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_RELEASE, 0));
        log("release t=" + net.minecraft.Util.getMillis() + " held=" + (net.minecraft.Util.getMillis() - pressedAt)
                + " screenBefore=" + screenName(Minecraft.getInstance()) + " handled=" + handled);
        timeline.add("RELEASE@" + net.minecraft.Util.getMillis() + " heldMs="
                + (net.minecraft.Util.getMillis() - pressedAt));
    }

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

    /** Mirrors the 1.20.1 wheel's WheelLayout.fit (8 sectors, HOTBAR_STRIP 24). */
    private static int[] sectorCentre(Minecraft mc, int slot, int width, int height) {
        var font = mc.font;
        int lines = font.split(Component.translatable("dronepal.wheel.hint"), Math.max(1, width - 20)).size();
        int footer = lines * (font.lineHeight + 1);
        int radius = Math.max(12, Math.min(140, Math.min(width / 2 - 6, height / 2 - footer - 24 - 5)));
        int hole = Math.max(5, radius / 3);
        double angle = slot * Math.PI / 4;
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
        int removed = 0;
        for (var drone : player.serverLevel().getEntitiesOfClass(DroneEntity.class,
                AABB.ofSize(player.position(), 96, 96, 96))) {
            drone.discard();
            removed++;
        }
        CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inv -> inv.getStacksHandler("drone"))
                .ifPresent(handler -> handler.getStacks().setStackInSlot(0, ItemStack.EMPTY));
        DroneDeployment.reconcile(player);
        player.getPersistentData().remove(dev.corvus.data.CorvusKeys.ACTIVE);
        log("cleared drones=" + removed + " controller=empty");
    }

    private static void equip(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ItemStack controller = Frames.create("peregrine");
        var parts = new ArrayList<ItemStack>();
        parts.add(new ItemStack(Hardware.BATTERIES.get(2).get()));
        parts.add(Hardware.moduleStack("scan_mk1"));
        Hardware.save(controller, parts);
        CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inv -> inv.getStacksHandler("drone"))
                .orElseThrow().getStacks().setStackInSlot(0, controller);
        DroneDeployment.reconcile(player);
        boolean found = false;
        for (var entity : player.serverLevel().getAllEntities()) {
            if (entity instanceof DroneEntity drone && drone.isAlive()) found = true;
        }
        droneSignal = found;
        log("equipped controller followOff=" + DroneRemote.disabled(controller, "follow")
                + " scanOff=" + DroneRemote.disabled(controller, "scan") + " signal=" + droneSignal);
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
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inv -> inv.getStacksHandler("drone"))
                .map(handler -> handler.getStacks().getStackInSlot(0)).orElse(ItemStack.EMPTY);
    }

    private static void logServer(String when, MinecraftServer server) {
        ItemStack stack = controller(server);
        log("server " + when + ": followOff=" + DroneRemote.disabled(stack, "follow")
                + " scanOff=" + DroneRemote.disabled(stack, "scan")
                + " energy=" + Hardware.energy(stack));
    }

    private static boolean controllerWorn(Minecraft mc) {
        try {
            return CuriosApi.getCuriosInventory(mc.player).resolve()
                    .flatMap(inv -> inv.getStacksHandler("drone"))
                    .map(handler -> {
                        var stacks = handler.getStacks();
                        for (int i = 0; i < stacks.getSlots(); i++) {
                            if (stacks.getStackInSlot(i).is(Corvus.DRONE.get())) return true;
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
            Path dir = Path.of(System.getProperty("corvus.wheel.dir", "artifacts/client"));
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
