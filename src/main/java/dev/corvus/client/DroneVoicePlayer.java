package dev.corvus.client;

import dev.corvus.Corvus;
import dev.corvus.DroneVoice;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Plays the chip's voice clips one at a time per drone, so barks never talk over a scan report.
 *
 * <p>Only the playback schedule is client-side; the choice of clip is the server's dialogue library.
 * An unregistered sound id is dropped here rather than at the server, which keeps the server from
 * having to know which sound events a client resource pack happens to ship.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT)
public final class DroneVoicePlayer {
    private static final Map<UUID, Channel> channels = new HashMap<>();

    private static final class Channel {
        final Queue<DroneVoice> queue = new ArrayDeque<>();
        SoundInstance current;
    }

    private DroneVoicePlayer() {}

    public static void accept(DroneVoice voice) {
        if (Minecraft.getInstance().getSoundManager().getSoundEvent(voice.event()) == null) return;
        Channel channel = channels.computeIfAbsent(voice.drone(), unused -> new Channel());
        if (channel.queue.size() < 8) channel.queue.add(voice);
    }

    @SubscribeEvent
    public static void play(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.isPaused() || channels.isEmpty()) return;
        var sounds = client.getSoundManager();
        channels.values().removeIf(channel -> {
            if (channel.current != null && sounds.isActive(channel.current)) return false;
            DroneVoice next = channel.queue.poll();
            if (next == null) return true;
            channel.current = new SimpleSoundInstance(next.event(), SoundSource.PLAYERS, 1F, next.speed(),
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                    0, 0, 0, true);
            sounds.play(channel.current);
            return false;
        });
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        channels.clear();
    }
}
