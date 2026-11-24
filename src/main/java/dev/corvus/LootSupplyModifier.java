package dev.corvus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds one roll of a supply table to a vanilla chest, with its own probability.
 *
 * <p>The 1.21 build expresses this as {@code forge:add_table}, which 1.20.1 does not ship. This
 * modifier is the 1.20.1 equivalent: the same conditions, the same target table, plus an explicit
 * {@code chance} so the three supply crates keep their 42% / 35% / 30% rates.</p>
 *
 * <p>JSON shape ({@code data/huhu_dronepal/loot_modifiers/*.json}):</p>
 * <pre>
 * {
 *   "type": "huhu_dronepal:supply",
 *   "conditions": [ { "condition": "minecraft:any_of", "terms": [ ... ] } ],
 *   "table": "huhu_dronepal:supplies/military",
 *   "chance": 0.42
 * }
 * </pre>
 */
public final class LootSupplyModifier extends LootModifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("corvus/loot");

    public static final Codec<LootSupplyModifier> CODEC = RecordCodecBuilder.create(instance ->
            codecStart(instance)
                    .and(ResourceLocation.CODEC.fieldOf("table").forGetter(modifier -> modifier.table))
                    .and(Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(modifier -> modifier.chance))
                    .apply(instance, LootSupplyModifier::new));

    private final ResourceLocation table;
    private final double chance;

    public LootSupplyModifier(LootItemCondition[] conditions, ResourceLocation table, double chance) {
        super(conditions);
        this.table = table;
        this.chance = Math.max(0D, Math.min(1D, chance));
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        if (context.getRandom().nextDouble() >= chance) return loot;
        MinecraftServer server = context.getLevel().getServer();
        if (server == null) return loot;
        LootTable supply = server.getLootData().getLootTable(table);
        if (supply == LootTable.EMPTY) {
            LOGGER.warn("Supply loot table {} is missing; the loot modifier did nothing", table);
            return loot;
        }
        List<ItemStack> extra = new ArrayList<>();
        supply.getRandomItems(context, extra::add);
        for (ItemStack stack : extra) prepare(stack);
        loot.addAll(extra);
        return loot;
    }

    /**
     * Loot tables can set a frame and a paint through {@code set_nbt}, but not a per-stack UUID, so a
     * dropped terminal gets its serial here. Without it the stack could never be deployed.
     */
    private static void prepare(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (stack.is(Corvus.DRONE.get())) {
            DroneItem.ensureSerial(stack);
            if (!Frames.PAINTS.contains(Frames.paint(stack))) Frames.setPaint(stack, Frames.defaultPaint(Frames.id(stack)));
        }
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
