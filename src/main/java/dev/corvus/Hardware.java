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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardware: the parts that live inside the physical controller.
 *
 * <p>The controller is one item stack. Its installed parts are a single ordered item list in NBT
 * ({@link CorvusKeys#PARTS}); a battery, up to four function modules, one personality chip and (via the
 * combat layer) a gun with ammo all sit in that one list. Which parts fit is decided by the frame:
 * module slot count, battery tier ceiling and the gun whitelist all come from
 * {@code data/huhu_dronepal/frames/*.json}.</p>
 *
 * <p>Module definitions are server data too: {@code data/huhu_dronepal/modules/*.json} plus the vendor
 * traits in {@code data/huhu_dronepal/vendors/*.json}. The same files are read from the jar at class
 * init so item names and tooltips work on a client that has not received a datapack yet.</p>
 *
 * <p>Energy never lives on the controller: it lives on the battery stack ({@link CorvusKeys#ENERGY}),
 * so pulling the battery out of the terminal keeps its charge, exactly like the 1.21 build.</p>
 */
public final class Hardware {
    private static final Logger LOGGER = LoggerFactory.getLogger("corvus/hardware");
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);

    /** The single module item; the product id in NBT decides which module of the 27 it is. */
    public static final RegistryObject<Item> MODULE = ITEMS.register("module",
            () -> new ModuleItem(new Item.Properties()));

    public static final List<String> FAMILIES =
            List.of("combat", "scan", "carry", "medic", "light", "link", "solar");
    public static final List<String> CHIP_IDS = List.of("silent", "chatty", "tactical");
    public static final Map<String, RegistryObject<Item>> CHIP_ITEMS = new LinkedHashMap<>();
    public static final List<RegistryObject<Item>> BATTERIES = Batteries.register(ITEMS);

    private static volatile Map<ResourceLocation, Module> modules = Map.of();
    private static volatile int moduleReloads;
    private static volatile Set<String> chips = Set.copyOf(CHIP_IDS);
    private static volatile Map<String, JsonObject> vendorTraits = builtinTraits();

    static {
        for (String id : CHIP_IDS) {
            CHIP_ITEMS.put(id, ITEMS.register("chip_" + id, () -> new Item(new Item.Properties())));
        }
    }

    private Hardware() {}

    /**
     * One module definition.
     *
     * @param id         product id, e.g. {@code huhu_dronepal:combat_mk2}
     * @param family     one of {@link #FAMILIES}; only one module per family may be fitted
     * @param tier       1..3
     * @param drain      base FE per tick while powered
     * @param params     the raw {@code params} object, read through {@link #integer}/{@link #decimal}
     * @param intents    wheel orders this module can execute ({@code order.cover}, ...)
     * @param wheelLabel translation key of the wheel label
     * @param vendor     vendor id, used for the trait multipliers and the name prefix
     */
    public record Module(ResourceLocation id, String family, int tier, int drain, JsonObject params,
                         Set<String> intents, String wheelLabel, String vendor, String tierLabel,
                         String productKey, String productName) {
        /** Vendor multiplier lookup; the vendor files carry the whole family of traits. */
        public double trait(String key, double fallback) {
            JsonObject values = vendorTraits.get(vendor);
            return values != null && values.has(key) ? values.get(key).getAsDouble() : fallback;
        }

        public boolean requiresSupply() {
            JsonObject values = vendorTraits.get(vendor);
            return values != null && values.has("consumes_medkit") && values.get("consumes_medkit").getAsBoolean();
        }

        /** FE drawn over {@code ticks}, after the vendor drain multiplier. */
        public int draw(int ticks) {
            return (int) Math.round(drain * ticks * Mth.clamp(trait("drain_multiplier", 1D), 0D, 10D));
        }

        public int integer(String key) {
            return params.get(key).getAsInt();
        }

        public float decimal(String key) {
            return params.get(key).getAsFloat();
        }

        public boolean flag(String key) {
            return params.has(key) && !params.get(key).isJsonNull() && params.get(key).getAsBoolean();
        }
    }

    /**
     * The combat layer (agent C) owns guns and ammo, which need a TaCZ-aware slot rule. It installs
     * itself here so this file never has to link against the combat classes; the default refuses every
     * part that is not a module, battery or chip.
     */
    public interface GunHandler {
        boolean isGun(ItemStack part);

        /** How many of {@code part} one install consumes (a magazine fits as one unit). */
        int installCount(ItemStack part);

        /** "" when the part may be fitted, otherwise one of the error codes the terminal translates. */
        String installError(ItemStack controller, ItemStack part, List<ItemStack> contents);
    }

    /** Order dispatch owns drone behaviour; the combat layer provides the implementation. */
    public interface IntentHandler {
        boolean execute(ServerPlayer player, String intent);
    }

    private static final GunHandler NO_GUNS = new GunHandler() {
        @Override public boolean isGun(ItemStack part) { return false; }
        @Override public int installCount(ItemStack part) { return 1; }
        @Override public String installError(ItemStack controller, ItemStack part, List<ItemStack> contents) {
            return "invalid";
        }
    };

    private static volatile GunHandler gunHandler = NO_GUNS;
    private static volatile IntentHandler intentHandler = (player, intent) -> false;

    public static void setGunHandler(GunHandler handler) {
        gunHandler = handler == null ? NO_GUNS : handler;
    }

    public static void setIntentHandler(IntentHandler handler) {
        intentHandler = handler == null ? (player, intent) -> false : handler;
    }

    /** Item registration plus the three datapack reload listeners and the legacy id remap. */
    static void register(IEventBus bus) {
        ITEMS.register(bus);
        MinecraftForge.EVENT_BUS.addListener(Hardware::addReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(Batteries::addReloadListener);
        MinecraftForge.EVENT_BUS.addListener(Hardware::remapLegacyItems);
    }

    private static void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "vendors") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> values, ResourceManager manager,
                                 ProfilerFiller profiler) {
                Map<String, JsonObject> next = new HashMap<>(builtinTraits());
                values.forEach((id, json) -> {
                    if (!Corvus.ID.equals(id.getNamespace()) || !json.isJsonObject()) return;
                    JsonObject object = json.getAsJsonObject();
                    if (object.has("traits") && object.get("traits").isJsonObject()) {
                        next.put(id.getPath(), object.getAsJsonObject("traits"));
                    }
                });
                vendorTraits = Map.copyOf(next);
            }
        });
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "modules") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> values, ResourceManager manager,
                                 ProfilerFiller profiler) {
                Map<ResourceLocation, Module> next = new HashMap<>();
                values.forEach((id, json) -> {
                    if (!Corvus.ID.equals(id.getNamespace()) || !json.isJsonObject()) return;
                    try {
                        next.put(id, parseModule(id, json.getAsJsonObject()));
                    } catch (RuntimeException invalid) {
                        LOGGER.warn("Skipping invalid drone module {}", id, invalid);
                    }
                });
                modules = Map.copyOf(next);
                moduleReloads++;
                LOGGER.info("Loaded {} drone module definitions", modules.size());
            }
        });
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "chips") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> values, ResourceManager manager,
                                 ProfilerFiller profiler) {
                Set<String> next = new HashSet<>();
                values.forEach((id, json) -> {
                    if (!Corvus.ID.equals(id.getNamespace()) || !json.isJsonObject()) return;
                    if (json.getAsJsonObject().has("prompt_profile")) next.add(id.getPath());
                });
                chips = next.isEmpty() ? Set.copyOf(CHIP_IDS) : Set.copyOf(next);
            }
        });
    }

    /** Battery ids from before the vendor prefixes existed (1.20.1 snapshot saves). */
    private static final Map<String, String> RENAMED_ITEMS = Map.of(
            "battery_mk1", "ahi_bat_mk1",
            "battery_mk2", "ahi_bat_mk2",
            "battery_mk3", "ahi_bat_mk3");

    /**
     * The id an old stack should become, or null when it is not a legacy spelling of one of our items.
     *
     * <p>Two migrations in one place: the pre-vendor battery ids, and the {@code dronepal:} namespace the
     * first 1.20.1 snapshot used for everything.</p>
     */
    public static ResourceLocation legacyTarget(ResourceLocation old) {
        if (old == null) return null;
        if (Corvus.ID.equals(old.getNamespace())) {
            String target = RENAMED_ITEMS.get(old.getPath());
            return target == null ? null : new ResourceLocation(Corvus.ID, target);
        }
        if ("dronepal".equals(old.getNamespace())) {
            return new ResourceLocation(Corvus.ID, old.getPath());
        }
        return null;
    }

    /**
     * Old saves predate the vendor-prefixed battery ids, and the first 1.20.1 snapshots registered
     * everything under a {@code dronepal:} namespace. Remap instead of dropping the stacks, so a world
     * carried over keeps its batteries.
     */
    private static void remapLegacyItems(MissingMappingsEvent event) {
        List<MissingMappingsEvent.Mapping<Item>> mappings =
                event.getAllMappings(ForgeRegistries.Keys.ITEMS);
        for (MissingMappingsEvent.Mapping<Item> mapping : mappings) {
            ResourceLocation target = legacyTarget(mapping.getKey());
            if (target == null) continue;
            Item replacement = ForgeRegistries.ITEMS.getValue(target);
            if (replacement != null) {
                mapping.remap(replacement);
                LOGGER.info("Remapped legacy item {} to {}", mapping.getKey(), target);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Module definitions
    // ---------------------------------------------------------------------------------------------

    static Module parseModule(ResourceLocation id, JsonObject o) {
        String family = o.get("family").getAsString();
        if (!FAMILIES.contains(family)) throw new IllegalArgumentException("Unknown family " + family);
        int tier = o.get("tier").getAsInt();
        if (tier < 1 || tier > 3) throw new IllegalArgumentException("tier out of range 1..3");
        ResourceLocation original = new ResourceLocation(Corvus.ID, family + "_mk" + tier);
        JsonObject wheel = o.has("wheel") && o.get("wheel").isJsonObject() ? o.getAsJsonObject("wheel") : null;
        String label = wheel != null && wheel.has("label")
                ? wheel.get("label").getAsString()
                : "dronepal.wheel." + family;
        Set<String> intents = new HashSet<>();
        if (o.has("intents")) o.getAsJsonArray("intents").forEach(value -> intents.add(value.getAsString()));
        String productKey = "dronepal.product." + (id.equals(original) ? family : id.getPath());
        Module module = new Module(id, family, tier, Mth.clamp(o.get("drain_watts").getAsInt(), 0, 10000),
                o.getAsJsonObject("params"), Set.copyOf(intents), label,
                o.has("vendor") ? o.get("vendor").getAsString() : "cvs",
                o.has("tier_label") ? o.get("tier_label").getAsString() : ("Mk." + tier),
                productKey,
                o.has("product_name") ? o.get("product_name").getAsString() : id.getPath());
        // Fail the reload on a module whose params the gameplay code would dereference later.
        switch (family) {
            case "combat" -> {
                module.integer("range");
                module.integer("burst");
                module.integer("cooldown_ticks");
                module.decimal("spread_deg");
                module.decimal("damage_mult");
            }
            case "scan" -> {
                module.integer("radius");
                module.integer("mark_ticks");
            }
            case "carry" -> {
                module.integer("slots");
                module.integer("pickup_radius");
            }
            case "medic" -> {
                module.integer("cooldown_ticks");
                module.integer("active_drain_watts");
                module.decimal("max_heal_per_use");
                module.decimal("heal_per_tick");
            }
            case "solar" -> {
                module.integer("clear");
                module.integer("rain");
                module.integer("night");
            }
            default -> { }
        }
        return module;
    }

    private static Map<String, JsonObject> builtinTraits() {
        Map<String, JsonObject> result = new HashMap<>();
        for (String id : List.of("ahi", "alb", "cvs", "omd", "rust")) {
            String path = "/data/" + Corvus.ID + "/vendors/" + id + ".json";
            try (var stream = Hardware.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing built-in vendor " + path);
                result.put(id, JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("traits"));
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Built-in module table, read from the jar: creative tabs, item names and tooltips are built before
     * a dedicated server's datapack reaches the client, so there has to be a usable table at class init.
     */
    private static final class Builtins {
        static final Map<ResourceLocation, Module> ALL = load();

        private static Map<ResourceLocation, Module> load() {
            Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
            for (String family : FAMILIES) {
                for (int tier = 1; tier <= 3; tier++) loadFile(files, family + "_mk" + tier);
                if (!family.equals("link")) loadFile(files, family + "_rust_g1");
            }
            Map<ResourceLocation, Module> result = new LinkedHashMap<>();
            files.forEach((id, json) -> result.put(id, parseModule(id, json.getAsJsonObject())));
            return Collections.unmodifiableMap(result);
        }

        private static void loadFile(Map<ResourceLocation, JsonElement> files, String name) {
            String path = "/data/" + Corvus.ID + "/modules/" + name + ".json";
            try (var stream = Hardware.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing built-in module " + path);
                files.put(new ResourceLocation(Corvus.ID, name),
                        JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }
    }

    /** Datapack reload counter for the module table. */
    public static int moduleReloads() {
        return moduleReloads;
    }

    /** Live definitions, or the jar table before any datapack has loaded. */
    public static Map<ResourceLocation, Module> definitions() {
        return modules.isEmpty() ? Builtins.ALL : modules;
    }

    /**
     * Adopts the server's module table on the client.
     *
     * <p>A client connected to a dedicated server never runs the {@code modules} reload listener, so its
     * table would stay empty and item names/tooltips would fall back to the jar copies. {@code ModuleSync}
     * ships the server's table over the mod channel and hands it here, so hover text shows the numbers the
     * server actually runs with. Empty or null payloads are ignored on purpose: a malformed packet must not
     * be able to blank the module table and turn every product into "unknown".</p>
     */
    public static void acceptSynced(Map<ResourceLocation, Module> synced) {
        if (synced == null || synced.isEmpty()) return;
        modules = Map.copyOf(synced);
    }

    /** True once a client has received the server's table (the jar fallback is no longer in use). */
    public static boolean synced() {
        return !modules.isEmpty();
    }

    public static Module definition(ItemStack part) {
        return definitions().get(productId(part));
    }

    public static ResourceLocation productId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(MODULE.get())) return unknownProduct();
        CorvusData data = CorvusData.of(stack);
        String product = data.getString(CorvusKeys.MODULE_PRODUCT);
        ResourceLocation id;
        if (!product.isEmpty()) {
            id = ResourceLocation.tryParse(product);
        } else {
            String family = data.getString(CorvusKeys.MODULE_FAMILY);
            int tier = data.getInt(CorvusKeys.MODULE_TIER);
            id = family.isEmpty() || tier < 1 ? null
                    : new ResourceLocation(Corvus.ID, family + "_mk" + tier);
        }
        return id == null ? unknownProduct() : id;
    }

    private static ResourceLocation unknownProduct() {
        return new ResourceLocation(Corvus.ID, "unknown");
    }

    public static ItemStack moduleStack(String moduleId) {
        ResourceLocation id = moduleId == null ? null
                : moduleId.contains(":") ? ResourceLocation.tryParse(moduleId)
                : new ResourceLocation(Corvus.ID, moduleId);
        Module product = id == null ? null : definitions().get(id);
        if (product == null && id != null) product = Builtins.ALL.get(id);
        if (product == null) throw new IllegalArgumentException("Unknown module product " + moduleId);
        return moduleStack(product);
    }

    private static ItemStack moduleStack(Module product) {
        ItemStack stack = new ItemStack(MODULE.get());
        CorvusData.of(stack)
                .setString(CorvusKeys.MODULE_PRODUCT, product.id().toString())
                .setString(CorvusKeys.MODULE_FAMILY, product.family())
                .setInt(CorvusKeys.MODULE_TIER, product.tier());
        return stack;
    }

    // ---------------------------------------------------------------------------------------------
    // Installed hardware
    // ---------------------------------------------------------------------------------------------

    public static List<ItemStack> contents(ItemStack controller) {
        if (controller == null || controller.isEmpty()) return List.of();
        return new ArrayList<>(CorvusData.of(controller).getItemList(CorvusKeys.PARTS));
    }

    public static void save(ItemStack controller, List<ItemStack> parts) {
        CorvusData.of(controller).setItemList(CorvusKeys.PARTS, parts);
    }

    public static List<Module> installed(ItemStack controller) {
        List<Module> result = new ArrayList<>();
        for (ItemStack part : contents(controller)) {
            Module module = definition(part);
            if (module != null) result.add(module);
        }
        return result;
    }

    public static int tier(ItemStack controller, String family) {
        for (Module module : installed(controller)) {
            if (module.family().equals(family)) return module.tier();
        }
        return 0;
    }

    public static String chipId(ItemStack part) {
        if (part == null || part.isEmpty()) return "";
        for (Map.Entry<String, RegistryObject<Item>> entry : CHIP_ITEMS.entrySet()) {
            if (part.is(entry.getValue().get())) return entry.getKey();
        }
        return "";
    }

    /** A chip must be physically installed for the drone to speak at all. */
    public static boolean hasChip(ItemStack controller) {
        return !fittedChip(controller).isEmpty();
    }

    /** The installed chip, or an empty string. */
    public static String fittedChip(ItemStack controller) {
        for (ItemStack part : contents(controller)) {
            String id = chipId(part);
            if (!id.isEmpty() && chips.contains(id)) return id;
        }
        return "";
    }

    /** Defaults to {@code silent}, matching the 1.21 accessor the dialogue layer calls. */
    public static String chip(ItemStack controller) {
        String fitted = fittedChip(controller);
        return fitted.isEmpty() ? "silent" : fitted;
    }

    // ---------------------------------------------------------------------------------------------
    // Install / remove
    // ---------------------------------------------------------------------------------------------

    public static String installError(ItemStack part, List<ItemStack> parts) {
        return installError(ItemStack.EMPTY, part, parts);
    }

    public static String installError(ItemStack controller, ItemStack part, List<ItemStack> parts) {
        if (part == null || part.isEmpty()) return "invalid";
        Frames.Frame frame = controller == null || controller.isEmpty()
                ? Frames.byId("peregrine")
                : Frames.of(controller);
        if (frame == null) frame = Frames.byId("peregrine");
        if (part.is(MODULE.get())) {
            Module module = definition(part);
            if (module == null) return "invalid";
            for (ItemStack existing : parts) {
                Module fitted = definition(existing);
                if (fitted != null && fitted.family().equals(module.family())) return "duplicate";
            }
            long fittedModules = parts.stream().filter(stack -> stack.is(MODULE.get())).count();
            return fittedModules >= frame.moduleSlots() ? "full" : "";
        }
        int batteryTier = Batteries.tier(part);
        if (batteryTier > 0) {
            if (batteryTier > frame.maxBatteryTier()) return "battery_tier";
            return hasBattery(parts) ? "full" : "";
        }
        String chip = chipId(part);
        if (!chip.isEmpty()) {
            if (!chips.contains(chip)) return "invalid";
            return anyChip(parts) ? "full" : "";
        }
        return gunHandler.installError(controller, part, parts);
    }

    private static boolean hasBattery(List<ItemStack> parts) {
        for (ItemStack part : parts) {
            if (Batteries.tier(part) > 0) return true;
        }
        return false;
    }

    private static boolean anyChip(List<ItemStack> parts) {
        for (ItemStack part : parts) {
            if (!chipId(part).isEmpty()) return true;
        }
        return false;
    }

    public static String installFromInventory(ServerPlayer player, ItemStack controller, int index) {
        if (index < 0 || index >= player.getInventory().getContainerSize()) return "invalid";
        ItemStack part = player.getInventory().getItem(index);
        List<ItemStack> parts = contents(controller);
        String error = installError(controller, part, parts);
        if (!error.isEmpty()) return error;
        int count = Mth.clamp(Math.max(1, gunHandler.installCount(part)), 1, part.getCount());
        parts.add(part.copyWithCount(count));
        save(controller, parts);
        part.shrink(count);
        player.getInventory().setChanged();
        return "";
    }

    public static String removeToInventory(ServerPlayer player, ItemStack controller, int index) {
        List<ItemStack> parts = contents(controller);
        if (index < 0 || index >= parts.size()) return "invalid";
        ItemStack part = parts.get(index);
        String error = removalError(part, parts);
        if (!error.isEmpty()) return error;
        var inventory = player.getInventory();
        int room = 0;
        for (int slot = 0; slot < 36 && room < part.getCount(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty()) room += part.getMaxStackSize();
            else if (ItemStack.isSameItemSameTags(current, part)) room += current.getMaxStackSize() - current.getCount();
        }
        if (room < part.getCount()) return "inventory_full";
        inventory.add(part.copy());
        parts.remove(index);
        save(controller, parts);
        inventory.setChanged();
        return "";
    }

    /** A gun must not be left hanging off a combat module that is about to disappear. */
    public static String removalError(ItemStack part, List<ItemStack> parts) {
        Module module = definition(part);
        if (module != null && module.family().equals("combat")) {
            boolean gunFitted = parts.stream().anyMatch(gunHandler::isGun);
            long combatModules = parts.stream()
                    .map(Hardware::definition)
                    .filter(Objects::nonNull)
                    .filter(other -> other.family().equals("combat"))
                    .count();
            if (gunFitted && combatModules == 1) return "remove_gun_first";
        }
        return "";
    }

    // ---------------------------------------------------------------------------------------------
    // Power
    // ---------------------------------------------------------------------------------------------

    /** The battery stack: the controller itself if it is a battery, else the one fitted in it. */
    public static ItemStack battery(ItemStack controller) {
        if (Batteries.model(controller) != null) return controller;
        if (controller == null || controller.isEmpty()) return ItemStack.EMPTY;
        for (ItemStack part : contents(controller)) {
            if (Batteries.model(part) != null) return part;
        }
        return ItemStack.EMPTY;
    }

    public static int batteryTier(ItemStack controller) {
        Batteries.Model model = Batteries.installed(controller);
        return model == null ? 0 : model.tier();
    }

    public static int capacity(ItemStack controller) {
        Batteries.Model model = Batteries.installed(controller);
        return model == null ? 0 : model.capacity();
    }

    public static int energy(ItemStack controller) {
        ItemStack cell = battery(controller);
        return Batteries.energy(cell, Batteries.model(cell));
    }

    /** Replaces the cell in the fitted list, so a charged copy is never a detached stack. */
    static void storeCell(ItemStack controller, ItemStack cell) {
        if (controller == cell) return;
        List<ItemStack> parts = contents(controller);
        for (int i = 0; i < parts.size(); i++) {
            if (Batteries.model(parts.get(i)) != null) {
                parts.set(i, cell);
                save(controller, parts);
                return;
            }
        }
    }

    public static void setEnergy(ItemStack controller, int value) {
        ItemStack cell = battery(controller);
        Batteries.Model model = Batteries.model(cell);
        if (model == null) return;
        int clamped = Mth.clamp(value, 0, model.capacity());
        CorvusData.of(cell).setInt(CorvusKeys.ENERGY, clamped);
        storeCell(controller, cell);
    }

    public static int charge(ItemStack controller, int amount, boolean simulate) {
        return Batteries.charge(controller, amount, simulate);
    }

    public static boolean online(ItemStack controller, String family) {
        return powered(controller, family);
    }

    /**
     * A module is online only if it is fitted, the controller holds charge, it has not tripped a fault,
     * and it is not the one module the brown-out rule switches off.
     *
     * <p>Below one fifth of capacity the hungriest powered module is cut, which is the 1.21 rule: the
     * drone keeps flying and following, but its largest consumer drops out instead of the whole drone
     * dying. At zero charge every module is offline.</p>
     */
    public static boolean powered(ItemStack controller, String family) {
        int energy = energy(controller);
        if (energy <= 0) return false;
        List<Module> installed = installed(controller);
        int capacity = capacity(controller);
        String brownedOut = energy < capacity / 5
                ? installed.stream()
                        .filter(module -> module.drain() > 0)
                        .max(Comparator.<Module>comparingInt(module -> module.draw(20))
                                .thenComparing(Module::family))
                        .map(Module::family)
                        .orElse("")
                : "";
        if (brownedOut.equals(family)) return false;
        return installed.stream().anyMatch(module -> module.family().equals(family) && !faulted(controller, module));
    }

    /**
     * FE/t this controller spends right now: the {@code drain_watts} of every fitted module that is
     * currently {@linkplain #powered online}, after its vendor {@code drain_multiplier}. That is the same
     * sum the 1.21 telemetry loop reports as {@code consumed / 20}.
     *
     * <p>Returns {@code -1} when there is no controller at all, so a panel can print "—" instead of a
     * zero it cannot vouch for. A fitted controller with no modules really does draw 0.</p>
     */
    public static int drainPerTick(ItemStack controller) {
        if (controller == null || controller.isEmpty()) return -1;
        double total = 0D;
        for (Module module : installed(controller)) {
            if (!powered(controller, module.family())) continue;
            total += module.drain() * Mth.clamp(module.trait("drain_multiplier", 1D), 0D, 10D);
        }
        return (int) Math.round(total);
    }

    /**
     * FE/t a fitted solar module makes in the given weather and daylight, or 0 when it has no view of
     * the sky. The figure is the module's own {@code clear / rain / night} parameter, which is already
     * expressed in FE/t (the 1.21 wheel reports the same nominal value; {@code output_jitter} is applied
     * by that build's once-per-second energy transaction, which this port does not run yet).
     */
    public static int solarOutput(Module module, boolean skyVisible, boolean day, boolean raining) {
        if (module == null || !"solar".equals(module.family()) || !skyVisible) return 0;
        String key = !day ? "night" : raining ? "rain" : "clear";
        return Mth.clamp(module.integer(key), 0, 500);
    }

    static boolean faulted(ItemStack controller, Module module) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        long until = CorvusData.of(controller).getLong(CorvusKeys.FAULT_PREFIX + module.id().getPath());
        return until > server.overworld().getGameTime();
    }

    /**
     * Random module trips, driven from the drone tick. Frames that roll their own malfunction
     * ({@code malfunction_chance} in the frame file) keep that roll and are skipped here.
     */
    public static void updateReliability(DroneEntity drone, ItemStack controller) {
        if (drone.tickCount % 200 != 0) return;
        MinecraftServer server = drone.getServer();
        if (server == null) return;
        for (Module module : installed(controller)) {
            if (module.params().has("malfunction_chance")) continue;
            double chance = module.trait("malfunction_chance", 0D);
            if (chance <= 0D || !powered(controller, module.family())) continue;
            if (drone.getRandom().nextDouble() < chance) {
                long until = server.overworld().getGameTime()
                        + (long) Mth.clamp((int) module.trait("malfunction_ticks", 60D), 1, 1200);
                CorvusData.of(controller).setLong(CorvusKeys.FAULT_PREFIX + module.id().getPath(), until);
            }
        }
    }

    /**
     * Battery side-effects that belong to the deployed body: knockback resistance from battery mass and
     * the rust-nail leak. Called every tick by {@code DroneEntity}; internally rate-limited.
     */
    public static void tickDeployed(DroneEntity drone) {
        if (!(drone.level() instanceof ServerLevel level)) return;
        if (drone.tickCount % 20 != 0) return;
        MinecraftServer server = level.getServer();
        ServerPlayer owner = drone.ownerId() == null ? null
                : server.getPlayerList().getPlayer(drone.ownerId());
        if (owner == null) return;
        ItemStack controller = controller(owner);
        if (controller.isEmpty()) return;
        Batteries.tickDeployed(drone, controller);
        updateReliability(drone, controller);
    }

    // ---------------------------------------------------------------------------------------------
    // Controller lookup and orders
    // ---------------------------------------------------------------------------------------------

    /** The worn terminal, but only when it is the one currently flying. */
    public static ItemStack controller(Player player) {
        if (!(player instanceof ServerPlayer server)) return ItemStack.EMPTY;
        ItemStack worn = DroneCurios.equipped(server);
        if (worn.isEmpty()) return ItemStack.EMPTY;
        CompoundTag state = server.getPersistentData();
        if (!state.hasUUID(CorvusKeys.ACTIVE)) return ItemStack.EMPTY;
        UUID serial = DroneItem.serial(worn);
        return serial != null && serial.equals(state.getUUID(CorvusKeys.ACTIVE)) ? worn : ItemStack.EMPTY;
    }

    public static boolean execute(ServerPlayer player, String intent) {
        return intentHandler.execute(player, intent);
    }

    // ---------------------------------------------------------------------------------------------
    // Creative page
    // ---------------------------------------------------------------------------------------------

    /** 27 modules, 3 chips and 10 batteries; the frames and paint cans are added by their own classes. */
    static void addToTab(CreativeModeTab.Output output) {
        Builtins.ALL.values().forEach(module -> output.accept(moduleStack(module)));
        CHIP_ITEMS.values().forEach(item -> output.accept(new ItemStack(item.get())));
        BATTERIES.forEach(item -> output.accept(new ItemStack(item.get())));
    }

    /** One line per fitted part, for the smoke harness and the terminal log. */
    public static String describe(ItemStack controller) {
        StringBuilder text = new StringBuilder();
        for (ItemStack part : contents(controller)) {
            Module module = definition(part);
            String chip = chipId(part);
            Batteries.Model battery = Batteries.model(part);
            if (module != null) text.append(module.family()).append("_mk").append(module.tier());
            else if (!chip.isEmpty()) text.append("chip:").append(chip);
            else if (battery != null) text.append("battery:").append(battery.id())
                    .append('(').append(Batteries.energy(part, battery)).append('/')
                    .append(battery.capacity()).append(')');
            else text.append("gun:").append(part.getItem().getDescriptionId());
            text.append(' ');
        }
        return text.toString().trim();
    }
}
