package dev.corvus.devtools;

import dev.corvus.Corvus;
import dev.corvus.DroneDeployment;
import dev.corvus.DroneEntity;
import dev.corvus.DroneCurios;
import dev.corvus.data.CorvusKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Development-only smoke harness (server half). Inactive unless {@code -Dcorvus.smoke=1}
 * (client run) or {@code -Dcorvus.genworld=1} (dedicated server, generate a save and stop).
 *
 * <p>Not gameplay code: it scripts the acceptance run (give a terminal, wear it in the Curios slot,
 * report what deployed) so the client screenshot is reproducible instead of hand-driven.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class SmokeServer {
    private static final boolean SMOKE = "1".equals(System.getProperty("corvus.smoke"));
    private static final boolean GEN_WORLD = "1".equals(System.getProperty("corvus.genworld"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-smoke");

    private static int ticks;
    private static boolean handedOut;
    private static boolean equipped;
    private static int reportAt = 200;

    private SmokeServer() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!SMOKE && !GEN_WORLD) return;
        MinecraftServer server = event.getServer();
        if (server.isStopped()) return;
        if (GEN_WORLD) {
            ticks++;
            if (ticks == 5) LOGGER.info("[corvus-smoke] genworld: waiting for spawn area");
            if (ticks >= 250) {
                LOGGER.info("[corvus-smoke] genworld: stopping server to flush the save");
                server.halt(false);
            }
            return;
        }
        // Smoke clock only runs while a player is actually in the world.
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ticks++;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (ticks == 2) {
            net.minecraft.world.item.ItemStack worn = DroneCurios.equipped(player);
            LOGGER.info("[corvus-smoke] login state: curios slot={} activeSerial={}",
                    worn.isEmpty() ? "empty" : worn.getHoverName().getString(),
                    player.getPersistentData().hasUUID(CorvusKeys.ACTIVE)
                            ? player.getPersistentData().getUUID(CorvusKeys.ACTIVE) : "none");
        }
        if (ticks == 40 && !player.isCreative()) {
            player.setGameMode(GameType.CREATIVE);
            LOGGER.info("[corvus-smoke] forced creative mode for the run");
        }
        if (ticks == 60 && !handedOut) {
            handedOut = true;
            handOut(player);
        }
        if (ticks == 80 && !equipped) {
            equipped = true;
            wear(player);
        }
        if (ticks == reportAt) {
            report(player);
        }
    }

    private static void handOut(ServerPlayer player) {
        // Same path a player uses: /give with frame + paint NBT.
        String command = "give @s huhu_dronepal:drone{dronepal_frame:\"owl\",dronepal_paint:\"neon\"}";
        // Permission 4 on purpose: a dedicated-server world has no ops, and this is a scripted run.
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
        LOGGER.info("[corvus-smoke] gave terminal via /{} -> inventory stacks of huhu_dronepal:drone={}",
                command, countTerminals(player));
    }

    private static void wear(ServerPlayer player) {
        ItemStack found = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.is(Corvus.DRONE.get())) {
                found = candidate.copy();
                break;
            }
        }
        if (found.isEmpty()) {
            LOGGER.error("[corvus-smoke] no terminal in inventory; cannot equip");
            return;
        }
        final ItemStack worn = found;
        boolean slotPresent = DroneCurios.slotPresent(player);
        boolean[] placed = {false};
        top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inventory -> inventory.getStacksHandler(DroneCurios.SLOT))
                .ifPresent(handler -> {
                    handler.getStacks().setStackInSlot(0, worn);
                    placed[0] = !handler.getStacks().getStackInSlot(0).isEmpty();
                });
        // The slot is guarded by the "curios:tag" validator, which tests #curios:drone.
        boolean inTag = worn.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                new net.minecraft.resources.ResourceLocation("curios", "drone")));
        boolean tagValidator = top.theillusivec4.curios.api.CuriosApi.getCurioPredicates()
                .containsKey(new net.minecraft.resources.ResourceLocation("curios", "tag"));
        LOGGER.info("[corvus-smoke] curios drone slot present={} equipped={} tag#curios:drone={} tagValidator={} serial={}",
                slotPresent, placed[0], inTag, tagValidator, dev.corvus.DroneItem.serial(worn));
        // Keep the loose copy out of the inventory so the screenshot has one terminal only.
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(Corvus.DRONE.get())) {
                player.getInventory().removeItem(slot, 1);
                break;
            }
        }
    }

    private static int countTerminals(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(Corvus.DRONE.get())) count++;
        }
        return count;
    }

    private static void report(ServerPlayer player) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone == null) {
            LOGGER.error("[corvus-smoke] NO DEPLOYED DRONE after equip (slot={} worn={})",
                    DroneCurios.slotPresent(player), !DroneCurios.equipped(player).isEmpty());
            return;
        }
        LOGGER.info("[corvus-smoke] deployed drone frame={} paint={} pos={} distToOwner={}",
                drone.getFrameId(), drone.getPaintName(), drone.position(), drone.distanceTo(player));
    }
}
