package dev.dronepal;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Dronepal.ID)
public record DroneVoice(UUID drone, ResourceLocation event, float speed) implements CustomPacketPayload {
    static final Type<DroneVoice> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "voice"));
    static final StreamCodec<RegistryFriendlyByteBuf,DroneVoice> CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, DroneVoice::drone, ResourceLocation.STREAM_CODEC, DroneVoice::event,
        ByteBufCodecs.FLOAT, DroneVoice::speed, DroneVoice::new);
    @Override public Type<DroneVoice> type() { return TYPE; }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, CODEC, (packet, ctx) -> dev.dronepal.client.DroneVoicePlayer.accept(packet));
    }
}
