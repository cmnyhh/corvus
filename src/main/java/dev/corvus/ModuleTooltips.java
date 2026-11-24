package dev.corvus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Hover text for a module: the numbers the module actually runs with, including the vendor trait
 * multipliers, so a player never has to cross-reference the manual.
 *
 * <p>1.20.1 renders tooltips through {@code Item#appendHoverText}; there is no tooltip codec. The
 * numbers come from the same definitions the gameplay code reads, so a datapack-tuned module reads
 * back its tuned values.</p>
 */
public final class ModuleTooltips {
    private ModuleTooltips() {}

    public static void append(ItemStack stack, List<Component> lines) {
        Hardware.Module module = Hardware.definition(stack);
        if (module == null) {
            lines.add(Component.translatable("item.dronepal.unknown_module").withStyle(ChatFormatting.RED));
            return;
        }
        lines.addAll(describe(module));
    }

    private static Component line(String key, Object... args) {
        return Component.translatable("dronepal.tip." + key, args).withStyle(ChatFormatting.GRAY);
    }

    private static String n(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public static List<Component> describe(Hardware.Module module) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("dronepal.tip.summary." + module.family()).withStyle(ChatFormatting.AQUA));
        switch (module.family()) {
            case "combat" -> {
                lines.add(line("combat",
                        Mth.clamp(module.integer("range"), 1, 48),
                        Mth.clamp(module.integer("burst"), 1, 8),
                        n(Mth.clamp(module.decimal("spread_deg") * module.trait("spread_multiplier", 1D), 0D, 45D))));
                lines.add(line("damage",
                        n(Mth.clamp(module.decimal("damage_mult"), 0D, 4D)),
                        Mth.clamp(module.integer("cooldown_ticks"), 2, 100)));
                if (module.tier() == 3) lines.add(line("modes"));
            }
            case "scan" -> {
                lines.add(line("scan",
                        Mth.clamp(Math.round(module.integer("radius") * module.trait("scan_radius_multiplier", 1D)), 1, 48),
                        n(Mth.clamp(module.integer("mark_ticks"), 20, 1200) / 20D)));
                if (module.flag("hostile")) lines.add(line("hostiles"));
                if (module.flag("ore")) {
                    boolean common = module.params().has("ore_filter")
                            && "common".equals(module.params().get("ore_filter").getAsString());
                    lines.add(line(common ? "common_ores" : "all_ores"));
                }
            }
            case "carry" -> {
                lines.add(line("carry",
                        Mth.clamp(module.integer("slots"), 1, 27),
                        Mth.clamp(module.integer("pickup_radius"), 1, 16)));
                if (module.flag("sort")) lines.add(line("sort"));
                if (module.flag("auto_deliver")) lines.add(line("auto_cargo"));
            }
            case "medic" -> {
                lines.add(line("medic",
                        n(20 * Mth.clamp(module.decimal("heal_per_tick"), 0F, 1F) * module.trait("heal_rate_multiplier", 1D)),
                        n(Mth.clamp(module.decimal("max_heal_per_use"), 0F, 40F)),
                        n(Mth.clamp(module.integer("cooldown_ticks"), 20, 72000) / 20D)));
                if (module.params().has("cure_effect_every_ticks")) {
                    lines.add(line("cure", n(Mth.clamp(module.integer("cure_effect_every_ticks"), 20, 72000) / 20D)));
                }
                if (module.requiresSupply()) lines.add(line("supply"));
                if (module.params().has("bonus") && !module.params().get("bonus").isJsonNull()
                        && "resistance_0_60t".equals(module.params().get("bonus").getAsString())) {
                    lines.add(line("resist"));
                }
                if (module.params().has("side_effect_chance")) {
                    lines.add(line("nausea", n(module.decimal("side_effect_chance") * 100D),
                            n(module.integer("side_effect_ticks") / 20D)));
                }
            }
            case "light" -> {
                lines.add(line("light",
                        Mth.clamp(module.integer("light_level"), 0, 15),
                        Mth.clamp(module.integer("radius"), 1, 16)));
                lines.add(line(module.flag("underwater") ? "submerged" : "dry"));
                if (module.flag("flicker")) lines.add(line("flicker"));
            }
            case "link" -> {
                if (module.flag("report_ammo")) lines.add(line("ammo"));
                if (module.flag("report_hits")) lines.add(line("hits"));
                if (module.flag("deliver_mags")) lines.add(line("deliver"));
                if (module.flag("auto_deliver")) lines.add(line("auto_ammo"));
                if (module.flag("armor_hint")) lines.add(line("armor"));
            }
            case "solar" -> {
                lines.add(line("solar",
                        Mth.clamp(module.integer("clear"), 0, 500),
                        Mth.clamp(module.integer("rain"), 0, 500),
                        Mth.clamp(module.integer("night"), 0, 500)));
                if (module.params().has("output_jitter")) {
                    lines.add(line("jitter", n(module.decimal("output_jitter") * 100D)));
                }
            }
            default -> { }
        }
        double watts = module.draw(20) / 20D;
        if (module.family().equals("medic")) {
            double active = watts + Mth.clamp(module.integer("active_drain_watts") - module.drain(), 0, 10000);
            lines.add(line("active_draw", n(watts), n(active)));
        } else {
            lines.add(line("draw", n(watts)));
        }
        for (String trait : List.of("drain_multiplier", "spread_multiplier",
                "scan_radius_multiplier", "heal_rate_multiplier")) {
            double value = module.trait(trait, 1D);
            if (value != 1D) lines.add(line(trait, n(value)));
        }
        if (module.params().has("malfunction_chance")) {
            lines.add(line("action_fault", n(module.decimal("malfunction_chance") * 100D),
                    n(module.integer("malfunction_ticks") / 20D)));
        } else if (module.trait("malfunction_chance", 0D) > 0D) {
            lines.add(line("timed_fault", n(module.trait("malfunction_chance", 0D) * 100D),
                    n(module.trait("malfunction_ticks", 60D) / 20D)));
        }
        return List.copyOf(lines);
    }
}
