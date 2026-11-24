package dev.dronepal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class Batteries {
    static final List<String> IDS = List.of("ahi_bat_mk1", "ahi_bat_mk2", "ahi_bat_mk3", "omd_bat_100", "omd_bat_200", "omd_bat_300", "cvs_bat_r1", "cvs_bat_r2", "rn_bat_g1", "rn_bat_g2");
    private static final Map<String, Model> BUILTIN = defaults();
    private static volatile Map<String, Model> models = BUILTIN;
    public record Model(String id, String vendor, int tier, int capacity, int maxReceive, double mass, double knockbackResistanceBonus, double mountCapacityBonus, double leakChance, double leakFraction) {}
    private Batteries() {}

    static List<DeferredItem<Item>> register(DeferredRegister.Items items) {
        for (int tier = 1; tier <= 3; tier++) {
            items.addAlias(key("battery_mk" + tier), key("ahi_bat_mk" + tier));
        }
        return IDS.stream().map(id -> items.<Item>register(id, () -> new BatteryItem(new Item.Properties().stacksTo(1)))).toList();
    }
    private static ResourceLocation key(String id) { return ResourceLocation.fromNamespaceAndPath(Dronepal.ID, id); }
    private static Map<String, Model> defaults() {
        Map<String, Model> result = new LinkedHashMap<>();
        for (String id : IDS) {
            try (var stream = Batteries.class.getResourceAsStream("/data/huhu_dronepal/batteries/" + id + ".json")) {
                if (stream == null) throw new IllegalStateException("Missing battery " + id);
                result.put(id, parse(id, JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()));
            } catch (java.io.IOException error) { throw new IllegalStateException(id, error); }
        }
        return Map.copyOf(result);
    }
    private static Model parse(String id, JsonObject o) {
        String vendor = o.get("vendor").getAsString();
        Model m = new Model(id, vendor, o.get("tier").getAsInt(), o.get("capacity_fe").getAsInt(), o.get("max_receive").getAsInt(),
                o.get("mass").getAsDouble(), o.get("knockback_resistance_bonus").getAsDouble(), o.get("mount_capacity_bonus").getAsDouble(),
                o.has("leak_chance_per_10s") ? o.get("leak_chance_per_10s").getAsDouble() : 0,
                o.has("leak_fraction") ? o.get("leak_fraction").getAsDouble() : 0);
        if (!"battery".equals(o.get("slot_type").getAsString()) || m.tier < 1 || m.tier > 3 || m.capacity <= 0 || m.maxReceive <= 0
                || !(m.mass > 0 && Double.isFinite(m.mass)) || !(m.leakChance >= 0 && m.leakChance <= 1) || !(m.leakFraction >= 0 && m.leakFraction <= 1)
                || !(m.knockbackResistanceBonus >= 0 && m.knockbackResistanceBonus <= 1) || !(m.mountCapacityBonus >= 0 && Double.isFinite(m.mountCapacityBonus))
                || (!vendor.equals("rust") && m.leakChance != 0)) throw new IllegalArgumentException("Invalid battery " + id);
        return m;
    }
    static void reload(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "batteries") {
            @Override protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
                Map<String, Model> next = new LinkedHashMap<>(BUILTIN);
                for (String id : IDS) {
                    JsonElement entry = entries.get(key(id));
                    if (entry != null) next.put(id, parse(id, entry.getAsJsonObject()));
                }
                models = Map.copyOf(next);
            }
        });
    }
    public static Model model(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return Dronepal.ID.equals(id.getNamespace()) ? models.get(id.getPath()) : null;
    }
    public static Model installed(ItemStack stack) { return model(Hardware.battery(stack)); }
    static void tickDeployed(DroneEntity drone, ItemStack controller) {
        Model model = installed(controller);
        var attr = drone.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        var modifierId = key("battery_mass");
        if (attr != null) {
            if (model == null || model.knockbackResistanceBonus == 0) attr.removeModifier(modifierId);
            else attr.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(modifierId,
                    model.knockbackResistanceBonus, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        }
        if (model != null && drone.tickCount % 200 == 0 && model.leakChance > 0 && drone.getRandom().nextDouble() < model.leakChance) {
            int before = Hardware.energy(controller);
            Hardware.setEnergy(controller, before - (int)Math.ceil(before * model.leakFraction));
        }
    }
    static int energy(ItemStack cell, Model model) {
        if (model == null) return 0;
        var data = cell.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.contains("energy") ? Math.clamp(data.getInt("energy"), 0, model.capacity) : model.capacity;
    }
    /** Per-tick receive ceiling for this model, after the server-wide multiplier. */
    static int receiveRate(Model model) {
        return receiveRate(model, ServerOptions.chargeMultiplier());
    }
    /** Pure scaling so tests can check the arithmetic without mutating the live config. */
    static int receiveRate(Model model, double multiplier) {
        if (model == null) return 0;
        return (int)Math.clamp(Math.round(model.maxReceive * multiplier), 1, 1_000_000);
    }
    static int charge(ItemStack stack, int amount, boolean simulate) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || amount <= 0) return 0;
        var cell = Hardware.battery(stack);
        var model = model(cell);
        if (model == null) return 0;
        var data = cell.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        long now = server.overworld().getGameTime();
        int rate = receiveRate(model);
        int used = data.contains("charge_tick") && data.getLong("charge_tick") == now ? Math.clamp(data.getInt("charge_received"), 0, rate) : 0;
        int before = energy(cell, model);
        int accepted = Math.min(amount, Math.min(rate - used, model.capacity - before));
        if (accepted > 0 && !simulate) {
            // Keep the budget on the physical cell across dock, inventory, and terminal transfers.
            CustomData.update(DataComponents.CUSTOM_DATA, cell, nbt -> {
                nbt.putInt("energy", before + accepted);
                nbt.putLong("charge_tick", now);
                nbt.putInt("charge_received", used + accepted);
            });
            if (stack != cell) Hardware.storeCell(stack, cell);
        }
        return accepted;
    }
}
