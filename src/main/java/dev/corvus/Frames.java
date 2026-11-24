package dev.corvus;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The four frames (机型) and nine paints (涂装) that a controller can carry.
 *
 * <p>The frame definitions are server data: {@code data/huhu_dronepal/frames/&lt;id&gt;.json} is loaded
 * through a datapack reload listener, so a pack can retune slots, ammo, durability, the gun whitelist,
 * the collision box and the flight quirks without a code change. The same files are also read straight
 * out of the jar as a built-in fallback, because the creative tab and item tooltips are built before a
 * dedicated server's datapack exists on the client.</p>
 *
 * <p>Frame and paint live in NBT on the controller stack; the entity copies them from the stack at
 * deploy time. Older saves spelled the frame differently, so {@link #canonical(String)} keeps the
 * aliases (standard→peregrine, scout→owl, heavy→bedrock) and an unknown value falls back to
 * peregrine rather than producing a frame-less controller.</p>
 */
public final class Frames {
    public static final List<String> IDS = List.of("peregrine", "owl", "bedrock", "scrap");
    public static final List<String> PAINTS = List.of(
            "standard", "arctic", "sand", "jungle", "crimson", "neon", "void", "chrome", "rust");

    private static final Logger LOGGER = LoggerFactory.getLogger("corvus/frames");

    /** Frame ids as they appear in older saves (the 1.21 build keeps the same aliases). */
    private static final Map<String, String> ALIASES = Map.of(
            "standard", "peregrine", "scout", "owl", "heavy", "bedrock");

    /** Callsign numbers used when a frame file omits {@code callsign_number}. */
    private static final Map<String, String> LEGACY_NUMBERS = Map.of(
            "peregrine", "07", "owl", "03", "bedrock", "12", "scrap", "00");

    /**
     * One reloadable frame definition.
     *
     * <p>Field order follows the JSON so a datapack author can read this record as documentation of
     * what the file controls.</p>
     */
    public record Frame(String id, String productName, String callsign, int moduleSlots, int ammoSlots,
                        int maxBatteryTier, String defaultPaint, Set<String> allowedGunTypes,
                        float mountOffset, float solarBonus, float teleportThreshold, int teleportCooldown,
                        float accelResponse, float knockbackResistance, float mountCapacity,
                        float width, float height, float malfunctionChance, int malfunctionTicks,
                        float teleportFailChance, float maxHealth, float hitCap) {
        /** True when the frame rolls its own malfunction, so the vendor trait must not add another. */
        public boolean rollsMalfunction() {
            return malfunctionChance > 0F && malfunctionTicks > 0;
        }

        public boolean allowsGun(String gunType) {
            return gunType != null && allowedGunTypes.contains(gunType.toLowerCase(Locale.ROOT));
        }
    }

    /** Jar copies, used before a datapack reload and as the fallback for a missing or invalid file. */
    private static final Map<String, Frame> BUILTIN = Builtins.load();
    private static volatile Map<String, Frame> definitions = BUILTIN;
    /** How many times the datapack listener actually ran; the smoke harness uses it to prove the
     *  jar fallback was replaced by real data rather than staying in place. */
    private static volatile int reloads;

    private Frames() {}

    /** Registers the datapack reload hook. Called once from {@link Corvus}. */
    static void register() {
        MinecraftForge.EVENT_BUS.addListener(Frames::addReloadListener);
    }

    private static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "frames") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                                 ProfilerFiller profiler) {
                Map<String, Frame> next = new HashMap<>(definitions);
                int loaded = 0;
                for (String id : IDS) {
                    JsonElement entry = resources.get(key(id));
                    if (entry == null || !entry.isJsonObject()) {
                        LOGGER.warn("Frame {} is missing from this datapack; keeping the built-in definition", id);
                        next.put(id, BUILTIN.get(id));
                        continue;
                    }
                    try {
                        next.put(id, parse(id, entry.getAsJsonObject()));
                        loaded++;
                    } catch (RuntimeException invalid) {
                        LOGGER.error("Frame {} is invalid; keeping the built-in definition", id, invalid);
                        next.put(id, BUILTIN.get(id));
                    }
                }
                definitions = Map.copyOf(next);
                reloads++;
                LOGGER.info("Loaded {} of {} frame definitions from the datapack", loaded, IDS.size());
            }
        });
    }

    private static ResourceLocation key(String id) {
        return new ResourceLocation(Corvus.ID, id);
    }

    /**
     * Reads one frame JSON. Every hardware limit the terminal enforces is validated here, so a broken
     * datapack fails at reload time with the offending file named instead of mis-slotting parts later.
     */
    static Frame parse(String id, JsonObject o) {
        int slots = o.get("module_slots").getAsInt();
        int tier = o.get("battery_max_tier").getAsInt();
        if (slots < 1 || slots > 4) throw new IllegalArgumentException("module_slots out of range 1..4");
        if (tier < 1 || tier > 3) throw new IllegalArgumentException("battery_max_tier out of range 1..3");
        int ammo = o.has("ammo_slots") ? o.get("ammo_slots").getAsInt() : defaultAmmoSlots(id);
        if (ammo < 1 || ammo > 8) throw new IllegalArgumentException("ammo_slots out of range 1..8");
        float health = o.has("max_health") ? o.get("max_health").getAsFloat() : 80F;
        float hitCap = o.has("max_damage_per_hit") ? o.get("max_damage_per_hit").getAsFloat()
                : Math.min(10F, health / 2F);
        if (!(health >= 2F && health <= 1024F)) throw new IllegalArgumentException("max_health out of range 2..1024");
        if (!(hitCap > 0F && hitCap < health)) throw new IllegalArgumentException("max_damage_per_hit must be > 0 and < max_health");

        Set<String> guns = new HashSet<>();
        if (o.has("allowed_gun_types")) {
            o.getAsJsonArray("allowed_gun_types").forEach(gun -> guns.add(gun.getAsString().toLowerCase(Locale.ROOT)));
        }
        float width = 0.7F;
        float height = 0.7F;
        if (o.has("collision")) {
            var collision = o.getAsJsonArray("collision");
            if (collision.size() >= 2) {
                width = collision.get(0).getAsFloat();
                height = collision.get(1).getAsFloat();
            }
        }
        if (!(width > 0F && width <= 4F) || !(height > 0F && height <= 4F)) {
            throw new IllegalArgumentException("collision box out of range 0..4");
        }
        String callsignPrefix = o.has("callsign_prefix") ? o.get("callsign_prefix").getAsString() : "PR";
        String callsignNumber = o.has("callsign_number") ? o.get("callsign_number").getAsString()
                : LEGACY_NUMBERS.getOrDefault(id, "00");
        String defaultPaint = paintId(o.has("default_paint") ? o.get("default_paint").getAsString() : "standard");

        return new Frame(id,
                o.has("product_name") ? o.get("product_name").getAsString() : id,
                callsignPrefix + "-" + callsignNumber,
                slots, ammo, tier, defaultPaint, Set.copyOf(guns),
                o.has("gun_mount_offset") ? o.get("gun_mount_offset").getAsFloat() : 0.22F,
                o.has("solar_bonus") ? o.get("solar_bonus").getAsFloat() : 1F,
                o.has("follow_teleport_threshold") ? o.get("follow_teleport_threshold").getAsFloat() : 16F,
                o.has("teleport_cooldown_ticks") ? o.get("teleport_cooldown_ticks").getAsInt() : 40,
                o.has("accel_response") ? o.get("accel_response").getAsFloat() : 1F,
                o.has("knockback_resistance") ? o.get("knockback_resistance").getAsFloat() : 0.3F,
                o.has("mount_capacity") ? o.get("mount_capacity").getAsFloat() : 1F,
                width, height,
                o.has("malfunction_chance") ? o.get("malfunction_chance").getAsFloat() : 0F,
                o.has("malfunction_ticks") ? o.get("malfunction_ticks").getAsInt() : 0,
                o.has("teleport_fail_chance") ? o.get("teleport_fail_chance").getAsFloat() : 0F,
                health, hitCap);
    }

    /**
     * The nine paints are known constants; a datapack can point at one by id or by full resource
     * location. Anything else falls back to the factory paint. Deliberately does not consult
     * {@link #definitions}: this runs while the built-in table itself is being initialised.
     */
    private static String paintId(String raw) {
        String name = raw == null ? "standard" : raw.toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        return PAINTS.contains(name) ? name : "standard";
    }

    public static String canonical(String raw) {
        if (raw == null) return "peregrine";
        String name = raw.toLowerCase(Locale.ROOT).trim();
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        String alias = ALIASES.get(name);
        if (alias != null) return alias;
        return IDS.contains(name) ? name : "peregrine";
    }

    public static String id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "peregrine";
        CorvusData data = CorvusData.of(stack);
        String stored = data.getString(CorvusKeys.FRAME);
        if (!stored.isEmpty()) return canonical(stored);
        // Older saves and hand-written /give commands used a shorter key.
        for (String legacy : CorvusKeys.LEGACY_FRAME_KEYS) {
            String value = data.getString(legacy);
            if (!value.isEmpty()) return canonical(value);
        }
        return "peregrine";
    }

    public static void setFrame(ItemStack stack, String frameId) {
        CorvusData.of(stack).setString(CorvusKeys.FRAME, canonical(frameId));
    }

    public static String paint(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "standard";
        String requested = CorvusData.of(stack).getString(CorvusKeys.PAINT).toLowerCase(Locale.ROOT);
        return PAINTS.contains(requested) ? requested : defaultPaint(id(stack));
    }

    public static void setPaint(ItemStack stack, String paint) {
        String id = paint == null ? "" : paint.toLowerCase(Locale.ROOT);
        CorvusData.of(stack).setString(CorvusKeys.PAINT,
                PAINTS.contains(id) ? id : defaultPaint(id(stack)));
    }

    public static String defaultPaint(String frameId) {
        Frame frame = byId(frameId);
        return frame == null ? "standard" : frame.defaultPaint();
    }

    /** The live definition, or null only if the built-in table itself is broken. */
    public static Frame of(ItemStack stack) {
        return byId(id(stack));
    }

    public static Frame byId(String frameId) {
        return definitions.get(canonical(frameId));
    }

    public static int moduleSlots(String frameId) {
        Frame frame = byId(frameId);
        return frame == null ? 3 : frame.moduleSlots();
    }

    public static int ammoSlots(String frameId) {
        Frame frame = byId(frameId);
        return frame == null ? defaultAmmoSlots(frameId) : frame.ammoSlots();
    }

    private static int defaultAmmoSlots(String id) {
        return switch (canonical(id)) {
            case "owl" -> 4;
            case "bedrock" -> 8;
            default -> 6;
        };
    }

    public static Component name(String frameId) {
        return Component.translatable("dronepal.frame." + canonical(frameId));
    }

    /** The stack the creative tab and {@code /give} hand out: frame + paint + serial already set. */
    public static ItemStack create(String frameId) {
        String id = canonical(frameId);
        ItemStack stack = new ItemStack(Corvus.DRONE.get());
        CorvusData.of(stack)
                .setString(CorvusKeys.FRAME, id)
                .setString(CorvusKeys.PAINT, defaultPaint(id));
        DroneItem.ensureSerial(stack);
        return stack;
    }

    /**
     * Built-in copies of the four frame files, read from the jar.
     *
     * <p>Item tooltips and the creative tab exist before a dedicated server hands over a datapack, so
     * there must be a usable table at class-init time. A client that later joins a server with edited
     * frames gets the reloaded table from {@link #addReloadListener}.</p>
     */
    private static final class Builtins {
        private static final List<String> FILES = IDS;

        private static Map<String, Frame> load() {
            Map<String, Frame> result = new LinkedHashMap<>();
            for (String id : FILES) {
                String path = "/data/" + Corvus.ID + "/frames/" + id + ".json";
                try (var stream = Frames.class.getResourceAsStream(path)) {
                    if (stream == null) throw new IllegalStateException("Missing built-in frame " + path);
                    JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    result.put(id, parse(id, json));
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            }
            return Map.copyOf(result);
        }
    }

    /** Datapack reload counter; 0 means only the jar fallback has been read so far. */
    public static int reloadCount() {
        return reloads;
    }

    /** Diagnostics for the smoke harness: which frames are live and where they came from. */
    public static List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (String id : IDS) {
            Frame frame = definitions.get(id);
            if (frame == null) {
                lines.add(id + "=absent");
                continue;
            }
            lines.add(id + " slots=" + frame.moduleSlots() + " ammo=" + frame.ammoSlots()
                    + " tier=" + frame.maxBatteryTier() + " hp=" + frame.maxHealth()
                    + " paint=" + frame.defaultPaint() + " guns=" + frame.allowedGunTypes()
                    + " box=" + frame.width() + "x" + frame.height());
        }
        return lines;
    }
}
