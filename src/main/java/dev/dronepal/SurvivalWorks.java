package dev.dronepal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.*;

/** Civilian production and structure supply. Assembly uses the station inventory. */
public final class SurvivalWorks {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Dronepal.ID);
    static final DeferredBlock<AssemblyBlock> BENCH = BLOCKS.register("assembly_bench", () -> new AssemblyBlock(
            BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL)));
    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> TYPES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Dronepal.ID);
    static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>, net.minecraft.world.level.block.entity.BlockEntityType<AssemblyStation>> STATION = TYPES.register("assembly_bench", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(AssemblyStation::new, BENCH.get()).build(null));
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(BuiltInRegistries.MENU, Dronepal.ID);
    public static final DeferredHolder<MenuType<?>, MenuType<AssemblyMenu>> MENU = MENUS.register("assembly_bench", () -> new MenuType<>(AssemblyMenu::new, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));
    static volatile List<Plan> plans = List.of();
    /** Same ceilings the loader enforces, so a forged catalog packet cannot allocate unbounded lists. */
    private static final int MAX_CATALOG_ENTRIES = 54, MAX_CATALOG_INGREDIENTS = 16;

    public static void register(IEventBus bus) {
        bus.addListener(SurvivalWorks::payloads);
        for (String part : List.of("airframe", "servo_motor", "circuit_board", "battery_cell", "optics", "armor_plate", "heat_sink",
                "flight_controller", "speed_controller", "powertrain", "sensor_array", "weapon_interface", "medical_pump",
                "combat_core", "scan_core", "carry_core", "medic_core", "light_core", "solar_core", "link_core",
                "upgrade_kit", "advanced_upgrade_kit", "medical_supply")) ITEMS.registerSimpleItem(part);
        ITEMS.registerSimpleBlockItem(BENCH);
        ITEMS.register(bus);
        BLOCKS.register(bus);
        TYPES.register(bus);
        MENUS.register(bus);
        NeoForge.EVENT_BUS.addListener(SurvivalWorks::reload);
        NeoForge.EVENT_BUS.addListener(SurvivalWorks::trades);
    }

    static void addToTab(net.minecraft.world.item.CreativeModeTab.Output output) {
        output.accept(BENCH.get());
        ITEMS.getEntries().stream().filter(item -> item.get() != BENCH.get().asItem()).forEach(item -> output.accept(item.get()));
    }

    private static void trades(VillagerTradesEvent event) {
        if (event.getType() != VillagerProfession.CLERIC) return;
        for (int tier = 1; tier <= 3; tier++) {
            int grade = tier;
            event.getTrades().get(tier + 2).add((merchant, random) -> new MerchantOffer(
                    new ItemCost(Items.EMERALD, grade * 12), Hardware.moduleStack("medic_mk" + grade), 3, grade * 8, 0.05f));
        }
    }

    record Product(String item, String module, String frame) {
        static Product parse(JsonObject o) {
            String id = o.get("item").getAsString();
            if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) throw new IllegalArgumentException("Unknown assembly item " + id);
            return new Product(id, o.has("module") ? o.get("module").getAsString() : "", o.has("frame") ? o.get("frame").getAsString() : "");
        }
        ItemStack stack() {
            if (!module.isEmpty()) return Hardware.moduleStack(module);
            if (!frame.isEmpty()) return Frames.create(frame);
            return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)));
        }
        boolean matches(ItemStack stack) {
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) return false;
            return module.isEmpty() || ResourceLocation.fromNamespaceAndPath(Dronepal.ID, module).equals(Hardware.productId(stack));
        }
    }
    record Cost(Product product, int count) {}
    record Plan(ResourceLocation id, Product output, List<Cost> costs, int ticks) {
        ItemStack icon(SimpleContainer inventory) {
            ItemStack result = output.stack();
            List<Component> lines = new ArrayList<>();
            for (Cost cost : costs) {
                int owned = 0;
                for (int i = 0; i < AssemblyStation.INPUTS; i++) {
                    ItemStack input = inventory.getItem(i);
                    if (cost.product.matches(input)) owned += input.getCount();
                }
                lines.add(Component.translatable("assembly.dronepal.material", cost.product.stack().getHoverName(), owned, cost.count)
                        .withStyle(owned < cost.count ? ChatFormatting.RED : ChatFormatting.GREEN));
            }
            result.set(DataComponents.LORE, new ItemLore(lines));
            return result;
        }
    }

    /**
     * Blueprint list as the server sees it, sent when a bench menu opens so the client can draw names,
     * icons and material state without loading resource-pack plans itself (that loader only runs on the server).
     * Entry order is the wire selection index: {@code AssemblyMenu.SELECT + index}.
     */
    public record Catalog(List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Catalog> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "assembly_catalog"));
        public record Entry(Component name, ItemStack icon, List<Ingredient> ingredients) {}
        /** One required input. {@code item}/{@code module} mirror {@link Product#matches} so client and server agree. */
        public record Ingredient(String item, String module, ItemStack display, int count) {
            public boolean matches(ItemStack stack) {
                if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) return false;
                return module.isEmpty() || ResourceLocation.fromNamespaceAndPath(Dronepal.ID, module).equals(Hardware.productId(stack));
            }
        }
        static final StreamCodec<RegistryFriendlyByteBuf, Catalog> CODEC = StreamCodec.of(Catalog::write, Catalog::read);
        private static void write(RegistryFriendlyByteBuf buf, Catalog catalog) {
            buf.writeVarInt(catalog.entries.size());
            for (Entry entry : catalog.entries) {
                ComponentSerialization.STREAM_CODEC.encode(buf, entry.name);
                ItemStack.STREAM_CODEC.encode(buf, entry.icon);
                buf.writeVarInt(entry.ingredients.size());
                for (Ingredient ingredient : entry.ingredients) {
                    buf.writeUtf(ingredient.item, 128);
                    buf.writeUtf(ingredient.module, 128);
                    ItemStack.STREAM_CODEC.encode(buf, ingredient.display);
                    buf.writeVarInt(ingredient.count);
                }
            }
        }
        private static Catalog read(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_CATALOG_ENTRIES) throw new DecoderException("Assembly catalog entry count " + size);
            List<Entry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
                ItemStack icon = ItemStack.STREAM_CODEC.decode(buf);
                int ingredients = buf.readVarInt();
                if (ingredients < 0 || ingredients > MAX_CATALOG_INGREDIENTS) throw new DecoderException("Assembly catalog ingredient count " + ingredients);
                List<Ingredient> costs = new ArrayList<>(ingredients);
                for (int j = 0; j < ingredients; j++) {
                    String item = buf.readUtf(128), module = buf.readUtf(128);
                    ItemStack display = ItemStack.STREAM_CODEC.decode(buf);
                    int count = buf.readVarInt();
                    if (count < 1 || count > 64) throw new DecoderException("Assembly catalog ingredient count " + count);
                    costs.add(new Ingredient(item, module, display, count));
                }
                entries.add(new Entry(name, icon, List.copyOf(costs)));
            }
            return new Catalog(List.copyOf(entries));
        }
        @Override public Type<Catalog> type() { return TYPE; }
    }

    /** Snapshot of {@link #plans} in list order; only meaningful on the logical server. */
    public static Catalog catalog() {
        List<Catalog.Entry> entries = new ArrayList<>();
        for (Plan plan : plans) {
            List<Catalog.Ingredient> ingredients = new ArrayList<>();
            for (Cost cost : plan.costs()) {
                ItemStack display = cost.product().stack();
                ingredients.add(new Catalog.Ingredient(BuiltInRegistries.ITEM.getKey(display.getItem()).toString(), cost.product().module(), display, cost.count()));
            }
            ItemStack icon = plan.output().stack();
            entries.add(new Catalog.Entry(icon.getHoverName(), icon, List.copyOf(ingredients)));
        }
        return new Catalog(List.copyOf(entries));
    }
    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Catalog.TYPE, Catalog.CODEC, (catalog, context) -> dev.dronepal.client.AssemblyScreen.accept(catalog));
    }
    private static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "assembly") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
                Map<ResourceLocation, Plan> ordered = new TreeMap<>();
                data.forEach((key, value) -> {
                    if (!key.getNamespace().equals(Dronepal.ID)) return;
                    JsonObject json = value.getAsJsonObject();
                    List<Cost> costs = new ArrayList<>();
                    for (JsonElement entry : json.getAsJsonArray("ingredients")) {
                        JsonObject obj = entry.getAsJsonObject();
                        int count = obj.get("count").getAsInt();
                        if (count < 1 || count > 64) throw new IllegalArgumentException("Invalid assembly cost " + key);
                        costs.add(new Cost(Product.parse(obj), count));
                    }
                    if (costs.isEmpty()) throw new IllegalArgumentException("Missing assembly cost " + key);
                    int ticks = json.has("duration_ticks") ? json.get("duration_ticks").getAsInt() : 80;
                    if (ticks < 1 || ticks > 32000) throw new IllegalArgumentException("Invalid duration " + key);
                    ordered.put(key, new Plan(key, Product.parse(json.getAsJsonObject("result")), List.copyOf(costs), ticks));
                });
                if (ordered.size() > 54) throw new IllegalArgumentException("Assembly bench supports 54 blueprints");
                plans = List.copyOf(ordered.values());
            }
        });
    }

    private SurvivalWorks() {}
}
