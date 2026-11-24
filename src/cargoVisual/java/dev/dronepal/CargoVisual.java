package dev.dronepal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Isolated client that opens the real terminal panel and the real cargo hold, then screenshots
 * both. The launch carries {@code options.txt lang:zh_cn}, so the Chinese titles are the shipped
 * language file, not hard-coded text.
 *
 * <p>Run with {@code gradlew -I gradle/cargo-visual.init.gradle runCargoVisual}.
 */
@EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class CargoVisual {
    private static int phase, ticks, settle;
    private static boolean pointed;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null || mc.getOverlay() != null) return;
        if (phase == 0) {
            if (mc.screen != null || ++ticks < 40) return;
            phase = 1;
            server.execute(() -> prepare(server));
        } else if (phase == 1) {
            if (!(mc.screen instanceof dev.dronepal.client.TerminalScreen)) return;
            if (!pointed) { pointed = true; movePointer(mc); return; }
            if (++settle < 12) return;
            settle = 0;
            capture(mc, "terminal-cargo-button-zh.png");
            server.execute(() -> openHold(server));
            phase = 2;
        } else if (phase == 2) {
            if (!(mc.screen instanceof dev.dronepal.client.CargoScreen)) return;
            if (++settle < 15) return;
            capture(mc, "cargo-hold-zh.png");
            phase = 3;
        } else if (phase == 3) {
            mc.stop();
        }
    }

    private static void prepare(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        player.setShiftKeyDown(false);
        ItemStack terminal = Dronepal.DRONE.toStack();
        Hardware.save(terminal, List.of(Hardware.moduleStack("carry_mk1"), Hardware.BATTERIES.getFirst().toStack()));
        DroneItem.ensureSerial(terminal);
        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow()
                .getStacks().setStackInSlot(0, terminal);
        DroneLifecycle.reconcile(player, terminal);
        CarryLight.save(player, terminal, List.of(
                new ItemStack(Items.PAPER, 12), new ItemStack(Items.IRON_INGOT, 64),
                new ItemStack(Items.REDSTONE, 32), new ItemStack(Items.GOLD_INGOT, 5),
                new ItemStack(Items.GLOWSTONE_DUST, 7), new ItemStack(Items.ARROW, 48)));
        var drone = player.serverLevel().getEntity(DroneItem.serial(terminal));
        if (!(drone instanceof DroneEntity entity)) return;
        player.setShiftKeyDown(true);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, entity));
        player.setShiftKeyDown(false);
    }

    private static void openHold(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (player.containerMenu instanceof TerminalMenu menu) menu.clickMenuButton(player, TerminalMenu.BUTTON_CARGO);
    }

    /** Park the pointer in a corner so no button tooltip covers the panel in the capture. */
    private static void movePointer(Minecraft mc) {
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 8.0, 8.0);
    }

    private static void capture(Minecraft mc, String name) {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(System.getProperty("dronepal.cargo.dir"));
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
            System.out.println("DRONEPAL_CARGO_CAPTURE " + dir.resolve(name));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private CargoVisual() {}
}
