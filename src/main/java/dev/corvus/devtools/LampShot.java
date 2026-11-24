package dev.corvus.devtools;

import com.mojang.blaze3d.platform.NativeImage;
import dev.corvus.Corvus;
import dev.corvus.DroneCurios;
import dev.corvus.DroneDeployment;
import dev.corvus.DroneEntity;
import dev.corvus.Hardware;
import dev.corvus.LightSync;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Development-only client harness (agent M): photograph the drone's lamp at night, then photograph it
 * switched off, so the difference between "lit" and "dark" is a picture instead of a claim.
 *
 * <p>Inactive unless {@code CORVUS_LAMPSHOT=1} in the environment or {@code -Dcorvus.lampShot=1}. The
 * scripted run is: wear a terminal with a battery, the tactical chip and {@code light_mk1} (radius 8,
 * brightness 12), set midnight so the lamp is the only light, wait for the server's once-per-second lamp
 * publish to reach this client, then take two screenshots a few seconds apart — before and after the
 * {@code /dronepal 开灯} order toggles the lamp off.</p>
 *
 * <p>It also logs the client-side lamp cache ({@link LightSync#lamp}) at both moments: that is the proof
 * that the state crossed the wire, not just that the server thinks the lamp is on.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class LampShot {
    private static final boolean ENABLED = "1".equals(System.getenv("CORVUS_LAMPSHOT"))
            || "1".equals(System.getProperty("corvus.lampShot"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-lamp-shot");

    private static final int EQUIP_AT = 40;
    private static final int FIT_AT = 110;
    private static final int CAMERA_AT = 120;
    private static final int REPORT_ON_AT = 170;
    private static final int SHOT_ON_AT = 185;
    private static final int TOGGLE_AT = 200;
    private static final int REPORT_OFF_AT = 260;
    private static final int SHOT_OFF_AT = 275;
    private static final int DONE_AT = 290;

    private static int ticks;
    private static boolean equipped;
    private static boolean fitted;
    private static boolean camera;
    private static boolean reportedOn;
    private static boolean shotOn;
    private static boolean toggled;
    private static boolean reportedOff;
    private static boolean shotOff;
    private static boolean done;

    private LampShot() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.level == null) return;
        ticks++;
        if (ticks == 1) {
            // A dev run has no window focus: without this the pause menu stops the integrated server.
            game.options.pauseOnLostFocus = false;
            LOGGER.info("[lamp-shot] pauseOnLostFocus=false, waiting for the world");
        }
        if (ticks == EQUIP_AT && !equipped) {
            equipped = true;
            server(game, LampShot::equip);
        }
        if (ticks == FIT_AT && !fitted) {
            fitted = true;
            server(game, LampShot::fitAndNight);
        }
        if (ticks == CAMERA_AT && !camera) {
            camera = true;
            // Third person, so the drone (right shoulder, 1.5 blocks up) is in frame with the player.
            game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            LOGGER.info("[lamp-shot] camera={}", game.options.getCameraType());
        }
        if (ticks == REPORT_ON_AT && !reportedOn) {
            reportedOn = true;
            report(game, "on");
        }
        if (ticks == SHOT_ON_AT && !shotOn) {
            shotOn = true;
            grab(game, "20-drone-lamp-on.png");
        }
        if (ticks == TOGGLE_AT && !toggled) {
            toggled = true;
            server(game, player -> player.getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4), "dronepal 开灯"));
        }
        if (ticks == REPORT_OFF_AT && !reportedOff) {
            reportedOff = true;
            report(game, "off");
        }
        if (ticks == SHOT_OFF_AT && !shotOff) {
            shotOff = true;
            grab(game, "21-drone-lamp-off.png");
        }
        if (ticks == DONE_AT && !done) {
            done = true;
            LOGGER.info("[lamp-shot] done, stopping the client");
            game.stop();
        }
    }

    private static void server(Minecraft game, java.util.function.Consumer<ServerPlayer> action) {
        var integrated = game.getSingleplayerServer();
        if (integrated == null) {
            LOGGER.error("[lamp-shot] no integrated server");
            return;
        }
        integrated.execute(() -> {
            List<ServerPlayer> players = integrated.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                LOGGER.error("[lamp-shot] no player on the integrated server");
                return;
            }
            action.accept(players.get(0));
        });
    }

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
        LOGGER.info("[lamp-shot] terminal worn={} describe=[{}]",
                !DroneCurios.equipped(player).isEmpty(), Hardware.describe(DroneCurios.equipped(player)));
    }

    /** Battery + tactical chip + lamp, midnight, clear weather, and the lamp switch written on. */
    private static void fitAndNight(ServerPlayer player) {
        ItemStack worn = DroneCurios.equipped(player);
        if (worn.isEmpty()) {
            LOGGER.error("[lamp-shot] no worn terminal to fit");
            return;
        }
        List<ItemStack> parts = new ArrayList<>();
        parts.add(item("omd_bat_300"));
        parts.add(new ItemStack(Hardware.CHIP_ITEMS.get("tactical").get()));
        parts.add(Hardware.moduleStack("light_mk1"));
        Hardware.save(worn, parts);
        Hardware.setEnergy(worn, Hardware.capacity(worn));
        // The switch has no NBT key until somebody turns it off; a world that has already been shot once
        // carries the "off" key, so switch the lamp back on through the same order a player would use.
        if (dev.corvus.DroneRemote.disabled(worn, "light")) Hardware.execute(player, "order.light");
        var commands = player.getServer().getCommands();
        commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "time set midnight");
        commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), "weather clear");
        DroneEntity drone = DroneDeployment.active(player);
        LOGGER.info("[lamp-shot] fitted [{}] energy={}/{} drone={} dayTime={}",
                Hardware.describe(worn), Hardware.energy(worn), Hardware.capacity(worn),
                drone == null ? "none" : drone.getUUID(), player.level().getDayTime());
    }

    /** Logs what this client actually holds for the lamp, entity by entity. */
    private static void report(Minecraft game, String phase) {
        int lamps = LightSync.cached();
        StringBuilder detail = new StringBuilder();
        for (var entity : game.level.entitiesForRendering()) {
            if (!(entity instanceof DroneEntity drone)) continue;
            LightSync.Lamp lamp = LightSync.lamp(drone.getId());
            detail.append(" id=").append(drone.getId())
                    .append(" lamp=").append(lamp == null ? "dark"
                            : ("r" + lamp.radius() + "/b" + lamp.brightness()
                                    + (lamp.flicker() ? "/flicker" : "")
                                    + " #" + String.format("%06X", lamp.rgb())));
        }
        LOGGER.info("[lamp-shot] phase={} client lamps cached={}{}", phase, lamps,
                detail.length() == 0 ? " (no drone in view)" : detail);
    }

    private static ItemStack item(String path) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(new net.minecraft.resources.ResourceLocation(Corvus.ID, path));
        if (item == null) throw new IllegalStateException("Unregistered item " + path);
        return new ItemStack(item);
    }

    private static void grab(Minecraft game, String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.error("[lamp-shot] cannot create screenshot directory {}", directory.getAbsolutePath());
            return;
        }
        NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            LOGGER.info("[lamp-shot] screenshot written: {} ({}x{})", target.getAbsolutePath(),
                    image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            LOGGER.error("[lamp-shot] screenshot failed: {}", failure.toString());
        } finally {
            image.close();
        }
    }
}
