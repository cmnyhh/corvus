package dev.dronepal.mill;

import dev.dronepal.Dronepal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Every mill product is its own item: eight concentrates (one per common mineral family), eight coarse
 * by-products (one per common mineral family, so the mill hands out the same mineral it just read) and
 * three rare shards. The item ids carry the mineral, so the smelting recipes, the 9-to-1 shard recipes
 * and the 9-to-1 coarse recipes are plain vanilla recipes with no stack components.
 */
public final class MillItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);

    public static final Map<OreKind, DeferredItem<ConcentrateItem>> REFINED = new EnumMap<>(OreKind.class);
    public static final Map<OreKind, DeferredItem<RareShardItem>> SHARD = new EnumMap<>(OreKind.class);
    /** {@code coarse_<kind>}: the by-product of milling that mineral, never a mixed bag. */
    public static final Map<OreKind, DeferredItem<CoarseItem>> COARSE = new EnumMap<>(OreKind.class);
    public static final DeferredItem<UpgradeItem> SIEVE =
            ITEMS.register("sieve", () -> new UpgradeItem(UpgradeItem.Kind.SIEVE, new Item.Properties()));
    public static final DeferredItem<UpgradeItem> COOLING =
            ITEMS.register("cooling_tower", () -> new UpgradeItem(UpgradeItem.Kind.COOLING, new Item.Properties()));

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
        DeferredItem<ConcentrateItem> item = REFINED.get(kind);
        if (item == null) throw new IllegalArgumentException("No concentrate for " + kind.id());
        return new ItemStack(item.get(), count);
    }

    public static ItemStack shard(OreKind kind, int count) {
        DeferredItem<RareShardItem> item = SHARD.get(kind);
        if (item == null) throw new IllegalArgumentException("No shard for " + kind.id());
        return new ItemStack(item.get(), count);
    }

    public static ItemStack coarse(OreKind kind, int count) {
        DeferredItem<CoarseItem> item = COARSE.get(kind);
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

    private static Component oreName(OreKind kind) { return Component.translatable(kind.nameKey()); }

    /** One concentrate item per common mineral family. */
    public static final class ConcentrateItem extends Item {
        private final OreKind kind;

        public ConcentrateItem(OreKind kind, Properties properties) { super(properties); this.kind = kind; }
        public OreKind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.huhu_dronepal.concentrate.tip",
                    kind.productCount(), kind.product().getName(kind.product().getDefaultInstance())).withStyle(ChatFormatting.GRAY));
        }
    }

    /** One coarse by-product per common mineral family; nine of a kind craft one of its concentrates. */
    public static final class CoarseItem extends Item {
        private final OreKind kind;

        public CoarseItem(OreKind kind, Properties properties) { super(properties); this.kind = kind; }
        public OreKind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.huhu_dronepal.coarse.tip",
                    oreName(kind), refinedName(kind)).withStyle(ChatFormatting.GRAY));
        }
    }

    /** The concentrate of a family, named by its own translation key (no registry lookup needed). */
    private static Component refinedName(OreKind kind) {
        return Component.translatable("item.huhu_dronepal." + kind.id() + "_concentrate");
    }

    /** One shard item per rare family; nine of a kind craft one mineral. */
    public static final class RareShardItem extends Item {
        private final OreKind kind;

        public RareShardItem(OreKind kind, Properties properties) { super(properties); this.kind = kind; }
        public OreKind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.huhu_dronepal.rare_shard.tip",
                    kind.product().getName(kind.product().getDefaultInstance())).withStyle(ChatFormatting.GRAY));
        }
    }

    /** A mill upgrade card; the machine reads the item, the tooltip only explains the numbers. */
    public static final class UpgradeItem extends Item {
        public enum Kind { SIEVE, COOLING }
        private final Kind kind;

        public UpgradeItem(Kind kind, Properties properties) { super(properties); this.kind = kind; }
        public Kind kind() { return kind; }

        @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            String key = kind == Kind.SIEVE ? "sieve" : "cooling_tower";
            lines.add(Component.translatable("item.huhu_dronepal." + key + ".tip.1").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.huhu_dronepal." + key + ".tip.2").withStyle(ChatFormatting.GRAY));
        }
    }
}
