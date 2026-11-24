package dev.dronepal;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Isolated to keep servers without TaCZ loadable. */
final class DialogueGunLookup {
    static String answer(ServerPlayer player, ItemStack controller, ResourceLocation id, String text) {
        if (id == null) {
            String name = text.replaceAll("(?i)枪械数据|这枪|多少|咋用|伤害|gun|[？?]", "").strip().toLowerCase(java.util.Locale.ROOT);
            if (!name.isEmpty()) {
                var hits = TimelessAPI.getAllCommonGunIndex().stream().map(java.util.Map.Entry::getKey)
                    .filter(key -> key.getPath().contains(name)).sorted().limit(3).toList();
                if (hits.isEmpty()) return "本地枪包没有匹配项：" + name;
                if (hits.size() > 1) return "请指定枪包 ID：" + hits;
                id = hits.getFirst();
            } else {
                var stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof AbstractGunItem)) stack = Hardware.contents(controller).stream()
                    .filter(s -> s.getItem() instanceof AbstractGunItem).findFirst().orElse(ItemStack.EMPTY);
                if (!(stack.getItem() instanceof AbstractGunItem gun)) return "请手持游戏枪械，或输入“枪械数据 tacz:ak47”。";
                id = gun.getGunId(stack);
            }
        }
        var found = TimelessAPI.getCommonGunIndex(id);
        if (found.isEmpty()) return "本地枪包没有这项数据：" + id;
        var data = found.get().getGunData();
        return "游戏枪包 " + id + "，基础伤害 " + data.getBulletData().getDamageAmount() + "，弹种 " + data.getAmmoId()
            + "，基础弹容 " + data.getAmmoAmount() + "，射速 " + data.getRoundsPerMinute();
    }
}
