package dev.dronepal;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Loaded only when the TaCZ mod is installed. */
final class TaczCombat {
    static boolean isGun(ItemStack s) { return s.getItem() instanceof AbstractGunItem gun && !gun.useDummyAmmo(s); }
    static String gunType(ItemStack stack) {
        if (!(stack.getItem() instanceof AbstractGunItem gun)) return "";
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> index.getPojo().getType()).orElse("");
    }
    // Convert gun-pack weight to the game's normalized frame capacity (six weight points per unit).
    static double mountLoad(ItemStack stack) {
        var gun = (AbstractGunItem) stack.getItem();
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> Math.max(0, index.getGunData().getWeight()) / 6.0).orElse(Double.POSITIVE_INFINITY);
    }
    static boolean isAmmo(ItemStack s) { return s.getItem() instanceof IAmmo || s.getItem() instanceof IAmmoBox; }
    static void draw(DroneEntity d) { IGunOperator.fromLivingEntity(d).draw(d::getMainHandItem); }
    static boolean shoot(DroneEntity d, float pitch, float yaw) {
        IGunOperator operator = IGunOperator.fromLivingEntity(d);
        // The two-argument overload uses TaCZ's current *relative* simulation timestamp.
        var result = operator.shoot(() -> pitch, () -> yaw);
        d.combat.lastResult = result.name();
        switch (result) {
            case SUCCESS -> { return true; }
            case NO_AMMO -> operator.reload();
            case NEED_BOLT -> operator.bolt();
            case NOT_DRAW -> draw(d);
            default -> { }
        }
        return false;
    }
    static void register() {
        TaczLink.register();
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (AmmoHitBlockEvent e) -> {
            if (e.getAmmo().getOwner() instanceof DroneEntity) {
                e.getAmmo().discard();
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ExplosionEvent.Start e) -> {
            if (e.getExplosion().getDirectSourceEntity() instanceof Projectile p && p.getOwner() instanceof DroneEntity) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (EntityHurtByGunEvent.Pre e) -> {
            if (!(e.getAttacker() instanceof DroneEntity drone) || e.getLogicalSide().isClient()) return;
            if (!(e.getHurtEntity() instanceof LivingEntity target) || !DroneCombat.validTarget(drone, target)) { e.setCanceled(true); return; }
            Hardware.installed(drone.controller()).stream().filter(m -> m.family().equals("combat")).findFirst()
                    .ifPresent(m -> e.setBaseAmount(e.getBaseAmount() * Math.clamp(m.decimal("damage_mult"), 0f, 4f)));
        });
    }
}
