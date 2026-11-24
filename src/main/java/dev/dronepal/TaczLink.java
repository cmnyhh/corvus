package dev.dronepal;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;

/** Loaded only by the optional TaCZ integration. All reported quantities come from game stacks. */
final class TaczLink {
    private record Sample(ResourceLocation gun, int loaded, int reserve) {}
    private static final Map<ServerPlayer, Sample> previous = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> nextHit = new WeakHashMap<>();
    /** Reserve rounds at or below this trigger the low-ammo line (roughly one magazine). */
    private static final int LOW_RESERVE = 6;
    private static final Map<ServerPlayer, Long> nextAmmoCry = new WeakHashMap<>();

    static void register() {
        NeoForge.EVENT_BUS.addListener((EntityHurtByGunEvent.Post event) -> {
            if (event.getLogicalSide().isServer() && event.getHurtEntity() instanceof LivingEntity target && target.isAlive()) {
                hit(event.getAttacker(), target, false);
            }
        });
        NeoForge.EVENT_BUS.addListener((EntityKillByGunEvent event) -> {
            if (event.getLogicalSide().isServer()) hit(event.getAttacker(), event.getKilledEntity(), true);
        });
    }
    private static Hardware.Module active(ServerPlayer player, ItemStack terminal) {
        if (terminal.isEmpty() || !player.isAlive() || player.isSpectator() || !Hardware.online(terminal, "link")) return null;
        // The wheel's link switch silences reports and delivery together; otherwise it would be a dead toggle.
        if (DroneRemote.disabled(terminal, "link")) return null;
        return Hardware.installed(terminal).stream().filter(m -> m.family().equals("link")).findFirst().orElse(null);
    }
    private static boolean permits(Hardware.Module module, String key) {
        return module != null && module.params().has(key) && module.params().get(key).getAsBoolean();
    }
    private static boolean compatible(ItemStack gun, ItemStack part) {
        if (part.getItem() instanceof IAmmo loose) return loose.isAmmoOfGun(gun, part);
        return part.getItem() instanceof IAmmoBox box && box.getAmmoCount(part) > 0 && box.isAmmoBoxOfGun(gun, part);
    }
    static boolean supply(ServerPlayer player) {
        ItemStack controller = Hardware.controller(player);
        if (!permits(active(player, controller), "deliver_mags")) return false;
        ItemStack gun = player.getMainHandItem();
        if (!TaczCombat.isGun(gun)) return false;
        int moved = CarryLight.deliverMatching(player, controller, stack -> compatible(gun, stack));
        if (moved > 0) player.displayClientMessage(Component.translatable("dronepal.link.supplied", moved), true);
        return moved > 0;
    }
    static void second(ServerPlayer player, ItemStack controller) {
        var module = active(player, controller);
        ItemStack held = player.getMainHandItem();
        if (!permits(module, "report_ammo") || !TaczCombat.isGun(held)) { previous.remove(player); return; }
        IGun gun = IGun.getIGunOrNull(held);
        int reserve = 0;
        for (ItemStack item : player.getInventory().items) if (compatible(held, item)) {
            reserve += item.getItem() instanceof IAmmoBox box ? box.getAmmoCount(item) : item.getCount();
        }
        var sample = new Sample(gun.getGunId(held), gun.getCurrentAmmoCount(held) + (gun.hasBulletInBarrel(held) ? 1 : 0), reserve);
        var last = previous.put(player, sample);
        boolean armed = DroneRemote.barksEnabled(controller);
        long now = player.serverLevel().getGameTime();
        // Ammo is not narrated on every tick: only a finished reload or a reserve running low gets a line,
        // and that line still obeys the chip's master switch.
        if (sample.loaded == 0 && reserve == 0) {
            if (armed && (last == null || !sample.equals(last))) DialogueServer.signal(player, "no_ammo");
        } else if (armed && last != null && sample.loaded > last.loaded && sample.reserve < last.reserve) {
            if (now >= nextAmmoCry.getOrDefault(player, 0L) && player.getRandom().nextFloat() < 0.75F) {
                nextAmmoCry.put(player, now + 200);
                DialogueServer.signal(player, "reload_done");
            }
        } else if (armed && reserve > 0 && reserve <= LOW_RESERVE) {
            if (now >= nextAmmoCry.getOrDefault(player, 0L) && player.getRandom().nextFloat() < 0.6F) {
                nextAmmoCry.put(player, now + 400);
                DialogueServer.signal(player, "low_ammo");
            }
        }
        if (sample.loaded == 0 && reserve == 0 && permits(module, "auto_deliver")) supply(player);
    }

    private static void hit(LivingEntity attacker, LivingEntity target, boolean kill) {
        ServerPlayer owner = attacker instanceof ServerPlayer player ? player
                : attacker instanceof DroneEntity drone && drone.owner != null && drone.getServer() != null
                ? drone.getServer().getPlayerList().getPlayer(drone.owner) : null;
        if (owner == null) return;
        var terminal = Hardware.controller(owner);
        if (attacker instanceof DroneEntity drone && drone.controller() != terminal) return;
        var module = active(owner, terminal);
        if (!permits(module, "report_hits")) return;
        long now = owner.serverLevel().getGameTime();
        if (!kill && now < nextHit.getOrDefault(owner, 0L)) return;
        nextHit.put(owner, now + 10);
        var report = Component.translatable(kill ? "dronepal.link.kill" : "dronepal.link.hit", target.getDisplayName());
        if (!kill && permits(module, "armor_hint") && target.getArmorValue() > 0) report.append(Component.translatable("dronepal.link.armor", target.getArmorValue()));
        owner.displayClientMessage(report, true);
    }
}
