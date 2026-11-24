package dev.corvus;

import java.util.WeakHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * TaCZ integration for 1.20.1 — <b>NOT PORTED</b>.
 *
 * <p>1.21 links against {@code com.tacz.guns.api.*}. This build has no TaCZ dependency (no 1.20.1
 * coordinate was declared in {@code build.gradle}, and adding one would gate every build on a
 * third-party maven), so every method here returns the "no gun" answer and nothing is fired. The
 * combat layer therefore runs its <em>degraded path</em>: policy selection, gimbal aiming, stow/deploy
 * and ally protection are live and testable; shooting, magazine transactions, ammo delivery and hit
 * reports are not.</p>
 *
 * <p>When a TaCZ artifact is added, the seven entry points below are the whole surface that needs a
 * real implementation, and {@link DroneCombat#fire} is the single call site:</p>
 * <ul>
 *   <li>{@link #isGun(ItemStack)}, {@link #isAmmo(ItemStack)}, {@link #gunType(ItemStack)},
 *       {@link #mountLoad(ItemStack)} — slot predicates, mirroring {@code TaczCombat} in 1.21</li>
 *   <li>{@link #draw(DroneEntity)}, {@link #shoot(DroneEntity, float, float)} — operator calls</li>
 *   <li>{@link #second(ServerPlayer, ItemStack)} — reserve/ammo telemetry and link-module supply</li>
 * </ul>
 */
public final class TaczLink {
    private static final Map<ServerPlayer, Long> nextReport = new WeakHashMap<>();

    static boolean isGun(ItemStack stack) {
        return false;
    }

    static boolean isAmmo(ItemStack stack) {
        return false;
    }

    static String gunType(ItemStack stack) {
        return "";
    }

    /** Gun-pack weight in normalized frame units; infinite means "cannot be judged". */
    static double mountLoad(ItemStack stack) {
        return Double.POSITIVE_INFINITY;
    }

    static void draw(DroneEntity drone) {
        // No draw animation without a gun backend.
    }

    static boolean shoot(DroneEntity drone, float pitch, float yaw) {
        return false;
    }

    static String gunId(ItemStack stack) {
        return "";
    }

    static ResourceLocation soundId(ItemStack stack) {
        return null;
    }

    /** Why the last {@link #shoot} attempt produced no round; the combat tick records this. */
    static String lastResult(DroneEntity drone) {
        return drone.combat.lastResult;
    }

    static void second(ServerPlayer player, ItemStack controller) {
        // Ammo counts and the link module's delivery loop belong to the gun backend.
    }

    private TaczLink() {}
}
