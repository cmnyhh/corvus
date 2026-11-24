package dev.corvus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * One completed scan, addressed to the pilot who owns the serial.
 *
 * <p>1.20.1 has no {@code CustomPacketPayload}/{@code StreamCodec}, so the record is a plain
 * payload class with hand-written {@link #encode}/{@link #decode} and is registered on the mod's
 * {@code SimpleChannel} (see {@link CorvusNetwork}). The wire layout is byte-for-byte the 1.21 one.</p>
 *
 * <p>{@code expiresAt} is a game time in the receiver's dimension: the client drops the snapshot on
 * its own once that instant passes, so a lost "clear" packet cannot leave markers behind.</p>
 */
public record ScanSnapshot(UUID serial, ResourceLocation dimension, long expiresAt, List<Ore> ores,
                           List<Contact> hostiles) {
    public record Ore(BlockPos pos, ResourceLocation type) {}

    public record Contact(int entityId, UUID uuid, BlockPos pos, ResourceLocation type) {}

    /** Both lists are capped so a hostile datapack cannot flood a client. */
    public static final int LIMIT = 32;

    public ScanSnapshot {
        if (ores.size() > LIMIT || hostiles.size() > LIMIT) {
            throw new IllegalArgumentException("Too many scan results");
        }
        ores = List.copyOf(ores);
        hostiles = List.copyOf(hostiles);
    }

    public static void encode(ScanSnapshot snapshot, FriendlyByteBuf buffer) {
        buffer.writeUUID(snapshot.serial);
        buffer.writeResourceLocation(snapshot.dimension);
        buffer.writeLong(snapshot.expiresAt);
        buffer.writeByte(snapshot.ores.size());
        for (Ore ore : snapshot.ores) {
            buffer.writeBlockPos(ore.pos);
            buffer.writeResourceLocation(ore.type);
        }
        buffer.writeByte(snapshot.hostiles.size());
        for (Contact mob : snapshot.hostiles) {
            buffer.writeVarInt(mob.entityId);
            buffer.writeUUID(mob.uuid);
            buffer.writeBlockPos(mob.pos);
            buffer.writeResourceLocation(mob.type);
        }
    }

    public static ScanSnapshot decode(FriendlyByteBuf buffer) {
        UUID serial = buffer.readUUID();
        ResourceLocation dimension = buffer.readResourceLocation();
        long expires = buffer.readLong();
        List<Ore> ores = new ArrayList<>();
        for (int n = count(buffer); n > 0; n--) {
            ores.add(new Ore(buffer.readBlockPos(), buffer.readResourceLocation()));
        }
        List<Contact> contacts = new ArrayList<>();
        for (int n = count(buffer); n > 0; n--) {
            contacts.add(new Contact(buffer.readVarInt(), buffer.readUUID(), buffer.readBlockPos(),
                    buffer.readResourceLocation()));
        }
        return new ScanSnapshot(serial, dimension, expires, ores, contacts);
    }

    private static int count(FriendlyByteBuf buffer) {
        int count = buffer.readUnsignedByte();
        if (count > LIMIT) throw new IllegalArgumentException("Invalid scan entry count " + count);
        return count;
    }

    public boolean isEmpty() {
        return ores.isEmpty() && hostiles.isEmpty();
    }
}
