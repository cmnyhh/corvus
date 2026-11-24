package dev.corvus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * The gun/ammo slots of one controller.
 *
 * <p>Every predicate here is a thin wrapper over TaCZ ({@link TaczLink}), gated on the mod actually
 * being loaded: TaCZ has no Forge maven coordinate, so it is an optional runtime dependency
 * ({@code compileOnly}/{@code runtimeOnly} on the staged 1.20.1 jar, plus {@code mandatory=false} in
 * {@code mods.toml}). Without it the slot predicates answer "not a gun", which keeps the gun slot
 * from accepting a wrong item instead of crashing, and the weapon bus degrades to aiming only.</p>
 *
 * <p>The frame limits come from the same frame definitions the rest of the hardware layer reads
 * ({@link Frames.Frame}), so a datapack can retune {@code allowed_gun_types} / {@code mount_capacity}
 * / {@code ammo_slots} without touching code. The battery's mass bonus widens the mount, matching the
 * 1.21 build.</p>
 */
public final class CombatSlots {
    /** TaCZ is a runtime-only possibility; no class of it is linked unless this is true. */
    public static boolean available() {
        return ModList.get().isLoaded("tacz");
    }

    public static boolean isGun(ItemStack stack) {
        return available() && TaczLink.isGun(stack);
    }

    public static boolean isAmmo(ItemStack stack) {
        return available() && TaczLink.isAmmo(stack);
    }

    public static int installCount(ItemStack part) {
        return isAmmo(part) ? part.getCount() : 1;
    }

    public static String gunType(ItemStack part) {
        return isGun(part) ? TaczLink.gunType(part) : "";
    }

    /** Frame gun-type allow-list plus mount capacity; a weight over capacity is refused. */
    public static boolean fits(ItemStack controller, ItemStack gun) {
        if (!isGun(gun)) return false;
        Frames.Frame frame = Frames.of(controller);
        if (frame == null || !frame.allowsGun(gunType(gun))) return false;
        Batteries.Model battery = Batteries.installed(controller);
        double capacity = frame.mountCapacity() + (battery == null ? 0D : battery.mountCapacityBonus());
        return TaczLink.mountLoad(gun) <= capacity;
    }

    static String installError(ItemStack controller, ItemStack part, List<ItemStack> contents) {
        if (isGun(part) && contents.stream().noneMatch(stack -> Hardware.tier(controller, "combat") > 0)) {
            return "combat_required";
        }
        if (isGun(part) && !fits(controller, part)) return "gun_type";
        if (isGun(part)) return contents.stream().anyMatch(CombatSlots::isGun) ? "full" : "";
        if (isAmmo(part)) {
            int capacity = Frames.ammoSlots(Frames.id(controller));
            return contents.stream().filter(CombatSlots::isAmmo).count() >= capacity ? "full" : "";
        }
        return "unrelated";
    }

    /**
     * The ammunition predicate for a gun: "rounds that feed this gun" when one is mounted, otherwise
     * "anything TaCZ calls ammunition". Used by the cargo layer's 递弹 order, which must not start
     * believing an apple is ammunition merely because TaCZ is installed.
     */
    public static java.util.function.Predicate<ItemStack> ammoFor(ItemStack gun) {
        if (!available()) return stack -> false;
        if (gun == null || gun.isEmpty() || !isGun(gun)) return CombatSlots::isAmmo;
        return stack -> TaczLink.isAmmoFor(gun, stack);
    }

    /** The gun currently mounted, if any; the drone holds exactly one. */
    static ItemStack mounted(ItemStack controller) {
        for (ItemStack part : Hardware.contents(controller)) {
            if (isGun(part)) return part;
        }
        return ItemStack.EMPTY;
    }

    static List<ItemStack> ammo(ItemStack controller) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack part : Hardware.contents(controller)) {
            if (isAmmo(part)) result.add(part);
        }
        return Collections.unmodifiableList(result);
    }

    /** Test hook: what the drone's own mob-hit handler should allow. */
    static boolean mayDroneAttack(DroneEntity drone, net.minecraft.world.entity.LivingEntity target) {
        return target instanceof Mob && Engagement.permitsHit(drone, target);
    }

    private CombatSlots() {}
}
