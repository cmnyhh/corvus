package dev.corvus.devtools;

import com.mojang.blaze3d.platform.NativeImage;
import dev.corvus.Corvus;
import dev.corvus.DroneCurios;
import dev.corvus.DroneDeployment;
import dev.corvus.Hardware;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Development-only client harness (agent G): photograph the terminal panel while it shows a real FE/t.
 *
 * <p>The number on that row only exists once the server has answered a wheel status for a controller with
 * fitted modules, so the shot needs a scripted setup: give and wear a terminal, fit a battery plus
 * {@code combat_mk1 + scan_mk1} (14 + 6 = 20 FE/t), open the panel server-side, wait for one status
 * round trip, screenshot, stop.</p>
 *
 * <p>Inactive unless {@code -Dcorvus.gapsShot=1}; it writes into {@code -Dcorvus.artifacts}
 * (runClient points that at {@code artifacts/client}).</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class GapShot {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.gapsShot"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-gap-shot");

    private static final int SETUP_AT = 40;
    private static final int FIT_AT = 110;
    /** Parked well before the shot: the screenshot captures the frame that was already drawn. */
    private static final int PARK_AT = 125;
    private static final int SHOT_AT = 145;
    private static final int DONE_AT = 155;

    private static int ticks;
    private static boolean setup, fitted, shot, reported;

    private GapShot() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.level == null) return;
        ticks++;
        if (ticks == 1) {
            // A dev run has no window focus: without this the pause menu stops the integrated server.
            game.options.pauseOnLostFocus = false;
            LOGGER.info("[gap-shot] pauseOnLostFocus=false, waiting for the world");
        }
        if (ticks == SETUP_AT && !setup) {
            setup = true;
            server(game, GapShot::equip);
        }
        if (ticks == FIT_AT && !fitted) {
            fitted = true;
            server(game, GapShot::fitAndOpen);
        }
        if (ticks == PARK_AT) {
            // The GUI takes its mouse coordinates from MouseHandler's cached position, and a screenshot
            // captures the frame drawn *before* this tick, so park the cursor a few ticks early: the
            // default position sits on the "Barks" button and its tooltip would cover the FE/t row.
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(game.getWindow().getWindow(), 8.0D, 8.0D);
            parkCursor(game);
        }
        if (ticks == SHOT_AT && !shot) {
            shot = true;
            grab(game, "13-terminal-fe.png");
        }
        if (ticks == DONE_AT && !reported) {
            reported = true;
            LOGGER.info("[gap-shot] done, stopping the client");
            game.stop();
        }
    }

    private static void server(Minecraft game, java.util.function.Consumer<ServerPlayer> action) {
        var integrated = game.getSingleplayerServer();
        if (integrated == null) {
            LOGGER.error("[gap-shot] no integrated server");
            return;
        }
        integrated.execute(() -> {
            List<ServerPlayer> players = integrated.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                LOGGER.error("[gap-shot] no player on the integrated server");
                return;
            }
            action.accept(players.get(0));
        });
    }

    /** Gives a terminal when the player has none, then wears it, exactly like the smoke server does. */
    private static void equip(ServerPlayer player) {
        ItemStack worn = DroneCurios.equipped(player);
        if (worn.isEmpty()) {
            player.getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                    "give @s huhu_dronepal:drone{dronepal_frame:\"bedrock\",dronepal_paint:\"teal\"}");
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack candidate = player.getInventory().getItem(slot);
                if (!candidate.is(Corvus.DRONE.get())) continue;
                ItemStack taken = candidate.copy();
                top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve()
                        .flatMap(inventory -> inventory.getStacksHandler(DroneCurios.SLOT))
                        .ifPresent(handler -> handler.getStacks().setStackInSlot(0, taken));
                player.getInventory().removeItem(slot, 1);
                break;
            }
        }
        LOGGER.info("[gap-shot] terminal worn={} describe=[{}]", !DroneCurios.equipped(player).isEmpty(),
                Hardware.describe(DroneCurios.equipped(player)));
    }

    /** Fits a battery and two modules (14 + 6 FE/t) and opens the panel so the row has a real figure. */
    private static void fitAndOpen(ServerPlayer player) {
        ItemStack worn = DroneCurios.equipped(player);
        if (worn.isEmpty()) {
            LOGGER.error("[gap-shot] no worn terminal to fit");
            return;
        }
        // Respect the frame: the panel draws one battery slot and N module slots, and a photo where the
        // fitted parts do not match the frame would be a picture of a data pack violation.
        var frame = dev.corvus.Frames.of(worn);
        int batteryTier = frame == null ? 3 : frame.maxBatteryTier();
        int moduleSlots = frame == null ? 2 : frame.moduleSlots();
        List<ItemStack> parts = new ArrayList<>();
        parts.add(item(batteryTier >= 3 ? "omd_bat_300" : batteryTier == 2 ? "omd_bat_200" : "omd_bat_100"));
        parts.add(new ItemStack(Hardware.CHIP_ITEMS.get("tactical").get()));
        if (moduleSlots >= 1) parts.add(Hardware.moduleStack("combat_mk1"));
        if (moduleSlots >= 2) parts.add(Hardware.moduleStack("scan_mk1"));
        Hardware.save(worn, parts);
        Hardware.setEnergy(worn, Hardware.capacity(worn));
        LOGGER.info("[gap-shot] fitted [{}] drain={} FE/t energy={}/{}", Hardware.describe(worn),
                Hardware.drainPerTick(worn), Hardware.energy(worn), Hardware.capacity(worn));
        var drone = DroneDeployment.active(player);
        boolean opened = drone != null && dev.corvus.TerminalMenus.openTerminal(player, drone);
        LOGGER.info("[gap-shot] drone={} terminal panel opened={}", drone, opened);
    }

    private static ItemStack item(String path) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(new net.minecraft.resources.ResourceLocation(Corvus.ID, path));
        if (item == null) throw new IllegalStateException("Unregistered item " + path);
        return new ItemStack(item);
    }

    /**
     * The GUI takes its mouse coordinates from {@code MouseHandler}'s cached position, which only the
     * window callbacks refresh; on a dev run without focus the GLFW call above may not land, so the cache
     * is parked directly. Nothing here touches gameplay state.
     */
    private static void parkCursor(Minecraft game) {
        try {
            for (String name : new String[]{"xpos", "ypos"}) {
                var field = net.minecraft.client.MouseHandler.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setDouble(game.mouseHandler, 8.0D);
            }
        } catch (ReflectiveOperationException unavailable) {
            LOGGER.warn("[gap-shot] cannot park the cursor ({}); the tooltip may cover a row", unavailable.toString());
        }
    }

    private static void grab(Minecraft game, String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.error("[gap-shot] cannot create screenshot directory {}", directory.getAbsolutePath());
            return;
        }
        if (game.screen == null) {
            LOGGER.error("[gap-shot] no screen open at the screenshot tick ({})", name);
            return;
        }
        LOGGER.info("[gap-shot] screenshot with screen={}", game.screen.getClass().getName());
        NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            LOGGER.info("[gap-shot] screenshot written: {} ({}x{})", target.getAbsolutePath(),
                    image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            LOGGER.error("[gap-shot] screenshot failed: {}", failure.toString());
        } finally {
            image.close();
        }
    }
}
