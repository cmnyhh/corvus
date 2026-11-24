package dev.dronepal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** A world's flight log follows the physical controller serial across owners and dimensions. */
@EventBusSubscriber(modid = Dronepal.ID)
public final class DroneArchive extends SavedData {
    record Entry(long firstTick, String frame, long ticks, int rebuilds, boolean awaitingRepair) {}
    private final Map<UUID, Entry> entries = new HashMap<>();
    private static final Factory<DroneArchive> FACTORY = new Factory<>(DroneArchive::new, DroneArchive::load);

    static DroneArchive get(ServerLevel world) {
        return world.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, "dronepal_archive");
    }

    static DroneArchive load(CompoundTag tag, HolderLookup.Provider registries) {
        DroneArchive archive = new DroneArchive();
        for (String key : tag.getAllKeys()) {
            if (!tag.contains(key, Tag.TAG_COMPOUND)) continue;
            CompoundTag data = tag.getCompound(key);
            try {
                archive.entries.put(UUID.fromString(key), new Entry(data.getLong("first"), data.getString("frame"),
                        data.getLong("ticks"), data.getInt("rebuilds"), data.getBoolean("repair")));
            } catch (IllegalArgumentException ignored) { /* Skip malformed serials individually. */ }
        }
        return archive;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        entries.forEach((serial, entry) -> {
            var data = new CompoundTag();
            data.putLong("first", entry.firstTick());
            data.putString("frame", entry.frame());
            data.putLong("ticks", entry.ticks());
            data.putInt("rebuilds", entry.rebuilds());
            data.putBoolean("repair", entry.awaitingRepair());
            tag.put(serial.toString(), data);
        });
        return tag;
    }

    Entry entry(UUID serial) { return entries.get(serial); }

    static void deployed(ServerLevel world, DroneEntity drone) {
        var archive = get(world);
        UUID serial = drone.getUUID();
        var old = archive.entry(serial);
        if (old == null) old = new Entry(world.getServer().overworld().getGameTime(), drone.getFrameId(), 0, 0, false);
        archive.entries.put(serial, new Entry(old.firstTick(), drone.getFrameId(), old.ticks(),
                old.rebuilds() + (old.awaitingRepair() ? 1 : 0), false));
        archive.setDirty();
    }

    static void lost(ServerLevel world, UUID serial) {
        var archive = get(world);
        var old = archive.entry(serial);
        if (old == null || old.awaitingRepair()) return;
        archive.entries.put(serial, new Entry(old.firstTick(), old.frame(), old.ticks(), old.rebuilds(), true));
        archive.setDirty();
    }

    static void checkpoint(ServerLevel world, DroneEntity drone) {
        int elapsed = drone.tickCount - drone.archiveTick;
        if (elapsed <= 0) return;
        var archive = get(world);
        var old = archive.entry(drone.getUUID());
        if (old == null) return;
        archive.entries.put(drone.getUUID(), new Entry(old.firstTick(), old.frame(), old.ticks() + elapsed,
                old.rebuilds(), old.awaitingRepair()));
        drone.archiveTick = drone.tickCount;
        archive.setDirty();
    }

    @SubscribeEvent static void left(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof DroneEntity drone && event.getLevel() instanceof ServerLevel level) checkpoint(level, drone);
    }

    static ItemStack selected(ServerPlayer player) {
        if (player.getMainHandItem().is(Dronepal.DRONE.get())) return player.getMainHandItem();
        if (player.getOffhandItem().is(Dronepal.DRONE.get())) return player.getOffhandItem();
        ItemStack equipped = CuriosDrones.equipped(player);
        return equipped.isEmpty() ? Hardware.controller(player) : equipped;
    }

    @SubscribeEvent static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dronepal").then(Commands.literal("memory").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            UUID id = DroneItem.serial(selected(player));
            Entry entry = get(player.serverLevel()).entry(id);
            if (entry == null) {
                ctx.getSource().sendFailure(Component.translatable("message.dronepal.memory_empty"));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.translatable("message.dronepal.memory", Frames.name(entry.frame()),
                    id.toString(), entry.firstTick(), entry.ticks() / 20, entry.rebuilds()), false);
            return 1;
        })));
    }
}
