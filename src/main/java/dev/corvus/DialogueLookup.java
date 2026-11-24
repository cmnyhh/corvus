package dev.corvus;

import java.util.WeakHashMap;
import java.util.List;
import java.util.Locale;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Answers for the three {@code lookup.*} intents.
 *
 * <p>1.21 has two dedicated classes ({@code DialogueLookup}, {@code DialogueGunLookup}). The 1.20.1
 * port folds them into one small class because the gun half has no TaCZ to ask; the recipe and
 * advancement halves are real server data lookups.</p>
 */
final class DialogueLookup {
    private static final int MAX_RESULTS = 3;
    private static final WeakHashMap<ServerPlayer, String> LAST = new WeakHashMap<>();

    static String answer(ServerPlayer player, ItemStack controller, String intent, String question) {
        return switch (intent) {
            case "lookup.gun" -> gun(controller);
            case "lookup.recipe" -> recipe(player, question);
            case "lookup.advancement" -> advancement(player);
            default -> "";
        };
    }

    private static String gun(ItemStack controller) {
        if (!CombatSlots.available()) {
            return "1.20.1 版还没有武器接口，读不到枪械数据。";
        }
        ItemStack gun = CombatSlots.mounted(controller);
        return gun.isEmpty() ? "没有挂枪。" : "挂着 " + gun.getHoverName().getString() + "。";
    }

    /**
     * Keyword search over the server's recipe list. The question is normalised the same way intent
     * matching does it, then every token of two or more characters is tried against the result
     * item's own name; the search stops at {@value #MAX_RESULTS} hits so a line stays readable.
     */
    private static String recipe(ServerPlayer player, String question) {
        String query = LocalDialogue.normalize(question);
        if (query.isEmpty()) return "想查什么配方？说出物品名就行。";
        var registry = player.serverLevel().registryAccess();
        var hits = new java.util.ArrayList<String>();
        for (Recipe<?> recipe : player.serverLevel().getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem(registry);
            if (result.isEmpty()) continue;
            String name = LocalDialogue.normalize(result.getHoverName().getString());
            String id = LocalDialogue.normalize(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            if ((!name.isEmpty() && (query.contains(name) || name.contains(query)))
                    || (!id.isEmpty() && query.contains(id))) {
                hits.add(result.getHoverName().getString());
                if (hits.size() >= MAX_RESULTS) break;
            }
        }
        if (hits.isEmpty()) return "配方表里没有这个名字。换个说法试试。";
        return "配方表里有：" + String.join("、", hits) + "。";
    }

    private static String advancement(ServerPlayer player) {
        String last = LAST.get(player);
        List<Advancement> all = List.copyOf(player.serverLevel().getServer().getAdvancements().getAllAdvancements());
        for (Advancement advancement : all) {
            var display = advancement.getDisplay();
            if (display == null || display.isHidden()) continue;
            if (player.getAdvancements().getOrStartProgress(advancement).isDone()) continue;
            String title = display.getTitle().getString();
            if (advancement.getId().toString().equals(last)) continue;
            LAST.put(player, advancement.getId().toString());
            return "还没做完：" + title + "。";
        }
        return "当前的进度都做完了。";
    }

    private DialogueLookup() {}
}
