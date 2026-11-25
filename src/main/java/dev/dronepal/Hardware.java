package dev.dronepal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.corvus.core.HardwareRules;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.registries.*;

/** Hardware remains in the physical controller, including the original battery's charge and components. */
public final class Hardware {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    static final DeferredItem<Item> MODULE = ITEMS.register("module", () -> new Item(new Item.Properties()) {
        @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, net.minecraft.world.item.TooltipFlag flag) {
            ModuleTooltips.append(stack, context, lines);
        }
        @Override public Component getName(ItemStack stack) {
            ResourceLocation id = productId(stack);
            Module module = modules.get(id);
            if (module == null) module = Builtins.ALL.get(id);
            if (module == null) return Component.translatableWithFallback("item.huhu_dronepal.unknown_module", "Unknown module");
            return Component.translatable("dronepal.name.module", Component.translatable("dronepal.vendor." + module.vendor()),
                    module.tierLabel(), Component.translatableWithFallback(module.productKey(), module.productName()));
        }
    });
    private static final List<String> FAMILIES = List.of("combat", "scan", "carry", "medic", "light", "link", "solar");
    private static final List<String> CHIP_IDS = List.of("silent", "chatty", "tactical");
    private static final Map<String, DeferredItem<Item>> CHIP_ITEMS = new LinkedHashMap<>();
    public static final List<DeferredItem<Item>> BATTERIES = Batteries.register(ITEMS);
    private static volatile Map<ResourceLocation, Module> modules = Map.of();
    private static volatile Set<String> chips = Set.of();
    private static volatile Map<String, JsonObject> vendorTraits = builtinTraits();
    private static Map<String, JsonObject> builtinTraits() {
        Map<String, JsonObject> result = new HashMap<>();
        for (String id : List.of("ahi", "alb", "cvs", "omd", "rust")) {
            try (var stream = Objects.requireNonNull(Hardware.class.getResourceAsStream("/data/huhu_dronepal/vendors/" + id + ".json"));
                 var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                result.put(id, com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("traits"));
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
        return Map.copyOf(result);
    }
    static Map<ResourceLocation, Module> tooltipModules() { return modules.isEmpty() ? Builtins.ALL : modules; }
    static { for (String id : CHIP_IDS) CHIP_ITEMS.put(id, ITEMS.registerSimpleItem("chip_" + id)); }

    static void register(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener(BatteryItem::register);
        NeoForge.EVENT_BUS.addListener(BatteryItem::sync);
        bus.addListener(ModuleTooltips::register);
        NeoForge.EVENT_BUS.addListener(ModuleTooltips::sync);
        NeoForge.EVENT_BUS.addListener(Hardware::reload);
        NeoForge.EVENT_BUS.addListener(Batteries::reload);
    }
    record Module(ResourceLocation id, String family, int tier, int drain, JsonObject params, Set<String> intents, String wheelLabel,
                  String vendor, String tierLabel, String productKey, String productName) {
        double trait(String key, double fallback) {
            var values = vendorTraits.get(vendor);
            return values != null && values.has(key) ? values.get(key).getAsDouble() : fallback;
        }
        boolean requiresSupply() {
            var values = vendorTraits.get(vendor);
            return values != null && values.has("consumes_medkit") && values.get("consumes_medkit").getAsBoolean();
        }
        int draw(int ticks) { return (int) Math.round(drain * ticks * Math.clamp(trait("drain_multiplier", 1), 0, 10)); }
        int integer(String key) { return params.get(key).getAsInt(); }
        float decimal(String key) { return params.get(key).getAsFloat(); }
    }
    private static Module parse(ResourceLocation id, JsonObject o, Map<ResourceLocation, JsonElement> all) {
        String family = o.get("family").getAsString();
        int tier = o.get("tier").getAsInt();
        if (tier < 1 || tier > 3) throw new IllegalArgumentException("Invalid tier for " + id);
        ResourceLocation original = ResourceLocation.fromNamespaceAndPath(Dronepal.ID, family + "_mk" + tier);
        JsonObject wheel = o.getAsJsonObject("wheel");
        if (wheel == null && all.containsKey(original)) wheel = all.get(original).getAsJsonObject().getAsJsonObject("wheel");
        String label = wheel == null ? "dronepal.wheel." + family : wheel.get("label").getAsString();
        Set<String> intents = new HashSet<>();
        o.getAsJsonArray("intents").forEach(v -> intents.add(v.getAsString()));
        String key = "dronepal.product." + (id.equals(original) ? family : id.getPath());
        Module m = new Module(id, family, tier, Math.clamp(o.get("drain_watts").getAsInt(), 0, 10000),
                o.getAsJsonObject("params"), Set.copyOf(intents), label, o.get("vendor").getAsString(),
                o.get("tier_label").getAsString(), key, o.get("product_name").getAsString());
        switch (family) {
            case "combat" -> { m.integer("range"); m.integer("burst"); m.integer("cooldown_ticks"); m.decimal("spread_deg"); m.decimal("damage_mult"); }
            case "scan" -> { m.integer("radius"); m.integer("mark_ticks"); m.params.get("ore").getAsBoolean(); m.params.get("hostile").getAsBoolean(); }
            case "medic" -> { m.integer("cooldown_ticks"); m.integer("active_drain_watts"); m.decimal("max_heal_per_use"); m.decimal("heal_per_tick"); }
            case "solar" -> { m.integer("clear"); m.integer("night"); m.integer("rain"); }
        }
        return m;
    }
    /** Client creative tabs and hover names are built before a dedicated server's datapack exists. */
    private static final class Builtins {
        static final Map<ResourceLocation, Module> ALL = load();
        private static Map<ResourceLocation, Module> load() {
            var files = new LinkedHashMap<ResourceLocation, JsonElement>();
            for (String family : FAMILIES) {
                for (int tier = 1; tier <= 3; tier++) loadFile(files, family + "_mk" + tier);
                if (!family.equals("link")) loadFile(files, family + "_rust_g1");
            }
            var result = new LinkedHashMap<ResourceLocation, Module>();
            files.forEach((id, json) -> result.put(id, parse(id, json.getAsJsonObject(), files)));
            return Collections.unmodifiableMap(result);
        }
        private static void loadFile(Map<ResourceLocation, JsonElement> files, String name) {
            String path = "/data/huhu_dronepal/modules/" + name + ".json";
            try (var in = Objects.requireNonNull(Hardware.class.getResourceAsStream(path), path);
                 var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                files.put(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, name), com.google.gson.JsonParser.parseReader(reader));
            } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        }
    }
    private static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "vendors") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> values, ResourceManager manager, ProfilerFiller profiler) {
                Map<String, JsonObject> next = new HashMap<>();
                values.forEach((id, json) -> {
                    if (id.getNamespace().equals(Dronepal.ID) && json.isJsonObject() && json.getAsJsonObject().has("traits")) {
                        next.put(id.getPath(), json.getAsJsonObject().getAsJsonObject("traits"));
                    }
                });
                vendorTraits = Map.copyOf(next);
            }
        });
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "modules") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> values, ResourceManager manager, ProfilerFiller profiler) {
                Map<ResourceLocation, Module> next = new HashMap<>();
                values.forEach((id, json) -> {
                    if (!id.getNamespace().equals(Dronepal.ID)) return;
                    try {
                        next.put(id, parse(id, json.getAsJsonObject(), values));
                    } catch (RuntimeException bad) { LogUtils.getLogger().warn("Skipping invalid drone module {}", id, bad); }
                });
                modules = Map.copyOf(next);
            }
        });
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "chips") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> values, ResourceManager manager, ProfilerFiller profiler) {
                Set<String> next = new HashSet<>();
                values.forEach((id, value) -> { if (id.getNamespace().equals(Dronepal.ID) && value.isJsonObject() && value.getAsJsonObject().has("prompt_profile")) next.add(id.getPath()); });
                chips = Set.copyOf(next);
            }
        });
    }
    private static CompoundTag data(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    static List<ItemStack> contents(ItemStack stack) { return new ArrayList<>(stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyStream().toList()); }
    static void save(ItemStack stack, List<ItemStack> parts) { stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(parts)); }
    private static int cellIndex(ItemStack stack) {
        for (int i = 0; i < BATTERIES.size(); i++) if (stack.is(BATTERIES.get(i).get())) return i;
        return -1;
    }
    static String chipId(ItemStack part) {
        for (var e : CHIP_ITEMS.entrySet()) if (part.is(e.getValue().get())) return e.getKey();
        return "";
    }
    /** A chip must be physically installed for the drone to speak at all. */
    public static boolean hasChip(ItemStack stack) {
        return contents(stack).stream().map(Hardware::chipId).anyMatch(id -> !id.isEmpty() && chips.contains(id));
    }
    public static String chip(ItemStack stack) { return contents(stack).stream().map(Hardware::chipId).filter(chips::contains).findFirst().orElse("silent"); }
    private static final ResourceLocation UNKNOWN = ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "unknown");
    public static ResourceLocation productId(ItemStack stack) {
        if (!stack.is(MODULE.get())) return UNKNOWN;
        CompoundTag tag = data(stack);
        ResourceLocation id = tag.contains("module_product") ? ResourceLocation.tryParse(tag.getString("module_product"))
                : ResourceLocation.tryBuild(Dronepal.ID, tag.getString("module_family") + "_mk" + tag.getInt("module_tier"));
        return id == null ? UNKNOWN : id;
    }
    public static ItemStack moduleStack(String path) {
        ResourceLocation id = path.contains(":") ? ResourceLocation.parse(path) : ResourceLocation.fromNamespaceAndPath(Dronepal.ID, path);
        Module product = modules.get(id);
        if (product == null) product = Builtins.ALL.get(id);
        if (product == null) throw new IllegalArgumentException("Unknown module product " + id);
        return moduleStack(product);
    }
    private static ItemStack moduleStack(Module product) {
        ItemStack stack = MODULE.toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString("module_product", product.id().toString());
            tag.putString("module_family", product.family());
            tag.putInt("module_tier", product.tier());
        });
        return stack;
    }
    static Module definition(ItemStack part) { return modules.get(productId(part)); }
    static List<Module> installed(ItemStack controller) { return contents(controller).stream().map(Hardware::definition).filter(Objects::nonNull).toList(); }
    public static int tier(ItemStack stack, String family) { return installed(stack).stream().filter(m -> m.family.equals(family)).mapToInt(Module::tier).findFirst().orElse(0); }
    static String installError(ItemStack part, List<ItemStack> parts) { return installError(ItemStack.EMPTY, part, parts); }
    static String installError(ItemStack controller, ItemStack part, List<ItemStack> parts) {
        Frames.Frame frame = Frames.of(controller);
        if (part.is(MODULE.get())) {
            Module m = definition(part);
            if (m == null) return "invalid";
            if (parts.stream().map(Hardware::definition).filter(Objects::nonNull).anyMatch(existing -> existing.family().equals(m.family()))) return "duplicate";
            return parts.stream().filter(p -> p.is(MODULE.get())).count() >= frame.moduleSlots() ? "full" : "";
        }
        int batteryTier = batteryTier(part);
        if (!part.is(Dronepal.DRONE.get()) && batteryTier > 0) {
            if (batteryTier > frame.maxBatteryTier()) return "battery_tier";
            return parts.stream().anyMatch(p -> batteryTier(p) > 0) ? "full" : "";
        }
        String chip = chipId(part);
        if (!chip.isEmpty()) return !chips.contains(chip) ? "invalid" : parts.stream().anyMatch(p -> !chipId(p).isEmpty()) ? "full" : "";
        return CombatSlots.installError(controller, part, parts);
    }
    static String installFromInventory(ServerPlayer player, ItemStack controller, int index) {
        if (index < 0 || index >= player.getInventory().getContainerSize()) return "invalid";
        ItemStack part = player.getInventory().getItem(index);
        List<ItemStack> parts = contents(controller);
        String error = installError(controller, part, parts);
        if (!error.isEmpty()) return error;
        int count = Math.clamp(CombatSlots.installCount(part), 1, part.getCount());
        parts.add(part.copyWithCount(count));
        save(controller, parts);
        part.shrink(count);
        player.getInventory().setChanged();
        return "";
    }
    static String removeToInventory(ServerPlayer player, ItemStack controller, int index) {
        List<ItemStack> parts = contents(controller);
        if (index < 0 || index >= parts.size()) return "invalid";
        ItemStack part = parts.get(index);
        String error = removalError(part, parts);
        if (!error.isEmpty()) return error;
        var inventory = player.getInventory();
        int room = 0;
        for (int i = 0; i < 36 && room < part.getCount(); i++) {
            ItemStack current = inventory.getItem(i);
            if (current.isEmpty()) room += part.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(current, part)) room += current.getMaxStackSize() - current.getCount();
        }
        if (room < part.getCount()) return "inventory_full";
        inventory.add(part.copy());
        parts.remove(index);
        save(controller, parts);
        inventory.setChanged();
        return "";
    }
    static String removalError(ItemStack part, List<ItemStack> parts) {
        var module = definition(part);
        if (module != null && module.family().equals("combat") && parts.stream().anyMatch(CombatSlots::isGun)
                && parts.stream().map(Hardware::definition).filter(m -> m != null && m.family().equals("combat")).count() == 1) {
            return "remove_gun_first";
        }
        return "";
    }
    public static boolean online(ItemStack stack, String family) {
        // One shared rule (dev.corvus.core.HardwareRules#online): the player switch always wins over
        // charge, so a module switched off in the wheel neither draws power nor speaks (R2).
        return HardwareRules.online(DroneRemote.disabled(stack, family), powered(stack, family));
    }
    public static boolean powered(ItemStack stack, String family) {
        int energy = energy(stack);
        if (energy <= 0) return false;
        var installed = installed(stack);
        String disabled = HardwareRules.brownout(energy, capacity(stack))
                ? installed.stream().filter(m -> m.drain() > 0)
                .max(Comparator.<Module>comparingInt(m -> m.draw(20)).thenComparing(Module::family)).map(Module::family).orElse("") : "";
        if (disabled.equals(family)) return false;
        return installed.stream().anyMatch(m -> m.family().equals(family) && !faulted(stack, m));
    }
    static boolean faulted(ItemStack stack, Module module) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null && data(stack).getLong("dronepal_fault_" + module.id().getPath()) > server.overworld().getGameTime();
    }
    static void updateReliability(DroneEntity drone, ItemStack stack, List<Module> installed) {
        if (drone.tickCount % 200 != 0) return;
        for (Module module : installed) {
            // Models with a dedicated malfunction roll make that roll when activated.
            if (module.params().has("malfunction_chance") || module.trait("malfunction_chance", 0) <= 0 || !online(stack, module.family())) continue;
            if (drone.getRandom().nextDouble() < module.trait("malfunction_chance", 0)) {
                long until = drone.getServer().overworld().getGameTime() + (long) Math.clamp(module.trait("malfunction_ticks", 60), 1, 1200);
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong("dronepal_fault_" + module.id().getPath(), until));
            }
        }
    }
    static ItemStack battery(ItemStack stack) {
        if (Batteries.model(stack) != null) return stack;
        return stack.is(Dronepal.DRONE.get()) ? contents(stack).stream().filter(p -> Batteries.model(p) != null).findFirst().orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
    }
    public static int batteryTier(ItemStack stack) {
        var model = Batteries.installed(stack);
        return model == null ? 0 : model.tier();
    }
    public static int capacity(ItemStack stack) {
        var model = Batteries.installed(stack);
        return model == null ? 0 : model.capacity();
    }
    public static int energy(ItemStack stack) {
        var cell = battery(stack);
        return Batteries.energy(cell, Batteries.model(cell));
    }
    static void storeCell(ItemStack stack, ItemStack cell) {
        var parts = contents(stack);
        for (int i = 0; i < parts.size(); i++) {
            if (Batteries.model(parts.get(i)) != null) {
                parts.set(i, cell);
                save(stack, parts);
                return;
            }
        }
    }
    public static void setEnergy(ItemStack stack, int value) {
        var cell = battery(stack);
        var model = Batteries.model(cell);
        if (model == null) return;
        CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> tag.putInt("energy", Math.clamp(value, 0, model.capacity())));
        if (stack != cell) storeCell(stack, cell);
    }
    public static int charge(ItemStack stack, int amount, boolean simulate) { return Batteries.charge(stack, amount, simulate); }
    /** The deployed, owned projection this player's fitted controller belongs to, or null. */
    public static DroneEntity active(ServerPlayer p) {
        var tag = p.getPersistentData();
        return tag.hasUUID(DroneItem.ACTIVE) && p.serverLevel().getEntity(tag.getUUID(DroneItem.ACTIVE)) instanceof DroneEntity d && p.getUUID().equals(d.owner) && d.getUUID().equals(DroneItem.serial(CuriosDrones.equipped(p))) ? d : null;
    }
    public static ItemStack controller(ServerPlayer p) { DroneEntity d = active(p); return d == null ? ItemStack.EMPTY : d.controller(); }
    public static boolean execute(ServerPlayer p, String intent) {
        DroneEntity drone = active(p);
        if (drone == null) return false;
        if (intent.equals("order.supply")) return CombatSlots.available() && TaczLink.supply(p);
        if (intent.equals("order.work") || intent.equals("order.idle")) return dev.dronepal.mill.DroneWork.order(p, intent);
        if (intent.equals("order.cover") || intent.equals("order.hold")) {
            if (installed(drone.controller()).stream().noneMatch(m -> m.family().equals("combat") && m.intents().contains(intent))) return false;
            return DroneCombat.setDeployed(drone, intent.equals("order.cover"));
        }
        return drone.execute(p, intent);
    }
    static void creative(net.minecraft.world.item.CreativeModeTab.Output event) {
        Builtins.ALL.values().forEach(product -> event.accept(moduleStack(product)));
        CHIP_ITEMS.values().forEach(item -> event.accept(item.get()));
        BATTERIES.forEach(item -> event.accept(item.get()));
    }
}
