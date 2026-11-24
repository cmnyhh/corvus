package dev.dronepal;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Private owner-only sample emitted once per simulated second after the energy transaction. */
@EventBusSubscriber(modid = Dronepal.ID)
public record DroneTelemetry(UUID serial, String dimension, String frame, String mode, boolean holding, int energy,
        int capacity, int consumed, int generated, int fitted, int online) implements CustomPacketPayload {
    public static final List<String> FAMILIES = List.of("combat", "scan", "carry", "medic", "light", "link", "solar");
    static final Type<DroneTelemetry> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "telemetry"));
    @Override public Type<DroneTelemetry> type() { return TYPE; }
    @SubscribeEvent static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, StreamCodec.of((b,s) -> {
            b.writeUUID(s.serial); b.writeUtf(s.dimension, 256); b.writeUtf(s.frame, 32); b.writeUtf(s.mode, 32);
            b.writeBoolean(s.holding); b.writeVarInt(s.energy); b.writeVarInt(s.capacity); b.writeVarInt(s.consumed);
            b.writeVarInt(s.generated); b.writeByte(s.fitted); b.writeByte(s.online);
        }, b -> new DroneTelemetry(b.readUUID(), b.readUtf(256), b.readUtf(32), b.readUtf(32), b.readBoolean(),
                b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUnsignedByte(), b.readUnsignedByte())),
                (message, ctx) -> dev.dronepal.client.DroneHud.receive(message));
    }
    static void publish(DroneEntity drone, ServerPlayer owner, ItemStack controller, int consumed, int generated) {
        if (controller.isEmpty() || !owner.getUUID().equals(drone.owner) || !drone.getUUID().equals(DroneItem.serial(controller))) return;
        int fitted = 0, online = 0;
        for (var module : Hardware.installed(controller)) {
            int i = FAMILIES.indexOf(module.family());
            if (i < 0) continue;
            fitted |= 1 << i;
            if (Hardware.online(controller, module.family())) online |= 1 << i;
        }
        var sample = new DroneTelemetry(drone.getUUID(), owner.level().dimension().location().toString(), drone.getFrameId(),
            Engagement.mode(controller), drone.holdingStation, Hardware.energy(controller), Hardware.capacity(controller), consumed, generated, fitted, online);
        drone.telemetry = sample;
        PacketDistributor.sendToPlayer(owner, sample);
    }
    public static boolean low(int energy, int capacity) { return capacity > 0 && energy * 5L < capacity; }
    public static String rate(int perSecond) {
        int tenths = (int)Math.round(perSecond / 2.0);
        return tenths % 10 == 0 ? Integer.toString(tenths / 10) : tenths / 10 + "." + tenths % 10;
    }
}
