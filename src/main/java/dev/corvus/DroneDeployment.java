package dev.corvus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import dev.corvus.data.CorvusKeys;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps the projection in sync with the controller in the Curios {@code drone} slot.
 *
 * <p>Equip == deploy, unequip == recall. The player's persistent data remembers the serial that is
 * currently flying, so the projection can be rebuilt after a dimension change or a relog without
 * duplicating it.</p>
 *
 * <p>Phase 1 simplicity: the live projection is also tracked in a server-thread map. Chunk-level
 * UUID lookups only see loaded entities, and a follower is always loaded anyway, so the map avoids
 * spawning a second body when the entity is briefly outside a loaded section.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class DroneDeployment {
    private static final Map<UUID, DroneEntity> LIVE = new HashMap<>();

    private DroneDeployment() {}

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.isAlive() || player.isSpectator()) return;
        reconcile(player);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) discardProjection(player, false);
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) discardProjection(player, false);
    }

    static void reconcile(ServerPlayer player) {
        CompoundTag state = player.getPersistentData();
        ItemStack equipped = DroneCurios.equipped(player);
        UUID requested = state.hasUUID(CorvusKeys.ACTIVE) ? state.getUUID(CorvusKeys.ACTIVE) : null;
        if (equipped.isEmpty()) {
            if (requested != null) recall(player);
            return;
        }
        UUID serial = DroneItem.ensureSerial(equipped);
        if (!serial.equals(requested)) recall(player);
        if (find(serial) == null) deploy(player, equipped, serial);
        state.putUUID(CorvusKeys.ACTIVE, serial);
    }

    static boolean deploy(ServerPlayer owner, ItemStack stack, UUID serial) {
        ServerLevel level = owner.serverLevel();
        DroneEntity drone = new DroneEntity(Corvus.ENTITY.get(), level);
        drone.setUUID(serial);
        drone.setOwner(owner.getUUID());
        drone.setFrame(Frames.id(stack));
        drone.setPaint(Frames.paint(stack));
        net.minecraft.world.phys.Vec3 station = DroneEntity.station(owner);
        drone.moveTo(station.x, station.y, station.z, owner.getYRot(), 0.0F);
        owner.getPersistentData().putUUID(CorvusKeys.ACTIVE, serial);
        if (!level.addFreshEntity(drone)) return false;
        LIVE.put(serial, drone);
        return true;
    }

    /** Unequip / slot swap: drop the body but remember the serial. */
    static boolean recall(ServerPlayer player) {
        CompoundTag state = player.getPersistentData();
        UUID serial = state.hasUUID(CorvusKeys.ACTIVE) ? state.getUUID(CorvusKeys.ACTIVE) : null;
        state.remove(CorvusKeys.ACTIVE);
        if (serial == null) return false;
        DroneEntity drone = find(serial);
        if (drone != null) drone.discard();
        LIVE.remove(serial);
        return true;
    }

    /** Dimension change and logout: the body must not keep flying in the old level. */
    private static void discardProjection(ServerPlayer player, boolean forgetSerial) {
        CompoundTag state = player.getPersistentData();
        UUID serial = state.hasUUID(CorvusKeys.ACTIVE) ? state.getUUID(CorvusKeys.ACTIVE) : null;
        if (forgetSerial) state.remove(CorvusKeys.ACTIVE);
        if (serial == null) return;
        DroneEntity drone = find(serial);
        if (drone != null) drone.discard();
        LIVE.remove(serial);
    }

    static DroneEntity find(UUID serial) {
        DroneEntity drone = LIVE.get(serial);
        if (drone != null && !drone.isRemoved() && drone.isAlive()) return drone;
        if (drone != null) LIVE.remove(serial);
        return null;
    }

    /** Deployed projection for this player, or null. Used by debug/smoke tooling. */
    public static DroneEntity active(ServerPlayer player) {
        CompoundTag state = player.getPersistentData();
        if (!state.hasUUID(CorvusKeys.ACTIVE)) return null;
        return find(state.getUUID(CorvusKeys.ACTIVE));
    }
}
