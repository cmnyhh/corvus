package dev.corvus.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import dev.corvus.DroneCombat;
import dev.corvus.DroneEntity;
import dev.corvus.TaczPlayerGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A drone round never consumes a player it is not allowed to shoot.
 *
 * <p>Line-for-line port of the 1.21.1 mixin ({@code dev.dronepal.mixin.TaczPlayerCollisionMixin}).
 * The 1.20.1 target in TaCZ <b>1.1.8</b> was verified against the shipped jar before writing this
 * file:</p>
 *
 * <pre>
 * javap -p -classpath tacz-1.20.1-1.1.8.jar com.tacz.guns.util.EntityUtil
 *   protected static com.tacz.guns.entity.EntityKineticBullet$EntityResult
 *       getHitResult(Projectile, Entity, Vec3, Vec3);
 * </pre>
 *
 * <p>It is the single candidate-intersection primitive: both the single-hit path
 * ({@code findEntityOnPath}) and the piercing path ({@code findEntitiesOnPath}) call it and both
 * treat a {@code null} answer as "this candidate is not a hit" (the method already returns
 * {@code null} for a candidate whose box the ray misses). Filtering here therefore removes the
 * candidate from <em>selection</em> — it does <b>not</b> cancel the round and it is <b>not</b> a
 * damage gate: the bullet keeps travelling and can still hit whatever sits behind the player, which
 * is exactly the 1.21.1 semantics ("the owner is not a shield, it is transparent to its own drone's
 * rounds").</p>
 *
 * <p>{@link DroneCombat#mayHitPlayer} is the same predicate 1.21.1 uses ({@code
 * Engagement.permitsHit}: the player must be the drone's <em>current</em> legal PvP target and must
 * not be friend-protected), so an owner, a teammate/pet-holder or any unrelated player is skipped,
 * while a genuinely hostile, opted-in target is still hittable.</p>
 *
 * <p>{@code @Pseudo} keeps the mod loadable without TaCZ: a missing target class is a no-op rather
 * than a mixin failure.</p>
 *
 * <p>The only addition over the 1.21.1 source is the {@link TaczPlayerGuard} call (a class in
 * {@code dev.corvus}, deliberately not in this mixin package), which counts the
 * skipped candidates for the dev smoke and has no effect on the decision.</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.util.EntityUtil", remap = false)
abstract class TaczPlayerCollisionMixin {
    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true)
    private static void dronepal$filterPlayer(Projectile projectile, Entity candidate, Vec3 start, Vec3 end,
                                              CallbackInfoReturnable<?> hit) {
        if (candidate instanceof Player player && projectile instanceof EntityKineticBullet
                && projectile.getOwner() instanceof DroneEntity drone && !DroneCombat.mayHitPlayer(drone, player)) {
            TaczPlayerGuard.filtered(drone, player, start, end);
            hit.setReturnValue(null);
        }
    }
}
