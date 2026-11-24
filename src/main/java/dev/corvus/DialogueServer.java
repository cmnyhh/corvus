package dev.corvus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server half of the dialogue system: loads the chip/intent/line library from the datapack, answers
 * {@code /dronepal}, edge-triggers the barks, and speaks scan results once per completed scan.
 *
 * <p>The chip owns every spoken line. No chip, a silent chip, the {@code barks} switch off, or a pack
 * whose profile has {@code unlocks_chat=false} all mean total silence — the same gate the terminal's
 * "播报" button exposes through {@link DroneRemote#barksEnabled(ItemStack)}.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class DialogueServer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-comms");
    private static volatile LocalDialogue library = new LocalDialogue(Map.of());
    private static final Map<ServerPlayer, Memory> conversations = new WeakHashMap<>();
    private static final Map<UUID, Revival> revivals = new HashMap<>();

    private static final class Memory {
        String previous = "";
        Set<String> online = Set.of();
        final ScanSpeech scans = new ScanSpeech();
        final DroneBarks events = new DroneBarks();
    }

    private DialogueServer() {}

    /** Merge shim: the channel is registered by {@link CorvusNetwork}; events are class-scanned. */
    public static void register(net.minecraftforge.eventbus.api.IEventBus bus) {
        CorvusNetwork.register();
    }

    // ------------------------------------------------------------------ data reload

    @SubscribeEvent
    public static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<LocalDialogue>() {
            @Override
            protected LocalDialogue prepare(ResourceManager manager, ProfilerFiller profiler) {
                Map<String, JsonObject> documents = new HashMap<>();
                for (String folder : List.of("chips", "lines", "intents")) {
                    var resources = manager.listResources(folder,
                            id -> id.getNamespace().equals(Corvus.ID) && id.getPath().endsWith(".json"));
                    for (var entry : resources.entrySet()) {
                        try (var reader = entry.getValue().openAsReader()) {
                            documents.put(entry.getKey().getPath(), JsonParser.parseReader(reader).getAsJsonObject());
                        } catch (IOException failure) {
                            throw new UncheckedIOException("Reading " + entry.getKey(), failure);
                        }
                    }
                }
                return new LocalDialogue(documents);
            }

            @Override
            protected void apply(LocalDialogue next, ResourceManager resources, ProfilerFiller profiler) {
                library = next;
                conversations.clear();
            }
        });
    }

    // ------------------------------------------------------------------ /dronepal

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        DialogueCommands.register(event.getDispatcher(), (source, text) -> {
            ServerPlayer player = source.getPlayerOrException();
            Memory memory = conversations.computeIfAbsent(player, unused -> new Memory());
            Device device = new Device(player, Hardware.controller(player));
            LocalDialogue.Reply reply = library.respond(device, text, memory.previous, ThreadLocalRandom.current());
            if (reply.text().isEmpty()) return 1;
            memory.previous = reply.lineId();
            LocalDialogue.Chip profile = device.active() ? library.profile(device.chip()) : null;
            if (profile == null || !profile.chat()) {
                source.sendSuccess(() -> Component.literal(reply.text()), false);
            } else {
                sendSubtitle(device, profile, Component.literal(reply.text()));
                playVoice(device, reply);
            }
            return 1;
        });
    }

    // ------------------------------------------------------------------ barks

    /** Edge-triggered warning, with each chip's probability and cooldown. */
    @SubscribeEvent
    public static void vitals(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;
        Memory memory = conversations.computeIfAbsent(player, unused -> new Memory());
        Device device = new Device(player, Hardware.controller(player));
        // Barks belong to the chip alone: no chip, a silent chip, or the bark switch off means silence.
        if (!device.active() || !DroneRemote.barksEnabled(device.stack())) return;
        LocalDialogue.Chip profile = library.profile(device.chip());
        if (profile == null || !profile.chat()) return;
        var level = player.serverLevel();
        int capacity = Hardware.capacity(device.stack());
        DroneBarks.State state = new DroneBarks.State(
                level.dimension().location().toString(),
                player.isAlive() && player.getHealth() <= player.getMaxHealth() * 0.3F,
                capacity > 0 && (long) Hardware.energy(device.stack()) * 5 < capacity,
                level.isNight(), level.isRaining());
        String trigger = memory.events.advance(state, level.getGameTime(),
                ThreadLocalRandom.current().nextDouble(), profile.barkRate(), profile.cooldown());
        Set<String> online = new HashSet<>();
        boolean wentOffline = false;
        for (Hardware.Module module : Hardware.installed(device.stack())) {
            if (Hardware.online(device.stack(), module.family())) online.add(module.family());
            else if (memory.online.contains(module.family())) wentOffline = true;
        }
        memory.online = online;
        if (trigger != null) announce(device, memory, profile, trigger);
        else if (wentOffline) signal(player, "module_offline");
    }

    public static void signal(ServerPlayer player, String trigger) {
        signal(new Device(player, Hardware.controller(player)), trigger);
    }

    private static void signal(Device device, String trigger) {
        if (!device.active() || !DroneRemote.barksEnabled(device.stack())) return;
        LocalDialogue.Chip profile = library.profile(device.chip());
        if (profile == null || !profile.chat()) return;
        Memory memory = conversations.computeIfAbsent(device.player(), unused -> new Memory());
        if (memory.events.allow(device.player().serverLevel().getGameTime(),
                ThreadLocalRandom.current().nextDouble(), profile.barkRate(), profile.cooldown())) {
            announce(device, memory, profile, trigger);
        }
    }

    private static void announce(Device device, Memory memory, LocalDialogue.Chip profile, String trigger) {
        LocalDialogue.Reply reply = library.speak(profile, trigger, device.slots(), memory.previous,
                ThreadLocalRandom.current());
        sendSubtitle(device, profile, Component.literal(reply.text()));
        memory.previous = reply.lineId();
        playVoice(device, reply);
    }

    // ------------------------------------------------------------------ scan completion

    public static void scanComplete(ServerPlayer player, ScanSnapshot completed, boolean automatic) {
        Device device = new Device(player, Hardware.controller(player));
        if (!device.active() || completed == null
                || !completed.dimension().equals(player.serverLevel().dimension().location())
                || !completed.serial().equals(DroneItem.serial(device.stack()))) return;
        LocalDialogue.Chip profile = library.profile(device.chip());
        if (profile == null || !profile.chat() || !DroneRemote.barksEnabled(device.stack())) {
            // The documented silence path: no chip / silent chip / barks off / chat locked.
            LOGGER.info("[corvus-comms] scan result suppressed (chip={} chat={} barks={})",
                    device.chip(), profile != null && profile.chat(), DroneRemote.barksEnabled(device.stack()));
            return;
        }
        Memory memory = conversations.computeIfAbsent(player, unused -> new Memory());
        Component text = memory.scans.describe(completed, player.position(), profile.pack(), automatic,
                player.serverLevel().getGameTime());
        LOGGER.info("[corvus-comms] scan complete pack={} automatic={} ores={} hostiles={} spoken={}",
                profile.pack(), automatic, completed.ores().size(), completed.hostiles().size(),
                text == null ? "deduped" : text.getString());
        if (text != null) sendSubtitle(device, profile, text);
    }

    private static void sendSubtitle(Device device, LocalDialogue.Chip profile, Component text) {
        UUID serial = DroneItem.serial(device.stack());
        if (serial == null) return;
        DroneIdentity identity = DroneIdentity.of(device.stack());
        String speaker = profile.pack().equals("tactical") ? identity.callsign()
                : identity.nickname().isBlank() ? identity.product() : identity.nickname();
        CommsMessage.send(device.player(), serial, Component.literal(speaker), profile.pack(), text);
    }

    // ------------------------------------------------------------------ lifecycle

    private record Revival(ItemStack controller, Memory conversation) {}

    @SubscribeEvent
    public static void fall(LivingFallEvent event) {
        if (event.getDistance() >= 4 && event.getEntity() instanceof ServerPlayer player) {
            signal(player, "fall");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Device device = new Device(player, Hardware.controller(player));
        if (!device.active()) return;
        signal(device, "death");
        revivals.put(player.getUUID(),
                new Revival(device.stack().copy(), conversations.computeIfAbsent(player, unused -> new Memory())));
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Revival revival = revivals.remove(player.getUUID());
        if (revival == null) return;
        conversations.put(player, revival.conversation());
        signal(new Device(player, revival.controller()), "respawn");
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        revivals.remove(event.getEntity().getUUID());
    }

    private static void playVoice(Device device, LocalDialogue.Reply reply) {
        if (reply.voice().isEmpty()) return;
        UUID serial = DroneItem.serial(device.stack());
        LocalDialogue.Chip chip = library.profile(device.chip());
        ResourceLocation event = new ResourceLocation(Corvus.ID, "voice/" + reply.voice());
        if (serial != null && chip != null) DroneVoice.send(device.player(), serial, event, chip.speed());
    }

    /** Test hook: the loaded chip ids, for the smoke harness. */
    static Set<String> chips() {
        return library.chips();
    }

    /**
     * Test hook: which intent the loaded library resolves this text to ({@code null} = unknown).
     *
     * <p>Acceptance runs assert on this because the interesting failure is silent: a text that matches no
     * intent still answers with a line and still returns 1, so only the resolved id proves that
     * {@code /dronepal 治疗} reaches the module runtime at all.</p>
     */
    static String intentOf(String text) {
        LocalDialogue.Intent intent = library.match(text);
        return intent == null ? null : intent.id();
    }

    record Device(ServerPlayer player, ItemStack stack) implements LocalDialogue.Device {
        public boolean active() {
            return !stack.isEmpty();
        }

        public String chip() {
            return Hardware.chip(stack);
        }

        public boolean installed(String family) {
            return Hardware.tier(stack, family) > 0;
        }

        public boolean execute(String id) {
            return Hardware.execute(player, id);
        }

        public String lookup(String intent, String question) {
            return DialogueLookup.answer(player, stack, intent, question);
        }

        public Map<String, String> slots() {
            StringJoiner modules = new StringJoiner("、");
            for (String family : List.of("scan", "medic", "combat", "light", "carry", "solar", "link")) {
                int tier = Hardware.tier(stack, family);
                if (tier > 0) modules.add(family + " Mk" + tier);
            }
            var world = player.serverLevel();
            int minutes = (int) ((Math.floorMod(world.getDayTime(), 24000L) * 60 / 1000 + 360) % 1440);
            DroneIdentity identity = DroneIdentity.of(stack);
            Map<String, String> slots = new HashMap<>();
            slots.put("product", identity.product());
            slots.put("callsign", identity.callsign());
            slots.put("nickname", identity.nickname().isBlank() ? identity.product() : identity.nickname());
            slots.put("designation", identity.nickname().isBlank() ? "" : "，代称" + identity.nickname());
            slots.put("energy", Integer.toString(Hardware.energy(stack)));
            slots.put("modules", modules.length() == 0 ? "无" : modules.toString());
            slots.put("hp", Integer.toString(Math.round(100 * player.getHealth() / player.getMaxHealth())));
            slots.put("time", String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60));
            slots.put("weather", world.isThundering() ? "雷雨" : world.isRaining() ? "雨天" : "晴天");
            return Map.copyOf(slots);
        }
    }
}
