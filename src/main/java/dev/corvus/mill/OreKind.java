package dev.corvus.mill;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A mineral family the mill can read out of an ore or a raw drop.
 *
 * <p>Every family maps to exactly one smelting result, so a concentrate always knows what it will
 * become. The three rare families (diamond / emerald / ancient debris) never double under a drone and
 * instead drop a {@code rare_shard}; nine shards of the same family craft one mineral.</p>
 *
 * <p>This is the same table as the 1.21.1 build; the only difference is where the family is stored: 1.20.1
 * has no data components, so the item id writes the id into the stack's NBT under {@code ore_kind}.</p>
 */
public enum OreKind {
    IRON("iron", false, Items.IRON_INGOT),
    COPPER("copper", false, Items.COPPER_INGOT),
    GOLD("gold", false, Items.GOLD_INGOT),
    NETHER_GOLD("nether_gold", false, Items.GOLD_NUGGET),
    COAL("coal", false, Items.COAL),
    REDSTONE("redstone", false, Items.REDSTONE),
    LAPIS("lapis", false, Items.LAPIS_LAZULI),
    QUARTZ("quartz", false, Items.QUARTZ),
    DIAMOND("diamond", true, Items.DIAMOND),
    EMERALD("emerald", true, Items.EMERALD),
    DEBRIS("debris", true, Items.NETHERITE_SCRAP);

    private static Map<Item, OreKind> inputs;

    private final String id;
    private final boolean rare;
    private final Item product;

    OreKind(String id, boolean rare, Item product) {
        this.id = id;
        this.rare = rare;
        this.product = product;
    }

    /** How many items one concentrate smelts into: coal 2, redstone 4, lapis 6, quartz 2, nether gold 4 nuggets. */
    public int productCount() {
        return switch (this) {
            case COAL -> 2;
            case REDSTONE -> 4;
            case LAPIS -> 6;
            case QUARTZ -> 2;
            case NETHER_GOLD -> 4;
            default -> 1;
        };
    }

    /** Rare families have shards instead of a concentrate item. */
    public boolean hasConcentrate() { return !rare; }

    public String id() { return id; }
    public boolean rare() { return rare; }
    public Item product() { return product; }
    public String nameKey() { return "dronepal.ore." + id; }

    public static OreKind byId(String id) {
        for (OreKind kind : values()) if (kind.id.equals(id)) return kind;
        return null;
    }

    /** The family of a stack in the input slots, or {@code null} when the mill cannot read it. */
    public static OreKind of(ItemStack stack) {
        return stack.isEmpty() ? null : inputs().get(stack.getItem());
    }

    private static Map<Item, OreKind> inputs() {
        Map<Item, OreKind> map = inputs;
        if (map != null) return map;
        map = new HashMap<>();
        put(map, IRON, "raw_iron", "iron_ore", "deepslate_iron_ore");
        put(map, COPPER, "raw_copper", "copper_ore", "deepslate_copper_ore");
        put(map, GOLD, "raw_gold", "gold_ore", "deepslate_gold_ore");
        put(map, NETHER_GOLD, "nether_gold_ore");
        put(map, COAL, "coal_ore", "deepslate_coal_ore");
        put(map, REDSTONE, "redstone_ore", "deepslate_redstone_ore");
        put(map, LAPIS, "lapis_ore", "deepslate_lapis_ore");
        put(map, QUARTZ, "nether_quartz_ore");
        put(map, DIAMOND, "diamond_ore", "deepslate_diamond_ore", "diamond");
        put(map, EMERALD, "emerald_ore", "deepslate_emerald_ore", "emerald");
        put(map, DEBRIS, "ancient_debris");
        inputs = Map.copyOf(map);
        return inputs;
    }

    private static void put(Map<Item, OreKind> map, OreKind kind, String... ids) {
        for (String id : ids) {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", id));
            if (item != Items.AIR) map.put(item, kind);
        }
    }
}
