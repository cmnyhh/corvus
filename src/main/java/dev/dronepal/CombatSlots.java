package dev.dronepal;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/** One mounted gun and frame-dependent reserve stacks. All optional types live in TaczCombat. */
public final class CombatSlots {
    static boolean available() { return ModList.get().isLoaded("tacz"); }
    public static boolean isGun(ItemStack stack) { return available() && TaczCombat.isGun(stack); }
    public static boolean isAmmo(ItemStack stack) { return available() && TaczCombat.isAmmo(stack); }
    public static int installCount(ItemStack part) { return isAmmo(part) ? part.getCount() : 1; }
    public static String gunType(ItemStack part) { return isGun(part) ? TaczCombat.gunType(part) : ""; }
    public static boolean fits(ItemStack controller, ItemStack gun) {
        if (!isGun(gun)) return false;
        var frame = Frames.of(controller);
        if (frame == null || !frame.allowedGunTypes().contains(gunType(gun))) return false;
        var battery = Batteries.installed(controller);
        double capacity = frame.mountCapacity() + (battery == null ? 0 : battery.mountCapacityBonus());
        return TaczCombat.mountLoad(gun) <= capacity;
    }
    public static String installError(ItemStack controller, ItemStack part, List<ItemStack> contents) {
        if (isGun(part) && contents.stream().map(Hardware::definition).noneMatch(m -> m != null && m.family().equals("combat"))) return "combat_required";
        if (isGun(part) && !fits(controller, part)) return "gun_type";
        if (isGun(part)) return contents.stream().anyMatch(CombatSlots::isGun) ? "full" : "";
        if (isAmmo(part)) {
            int capacity = Frames.ammoSlots(Frames.id(controller));
            return contents.stream().filter(CombatSlots::isAmmo).count() >= capacity ? "full" : "";
        }
        return "unrelated";
    }
}
