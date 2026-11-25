package dev.corvus;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Civilian production: the assembly bench, its parts and the blueprint catalog.
 *
 * <p>The catalog is loaded from {@code data/huhu_dronepal/assembly/*.json} on the logical server and
 * shipped to the client when a bench menu opens, so the screen never has to load resource-pack plans
 * itself. 1.20.1 has no payload registry: the packet goes over a plain {@link SimpleChannel}.</p>
 */
public final class SurvivalWorks {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Corvus.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Corvus.ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Corvus.ID);

    public static final RegistryObject<Block> BENCH = BLOCKS.register("assembly_bench",
            () -> new AssemblyBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)));
    public static final RegistryObject<Item> BENCH_ITEM = ITEMS.register("assembly_bench",
            () -> new BlockItem(BENCH.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<AssemblyStation>> STATION = TYPES.register("assembly_bench",
            () -> BlockEntityType.Builder.of(AssemblyStation::new, BENCH.get()).build(null));
    public static final RegistryObject<MenuType<AssemblyMenu>> MENU = MENUS.register("assembly_bench",
            () -> new MenuType<AssemblyMenu>(AssemblyMenu::new, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

    /** The 23 bench parts, in creative-tab order. */
    public static final List<String> PARTS = List.of("airframe", "servo_motor", "circuit_board", "battery_cell",
            "optics", "armor_plate", "heat_sink", "flight_controller", "speed_controller", "powertrain",
            "sensor_array", "weapon_interface", "medical_pump", "combat_core", "scan_core", "carry_core",
            "medic_core", "light_core", "solar_core", "link_core", "upgrade_kit", "advanced_upgrade_kit",
            "medical_supply");
    private static final Map<String, RegistryObject<Item>> PART_ITEMS = new java.util.LinkedHashMap<>();

    static {
        for (String part : PARTS) PART_ITEMS.put(part, ITEMS.register(part, () -> new Item(new Item.Properties())));
    }

    static volatile List<Plan> plans = List.of();
    /** Same ceilings the loader enforces, so a forged catalog packet cannot allocate unbounded lists. */
    private static final int MAX_CATALOG_ENTRIES = 54, MAX_CATALOG_INGREDIENTS = 16;
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Corvus.ID, "assembly"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean wired;

    private SurvivalWorks() {}

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        BLOCKS.register(bus);
        TYPES.register(bus);
        MENUS.register(bus);
        if (!wired) {
            wired = true;
            CHANNEL.registerMessage(0, Catalog.class, Catalog::write, Catalog::read, Catalog::handle);
            MinecraftForge.EVENT_BUS.addListener(SurvivalWorks::reload);
            MinecraftForge.EVENT_BUS.addListener(SurvivalWorks::trades);
        }
    }

    public static Item part(String name) {
        RegistryObject<Item> item = PART_ITEMS.get(name);
        if (item == null) throw new IllegalArgumentException("Unknown bench part " + name);
        return item.get();
    }

    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(BENCH_ITEM.get());
        PART_ITEMS.values().forEach(item -> output.accept(item.get()));
    }

    /** Clerics sell the medic module line, one tier per trade level. */
    private static void trades(VillagerTradesEvent event) {
        if (event.getType() != VillagerProfession.CLERIC) return;
        for (int tier = 1; tier <= 3; tier++) {
            int grade = tier;
            List<VillagerTrades.ItemListing> listings = event.getTrades().get(tier + 2);
            if (listings == null) continue;
            listings.add((trader, random) -> new MerchantOffer(new ItemStack(Items.EMERALD, grade * 12),
                    Hardware.moduleStack("medic_mk" + grade), 3, grade * 8, 0.05F));
        }
    }

    // ---- blueprints ----

    record Product(String item, String module, String frame) {
        static Product parse(JsonObject o) {
            String id = o.get("item").getAsString();
            ResourceLocation key = new ResourceLocation(id);
            if (!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Unknown assembly item " + id);
            return new Product(id, o.has("module") ? o.get("module").getAsString() : "",
                    o.has("frame") ? o.get("frame").getAsString() : "");
        }

        ItemStack stack() {
            if (!module.isEmpty()) return Hardware.moduleStack(module);
            if (!frame.isEmpty()) return Frames.create(frame);
            return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(item)));
        }

        boolean matches(ItemStack stack) {
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) return false;
            return module.isEmpty() || new ResourceLocation(Corvus.ID, module).equals(Hardware.productId(stack));
        }
    }

    record Cost(Product product, int count) {}

    record Plan(ResourceLocation id, Product output, List<Cost> costs, int ticks, int count) {
        /** The stack one craft commits. {@code count} comes from the blueprint's optional result count. */
        ItemStack result() {
            ItemStack stack = output.stack();
            if (!stack.isEmpty()) stack.setCount(count);
            return stack;
        }
        ItemStack icon(SimpleContainer inventory) { return result(); }
    }

    /**
     * Blueprint list as the server sees it. Entry order is the wire selection index:
     * {@code AssemblyMenu.SELECT + index}.
     */
    public record Catalog(List<Entry> entries) {
        /** One blueprint: the preview stack plus what it consumes. */
        public record Entry(ItemStack icon, List<Ingredient> ingredients) {}
        /** One required input; {@code item}/{@code module} mirror {@link Product#matches}. */
        public record Ingredient(String item, String module, ItemStack display, int count) {
            public boolean matches(ItemStack stack) {
                if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) return false;
                return module.isEmpty() || new ResourceLocation(Corvus.ID, module).equals(Hardware.productId(stack));
            }
        }

        static void write(Catalog catalog, FriendlyByteBuf buf) {
            buf.writeVarInt(catalog.entries.size());
            for (Entry entry : catalog.entries) {
                PacketStacks.write(buf, entry.icon);
                buf.writeVarInt(entry.ingredients.size());
                for (Ingredient ingredient : entry.ingredients) {
                    buf.writeUtf(ingredient.item, 128);
                    buf.writeUtf(ingredient.module, 128);
                    PacketStacks.write(buf, ingredient.display);
                    buf.writeVarInt(ingredient.count);
                }
            }
        }

        static Catalog read(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_CATALOG_ENTRIES) throw new DecoderException("Assembly catalog entry count " + size);
            List<Entry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                ItemStack icon = PacketStacks.read(buf);
                int ingredients = buf.readVarInt();
                if (ingredients < 0 || ingredients > MAX_CATALOG_INGREDIENTS)
                    throw new DecoderException("Assembly catalog ingredient count " + ingredients);
                List<Ingredient> costs = new ArrayList<>(ingredients);
                for (int j = 0; j < ingredients; j++) {
                    String item = buf.readUtf(128), module = buf.readUtf(128);
                    ItemStack display = PacketStacks.read(buf);
                    int count = buf.readVarInt();
                    if (count < 1 || count > 64) throw new DecoderException("Assembly catalog ingredient count " + count);
                    costs.add(new Ingredient(item, module, display, count));
                }
                entries.add(new Entry(icon, List.copyOf(costs)));
            }
            return new Catalog(List.copyOf(entries));
        }

        static void handle(Catalog catalog, java.util.function.Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            // The client screen class only resolves on the physical client; this handler never runs
            // on a dedicated server.
            ctx.enqueueWork(() -> dev.corvus.client.AssemblyScreen.accept(catalog));
            ctx.setPacketHandled(true);
        }
    }

    /** Snapshot of {@link #plans} in list order; only meaningful on the logical server. */
    public static Catalog catalog() {
        List<Catalog.Entry> entries = new ArrayList<>();
        for (Plan plan : plans) {
            List<Catalog.Ingredient> ingredients = new ArrayList<>();
            for (Cost cost : plan.costs()) {
                ItemStack display = cost.product().stack();
                ingredients.add(new Catalog.Ingredient(BuiltInRegistries.ITEM.getKey(display.getItem()).toString(),
                        cost.product().module(), display, cost.count()));
            }
            entries.add(new Catalog.Entry(plan.result(), List.copyOf(ingredients)));
        }
        return new Catalog(List.copyOf(entries));
    }

    static void sendCatalog(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), catalog());
    }

    private static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "assembly") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
                Map<ResourceLocation, Plan> ordered = new TreeMap<>();
                data.forEach((key, value) -> {
                    if (!key.getNamespace().equals(Corvus.ID)) return;
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
                    JsonObject result = json.getAsJsonObject("result");
                    int produced = result.has("count") ? result.get("count").getAsInt() : 1;
                    if (produced < 1 || produced > 64) throw new IllegalArgumentException("Invalid assembly result count " + key);
                    ordered.put(key, new Plan(key, Product.parse(result), List.copyOf(costs), ticks, produced));
                });
                if (ordered.size() > MAX_CATALOG_ENTRIES) throw new IllegalArgumentException("Assembly bench supports 54 blueprints");
                plans = List.copyOf(ordered.values());
            }
        });
    }

    /** 1.20.1 has no {@code ItemStack.STREAM_CODEC}; the catalog ships stacks as NBT. */
    static final class PacketStacks {
        static void write(FriendlyByteBuf buf, ItemStack stack) {
            buf.writeNbt(stack.save(new net.minecraft.nbt.CompoundTag()));
        }

        static ItemStack read(FriendlyByteBuf buf) {
            net.minecraft.nbt.CompoundTag tag = buf.readNbt();
            return tag == null ? ItemStack.EMPTY : ItemStack.of(tag);
        }

        private PacketStacks() {}
    }
}
