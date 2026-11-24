package dev.dronepal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The frame is a property of the physical controller. The four server definitions are reloadable. */
public final class Frames {
    public static final List<String> IDS = List.of("peregrine", "owl", "bedrock", "scrap");
    private static final DeferredRegister.DataComponents TYPES = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Dronepal.ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> TYPE = TYPES.registerComponentType("frame",
        builder -> builder.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));
    static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> NAME = TYPES.registerComponentType("name",
        builder -> builder.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));
    private static final Map<String,String> LEGACY_NUMBERS = Map.of("peregrine", "07", "owl", "03", "bedrock", "12", "scrap", "00");
    private static final List<String> LEGACY_KEYS = List.of("dronepal_frame", "dronepal:frame", "frame");
    private static volatile Map<String, Frame> definitions = Map.of();

    public record Frame(String id, String productName, String callsign, int moduleSlots, int ammoSlots, int maxBatteryTier, String defaultPaint, Set<String> allowedGunTypes,
                        float mountOffset, float solarBonus, float teleportThreshold, int teleportCooldown,
                        float accelResponse, float knockbackResistance, float mountCapacity, float width, float height,
                        float malfunctionChance, int malfunctionTicks, float teleportFailChance, float maxHealth, float hitCap) {}

    static void register(IEventBus bus) {
        TYPES.register(bus);
        NeoForge.EVENT_BUS.addListener(Frames::reload);
    }
    private static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "frames") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
                Map<String, Frame> next = new HashMap<>();
                for (String id : IDS) {
                    ResourceLocation key = key(id);
                    JsonElement entry = resources.get(key);
                    if (entry == null) throw new IllegalArgumentException("Missing frame " + key);
                    JsonObject o = entry.getAsJsonObject();
                    int slots = o.get("module_slots").getAsInt(), tier = o.get("battery_max_tier").getAsInt();
                    if (slots < 1 || slots > 4 || tier < 1 || tier > 3) throw new IllegalArgumentException("Invalid hardware limits in " + key);
                    int ammo = o.has("ammo_slots") ? o.get("ammo_slots").getAsInt() : defaultAmmoSlots(id);
                    if (ammo < 1 || ammo > 8) throw new IllegalArgumentException("Invalid ammo_slots in " + key);
                    float hp = o.has("max_health") ? o.get("max_health").getAsFloat() : 80F;
                    float cap = o.has("max_damage_per_hit") ? o.get("max_damage_per_hit").getAsFloat() : Math.min(10F, hp / 2);
                    if (!(hp >= 2 && hp <= 1024 && cap > 0 && cap < hp)) throw new IllegalArgumentException("Invalid durability in " + key);
                    Set<String> guns = new HashSet<>();
                    o.getAsJsonArray("allowed_gun_types").forEach(gun -> guns.add(gun.getAsString()));
                    var collision = o.getAsJsonArray("collision");
                    next.put(id, new Frame(id, o.get("product_name").getAsString(), o.get("callsign_prefix").getAsString() + "-" + (o.has("callsign_number") ? o.get("callsign_number").getAsString() : LEGACY_NUMBERS.get(id)), slots, ammo, tier, ResourceLocation.parse(o.get("default_paint").getAsString()).getPath(), Set.copyOf(guns),
                        o.get("gun_mount_offset").getAsFloat(), o.get("solar_bonus").getAsFloat(), o.get("follow_teleport_threshold").getAsFloat(),
                        o.get("teleport_cooldown_ticks").getAsInt(), o.get("accel_response").getAsFloat(), o.get("knockback_resistance").getAsFloat(),
                        o.get("mount_capacity").getAsFloat(), collision.get(0).getAsFloat(), collision.get(1).getAsFloat(),
                        o.has("malfunction_chance") ? o.get("malfunction_chance").getAsFloat() : 0,
                        o.has("malfunction_ticks") ? o.get("malfunction_ticks").getAsInt() : 0,
                        o.has("teleport_fail_chance") ? o.get("teleport_fail_chance").getAsFloat() : 0, hp, cap));
                }
                definitions = Map.copyOf(next);
            }
        });
    }
    private static ResourceLocation key(String id) { return ResourceLocation.fromNamespaceAndPath(Dronepal.ID, id); }
    private static String canonical(String raw) {
        int colon = raw.indexOf(':');
        String name = colon >= 0 ? raw.substring(colon + 1) : raw;
        return switch (name) {
            case "standard" -> "peregrine";
            case "scout" -> "owl";
            case "heavy" -> "bedrock";
            default -> IDS.contains(name) ? name : "peregrine";
        };
    }
    public static String id(ItemStack stack) {
        ResourceLocation value = stack.get(TYPE.get());
        if (value != null) return canonical(value.toString());
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        for (String key : LEGACY_KEYS) if (data.contains(key, 8)) return canonical(data.getString(key));
        return "peregrine";
    }
    static void migrate(ItemStack stack) {
        ResourceLocation current = key(id(stack));
        if (!current.equals(stack.get(TYPE.get()))) stack.set(TYPE.get(), current);
    }
    public static Frame of(ItemStack stack) { return definitions.get(id(stack)); }
    private static int defaultAmmoSlots(String id) {
        return switch (canonical(id)) {
            case "owl" -> 4;
            case "bedrock" -> 8;
            default -> 6;
        };
    }
    public static int ammoSlots(String frameId) {
        Frame frame = byId(frameId);
        return frame == null ? defaultAmmoSlots(frameId) : frame.ammoSlots();
    }
    public static Frame byId(String id) { return definitions.get(canonical(id)); }
    public static ItemStack create(String id) {
        if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown frame " + id);
        ItemStack stack = Dronepal.DRONE.toStack();
        stack.set(TYPE.get(), key(id));
        return stack;
    }
    public static Component name(String id) { return Component.translatable("dronepal.frame." + canonical(id)); }
    static String paint(ItemStack controller) {
        String paint = controller.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("dronepal_paint");
        return paint.isEmpty() ? of(controller).defaultPaint() : paint;
    }
    private Frames() {}
}
