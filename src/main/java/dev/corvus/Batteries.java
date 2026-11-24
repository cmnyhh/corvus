package dev.corvus;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ten battery models plus the charge bookkeeping that lives on the battery stack itself.
 *
 * <p>Data: {@code data/huhu_dronepal/batteries/*.json} holds capacity, receive rate, mass and the
 * rust-nail leak. Charge is stored on the item ({@link CorvusKeys#ENERGY}) and is absent until the
 * stack is first written, which means "full" — the same rule the 1.21 build uses, so a freshly
 * crafted battery is not empty.</p>
 *
 * <p>The per-tick receive ceiling is enforced here rather than at the charger, because the budget has
 * to hold across a dock transfer, an inventory trip and a terminal transfer within the same tick.</p>
 */
public final class Batteries {
    /** Ten models, stable order: the creative page, the tooltip catalog and the sync packets use it. */
    public static final List<String> IDS = List.of(
            "ahi_bat_mk1", "ahi_bat_mk2", "ahi_bat_mk3",
            "omd_bat_100", "omd_bat_200", "omd_bat_300",
            "cvs_bat_r1", "cvs_bat_r2",
            "rn_bat_g1", "rn_bat_g2");

    private static final Logger LOGGER = LoggerFactory.getLogger("corvus/batteries");
    private static final Map<String, Model> BUILTIN = defaults();
    private static volatile Map<String, Model> models = BUILTIN;
    private static volatile int reloads;

    /** Knockback-resistance modifier id, stable so re-fitting a battery replaces rather than stacks. */
    private static final UUID MASS_MODIFIER = UUID.nameUUIDFromBytes("corvus:battery_mass".getBytes(StandardCharsets.UTF_8));

    public record Model(String id, String vendor, int tier, int capacity, int maxReceive, double mass,
                        double knockbackResistanceBonus, double mountCapacityBonus, double leakChance,
                        double leakFraction) {}

    private Batteries() {}

    static List<RegistryObject<Item>> register(DeferredRegister<Item> items) {
        // stacksTo(1): a battery carries its own charge, so two cells must never merge into one stack.
        return IDS.stream()
                .map(id -> items.<Item>register(id, () -> new BatteryItem(new Item.Properties().stacksTo(1))))
                .toList();
    }

    private static ResourceLocation key(String id) {
        return new ResourceLocation(Corvus.ID, id);
    }

    private static Map<String, Model> defaults() {
        Map<String, Model> result = new LinkedHashMap<>();
        for (String id : IDS) {
            String path = "/data/" + Corvus.ID + "/batteries/" + id + ".json";
            try (var stream = Batteries.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing built-in battery " + path);
                result.put(id, parse(id, JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()));
            } catch (java.io.IOException error) {
                throw new IllegalStateException(id, error);
            }
        }
        return Map.copyOf(result);
    }

    static Model parse(String id, JsonObject o) {
        String vendor = o.get("vendor").getAsString();
        Model model = new Model(id, vendor, o.get("tier").getAsInt(), o.get("capacity_fe").getAsInt(),
                o.get("max_receive").getAsInt(), o.get("mass").getAsDouble(),
                o.get("knockback_resistance_bonus").getAsDouble(),
                o.get("mount_capacity_bonus").getAsDouble(),
                o.has("leak_chance_per_10s") ? o.get("leak_chance_per_10s").getAsDouble() : 0D,
                o.has("leak_fraction") ? o.get("leak_fraction").getAsDouble() : 0D);
        boolean valid = "battery".equals(o.get("slot_type").getAsString())
                && model.tier >= 1 && model.tier <= 3
                && model.capacity > 0 && model.maxReceive > 0
                && model.mass > 0 && Double.isFinite(model.mass)
                && model.leakChance >= 0 && model.leakChance <= 1
                && model.leakFraction >= 0 && model.leakFraction <= 1
                && model.knockbackResistanceBonus >= 0 && model.knockbackResistanceBonus <= 1
                && model.mountCapacityBonus >= 0 && Double.isFinite(model.mountCapacityBonus)
                && (vendor.equals("rust") || model.leakChance == 0D);
        if (!valid) throw new IllegalArgumentException("Invalid battery " + id);
        return model;
    }

    static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "batteries") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager,
                                 ProfilerFiller profiler) {
                Map<String, Model> next = new LinkedHashMap<>(BUILTIN);
                for (String id : IDS) {
                    JsonElement entry = entries.get(key(id));
                    if (entry == null || !entry.isJsonObject()) continue;
                    try {
                        next.put(id, parse(id, entry.getAsJsonObject()));
                    } catch (RuntimeException invalid) {
                        LOGGER.error("Battery {} is invalid; keeping the built-in model", id, invalid);
                    }
                }
                models = Map.copyOf(next);
                reloads++;
            }
        });
    }

    public static int reloadCount() {
        return reloads;
    }

    /** Null when the stack is not one of our batteries. */
    public static Model model(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BatteryItem)) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return Corvus.ID.equals(id.getNamespace()) ? models.get(id.getPath()) : null;
    }

    /** The model of the battery fitted in a controller (or of the controller itself). */
    public static Model installed(ItemStack controller) {
        return model(Hardware.battery(controller));
    }

    /** Tier of a battery stack, or 0 when it is not a battery. */
    public static int tier(ItemStack stack) {
        Model model = model(stack);
        return model == null ? 0 : model.tier();
    }

    static void tickDeployed(DroneEntity drone, ItemStack controller) {
        Model model = installed(controller);
        AttributeInstance resistance = drone.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (resistance != null) {
            if (model == null || model.knockbackResistanceBonus == 0D) {
                resistance.removeModifier(MASS_MODIFIER);
            } else if (resistance.getModifier(MASS_MODIFIER) == null
                    || resistance.getModifier(MASS_MODIFIER).getAmount() != model.knockbackResistanceBonus) {
                resistance.removeModifier(MASS_MODIFIER);
                resistance.addTransientModifier(new AttributeModifier(MASS_MODIFIER, "corvus:battery_mass",
                        model.knockbackResistanceBonus, AttributeModifier.Operation.ADDITION));
            }
        }
        if (model != null && drone.tickCount % 200 == 0 && model.leakChance > 0D
                && drone.getRandom().nextDouble() < model.leakChance) {
            int before = Hardware.energy(controller);
            int after = before - (int) Math.ceil(before * model.leakFraction);
            Hardware.setEnergy(controller, after);
            LOGGER.debug("Battery {} leaked {} FE", model.id(), before - after);
        }
    }

    /** Stored FE; an unwritten stack counts as full, matching the 1.21 behaviour. */
    public static int energy(ItemStack cell, Model model) {
        if (model == null) return 0;
        CorvusData data = CorvusData.of(cell);
        return data.contains(CorvusKeys.ENERGY)
                ? Mth.clamp(data.getInt(CorvusKeys.ENERGY), 0, model.capacity())
                : model.capacity();
    }

    /** Per-tick receive ceiling for this model, after the server-wide multiplier. */
    public static int receiveRate(Model model) {
        return receiveRate(model, ServerOptions.chargeMultiplier());
    }

    /** Pure scaling so the smoke harness can check the arithmetic without mutating live config. */
    public static int receiveRate(Model model, double multiplier) {
        if (model == null) return 0;
        return (int) Mth.clamp(Math.round(model.maxReceive * multiplier), 1, 1_000_000);
    }

    /**
     * Accepts at most the model's receive rate per tick and never overfills.
     *
     * <p>Server thread only: the budget key ({@link CorvusKeys#CHARGE_TICK}) is game time, and writing
     * item NBT off-thread would race the inventory. The budget stays on the physical cell, so a cell
     * that is charged in the dock and then moved into a terminal keeps its per-tick accounting.</p>
     */
    public static int charge(ItemStack controller, int amount, boolean simulate) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || amount <= 0) return 0;
        ItemStack cell = Hardware.battery(controller);
        Model model = model(cell);
        if (model == null) return 0;
        CorvusData data = CorvusData.of(cell);
        long now = server.overworld().getGameTime();
        int rate = receiveRate(model);
        int used = data.contains(CorvusKeys.CHARGE_TICK) && data.getLong(CorvusKeys.CHARGE_TICK) == now
                ? Mth.clamp(data.getInt(CorvusKeys.CHARGE_RECEIVED), 0, rate)
                : 0;
        int before = energy(cell, model);
        int accepted = Math.min(amount, Math.min(rate - used, model.capacity() - before));
        if (accepted > 0 && !simulate) {
            data.setInt(CorvusKeys.ENERGY, before + accepted)
                    .setLong(CorvusKeys.CHARGE_TICK, now)
                    .setInt(CorvusKeys.CHARGE_RECEIVED, used + accepted);
            Hardware.storeCell(controller, cell);
        }
        return Math.max(0, accepted);
    }
}
