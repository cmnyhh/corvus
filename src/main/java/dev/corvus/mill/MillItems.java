package dev.corvus.mill;

import dev.corvus.Corvus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

/**
 * Every mill product is its own item: eight concentrates (one per common mineral family), eight coarse
 * by-products (one per common mineral family, so the mill hands out the same mineral it just read) and
 * three rare shards. The item ids carry the mineral, so the smelting recipes, the 9-to-1 shard recipes
 * and the 9-to-1 coarse recipes are plain vanilla recipes.
 */
public final class MillItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);

    public static final Map<OreKind, RegistryObject<Item>> REFINED = new EnumMap<>(OreKind.class);
    public static final Map<OreKind, RegistryObject<Item>> SHARD = new EnumMap<>(OreKind.class);
    /** {@code coarse_<kind>}: the by-product of milling that mineral, never a mixed bag. */
    public static final Map<OreKind, RegistryObject<Item>> COARSE = new EnumMap<>(OreKind.class);

    public static final RegistryObject<Item> SIEVE = ITEMS.register("sieve",
            () -> new UpgradeItem(UpgradeItem.Kind.SIEVE, new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> COOLING = ITEMS.register("cooling_tower",
            () -> new UpgradeItem(UpgradeItem.Kind.COOLING, new Item.Properties().stacksTo(1)));

    static {
        for (OreKind kind : OreKind.values()) {
            if (kind.hasConcentrate()) {
                REFINED.put(kind, ITEMS.register(kind.id() + "_concentrate",
                        () -> new ConcentrateItem(kind, new Item.Properties())));
                COARSE.put(kind, ITEMS.register("coarse_" + kind.id(),
                        () -> new CoarseItem(kind, new Item.Properties())));
            } else {
                SHARD.put(kind, ITEMS.register(kind.id() + "_shard",
                        () -> new RareShardItem(kind, new Item.Properties())));
            }
        }
    }

    private MillItems() {}

    static void register(IEventBus bus) { ITEMS.register(bus); }

    static void creative(CreativeModeTab.Output output) {
        REFINED.values().forEach(item -> output.accept(item.get()));
        COARSE.values().forEach(item -> output.accept(item.get()));
        SHARD.values().forEach(item -> output.accept(item.get()));
        output.accept(SIEVE.get());
        output.accept(COOLING.get());
    }

    public static boolean isSieve(ItemStack stack) { return stack.is(SIEVE.get()); }

    public static boolean isCooling(ItemStack stack) { return stack.is(COOLING.get()); }

    public static ItemStack refined(OreKind kind, int count) {
        RegistryObject<Item> item = REFINED.get(kind);
        if (item == null) throw new IllegalArgumentException("No concentrate for " + kind.id());
        return new ItemStack(item.get(), count);
    }

    public static ItemStack shard(OreKind kind, int count) {
        RegistryObject<Item> item = SHARD.get(kind);
        if (item == null) throw new IllegalArgumentException("No shard for " + kind.id());
        return new ItemStack(item.get(), count);
    }

    public static ItemStack coarse(OreKind kind, int count) {
        RegistryObject<Item> item = COARSE.get(kind);
        if (item == null) throw new IllegalArgumentException("No coarse by-product for " + kind.id());
        return new ItemStack(item.get(), count);
    }

    /** {@code true} for a primary product: a concentrate or a rare shard. */
    public static boolean isPrimary(ItemStack stack) {
        return stack.getItem() instanceof ConcentrateItem || stack.getItem() instanceof RareShardItem;
    }

    public static boolean isConcentrate(ItemStack stack) { return stack.getItem() instanceof ConcentrateItem; }

    public static boolean isShard(ItemStack stack) { return stack.getItem() instanceof RareShardItem; }

    public static boolean isCoarse(ItemStack stack) { return stack.getItem() instanceof CoarseItem; }

    /** The mineral family of a coarse by-product, or {@code null} for anything else. */
    public static OreKind coarseKind(ItemStack stack) {
        return stack.getItem() instanceof CoarseItem coarse ? coarse.kind() : null;
    }

    /** One concentrate item per common mineral family. */
    public static final class ConcentrateItem extends Item {
        private final OreKind kind;

        ConcentrateItem(OreKind kind, Properties properties) {
            super(properties);
            this.kind = kind;
        }

        public OreKind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines,
                                              TooltipFlag flag) {
            lines.add(Component.translatable("item.huhu_dronepal.concentrate.tip",
                            kind.productCount(), new ItemStack(kind.product()).getHoverName())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** One coarse by-product per common mineral family; nine of a kind craft one of its concentrates. */
    public static final class CoarseItem extends Item {
        private final OreKind kind;

        CoarseItem(OreKind kind, Properties properties) {
            super(properties);
            this.kind = kind;
        }

        public OreKind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines,
                                              TooltipFlag flag) {
            lines.add(Component.translatable("item.huhu_dronepal.coarse.tip",
                            Component.translatable(kind.nameKey()),
                            Component.translatable("item.huhu_dronepal." + kind.id() + "_concentrate"))
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** One shard item per rare family; nine of a kind craft one mineral. */
    public static final class RareShardItem extends Item {
        private final OreKind kind;

        RareShardItem(OreKind kind, Properties properties) {
            super(properties);
            this.kind = kind;
        }

        public OreKind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines,
                                              TooltipFlag flag) {
            lines.add(Component.translatable("item.huhu_dronepal.rare_shard.tip",
                            new ItemStack(kind.product()).getHoverName())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** A mill upgrade card; the machine reads the item, the tooltip only explains the numbers. */
    public static final class UpgradeItem extends Item {
        public enum Kind { SIEVE, COOLING }

        private final Kind kind;

        UpgradeItem(Kind kind, Properties properties) {
            super(properties);
            this.kind = kind;
        }

        public Kind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines,
                                              TooltipFlag flag) {
            String key = kind == Kind.SIEVE ? "sieve" : "cooling_tower";
            lines.add(Component.translatable("item.huhu_dronepal." + key + ".tip.1").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.huhu_dronepal." + key + ".tip.2").withStyle(ChatFormatting.GRAY));
        }
    }
}
