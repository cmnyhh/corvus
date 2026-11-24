package dev.corvus;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * One subtitle line, pushed to the owner only. 1.20.1 replacement for the 1.21
 * {@code CustomPacketPayload} record: same fields, {@code Component} carried by
 * {@link FriendlyByteBuf#writeComponent}.
 */
public record CommsMessage(UUID drone, Component speaker, String persona, Component text) {
    public static void send(ServerPlayer owner, UUID drone, Component speaker, String persona, Component text) {
        if (owner == null || drone == null || text == null || text.getString().isBlank()) return;
        CorvusNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner),
                new CommsMessage(drone, speaker, persona, text));
    }

    public static void encode(CommsMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.drone);
        buffer.writeComponent(message.speaker);
        buffer.writeUtf(message.persona == null ? "" : message.persona, 64);
        buffer.writeComponent(message.text);
    }

    public static CommsMessage decode(FriendlyByteBuf buffer) {
        return new CommsMessage(buffer.readUUID(), buffer.readComponent(), buffer.readUtf(64), buffer.readComponent());
    }
}
