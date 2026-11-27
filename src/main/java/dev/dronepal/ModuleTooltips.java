package dev.dronepal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Send display components after login and reload so remote players see their server's actual values. */
final class ModuleTooltips {
    record Catalog(Map<ResourceLocation, List<Component>> entries) implements CustomPacketPayload {
        static final Type<Catalog> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "module_tooltips"));
        static final StreamCodec<RegistryFriendlyByteBuf, Catalog> CODEC = StreamCodec.of((buf, catalog) -> {
            buf.writeMap(catalog.entries, (b, id) -> b.writeResourceLocation(id), (b, lines) ->
                    b.writeCollection(lines, (out, line) -> ComponentSerialization.STREAM_CODEC.encode(buf, line)));
        }, buf -> new Catalog(Map.copyOf(buf.readMap((b) -> b.readResourceLocation(), b ->
                List.copyOf(b.readList(in -> ComponentSerialization.STREAM_CODEC.decode(buf)))))));
        public Type<Catalog> type() { return TYPE; }
    }
    private static volatile Catalog remote;
    static void clearRemote() { remote = null; }
    static void accept(Catalog catalog) { remote = catalog; }
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Catalog.TYPE, Catalog.CODEC, (catalog, context) -> accept(catalog));
    }
    static void sync(OnDatapackSyncEvent event) {
        Map<ResourceLocation, List<Component>> entries = new HashMap<>();
        Hardware.tooltipModules().forEach((id, module) -> entries.put(id, describe(module)));
        Catalog payload = new Catalog(Map.copyOf(entries));
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }
    static void append(ItemStack stack, Item.TooltipContext context, List<Component> lines) {
        var id = Hardware.productId(stack);
        Catalog received = remote;
        if (received != null && (context.level() == null || context.level().isClientSide)) {
            lines.addAll(received.entries().getOrDefault(id, List.of(Component.translatable("item.huhu_dronepal.unknown_module"))));
            return;
        }
        var module = Hardware.tooltipModules().get(id);
        if (module != null) lines.addAll(describe(module));
    }
    private static Component line(String key, Object... args) {
        return Component.translatable("dronepal.tip." + key, args).withStyle(ChatFormatting.GRAY);
    }
    private static String n(double value) { return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private static boolean flag(Hardware.Module m, String key) { return m.params().has(key) && m.params().get(key).getAsBoolean(); }
    static List<Component> describe(Hardware.Module m) {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("dronepal.tip.summary." + m.family()).withStyle(ChatFormatting.AQUA));
        switch (m.family()) {
            case "combat" -> {
                lines.add(line("combat", Math.clamp(m.integer("range"),1,48), Math.clamp(m.integer("burst"),1,8), n(Math.clamp(m.decimal("spread_deg") * m.trait("spread_multiplier",1),0,45))));
                lines.add(line("damage", n(Math.clamp(m.decimal("damage_mult"),0,4)), Math.clamp(m.integer("cooldown_ticks"),2,100)));
                if (m.tier() == 3) lines.add(line("modes"));
            }
            case "scan" -> {
                lines.add(line("scan", Math.clamp(Math.round(m.integer("radius") * m.trait("scan_radius_multiplier",1)),1,48), n(Math.clamp(m.integer("mark_ticks"),20,1200)/20d)));
                if (flag(m,"hostile")) lines.add(line("hostiles"));
                if (flag(m,"ore")) lines.add(line("common".equals(m.params().get("ore_filter").getAsString()) ? "common_ores" : "all_ores"));
            }
            case "carry" -> {
                lines.add(line("carry", Math.clamp(m.integer("slots"),1,27), Math.clamp(m.integer("pickup_radius"),1,16)));
                if (flag(m,"sort")) lines.add(line("sort"));
                if (flag(m,"auto_deliver")) lines.add(line("auto_cargo"));
            }
            case "medic" -> {
                lines.add(line("medic", n(20 * Math.clamp(m.decimal("heal_per_tick"),0,1) * m.trait("heal_rate_multiplier",1)), n(Math.clamp(m.decimal("max_heal_per_use"),0,40)), n(Math.clamp(m.integer("cooldown_ticks"),20,72000)/20d)));
                lines.add(line("cure", n(Math.clamp(m.integer("cure_effect_every_ticks"),20,72000)/20d)));
                if (m.requiresSupply()) lines.add(line("supply"));
                if (m.params().has("bonus") && !m.params().get("bonus").isJsonNull() && "resistance_0_60t".equals(m.params().get("bonus").getAsString())) lines.add(line("resist"));
                if (m.params().has("side_effect_chance")) lines.add(line("nausea", n(m.decimal("side_effect_chance")*100), n(m.integer("side_effect_ticks")/20d)));
            }
            case "light" -> {
                lines.add(line("light", Math.clamp(m.integer("light_level"),0,15), Math.clamp(m.integer("radius"),1,16)));
                lines.add(line(flag(m,"underwater") ? "submerged" : "dry"));
                if (flag(m,"flicker")) lines.add(line("flicker"));
            }
            case "link" -> {
                if (flag(m,"report_ammo")) lines.add(line("ammo"));
                if (flag(m,"report_hits")) lines.add(line("hits"));
                if (flag(m,"deliver_mags")) lines.add(line("deliver"));
                if (flag(m,"auto_deliver")) lines.add(line("auto_ammo"));
                if (flag(m,"armor_hint")) lines.add(line("armor"));
            }
            case "solar" -> {
                lines.add(line("solar", Math.clamp(m.integer("clear"),0,500), Math.clamp(m.integer("rain"),0,500), Math.clamp(m.integer("night"),0,500)));
                if (m.params().has("output_jitter")) lines.add(line("jitter", n(m.decimal("output_jitter")*100)));
            }
        }
        // The hover used to print one figure because every module drew it all the time. Now the two
        // halves are printed whenever they differ, so "0 FE/t standing by" is not a surprise in the HUD.
        double idle = m.idleWatts(), active = m.activeWatts();
        if (m.family().equals("medic")) {
            double treating = active + Math.clamp(m.integer("active_drain_watts") - m.drainActive(), 0, 10000);
            lines.add(line("active_draw", n(idle), n(treating)));
        } else if (Math.abs(idle - active) > 1e-9) {
            lines.add(line("idle_active", n(idle), n(active)));
        } else {
            lines.add(line("draw", n(active)));
        }
        for (String trait : List.of("drain_multiplier", "spread_multiplier", "scan_radius_multiplier", "heal_rate_multiplier")) {
            double value = m.trait(trait, 1);
            if (value != 1) lines.add(line(trait, n(value)));
        }
        if (m.params().has("malfunction_chance")) lines.add(line("action_fault", n(m.decimal("malfunction_chance")*100), n(m.integer("malfunction_ticks")/20d)));
        else if (m.trait("malfunction_chance",0)>0) lines.add(line("timed_fault", n(m.trait("malfunction_chance",0)*100), n(m.trait("malfunction_ticks",60)/20d)));
        return List.copyOf(lines);
    }
}
