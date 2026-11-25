package dev.corvus;

import com.mojang.blaze3d.platform.NativeImage;
import dev.corvus.client.AssemblyScreen;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client acceptance harness for the 1.20.1 assembly bench list — the mirror of the 1.21.1
 * {@code AssemblyVisual}. Dev-only: inactive unless {@code -Dcorvus.assemblyShot=1}, and compiled only
 * when {@code gradle/assembly-shot.init.gradle} adds {@code gradle/assemblyshot/java} to the main
 * source set (a plain {@code gradle build} never sees it).
 *
 * <p>Opens the real bench screen, then drives {@code screen.mouseClicked(x, y, 0)} on known rows and
 * asserts that the highlight, {@code menu.selection()} and the preview slot end up on the clicked row:
 * a single click, a burst of clicks faster than any round trip, a click after scrolling, an arrow-key
 * step, and a click while a job owns the blueprint.
 *
 * <p>Run: {@code gradle -I gradle/assembly-shot.init.gradle runClient -PcorvusQuickPlay=CorvusSmoke}.
 */
@Mod.EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class AssemblyShot {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.assemblyShot"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-assembly-shot");

    private static int stage, ticks, wait;
    private static boolean staged, prepared, reflected;
    private static BlockPos benchPos;
    private static Method rowAt, entriesOf, selectedRowOf, deniedOf;
    private static Field scrollField;
    private static final List<String> failures = new ArrayList<>();
    private static int burstRow, awaitStart = -1;
    private static java.lang.reflect.Method mouseOnMove;
    private static boolean done;

    private AssemblyShot() {}

    @SubscribeEvent public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft game = Minecraft.getInstance();
        MinecraftServer server = game.getSingleplayerServer();
        ticks++;
        if (stage == 0) {
            if (game.player == null || game.level == null || server == null || game.getOverlay() != null) return;
            game.options.pauseOnLostFocus = false;
            if (game.screen instanceof net.minecraft.client.gui.screens.PauseScreen) game.setScreen(null);
            if (game.screen != null || ticks < 40) return;
            server.execute(() -> prepare(server));
            stage = 1;
            return;
        }
        if (game.player == null || game.level == null || server == null || game.getOverlay() != null) return;
        game.options.pauseOnLostFocus = false;
        if (game.screen instanceof net.minecraft.client.gui.screens.PauseScreen) game.setScreen(null);
        try {
            switch (stage) {
                case 1 -> {
                    if (!(game.screen instanceof AssemblyScreen)) return;
                    reflect();
                    stage = 2;
                }
                case 2 -> {
                    if (waitFor(() -> screen().getMenu().selection() == 1, 1200)) return;
                    audit("open");
                    check("catalog_matches_plans", catalogSize() == plansSize(), "catalog=" + catalogSize() + " plans=" + plansSize());
                    check("initial_selection", screen().getMenu().selection() == 1, "selection=" + screen().getMenu().selection());
                    click(1);
                    stage = 3;
                }
                case 3 -> {
                    if (waitForPoked(() -> screen().getMenu().selection() == 2, 1200, 40, () -> click(1))) return;
                    check("warmup_link", screen().getMenu().selection() == 2, "selection=" + screen().getMenu().selection());
                    click(3);
                    check("click_row3_highlight_immediately", selectedRow() == 3, "selectedRow=" + selectedRow());
                    stage = 4;
                }
                case 4 -> { if (waitFor(() -> screen().getMenu().selection() == 4, 600)) return; settleTo(3, "single_click_row3"); stage = 5; }
                case 5 -> { burstRow = 4; click(5); stage = 6; }
                case 6 -> { if (--wait > 0) return; click(1); stage = 7; wait = 2; }
                case 7 -> { if (--wait > 0) return; click(3); stage = 8; wait = 2; }
                case 8 -> { if (--wait > 0) return; click(0); stage = 9; wait = 2; }
                case 9 -> { if (--wait > 0) return; click(burstRow); stage = 10; }
                case 10 -> { if (waitFor(() -> screen().getMenu().selection() == burstRow + 1, 600)) return; settleTo(burstRow, "burst_last_click_wins"); stage = 11; }
                case 11 -> {
                    scrollBy(-1); scrollBy(-1);
                    check("scroll_moved", scroll() == 2, "scroll=" + scroll());
                    click(1);          // row 1 of the visible window == catalog index 3
                    stage = 12;
                }
                case 12 -> { if (waitFor(() -> screen().getMenu().selection() == 4, 600)) return; settleTo(3, "click_after_scroll"); stage = 13; }
                case 13 -> { screen().keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0); stage = 14; }
                case 14 -> {
                    int expected = Math.floorMod(3 + 1, plansSize()) + 1;
                    if (waitFor(() -> screen().getMenu().selection() == expected, 600)) return;
                    check("arrow_key_steps", screen().getMenu().selection() == expected,
                            "selection=" + screen().getMenu().selection() + " expected=" + expected);
                    stage = 15;
                }
                case 15 -> { parkMouse(); stage = 16; wait = 4; }
                case 16 -> { if (--wait > 0) return; grab(game, "assembly-1.20.1-row-selected.png"); stage = 17; }
                case 17 -> { game.getSingleplayerServer().execute(AssemblyShot::fillAndStart); stage = 18; }
                case 18 -> {
                    if (waitForPoked(() -> screen().getMenu().busy(), 600, 100,
                            () -> game.getSingleplayerServer().execute(AssemblyShot::fillAndStart))) return;
                    check("busy_visible_to_client", screen().getMenu().busy(), "busy=" + screen().getMenu().busy());
                    int before = screen().getMenu().selection();
                    click(5);
                    check("busy_click_keeps_selection", screen().getMenu().selection() == before, "before=" + before + " after=" + screen().getMenu().selection());
                    check("busy_click_gives_feedback", denied(), "denied=" + denied());
                    stage = 19; wait = 2;
                }
                case 19 -> { if (--wait > 0) return; parkMouse(); stage = 20; wait = 3; }
                case 20 -> { if (--wait > 0) return; grab(game, "assembly-1.20.1-busy-denied.png"); stage = 21; }
                case 21 -> {
                    if (done) return;
                    done = true;
                    String result = failures.isEmpty() ? "ASSY_RESULT PASS" : "ASSY_RESULT FAIL " + failures.size() + " " + failures;
                    System.out.println(result);
                    LOGGER.info(result);
                    game.stop();
                }
                default -> game.stop();
            }
        } catch (Throwable failure) {
            LOGGER.error("ASSY_FAIL harness_exception stage={} {}", stage, failure.toString());
            failure.printStackTrace(System.out);
            game.stop();
        }
    }

    /** True while still waiting. A timeout just ends the wait and lets the assertion report the value. */
    /** Same, but pokes the server every {@code everyTicks} while waiting: a still-loading world answers late. */
    private static boolean waitForPoked(java.util.function.BooleanSupplier condition, int timeoutTicks, int everyTicks, Runnable poke) {
        if (awaitStart < 0) awaitStart = ticks;
        if (condition.getAsBoolean()) { awaitStart = -1; return false; }
        int waited = ticks - awaitStart;
        if (waited >= timeoutTicks) { awaitStart = -1; return false; }
        if (everyTicks > 0 && waited % everyTicks == 0) poke.run();
        return true;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, int timeoutTicks) {
        if (awaitStart < 0) awaitStart = ticks;
        if (condition.getAsBoolean()) { awaitStart = -1; return false; }
        if (ticks - awaitStart >= timeoutTicks) { awaitStart = -1; return false; }
        return true;
    }

    /**
     * Parks the pointer in the corner so no hover highlight or tooltip lands in the capture. An
     * unfocused window ignores glfwSetCursorPos, so drive the engine's own pointer callback too.
     */
    private static void parkMouse() {
        Minecraft game = Minecraft.getInstance();
        long window = game.getWindow().getWindow();
        try {
            if (mouseOnMove == null) {
                mouseOnMove = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                mouseOnMove.setAccessible(true);
            }
            mouseOnMove.invoke(game.mouseHandler, window, 0.0, 0.0);
        } catch (Throwable failure) {
            mouseOnMove = null;
        }
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(window, 2.0, 2.0);
    }

    private static void settleTo(int row, String label) {
        var menu = screen().getMenu();
        int want = row + 1;
        check(label, menu.selection() == want, "selection=" + menu.selection() + " expected=" + want);
        String previewName = menu.getSlot(AssemblyMenu.PREVIEW).getItem().getHoverName().getString();
        List<SurvivalWorks.Catalog.Entry> entries = entries();
        String entryName = row >= 0 && row < entries.size() ? entries.get(row).icon().getHoverName().getString() : "<none>";
        check(label + "_preview", previewName.equals(entryName), "preview='" + previewName + "' expected='" + entryName + "'");
        check(label + "_highlight", selectedRow() == row, "selectedRow=" + selectedRow() + " expected=" + row);
    }

    private static void check(String label, boolean ok, String detail) {
        String line = (ok ? "ASSY_PASS " : "ASSY_FAIL ") + label + " " + detail;
        if (!ok) failures.add(label);
        System.out.println(line);
        LOGGER.info(line);
    }

    // ---- plumbing ----

    private static void reflect() {
        if (reflected) return;
        reflected = true;
        try {
            rowAt = AssemblyScreen.class.getDeclaredMethod("rowAt", double.class, double.class);
            rowAt.setAccessible(true);
            entriesOf = AssemblyScreen.class.getDeclaredMethod("entries");
            entriesOf.setAccessible(true);
            selectedRowOf = AssemblyScreen.class.getDeclaredMethod("selectedRow");
            selectedRowOf.setAccessible(true);
            deniedOf = AssemblyScreen.class.getDeclaredMethod("deniedNow");
            deniedOf.setAccessible(true);
            scrollField = AssemblyScreen.class.getDeclaredField("scroll");
            scrollField.setAccessible(true);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int left() { return (Minecraft.getInstance().getWindow().getGuiScaledWidth() - AssemblyMenu.PANEL_WIDTH) / 2; }
    private static int top() { return (Minecraft.getInstance().getWindow().getGuiScaledHeight() - AssemblyMenu.PANEL_HEIGHT) / 2; }
    private static int rowX() { return left() + AssemblyMenu.LIST_X + 20; }
    private static int rowY(int row) { return top() + AssemblyMenu.LIST_Y + row * AssemblyMenu.ROW_HEIGHT + AssemblyMenu.ROW_HEIGHT / 2; }
    private static AssemblyScreen screen() { return (AssemblyScreen) Minecraft.getInstance().screen; }

    private static int scroll() {
        try { return scrollField.getInt(Minecraft.getInstance().screen); } catch (Throwable failure) { return -1; }
    }
    @SuppressWarnings("unchecked")
    private static List<SurvivalWorks.Catalog.Entry> entries() {
        try { return (List<SurvivalWorks.Catalog.Entry>) entriesOf.invoke(null); } catch (Throwable failure) { return List.of(); }
    }
    private static int catalogSize() { return entries().size(); }
    private static int plansSize() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? -1 : SurvivalWorks.plans.size();
    }
    private static int selectedRow() {
        try { return (int) selectedRowOf.invoke(Minecraft.getInstance().screen); } catch (Throwable failure) { return -99; }
    }
    private static boolean denied() {
        try { return (boolean) deniedOf.invoke(Minecraft.getInstance().screen); } catch (Throwable failure) { return false; }
    }

    private static void audit(String label) {
        var menu = screen().getMenu();
        System.out.println("ASSY_AUDIT " + label
                + " gui=" + Minecraft.getInstance().getWindow().getGuiScaledWidth() + "x" + Minecraft.getInstance().getWindow().getGuiScaledHeight()
                + " origin=" + left() + "," + top()
                + " catalog=" + catalogSize() + " scroll=" + scroll()
                + " selection=" + menu.selection() + " busy=" + menu.busy()
                + " preview=" + menu.getSlot(AssemblyMenu.PREVIEW).getItem());
        serverState(label);
    }

    private static void click(int row) {
        int x = rowX(), y = rowY(row);
        int index;
        try { index = (int) rowAt.invoke(Minecraft.getInstance().screen, (double) x, (double) y); } catch (Throwable failure) { index = -98; }
        check("rowAt_" + row, index == scroll() + row, "rowAt=" + index + " expected=" + (scroll() + row) + " scroll=" + scroll());
        boolean handled = screen().mouseClicked(x, y, 0);
        String line = "ASSY_CLICK row=" + row + " at=" + x + "," + y + " index=" + index + " handled=" + handled
                + " selection=" + screen().getMenu().selection() + " selectedRow=" + selectedRow();
        System.out.println(line);
        LOGGER.info(line);
    }

    private static void scrollBy(int notches) {
        screen().mouseScrolled(rowX(), rowY(2), notches);
        System.out.println("ASSY_SCROLL notches=" + notches + " -> scroll=" + scroll());
    }

    private static void serverState(String label) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            String state = "no-be";
            if (player != null && benchPos != null && player.serverLevel().getBlockEntity(benchPos) instanceof AssemblyStation station) {
                state = "selected='" + station.selected + "' index=" + station.selectedIndex()
                        + " busy=" + station.busy() + " elapsed=" + station.elapsed + "/" + station.duration;
            }
            System.out.println("ASSY_SERVER " + label + " plans=" + SurvivalWorks.plans.size() + " station{" + state + "}");
        });
    }

    private static void prepare(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        var level = player.serverLevel();
        if (!staged) {
            staged = true;
            BlockPos base = player.blockPosition().above(16);
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(base.below().offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
                for (int dy = 1; dy <= 6; dy++) level.setBlockAndUpdate(base.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            benchPos = base;
        }
        level.setBlockAndUpdate(benchPos, SurvivalWorks.BENCH.get().defaultBlockState());
        if (!(level.getBlockEntity(benchPos) instanceof AssemblyStation station)) {
            System.out.println("ASSY_PREP no station at " + benchPos);
            return;
        }
        station.inventory.clearContent();
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new AssemblyMenu(id, inventory, station),
                Component.translatable("block.huhu_dronepal.assembly_bench")));
        player.teleportTo(benchPos.getX() + 3.5, benchPos.getY() + 1.0, benchPos.getZ() + 0.5);
        System.out.println("ASSY_PREP bench=" + benchPos + " plans=" + SurvivalWorks.plans.size());
    }

    private static void fillAndStart() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || benchPos == null) { System.out.println("ASSY_START skip server=" + server + " bench=" + benchPos); return; }
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (!(player.serverLevel().getBlockEntity(benchPos) instanceof AssemblyStation station)) {
            System.out.println("ASSY_START no station at " + benchPos);
            return;
        }
        SurvivalWorks.Plan plan = station.plan();
        if (plan == null) { System.out.println("ASSY_START plan=null"); return; }
        int slot = 0;
        for (var cost : plan.costs()) {
            if (slot >= AssemblyStation.INPUTS) break;
            var stack = cost.product().stack();
            stack.setCount(cost.count());
            station.inventory.setItem(slot++, stack);
        }
        boolean started = station.start();
        System.out.println("ASSY_START plan=" + plan.id() + " started=" + started + " busy=" + station.busy() + " duration=" + station.duration);
    }

    private static void grab(Minecraft game, String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            System.out.println("ASSY_CAPTURE cannot create " + directory);
            return;
        }
        NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            System.out.println("ASSY_CAPTURE " + target.getAbsolutePath() + " selection=" + screen().getMenu().selection()
                    + " selectedRow=" + selectedRow());
        } catch (IOException failure) {
            System.out.println("ASSY_CAPTURE failed " + failure);
        } finally {
            image.close();
        }
    }
}
