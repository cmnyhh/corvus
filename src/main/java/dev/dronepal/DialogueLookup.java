package dev.dronepal;

import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Queries the same registries and pack indices used by the running server. */
final class DialogueLookup {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    static ResourceLocation namedId(String question) {
        var match = IDENTIFIER.matcher(question.toLowerCase(Locale.ROOT));
        return match.find() ? ResourceLocation.tryParse(match.group()) : null;
    }
    static String answer(ServerPlayer player, ItemStack controller, String intent, String text) {
        var id = namedId(text);
        return switch (intent) {
            case "lookup.recipe" -> recipe(player, id, text);
            case "lookup.advancement" -> advancement(player, id);
            case "lookup.gun" -> CombatSlots.available() ? DialogueGunLookup.answer(player, controller, id, text) : "本地未安装 TaCZ。";
            default -> "没有这项本地查询。";
        };
    }
    private static String recipe(ServerPlayer player, ResourceLocation id, String text) {
        var world = player.serverLevel();
        var recipes = world.getRecipeManager();
        if (id == null) {
            String name = text.replaceAll("(?i)配方|怎么合成|怎么做|合成表|recipe", "").strip();
            id = switch (name) {
                case "火把" -> ResourceLocation.withDefaultNamespace("torch");
                case "工作台" -> ResourceLocation.withDefaultNamespace("crafting_table");
                case "" -> player.getMainHandItem().isEmpty() ? null : BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
                default -> ResourceLocation.tryParse(name);
            };
        }
        if (id == null) return "请手持物品，或输入“配方 minecraft:torch”。";
        var key = id;
        var found = recipes.byKey(key);
        if (found.isEmpty()) found = recipes.getRecipes().stream().filter(r -> {
            var out = r.value().getResultItem(world.registryAccess());
            return !out.isEmpty() && BuiltInRegistries.ITEM.getKey(out.getItem()).equals(key);
        }).min(Comparator.comparing(RecipeHolder::id));
        if (found.isEmpty()) return "没有找到本地配方：" + key;
        var holder = found.get();
        var recipe = holder.value();
        var materials = new LinkedHashMap<String,Integer>();
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            var options = ingredient.getItems();
            if (options.length == 0) continue;
            String label = BuiltInRegistries.ITEM.getKey(options[0].getItem()).toString() + (options.length > 1 ? "（或同组材料）" : "");
            materials.merge(label, 1, Integer::sum);
        }
        var list = new StringJoiner("、");
        materials.forEach((name, amount) -> list.add(name + "×" + amount));
        var out = recipe.getResultItem(world.registryAccess());
        return "配方 " + holder.id() + "，产物 " + BuiltInRegistries.ITEM.getKey(out.getItem()) + "×" + out.getCount()
            + (materials.isEmpty() ? "，材料请查阅工作站。" : "，材料 " + list + "。");
    }
    private static String advancement(ServerPlayer player, ResourceLocation id) {
        var manager = player.serverLevel().getServer().getAdvancements();
        if (id != null) {
            var holder = manager.get(id);
            if (holder == null || holder.value().display().isEmpty() || holder.value().display().get().isHidden()) return "没有可显示的进度：" + id;
            return "进度 " + id + (player.getAdvancements().getOrStartProgress(holder).isDone() ? " 已完成。" : " 未完成。");
        }
        return manager.getAllAdvancements().stream().filter(h -> h.value().display().isPresent() && !h.value().display().get().isHidden())
            .filter(h -> !player.getAdvancements().getOrStartProgress(h).isDone())
            .filter(h -> h.value().parent().map(manager::get).map(p -> player.getAdvancements().getOrStartProgress(p).isDone()).orElse(true))
            .min(Comparator.comparing(AdvancementHolder::id)).map(h -> "下一项进度 " + h.id() + "：" + h.value().display().get().getDescription().getString())
            .orElse("当前可见进度均已完成。");
    }
}
