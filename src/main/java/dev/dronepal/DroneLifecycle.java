package dev.dronepal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Player intent survives projection removals. The repair clock belongs to the serial,
 * including when the controller is dropped, moved between players, or copied. */
@EventBusSubscriber(modid = Dronepal.ID)
public final class DroneLifecycle {
    private static final String SLOT = "dronepal_slot_active";
    static final int REPAIR = 600, UNLOAD = 20;
    private DroneLifecycle() {}

    static final class Repairs extends SavedData {
        final Map<UUID, Long> deadlines = new HashMap<>();
        static final Factory<Repairs> FACTORY = new Factory<>(Repairs::new, Repairs::load);
        static Repairs load(CompoundTag tag, HolderLookup.Provider registries) {
            Repairs data = new Repairs();
            for (String key : tag.getAllKeys()) {
                try { data.deadlines.put(UUID.fromString(key), tag.getLong(key)); }
                catch (IllegalArgumentException ignored) { /* Ignore an invalid saved serial. */ }
            }
            return data;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            deadlines.forEach((id, until) -> tag.putLong(id.toString(), until));
            return tag;
        }
    }
    private static Repairs repairs(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Repairs.FACTORY, "dronepal_repairs");
    }
    static void delay(DroneEntity drone, int ticks) {
        if (!(drone.level() instanceof ServerLevel level)) return;
        Repairs repairs = repairs(level);
        repairs.deadlines.merge(drone.getUUID(), level.getServer().overworld().getGameTime() + ticks, Math::max);
        repairs.setDirty();
    }
    static boolean repairing(ServerLevel level, UUID id) {
        return repairs(level).deadlines.getOrDefault(id, 0L) > level.getServer().overworld().getGameTime();
    }
    @SubscribeEvent static void expire(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        if (now % 600 != 0) return;
        Repairs data = repairs(level);
        if (data.deadlines.values().removeIf(until -> until <= now)) data.setDirty();
    }
    @SubscribeEvent static void unloaded(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof DroneEntity drone && drone.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK)
            delay(drone, UNLOAD);
    }
    @SubscribeEvent static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) reconcile(player, CuriosDrones.equipped(player));
    }
    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) discardProjection(player);
    }
    @SubscribeEvent static void clone(PlayerEvent.Clone event) {
        CompoundTag from = event.getOriginal().getPersistentData(), to = event.getEntity().getPersistentData();
        if (from.hasUUID(DroneItem.ACTIVE)) to.putUUID(DroneItem.ACTIVE, from.getUUID(DroneItem.ACTIVE));
        if (from.getBoolean(SLOT)) to.putBoolean(SLOT, true);
    }
    @SubscribeEvent static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.isAlive() && !p.isSpectator()) reconcile(p, CuriosDrones.equipped(p));
    }
    static boolean recall(ServerPlayer player) {
        CompoundTag state = player.getPersistentData();
        boolean active = state.hasUUID(DroneItem.ACTIVE);
        discardProjection(player);
        state.remove(DroneItem.ACTIVE);
        state.remove(SLOT);
        return active;
    }
    private static void discardProjection(ServerPlayer player) {
        CompoundTag state = player.getPersistentData();
        if (!state.hasUUID(DroneItem.ACTIVE)) return;
        UUID id = state.getUUID(DroneItem.ACTIVE);
        for (ServerLevel level : player.serverLevel().getServer().getAllLevels()) {
            if (level.getEntity(id) instanceof DroneEntity drone && player.getUUID().equals(drone.owner)) drone.discard();
        }
    }
    static void reconcile(ServerPlayer player, ItemStack equipped) {
        CompoundTag state = player.getPersistentData();
        UUID requested = state.hasUUID(DroneItem.ACTIVE) ? state.getUUID(DroneItem.ACTIVE) : null;
        if (equipped.isEmpty()) {
            if (requested != null || state.contains(SLOT)) recall(player);
            return;
        }
        UUID id = DroneItem.ensureSerial(equipped);
        if (!id.equals(requested)) {
            recall(player);
            state.putUUID(DroneItem.ACTIVE, id);
        }
        requested = id;
        ServerLevel here = player.serverLevel();
        if (here.getEntity(requested) != null || repairing(here, requested)) return;
        // During a dimension change entity tracking can lag behind the player. Defer
        // spawning until the destination section can track the projection by UUID.
        if (!here.isPositionEntityTicking(net.minecraft.core.BlockPos.containing(DroneEntity.station(player)))) return;
        // Remove only this owner's stale cross-dimension projection. Deploy checks all
        // dimensions again, so another player's copy cannot take over their live entity.
        discardProjection(player);
        DroneItem.deploy(player, equipped, requested);
    }
}
