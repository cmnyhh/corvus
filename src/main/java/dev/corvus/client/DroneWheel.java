package dev.corvus.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.corvus.Corvus;
import dev.corvus.CorvusNetwork;
import dev.corvus.DroneRemote;
import java.io.File;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * The V-key radial command wheel.
 *
 * <p>Hold {@code key.dronepal.wheel}, aim with the mouse, release: the highlighted sector is sent as
 * one {@link DroneRemote.Command}. A left click on a sector sends that one action without closing the
 * wheel, so a pilot can flip a few switches in one hold. The wheel never keeps its own copy of the
 * drone state: every refresh is a {@link DroneRemote.Query} and the server's answer drives the labels,
 * the enable mask and the sector availability.</p>
 *
 * <p>1.20.1 wiring differs from the 1.21 source: the key is registered on the mod bus
 * ({@link RegisterKeyMappingsEvent}), opening and closing are driven by {@link InputEvent.Key} plus a
 * {@link TickEvent.ClientTickEvent} watchdog, and packets go through {@link CorvusNetwork}'s
 * {@code SimpleChannel} instead of {@code PacketDistributor}. The 1.21 {@code WheelLayout} helper is
 * folded in here as {@link WheelLayout} so the port adds no third file.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class DroneWheel extends Screen {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-wheel");
    private static final KeyMapping KEY = new KeyMapping("key.dronepal.wheel", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V, "key.categories.dronepal");
    private static final int RING = 0xd526293a, EMPTY = 0xa51c1e2c, SELECTED = 0xf165438e, WHITE = 0xfff5f2ff, MUTED = 0xffc3bcd2;
    /** Dev-only timeline trace: {@code -Dcorvus.wheelLog=1} prints every open/close with a timestamp. */
    private static final boolean DIAG = "1".equals(System.getProperty("corvus.wheelLog"));
    private static void diag(String message, Object... args) {
        if (DIAG) System.out.println("[wheel-diag] " + String.format(message, args));
    }
    /** The wheel is a hold-to-open UI; the minimum hold stops a stray tap from firing a sector. */
    private static final long MINIMUM_HOLD_MILLIS = 160;
    /**
     * Physical V state, maintained from {@link InputEvent.Key} instead of {@code KEY.isDown()}.
     * {@code Minecraft.setScreen} calls {@code KeyMapping.releaseAll()}, which clears the mapping while
     * the key is still held; a watchdog reading the mapping therefore closed every wheel one tick after
     * opening, no matter how long the player held V.
     */
    private static boolean physicallyDown;
    /** Wall-clock start of the press under measurement, 0 when no press is pending. */
    private static long pressedAt;
    /** This press already opened the wheel or showed the no-drone hint. */
    private static boolean resolved;
    /** The status answer is treated as current for this long when drawing. */
    private static final long STATUS_TTL_MILLIS = 3_000;

    /** Dev harness switch: {@code -Dcorvus.wheelSmoke=1} scripts the acceptance screenshots. */
    private static final boolean SMOKE = "1".equals(System.getProperty("corvus.wheelSmoke"));

    private static int sequence;
    private static int revision;
    private static int accepted;
    private static int polling;
    private static DroneRemote.Status status;
    private static long statusAt;
    private static int smokeTicks;

    private final int nonce = ++sequence;
    private final long opened = net.minecraft.Util.getMillis();
    private int selection = -1;
    private int refreshTicks;
    /** Only a wheel opened by the key itself is closed by the key watchdog. */
    private boolean keyHeld;
    private boolean clickedWhileOpen;
    private boolean closed;

    private DroneWheel() {
        super(Component.translatable("key.dronepal.wheel"));
    }

    // ------------------------------------------------------------------ protocol

    /** Latest server answer, or null when nothing arrived within {@link #STATUS_TTL_MILLIS}. */
    static DroneRemote.Status status() {
        return status == null || net.minecraft.Util.getMillis() - statusAt > STATUS_TTL_MILLIS ? null : status;
    }

    /** Client packet sink; installed by {@link Keys} before any answer can arrive. */
    static void accept(DroneRemote.Status update) {
        if (update == null || update.revision() <= accepted) return;
        diag("accept nonce=%d rev=%d signal=%s enabled=0x%x", update.nonce(), update.revision(),
                update.signal(), update.enabled());
        accepted = update.revision();
        status = update;
        statusAt = net.minecraft.Util.getMillis();
    }

    private static void query() {
        revision++;
        CorvusNetwork.CHANNEL.sendToServer(new DroneRemote.Query(sequence, revision));
    }

    private void refresh() {
        revision++;
        CorvusNetwork.CHANNEL.sendToServer(new DroneRemote.Query(nonce, revision));
    }

    private void sendAction(int slot) {
        DroneRemote.Status state = status();
        diag("sendAction slot=%d action=%s state=%s selectable=%s", slot, action(slot), state == null ? "null" : "ok",
                selectable(slot));
        if (state == null || !selectable(slot)) return;
        revision++;
        CorvusNetwork.CHANNEL.sendToServer(new DroneRemote.Command(state.serial(), action(slot), nonce, revision));
    }

    // ------------------------------------------------------------------ keys and ticks

    /** Mod-bus half: the key binding plus the client-side status sink. */
    @Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        @SubscribeEvent
        static void register(RegisterKeyMappingsEvent event) {
            event.register(KEY);
            DroneRemote.receive = DroneWheel::accept;
        }
    }

    @SubscribeEvent
    static void input(InputEvent.Key event) {
        boolean matches = KEY.matches(event.getKey(), event.getScanCode());
        if (!matches) return;
        if (event.getAction() == GLFW.GLFW_PRESS) {
            Minecraft game = Minecraft.getInstance();
            if (game.player == null || game.screen != null) return;
            // Do not open here: accumulate hold time first, so a tap never creates a screen at all.
            if (pressedAt == 0) {
                pressedAt = net.minecraft.Util.getMillis();
                resolved = false;
            }
            physicallyDown = true;
            diag("input PRESS key=%d scan=%d at=%d", event.getKey(), event.getScanCode(), pressedAt);
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            physicallyDown = false;
            diag("input RELEASE key=%d scan=%d screen=%s", event.getKey(), event.getScanCode(),
                    Minecraft.getInstance().screen == null ? "null"
                            : Minecraft.getInstance().screen.getClass().getSimpleName());
        }
    }

    /** Opens the wheel the way the V key does; {@code keyHeld} is set by the caller that owns a press. */
    private static DroneWheel open(Minecraft game) {
        DroneWheel wheel = new DroneWheel();
        game.setScreen(wheel);
        wheel.refresh();
        return wheel;
    }

    @SubscribeEvent
    static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft game = Minecraft.getInstance();
        if (game.player == null) return;
        long now = net.minecraft.Util.getMillis();
        if (pressedAt != 0 && game.screen != null && !(game.screen instanceof DroneWheel)) {
            // Another screen (inventory, chat) took the hold: it is no longer a wheel gesture.
            pressedAt = 0;
            resolved = false;
        }
        if (physicallyDown && !resolved && pressedAt != 0 && game.screen == null
                && now - pressedAt >= MINIMUM_HOLD_MILLIS) {
            resolved = true;
            long held = now - pressedAt;
            pressedAt = 0;
            if (noController()) {
                hint(game);
                diag("hold %d ms: no controller, hotbar hint instead of a wheel", held);
            } else {
                open(game).keyHeld = true;
                diag("hold %d ms: wheel opened", held);
            }
        }
        if (!physicallyDown && pressedAt != 0) {
            // Released before the hold completed: a tap never opens, never acts, and stays silent.
            diag("tap released at=%d held=%d resolved=%s", now, now - pressedAt, resolved);
            pressedAt = 0;
            resolved = false;
        }
        if (game.screen instanceof DroneWheel wheel) {
            // Watchdog: the release also arrives as Screen.keyReleased, this covers a lost key event.
            // It must use the physical state: KEY.isDown() is cleared by setScreen's releaseAll().
            if (wheel.keyHeld && !physicallyDown) {
                diag("watchdog release nonce=%d physicallyDown=false screen=%s", wheel.nonce,
                        game.screen.getClass().getSimpleName());
                wheel.release();
            }
        } else if (++polling >= 20) {
            // One status per second keeps the HUD live without the wheel being open.
            polling = 0;
            query();
        }
        DroneHud.tick();
        if (SMOKE) smoke(game);
    }

    /** True only when the client positively sees neither a controller nor a deployed drone. */
    private static boolean noController() {
        // Any positive sign wins: Curios sync can lag a second behind the server, and a drone drawn in
        // the world proves a controller exists even when the synced slot has not caught up yet.
        Boolean worn = DroneHud.controllerWorn();
        if (worn != null && worn) return false;
        if (DroneHud.deployedVisible()) return false;
        DroneRemote.Status state = status();
        if (state != null) return state.enabled() == 0;
        if (worn != null) return !worn;
        return false;
    }

    private static void hint(Minecraft game) {
        if (game.player == null) return;
        game.player.displayClientMessage(Component.translatable("dronepal.wheel.no_drone"), true);
        diag("hint shown: no deployed drone");
    }

    @Override public void tick() {
        // Open wheel: the server answer refreshes the sectors, so no close/reopen is needed.
        if (minecraft == null || closed || ++refreshTicks < 10) return;
        refreshTicks = 0;
        refresh();
    }

    // ------------------------------------------------------------------ interaction

    @Override protected void init() {
        diag("init nonce=%d age=%d keyDown=%s", nonce, net.minecraft.Util.getMillis() - opened, KEY.isDown());
        var window = minecraft.getWindow();
        GLFW.glfwSetCursorPos(window.getWindow(), window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);
    }

    @Override public void onClose() {
        diag("onClose nonce=%d age=%d selection=%d", nonce, net.minecraft.Util.getMillis() - opened, selection);
        if (DIAG) {
            var stack = new Throwable().getStackTrace();
            for (int i = 1; i < Math.min(stack.length, 9); i++) System.out.println("[wheel-diag]   at " + stack[i]);
        }
        super.onClose();
    }

    @Override public void removed() {
        diag("removed nonce=%d age=%d", nonce, net.minecraft.Util.getMillis() - opened);
        super.removed();
    }

    @Override public boolean isPauseScreen() {
        return false;
    }

    private void release() {
        if (closed) return;
        closed = true;
        var window = minecraft.getWindow();
        selection = layout().sector(minecraft.mouseHandler.xpos() * width / window.getScreenWidth(),
                minecraft.mouseHandler.ypos() * height / window.getScreenHeight());
        diag("release nonce=%d age=%d selection=%d clicked=%s keyDown=%s", nonce,
                net.minecraft.Util.getMillis() - opened, selection, clickedWhileOpen, KEY.isDown());
        // The screen only exists after a completed hold, so a release always runs the aimed sector.
        if (!clickedWhileOpen) sendAction(selection);
        onClose();
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        diag("keyReleased key=%d scan=%d matches=%s screen=%s", key, scan, KEY.matches(key, scan),
                minecraft == null || minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName());
        if (KEY.matches(key, scan)) {
            release();
            return true;
        }
        return super.keyReleased(key, scan, modifiers);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (KEY.matchesMouse(button)) {
            release();
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !KEY.matchesMouse(button)) {
            clickedWhileOpen = true;
            sendAction(layout().sector(x, y));
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    // ------------------------------------------------------------------ sector model

    private DroneRemote.Action action(int slot) {
        if (slot < 0) return null;
        DroneRemote.Status state = status();
        if (slot == 0 && (hasShiftDown() || state != null && !state.signal())) return DroneRemote.Action.RECALL;
        if (slot == 7 && hasShiftDown()) return DroneRemote.Action.SUPPLY;
        if (slot == 5 && hasShiftDown()) return DroneRemote.Action.DELIVER;
        if (slot == 2 && hasShiftDown()) return DroneRemote.Action.FIREMODE;
        if (slot == 6) {
            // Shift+6 is always the work sector. Without a lamp module the server relabels the whole
            // sector as work when a mill is in range, so a plain press means work there too.
            if (hasShiftDown()) return DroneRemote.Action.WORK;
            if (state != null && "dronepal.wheel.work".equals(state.labels().get(6))) {
                return DroneRemote.Action.WORK;
            }
        }
        return DroneRemote.Action.values()[slot];
    }

    private boolean selectable(int slot) {
        DroneRemote.Status state = status();
        return slot >= 0 && state != null && (state.enabled() & 1 << slot) != 0 && (state.signal() || slot == 0);
    }

    private Component label(int slot) {
        DroneRemote.Status state = status();
        return switch (action(slot)) {
            case RECALL -> text("recall");
            case SUPPLY -> text("supply");
            case DELIVER -> text("deliver");
            case FIREMODE -> text("firemode");
            case WORK -> text("work");
            default -> state != null && slot < state.labels().size()
                    ? Component.translatable(state.labels().get(slot)) : Component.empty();
        };
    }

    private String sectorIcon(int slot) {
        DroneRemote.Status state = status();
        return switch (action(slot)) {
            case FOLLOW -> state != null && (state.off() & 1) != 0 ? "wheel_hold" : "wheel_follow";
            case RECALL -> "wheel_recall";
            case WEAPON -> state != null && state.labels().get(1).endsWith("safe") ? "wheel_weapon_stow" : "wheel_weapon_open";
            case MODE -> state == null ? "wheel_engage" : GuiIcons.mode(state.labels().get(2).replace("dronepal.engage.", ""));
            case SCAN -> "wheel_scan";
            case MEDIC -> "wheel_medic";
            case CARRY -> "wheel_pickup";
            case DELIVER -> "wheel_deliver";
            case LIGHT -> "wheel_light";
            case LINK, SUPPLY -> "wheel_link";
            case FIREMODE -> "wheel_burst";
            case WORK -> "wheel_work";
        };
    }

    private static Component text(String key) {
        return Component.translatable("dronepal.wheel." + key);
    }

    // ------------------------------------------------------------------ drawing

    private WheelLayout layout() {
        return WheelLayout.fit(width, height, footer().size(), font.lineHeight + 1);
    }

    private List<FormattedCharSequence> footer() {
        return font.split(Component.translatable("dronepal.wheel.hint"), Math.max(1, width - 20));
    }

    private void drawLabel(GuiGraphics graphics, Component message, WheelLayout.Box box, int color) {
        if (font.width(message) == 0) return;
        // Native glyph size first; shrink the whole block only when the sector is too small.
        float scale = 1;
        var lines = font.split(message, box.width());
        for (int step = 1; lines.size() * font.lineHeight * scale > box.height() && step <= 8; step++) {
            scale = 1f - step * .075f;
            lines = font.split(message, Math.max(1, (int) (box.width() / scale)));
        }
        scale = Math.min(scale, (float) box.height() / (lines.size() * font.lineHeight));
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(box.x(), box.y(), 0);
        pose.scale(scale, scale, 1);
        int y = -lines.size() * font.lineHeight / 2;
        for (var line : lines) {
            graphics.drawString(font, line, -font.width(line) / 2, y, color, true);
            y += font.lineHeight;
        }
        pose.popPose();
    }

    private void iconLabel(GuiGraphics graphics, String icon, Component label, WheelLayout.Box box, int color) {
        int size = Math.min(16, Math.max(8, box.height() / 2));
        int top = box.y() - box.height() / 2;
        GuiIcons.draw(graphics, icon, box.x() - size / 2, top, size);
        int available = Math.max(4, box.height() - size - 1);
        drawLabel(graphics, label, new WheelLayout.Box(box.x(), top + size + 1 + available / 2, box.width(), available), color);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        var wheel = layout();
        int x = wheel.x(), y = wheel.y(), r = wheel.radius(), hole = wheel.hole();
        DroneRemote.Status state = status();
        selection = wheel.sector(mouseX, mouseY);
        // Eight wedge strips leave a physical gap between neighbouring targets.
        var pose = g.pose();
        for (int slot = 0; slot < 8; slot++) {
            int color = !selectable(slot) ? EMPTY : slot == selection ? SELECTED : RING;
            pose.pushPose();
            pose.translate(x, y, 0);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(slot * 45));
            for (int dy = 0; dy < r; dy++) {
                int outer = Math.min((int) (dy * .4142) - 1, (int) Math.sqrt(r * r - dy * dy));
                if (outer <= 0) continue;
                if (dy >= hole) g.fill(-outer, -dy, outer + 1, 1 - dy, color);
                else {
                    int inner = (int) Math.sqrt(hole * hole - dy * dy);
                    if (inner < outer) {
                        g.fill(-outer, -dy, -inner, 1 - dy, color);
                        g.fill(inner, -dy, outer + 1, 1 - dy, color);
                    }
                }
            }
            pose.popPose();
        }
        for (int slot = 0; slot < 8; slot++) {
            if (state != null && (state.enabled() & 1 << slot) != 0) {
                iconLabel(g, sectorIcon(slot), label(slot), wheel.sectorText(slot), selectable(slot) ? WHITE : MUTED);
            }
        }
        Component center = state == null ? text("connecting") : state.enabled() == 0 ? text("none")
                : !state.signal() ? text("signal") : selectable(selection) ? label(selection) : text("cancel");
        boolean off = state != null && selection >= 0 && (state.off() & 1 << selection) != 0;
        String centerIcon = state == null ? "wheel_signal" : state.enabled() == 0 ? "wheel_none"
                : !state.signal() ? "wheel_lost" : selectable(selection) ? sectorIcon(selection) : "wheel_cancel";
        iconLabel(g, centerIcon, off ? center.copy().append("\n").append(text("off")) : center, wheel.centerText(), WHITE);
        Component header = selectable(selection) ? label(selection) : Component.empty();
        if (state != null && state.solar() >= 0) {
            var solar = Component.translatable("dronepal.wheel.solar", state.solar());
            header = header.getString().isEmpty() ? solar : header.copy().append(" · ").append(solar);
        }
        int headerHeight = Math.max(1, y - r - 5);
        drawLabel(g, header, new WheelLayout.Box(x, headerHeight / 2 + 1, width - 16, headerHeight), WHITE);
        int footerY = y + r + 5;
        for (var line : footer()) {
            g.drawString(font, line, x - font.width(line) / 2, footerY, WHITE, true);
            footerY += font.lineHeight + 1;
        }
    }

    // ------------------------------------------------------------------ dev acceptance harness

    /**
     * Mirrors {@code devtools.SmokeClient}: scripted only when {@code -Dcorvus.wheelSmoke=1}, so a plain
     * run is untouched. It opens the wheel through {@link #open(Minecraft)} (the same call the V key
     * makes), writes the two acceptance screenshots and stops the game.
     */
    private static void smoke(Minecraft game) {
        if (game.player == null || game.level == null) return;
        smokeTicks++;
        if (smokeTicks == 1) {
            game.options.pauseOnLostFocus = false;
            LOGGER.info("[corvus-wheel] smoke: pauseOnLostFocus=false");
        }
        if (smokeTicks == 40) {
            if (game.screen == null) open(game);
            LOGGER.info("[corvus-wheel] smoke: wheel opened, screen={}", game.screen);
        }
        if (smokeTicks == 45) {
            // A dev window is launched in the background; warping the cursor needs focus.
            GLFW.glfwFocusWindow(game.getWindow().getWindow());
            LOGGER.info("[corvus-wheel] smoke: window focus requested");
        }
        if (smokeTicks == 50 && game.screen instanceof DroneWheel wheel) {
            // Best effort: park the OS cursor in the east sector so the shot shows a highlight.
            var window = game.getWindow();
            var layout = wheel.layout();
            GLFW.glfwSetCursorPos(window.getWindow(),
                    (layout.x() + layout.radius() * 0.55) * window.getScreenWidth() / wheel.width,
                    layout.y() * window.getScreenHeight() / wheel.height);
            LOGGER.info("[corvus-wheel] smoke: cursor parked in the east sector");
        }
        if (smokeTicks == 55) {
            LOGGER.info("[corvus-wheel] smoke: mouse at {},{} (screen {}x{}), selection={}",
                    game.mouseHandler.xpos(), game.mouseHandler.ypos(), game.screen == null ? 0 : game.screen.width,
                    game.screen == null ? 0 : game.screen.height,
                    game.screen instanceof DroneWheel wheel ? wheel.layout().sector(
                            game.mouseHandler.xpos() * wheel.width / game.getWindow().getScreenWidth(),
                            game.mouseHandler.ypos() * wheel.height / game.getWindow().getScreenHeight()) : "n/a");
            DroneRemote.Status state = status();
            LOGGER.info("[corvus-wheel] smoke: status before shot = {}", state == null ? "none"
                    : "nonce=" + state.nonce() + " rev=" + state.revision() + " signal=" + state.signal()
                    + " enabled=0x" + Integer.toHexString(state.enabled()) + " labels=" + state.labels());
            if (game.screen instanceof DroneWheel) grab(game, "10-wheel-open.png");
            else LOGGER.error("[corvus-wheel] smoke: wheel is not open at the screenshot tick");
        }
        if (smokeTicks == 62 && game.screen instanceof DroneWheel wheel) {
            // Sector 3 = scan patrol: a command round trip whose effect is visible in the next status.
            wheel.sendAction(3);
            LOGGER.info("[corvus-wheel] smoke: sent SCAN at revision {}", revision);
        }
        if (smokeTicks == 70) logStatus("after the scan command");
        if (smokeTicks == 78 && game.screen instanceof DroneWheel wheel) {
            wheel.sendAction(3);
            LOGGER.info("[corvus-wheel] smoke: sent SCAN again at revision {}", revision);
        }
        if (smokeTicks == 86) logStatus("after the second scan command");
        if (smokeTicks == 92 && game.screen != null) game.setScreen(null);
        if (smokeTicks == 105) grab(game, "11-drone-hud.png");
        if (smokeTicks == 115) openPanel(game);
        if (smokeTicks == 133) {
            LOGGER.info("[corvus-wheel] smoke: screen at the panel shot: {}",
                    game.screen == null ? "none" : game.screen.getClass().getName());
            if (game.screen instanceof TerminalScreen) grab(game, "12-terminal-panel.png");
        }
        if (smokeTicks == 145) {
            LOGGER.info("[corvus-wheel] smoke: finished at tick {}", smokeTicks);
            game.stop();
        }
    }

    private static void logStatus(String when) {
        DroneRemote.Status state = status();
        LOGGER.info("[corvus-wheel] smoke: status {} = {}", when, state == null ? "none"
                : "nonce=" + state.nonce() + " rev=" + state.revision() + " signal=" + state.signal()
                + " enabled=0x" + Integer.toHexString(state.enabled()) + " labels=" + state.labels());
    }

    /** Dev harness: opens the terminal panel server-side so its HUD switch can be photographed. */
    private static void openPanel(Minecraft game) {
        var server = game.getSingleplayerServer();
        if (server == null) {
            LOGGER.error("[corvus-wheel] smoke: no integrated server, cannot open the terminal panel");
            return;
        }
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            var player = players.get(0);
            var drone = dev.corvus.DroneDeployment.active(player);
            LOGGER.info("[corvus-wheel] smoke: opening the terminal panel, drone={}", drone);
            if (drone != null) {
                LOGGER.info("[corvus-wheel] smoke: terminal panel opened={}",
                        dev.corvus.TerminalMenus.openTerminal(player, drone));
            }
        });
    }

    private static void grab(Minecraft game, String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.error("[corvus-wheel] cannot create screenshot directory {}", directory.getAbsolutePath());
            return;
        }
        NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            LOGGER.info("[corvus-wheel] screenshot written: {} ({}x{})", target.getAbsolutePath(),
                    image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            LOGGER.error("[corvus-wheel] screenshot failed: {}", failure.toString());
        } finally {
            image.close();
        }
    }

    /**
     * GUI-scaled geometry shared by drawing and hit testing. 0 = north, 1 = north-east, 2 = east …
     * 7 = north-west, matching the server's {@link DroneRemote.Action} order.
     */
    record WheelLayout(int x, int y, int radius, int hole) {
        /** Hotbar height plus its one-pixel border: the hint is never allowed to land on it. */
        private static final int HOTBAR_STRIP = 24;

        static WheelLayout fit(int width, int height, int footerLines, int lineHeight) {
            int y = height / 2;
            int footer = footerLines * lineHeight;
            // Ring, then the hint below it, then the hotbar: the ring shrinks on small GUIs
            // (a 4x scaled 1080p window is only 240 logical pixels tall) instead of overlapping it.
            int radius = Math.max(12, Math.min(140,
                    Math.min(width / 2 - 6, y - footer - HOTBAR_STRIP - 5)));
            return new WheelLayout(width / 2, y, radius, Math.max(5, radius / 3));
        }

        int sector(double mouseX, double mouseY) {
            double dx = mouseX - x, dy = mouseY - y;
            double distance = Math.hypot(dx, dy);
            if (distance < hole || distance > radius) return -1;
            return Math.floorMod((int) Math.floor(Math.atan2(dx, -dy) / (Math.PI / 4) + 0.5), 8);
        }

        record Box(int x, int y, int width, int height) {}

        Box sectorText(int slot) {
            // The rotated wedge contains this circle, so its inscribed rectangle fits even diagonally.
            double angle = slot * Math.PI / 4;
            double distance = (radius + hole) * 0.52;
            double safe = Math.min(radius - distance, Math.min(distance - hole, distance * Math.sin(Math.PI / 8))) - 2;
            int cx = x + (int) Math.round(distance * Math.sin(angle));
            int cy = y - (int) Math.round(distance * Math.cos(angle));
            return new Box(cx, cy, Math.max(2, (int) (safe * 1.76)), Math.max(2, (int) (safe * .90)));
        }

        Box centerText() {
            return new Box(x, y, Math.max(2, (int) (hole * 1.6) - 2), Math.max(2, hole - 2));
        }
    }
}
