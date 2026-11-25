package dev.dronepal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = Dronepal.ID)
public final class DialogueServer {
    private static volatile LocalDialogue library = new LocalDialogue(Map.of());
    private static final Map<ServerPlayer,Memory> conversations = new WeakHashMap<>();
    private static final class Memory {
        String previous = "";
        Set<String> online = Set.of();
        final ScanSpeech scans = new ScanSpeech();
        final DroneBarks events = new DroneBarks();
    }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<LocalDialogue>() {
            @Override protected LocalDialogue prepare(ResourceManager manager, ProfilerFiller profiler) {
                Map<String,JsonObject> docs = new HashMap<>();
                for (String folder : List.of("chips", "lines", "intents")) {
                    var resources = manager.listResources(folder, id -> id.getNamespace().equals(Dronepal.ID) && id.getPath().endsWith(".json"));
                    for (var entry : resources.entrySet()) {
                        try (var reader = entry.getValue().openAsReader()) {
                            docs.put(entry.getKey().getPath(), JsonParser.parseReader(reader).getAsJsonObject());
                        } catch (IOException ex) { throw new UncheckedIOException("Reading " + entry.getKey(), ex); }
                    }
                }
                return new LocalDialogue(docs);
            }
            @Override protected void apply(LocalDialogue next, ResourceManager resources, ProfilerFiller profiler) {
                library = next;
                conversations.clear();
            }
        });
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        DialogueCommands.register(event.getDispatcher(), (source, text) -> {
            var player = source.getPlayerOrException();
            var memory = conversations.computeIfAbsent(player, unused -> new Memory());
            var device = new Device(player, Hardware.controller(player));
            var reply = library.respond(device, text, memory.previous, ThreadLocalRandom.current());
            if (reply.text().isEmpty()) return 1;
            memory.previous = reply.lineId();
            var profile = device.active() ? library.profile(device.chip()) : null;
            if (profile == null || !profile.chat()) source.sendSuccess(() -> Component.literal(reply.text()), false);
            else {
                sendSubtitle(device, profile, Component.literal(reply.text()));
                playVoice(device, reply);
            }
            return 1;
        });
    }
    /** Edge-triggered warning, with each chip's probability and cooldown. */
    @SubscribeEvent public static void vitals(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;
        var memory = conversations.computeIfAbsent(player, unused -> new Memory());
        var device = new Device(player, Hardware.controller(player));
        // Barks belong to the chip alone: no chip, a silent chip, or the bark switch off means silence.
        if (!device.active() || !DroneRemote.barksEnabled(device.stack())) return;
        var profile = library.profile(device.chip());
        var rng = ThreadLocalRandom.current();
        if (profile == null || !profile.chat()) return;
        var level = player.serverLevel();
        int capacity = Hardware.capacity(device.stack());
        var state = new DroneBarks.State(level.dimension().location().toString(),
            player.isAlive() && player.getHealth() <= player.getMaxHealth() * 0.3F,
            capacity > 0 && (long)Hardware.energy(device.stack()) * 5 < capacity,
            level.isNight(), level.isRaining());
        String trigger = memory.events.advance(state, level.getGameTime(), rng.nextDouble(), profile.barkRate(), profile.cooldown());
        var online = new HashSet<String>();
        boolean wentOffline = false;
        for (var module : Hardware.installed(device.stack())) {
            if (Hardware.online(device.stack(), module.family())) online.add(module.family());
            else if (memory.online.contains(module.family())) wentOffline = true;
        }
        memory.online = online;
        if (trigger != null) announce(device, memory, profile, trigger);
        else if (wentOffline) signal(player, "module_offline");
    }
    /**
     * Deterministic event line (no bark dice): used by explicit orders such as the mill work order, so a
     * fitted chip always speaks. Still silent without a chip, with a silent chip or with barks switched off.
     */
    public static void event(ServerPlayer player, String trigger) {
        var device = new Device(player, Hardware.controller(player));
        if (!device.active() || !DroneRemote.barksEnabled(device.stack())) return;
        var profile = library.profile(device.chip());
        if (profile == null || !profile.chat()) return;
        var memory = conversations.computeIfAbsent(player, unused -> new Memory());
        announce(device, memory, profile, trigger);
    }

    public static void signal(ServerPlayer player, String trigger) {
        signal(new Device(player, Hardware.controller(player)), trigger);
    }
    private static void signal(Device device, String trigger) {
        // Barks belong to the chip alone: no chip, a silent chip, or the bark switch off means silence.
        if (!device.active() || !DroneRemote.barksEnabled(device.stack())) return;
        var profile = library.profile(device.chip());
        if (profile == null || !profile.chat()) return;
        var memory = conversations.computeIfAbsent(device.player(), unused -> new Memory());
        if (memory.events.allow(device.player().serverLevel().getGameTime(), ThreadLocalRandom.current().nextDouble(), profile.barkRate(), profile.cooldown()))
            announce(device, memory, profile, trigger);
    }
    private static void announce(Device device, Memory memory, LocalDialogue.Chip profile, String trigger) {
        var reply = library.speak(profile, trigger, device.slots(), memory.previous, ThreadLocalRandom.current());
        sendSubtitle(device, profile, Component.literal(reply.text()));
        memory.previous = reply.lineId();
        playVoice(device, reply);
    }
    public static void scanComplete(ServerPlayer player, ScanSnapshot completed, boolean automatic) {
        var device = new Device(player, Hardware.controller(player));
        if (!device.active() || !completed.dimension().equals(player.serverLevel().dimension().location())
                || !completed.serial().equals(DroneItem.serial(device.stack()))) return;
        var profile = library.profile(device.chip());
        if (profile == null || !profile.chat() || !DroneRemote.barksEnabled(device.stack())) return;
        var memory = conversations.computeIfAbsent(player, unused -> new Memory());
        var text = memory.scans.describe(completed, player.position(), profile.pack(), automatic, player.serverLevel().getGameTime());
        com.mojang.logging.LogUtils.getLogger().info("SCANDBG pack={} auto={} text={}", profile.pack(), automatic, text == null ? "null" : "ok");
        if (text != null) sendSubtitle(device, profile, text);
    }
    private static void sendSubtitle(Device device, LocalDialogue.Chip profile, Component text) {
        var serial = DroneItem.serial(device.stack());
        if (serial == null) return;
        var id = DroneIdentity.of(device.stack());
        String speaker = profile.pack().equals("tactical") ? id.callsign() : id.nickname().isBlank() ? id.product() : id.nickname();
        CommsMessage.send(device.player(), serial, Component.literal(speaker), profile.pack(), text);
    }
    private record Revival(ItemStack controller, Memory conversation) {}
    private static final Map<UUID,Revival> revivals = new HashMap<>();
    @SubscribeEvent public static void fall(net.neoforged.neoforge.event.entity.living.LivingFallEvent event) {
        if (event.getDistance() >= 4 && event.getEntity() instanceof ServerPlayer player) signal(player, "fall");
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var device = new Device(player, Hardware.controller(player));
        if (!device.active()) return;
        signal(device, "death");
        revivals.put(player.getUUID(), new Revival(device.stack().copy(), conversations.computeIfAbsent(player, unused -> new Memory())));
    }
    @SubscribeEvent public static void respawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var revival = revivals.remove(player.getUUID());
        if (revival == null) return;
        conversations.put(player, revival.conversation());
        signal(new Device(player, revival.controller()), "respawn");
    }
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        revivals.remove(event.getEntity().getUUID());
    }
    private static void playVoice(Device device, LocalDialogue.Reply reply) {
        if (reply.voice().isEmpty()) return;
        var serial = DroneItem.serial(device.stack());
        var event = net.minecraft.resources.ResourceLocation.tryBuild(Dronepal.ID, "voice/" + reply.voice());
        var chip = library.profile(device.chip());
        if (serial != null && event != null && chip != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(device.player(), new DroneVoice(serial, event, chip.speed()));
        }
    }
    private record Device(ServerPlayer player, ItemStack stack) implements LocalDialogue.Device {
        public boolean active() { return !stack.isEmpty(); }
        public String chip() { return Hardware.chip(stack); }
        public boolean installed(String family) { return Hardware.tier(stack, family) > 0; }
        public boolean execute(String id) { return Hardware.execute(player, id); }
        public String lookup(String intent, String question) { return DialogueLookup.answer(player, stack, intent, question); }
        public Map<String,String> slots() {
            var modules = new StringJoiner("、");
            for (String family : List.of("scan", "medic", "combat", "light", "carry", "solar", "link")) {
                int tier = Hardware.tier(stack, family);
                if (tier > 0) modules.add(family + " Mk" + tier);
            }
            var world = player.serverLevel();
            int minutes = (int)((Math.floorMod(world.getDayTime(), 24000L) * 60 / 1000 + 360) % 1440);
            var identity = DroneIdentity.of(stack);
            return Map.of("product", identity.product(), "callsign", identity.callsign(),
                "nickname", identity.nickname().isBlank() ? identity.product() : identity.nickname(),
                "designation", identity.nickname().isBlank() ? "" : "，代称" + identity.nickname(), "energy", Integer.toString(Hardware.energy(stack)),
                "modules", modules.length() == 0 ? "无" : modules.toString(),
                "hp", Integer.toString(Math.round(100 * player.getHealth() / player.getMaxHealth())),
                "time", String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60),
                "weather", world.isThundering() ? "雷雨" : world.isRaining() ? "雨天" : "晴天");
        }
    }
}
