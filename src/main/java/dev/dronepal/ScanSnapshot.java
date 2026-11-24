package dev.dronepal;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Dronepal.ID)
public record ScanSnapshot(UUID serial, ResourceLocation dimension, long expiresAt, List<Ore> ores, List<Contact> hostiles) implements CustomPacketPayload {
    public record Ore(BlockPos pos, ResourceLocation type) {}
    public record Contact(int entityId, UUID uuid, BlockPos pos, ResourceLocation type) {}
    static final int LIMIT = 32;
    public ScanSnapshot {
        if (ores.size() > LIMIT || hostiles.size() > LIMIT) throw new IllegalArgumentException("Too many scan results");
        ores = List.copyOf(ores);
        hostiles = List.copyOf(hostiles);
    }
    static final Type<ScanSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "scan_snapshot"));
    static final StreamCodec<RegistryFriendlyByteBuf, ScanSnapshot> CODEC = StreamCodec.of(ScanSnapshot::write, ScanSnapshot::read);
    public Type<ScanSnapshot> type() { return TYPE; }
    @SubscribeEvent static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, CODEC, (snapshot, ctx) -> dev.dronepal.client.ScanDisplay.accept(snapshot));
    }
    private static void write(RegistryFriendlyByteBuf buf, ScanSnapshot s) {
        buf.writeUUID(s.serial); buf.writeResourceLocation(s.dimension); buf.writeLong(s.expiresAt);
        buf.writeByte(s.ores.size());
        for (var ore : s.ores) { buf.writeBlockPos(ore.pos); buf.writeResourceLocation(ore.type); }
        buf.writeByte(s.hostiles.size());
        for (var mob : s.hostiles) {
            buf.writeVarInt(mob.entityId); buf.writeUUID(mob.uuid); buf.writeBlockPos(mob.pos); buf.writeResourceLocation(mob.type);
        }
    }
    private static int count(RegistryFriendlyByteBuf buf) {
        int n = buf.readUnsignedByte();
        if (n > LIMIT) throw new IllegalArgumentException("Invalid scan entry count");
        return n;
    }
    private static ScanSnapshot read(RegistryFriendlyByteBuf buf) {
        UUID serial = buf.readUUID();
        var dimension = buf.readResourceLocation();
        long expires = buf.readLong();
        var ores = new ArrayList<Ore>();
        for (int n = count(buf); n > 0; n--) ores.add(new Ore(buf.readBlockPos(), buf.readResourceLocation()));
        var contacts = new ArrayList<Contact>();
        for (int n = count(buf); n > 0; n--) contacts.add(new Contact(buf.readVarInt(), buf.readUUID(), buf.readBlockPos(), buf.readResourceLocation()));
        return new ScanSnapshot(serial, dimension, expires, ores, contacts);
    }
}
