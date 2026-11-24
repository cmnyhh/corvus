package dev.dronepal;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid=Dronepal.ID)
public record CommsMessage(UUID drone, Component speaker, String persona, Component text) implements CustomPacketPayload {
    private static final Type<CommsMessage> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID,"comms_subtitle"));
    @Override public Type<CommsMessage> type() { return TYPE; }
    public static void send(ServerPlayer owner, UUID drone, Component speaker, String persona, Component text) {
        PacketDistributor.sendToPlayer(owner,new CommsMessage(drone,speaker,persona,text));
    }
    @SubscribeEvent static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, StreamCodec.of((buf, msg) -> {
            buf.writeUUID(msg.drone());
            ComponentSerialization.STREAM_CODEC.encode(buf,msg.speaker());
            buf.writeUtf(msg.persona(),64);
            ComponentSerialization.STREAM_CODEC.encode(buf,msg.text());
        }, buf -> new CommsMessage(buf.readUUID(), ComponentSerialization.STREAM_CODEC.decode(buf),buf.readUtf(64),ComponentSerialization.STREAM_CODEC.decode(buf))),
            (msg,ctx) -> dev.dronepal.client.CommsSubtitles.accept(msg.speaker(),msg.persona(),msg.text()));
    }
}
