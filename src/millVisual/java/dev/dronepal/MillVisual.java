package dev.dronepal;

import dev.dronepal.client.OreMillScreen;
import dev.dronepal.mill.MillItems;
import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillMenu;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.mill.OreMillRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Screenshot harness for the ore mill panel. It opens the real screen, refills the tank so the machine
 * is genuinely running (green lamp, live progress, speed and cost), captures it at GUI scale 2 and 4,
 * then drains the tank so the panel reports "stopped: no water" (yellow lamp) and captures that at both
 * scales as well. Before the first shot it runs real jobs so the three output wells hold genuine output:
 * iron concentrate (primary), coarse_iron (by-product) and a bonus diamond shard (extra).
 *
 * <p>Run with {@code gradlew -I gradle/mill-visual.init.gradle runMillVisual}. Requires a display; the
 * harness quits after the fourth capture.</p>
 */
@EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class MillVisual {
    private static final int[] SCALES = {2, 4};

    private static int phase, ticks, settle;
    private static boolean opening, mouseChecked;
    private static java.lang.reflect.Method mouseOnMove;
    private static BlockPos millPos;
    private static boolean staged;
    private static String shotPrefix = System.getProperty("dronepal.mill.prefix", "millgui");

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        ticks++;
        if (phase == 0) {
            // The launch passes --quickPlaySingleplayer; if that misfires we open the world ourselves.
            setTutorialDone(mc);
            if (ticks % 200 == 0) {
                System.out.println("MILL_VISUAL_WAIT ticks=" + ticks + " screen="
                        + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName())
                        + " level=" + (mc.level != null) + " player=" + (mc.player != null));
            }
            // A busy machine can take minutes to build the spawn chunks, so the boot guard is generous;
            // it only exists to stop a genuinely hung run.
            if (ticks > 24_000) { System.out.println("MILL_VISUAL_TIMEOUT"); mc.stop(); return; }
            if (!opening && mc.getOverlay() == null && ticks > 100
                    && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                opening = true;
                System.out.println("MILL_VISUAL_OPEN_WORLD from title screen");
                mc.createWorldOpenFlows().openWorld("New World", () -> opening = false);
                return;
            }
            if (server == null || mc.player == null || mc.level == null || mc.getOverlay() != null) return;
            if (mc.screen != null || ticks < 40) return;
            mc.options.pauseOnLostFocus = false;
            phase = 1;
            server.execute(() -> prepare(server));
            return;
        }
        if (server == null || mc.player == null || mc.level == null || mc.getOverlay() != null) return;
        // A headless window loses focus and the integrated server pauses, which freezes menu data.
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        switch (phase) {
            case 1 -> {
                // Working state, GUI scale 2.
                if (!(mc.screen instanceof OreMillScreen)) return;
                if (settle == 0) { setScale(mc, SCALES[0]); focusWindow(mc); }
                aimMouse(mc);
                settle++;
                if (settle == 20) server.execute(MillVisual::refill);
                if (settle < 26) return;
                aimMouse(mc);
                capture(mc, shotPrefix + "-working.png");
                // The deliverable the acceptance notes ask for: the three output wells with real output.
                capture(mc, shotPrefix + "-3slots.png");
                settle = 0;
                phase = 2;
            }
            case 2 -> {
                // Working state, GUI scale 4.
                if (settle == 0) { setScale(mc, SCALES[1]); focusWindow(mc); }
                aimMouse(mc);
                settle++;
                if (settle < 20) return;
                server.execute(MillVisual::refill);
                if (settle < 26) return;
                aimMouse(mc);
                capture(mc, shotPrefix + "-working-scale4.png");
                settle = 0;
                phase = 3;
            }
            case 3 -> {
                // Dry tank: the machine stops and the lamp turns yellow. GUI scale 4 first.
                if (settle == 0) { server.execute(MillVisual::drain); focusWindow(mc); }
                aimMouse(mc);
                settle++;
                if (settle < 26) return;
                aimMouse(mc);
                capture(mc, shotPrefix + "-dry-scale4.png");
                settle = 0;
                phase = 4;
            }
            case 4 -> {
                // Dry tank at GUI scale 2.
                if (settle == 0) { setScale(mc, SCALES[0]); focusWindow(mc); }
                aimMouse(mc);
                settle++;
                if (settle < 20) return;
                aimMouse(mc);
                capture(mc, shotPrefix + "-dry.png");
                phase = 5;
            }
            default -> mc.stop();
        }
    }

    private static void setTutorialDone(Minecraft mc) {
        if (mc.options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
        }
    }

    private static void setScale(Minecraft mc, int scale) {
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
    }

    /**
     * Park the virtual cursor in the corner so no slot tooltip is painted into the capture. The engine
     * keeps the scaled pointer position private, so reach it reflectively when the runtime allows it and
     * fall back to warping the real cursor.
     */
    private static void aimMouse(Minecraft mc) {
        if (!mouseChecked) {
            mouseChecked = true;
            try {
                mouseOnMove = net.minecraft.client.MouseHandler.class
                        .getDeclaredMethod("onMove", long.class, double.class, double.class);
                mouseOnMove.setAccessible(true);
                System.out.println("MILL_VISUAL_MOUSE reflection=ok");
            } catch (Throwable failure) {
                mouseOnMove = null;
                System.out.println("MILL_VISUAL_MOUSE reflection=unavailable " + failure);
            }
        }
        if (mouseOnMove != null) {
            try {
                mouseOnMove.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0.0, 0.0);
            } catch (Throwable failure) {
                mouseOnMove = null;
            }
        }
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 2.0, 2.0);
    }

    private static void focusWindow(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        org.lwjgl.glfw.GLFW.glfwFocusWindow(window);
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(window, 2.0, 2.0);
    }

    /** A clean stone stage high above the spawn trees, so the world capture frames the machine. */
    private static void stage(MinecraftServer server, ServerPlayer player) {
        if (staged) return;
        staged = true;
        var level = player.serverLevel();
        BlockPos base = player.blockPosition().above(14);
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
            level.setBlockAndUpdate(base.below().offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
            for (int dy = 1; dy <= 9; dy++) level.setBlockAndUpdate(base.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        millPos = base;
    }

    private static void prepare(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        var level = player.serverLevel();
        stage(server, player);
        level.setBlockAndUpdate(millPos, OreMillRegistry.MILL.get().defaultBlockState());
        if (!(level.getBlockEntity(millPos) instanceof OreMillEntity mill)) { System.out.println("MILL_PREP no be at " + millPos); return; }
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 32));
        mill.inventory().setItem(1, new ItemStack(Items.DIAMOND_ORE, 16));
        mill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        mill.inventory().setItem(OreMillMenu.UPGRADE_FIRST + 1, new ItemStack(MillItems.COOLING.get()));
        ItemStack controller = Frames.create("peregrine");
        ItemStack battery = Hardware.BATTERIES.get(2).toStack();
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, battery,
                tag -> tag.putInt("energy", Hardware.capacity(battery)));
        var parts = new ArrayList<ItemStack>();
        parts.add(battery);
        parts.add(Hardware.moduleStack("scan_mk1"));
        parts.add(Hardware.moduleStack("carry_mk1"));
        controller.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(parts));
        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow()
                .getStacks().setStackInSlot(0, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = player.serverLevel().getEntity(DroneItem.serial(controller));
        if (!(drone instanceof DroneEntity entity)) return;
        var hover = mill.hoverCenter(); entity.setPos(hover.x, hover.y, hover.z);
        entity.assignWork(millPos);
        mill.assignDrone(entity.getUUID(), player.getUUID());
        prime(mill, level);
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 32));
        mill.inventory().setItem(1, ItemStack.EMPTY);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new OreMillMenu(id, inventory, mill),
                Component.translatable("block.huhu_dronepal.ore_mill")));
        player.teleportTo(millPos.getX() + 5.5, millPos.getY() + 1.6, millPos.getZ() + 0.5);
        player.setYRot(90f);
        player.setXRot(8f);
    }

    /**
     * Drives the real block entity before the capture so the panel shows genuine mill output: a rare pass
     * puts a bonus shard in the extra well, then an iron pass leaves iron concentrate in the primary well
     * and {@code coarse_iron} in the by-product well. Nothing is hand-placed in a slot.
     */
    private static void prime(OreMillEntity mill, ServerLevel level) {
        mill.setRandom(net.minecraft.util.RandomSource.create(20261125L));
        for (int i = 0; i < 1200 && mill.output(OreMillMenu.OUTPUT_EXTRA).isEmpty(); i++) {
            topUp(mill);
            mill.inventory().setItem(0, new ItemStack(Items.DIAMOND_ORE, 1));
            mill.tick(level);
            // Well 0 collects the rare primary shards; draining it keeps a full stack from stopping us.
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY, ItemStack.EMPTY);
        }
        for (int i = 0; i < 4000; i++) {
            ItemStack sand = mill.output(OreMillMenu.OUTPUT_BYPRODUCT);
            if (!sand.isEmpty() && sand.getCount() >= 3) break;
            topUp(mill);
            mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
            mill.tick(level);
        }
        System.out.println("MILL_VISUAL_PRIME primary=" + mill.output(OreMillMenu.OUTPUT_PRIMARY)
                + " byproduct=" + mill.output(OreMillMenu.OUTPUT_BYPRODUCT)
                + " extra=" + mill.output(OreMillMenu.OUTPUT_EXTRA)
                + " link=" + mill.link());
    }

    private static void topUp(OreMillEntity mill) {
        if (mill.water() < mill.currentWater()) mill.setWater(OreMillRules.WATER_CAPACITY);
        if (mill.energy() < mill.currentCost()) mill.setEnergy(OreMillRules.BUFFER);
    }

    /** Keep the tank full and a stack in the input so the panel stays in the green "working" state. */
    private static void refill() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || millPos == null) return;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (!(player.level().getBlockEntity(millPos) instanceof OreMillEntity mill)) return;
        mill.setWater(OreMillRules.WATER_CAPACITY);
        mill.setEnergy(OreMillRules.BUFFER);
        if (mill.inventory().getItem(0).getCount() < 8) mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 32));
    }

    /** Empty the tank so the machine reports "stopped: no water" and the lamp turns yellow. */
    private static void drain() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || millPos == null) return;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (!(player.level().getBlockEntity(millPos) instanceof OreMillEntity mill)) return;
        mill.setWater(0);
        mill.setEnergy(OreMillRules.BUFFER);
    }

    private static void capture(Minecraft mc, String name) {
        if (mc.screen instanceof OreMillScreen screen) {
            var menu = screen.getMenu();
            int waterPercent = menu.waterCapacity() <= 0 ? 0 : menu.water() * 100 / menu.waterCapacity();
            int energyPercent = menu.buffer() <= 0 ? 0 : menu.energy() * 100 / menu.buffer();
            System.out.println("MILL_GUI_AUDIT shot=" + name
                    + " guiScale=" + mc.options.guiScale().get()
                    + " gui=" + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight()
                    + " panel=" + OreMillMenu.PANEL_WIDTH + "x" + OreMillMenu.PANEL_HEIGHT
                    + " left=" + (screen.width - OreMillMenu.PANEL_WIDTH) / 2
                    + " top=" + (screen.height - OreMillMenu.PANEL_HEIGHT) / 2
                    + " link=" + menu.link()
                    + " water=" + waterPercent + "% energy=" + energyPercent + "%"
                    + " speed=" + menu.speedPercent() + "% cost=" + menu.energyPerItem()
                    + " progress=" + menu.progress() + "/" + menu.duration()
                    + " mouse=" + String.format(java.util.Locale.ROOT, "%.1f,%.1f", mc.mouseHandler.xpos(), mc.mouseHandler.ypos()));
        }
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(System.getProperty("dronepal.mill.dir"));
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
            System.out.println("DRONEPAL_MILL_CAPTURE " + dir.resolve(name));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private MillVisual() {}
}
