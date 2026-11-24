package dev.corvus;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/** A queued voice clip for one drone, played only after any earlier clip of that drone finishes. */
public record DroneVoice(UUID drone, ResourceLocation event, float speed) {
    public static void send(ServerPlayer owner, UUID drone, ResourceLocation event, float speed) {
        if (owner == null || drone == null || event == null) return;
        CorvusNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), new DroneVoice(drone, event, speed));
    }

    public static void encode(DroneVoice voice, FriendlyByteBuf buffer) {
        buffer.writeUUID(voice.drone);
        buffer.writeResourceLocation(voice.event);
        buffer.writeFloat(voice.speed);
    }

    public static DroneVoice decode(FriendlyByteBuf buffer) {
        return new DroneVoice(buffer.readUUID(), buffer.readResourceLocation(), buffer.readFloat());
    }
}
