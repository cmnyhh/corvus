package dev.dronepal.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import dev.dronepal.DroneCombat;
import dev.dronepal.DroneEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both single-hit and piercing TaCZ projectiles use this candidate intersection. */
@Pseudo
@Mixin(targets = "com.tacz.guns.util.EntityUtil", remap = false)
abstract class TaczPlayerCollisionMixin {
    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true)
    private static void dronepal$filterPlayer(Projectile projectile, Entity candidate, Vec3 start, Vec3 end,
                                              CallbackInfoReturnable<?> hit) {
        if (candidate instanceof Player player && projectile instanceof EntityKineticBullet
                && projectile.getOwner() instanceof DroneEntity drone && !DroneCombat.mayHitPlayer(drone, player)) {
            hit.setReturnValue(null);
        }
    }
}
