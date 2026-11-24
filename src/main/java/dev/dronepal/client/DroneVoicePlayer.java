package dev.dronepal.client;

import dev.dronepal.DroneVoice;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "dronepal", value = Dist.CLIENT)
public final class DroneVoicePlayer {
    private static final Map<UUID, Channel> channels = new HashMap<>();
    private static final class Channel {
        final Queue<DroneVoice> queue = new ArrayDeque<>();
        SoundInstance current;
    }
    public static void accept(DroneVoice voice) {
        if (Minecraft.getInstance().getSoundManager().getSoundEvent(voice.event()) == null) return;
        var channel = channels.computeIfAbsent(voice.drone(), unused -> new Channel());
        if (channel.queue.size() < 8) channel.queue.add(voice);
    }
    @SubscribeEvent public static void play(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.isPaused() || channels.isEmpty()) return;
        var sounds = mc.getSoundManager();
        channels.values().removeIf(channel -> {
            if (channel.current != null && sounds.isActive(channel.current)) return false;
            var next = channel.queue.poll();
            if (next == null) return true;
            channel.current = new SimpleSoundInstance(next.event(), SoundSource.PLAYERS, 1F, next.speed(),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
            sounds.play(channel.current);
            return false;
        });
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { channels.clear(); }
}
