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
 * <p>In 1.21 every one of these predicates is a thin wrapper over TaCZ. TaCZ has no declared 1.20.1
 * artifact in this build, so the port keeps the same public surface but answers "no gun" when the
 * mod is absent: the gun slot then accepts nothing instead of silently accepting a wrong item. The
 * TaCZ-backed implementation is {@link TaczLink} and is marked NOT PORTED there.</p>
 */
public final class CombatSlots {
    /** TaCZ is a runtime-only possibility; the class itself is never linked directly. */
    private static final boolean AVAILABLE = ModList.get().isLoaded("tacz");

    public static boolean available() {
        return AVAILABLE;
    }

    public static boolean isGun(ItemStack stack) {
        return TaczLink.isGun(stack);
    }

    public static boolean isAmmo(ItemStack stack) {
        return TaczLink.isAmmo(stack);
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
        String type = gunType(gun);
        double load = TaczLink.mountLoad(gun);
        FrameLimits limits = FrameLimits.of(Frames.id(controller));
        return limits.allowed().contains(type) && load <= limits.capacity();
    }

    static String installError(ItemStack controller, ItemStack part, List<ItemStack> contents) {
        if (isGun(part) && contents.stream().noneMatch(stack -> Hardware.tier(controller, "combat") > 0)) {
            return "combat_required";
        }
        if (isGun(part) && !fits(controller, part)) return "gun_type";
        if (isGun(part)) return contents.stream().anyMatch(CombatSlots::isGun) ? "full" : "";
        if (isAmmo(part)) {
            int capacity = FrameLimits.of(Frames.id(controller)).ammoSlots();
            return contents.stream().filter(CombatSlots::isAmmo).count() >= capacity ? "full" : "";
        }
        return "unrelated";
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

    /**
     * Frame limits for the gun slot. Read from the frame definition once, mirroring
     * {@code allowed_gun_types} / {@code mount_capacity} / {@code ammo_slots} in the data files.
     */
    record FrameLimits(java.util.Set<String> allowed, double capacity, int ammoSlots) {
        private static final java.util.Map<String, FrameLimits> CACHE = load();

        private static java.util.Map<String, FrameLimits> load() {
            java.util.Map<String, FrameLimits> result = new java.util.HashMap<>();
            for (String id : Frames.IDS) {
                String path = "/data/huhu_dronepal/frames/" + id + ".json";
                try (var in = CombatSlots.class.getResourceAsStream(path)) {
                    if (in == null) continue;
                    try (var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                        var json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                        java.util.Set<String> allowed = new java.util.HashSet<>();
                        if (json.has("allowed_gun_types")) {
                            for (var element : json.getAsJsonArray("allowed_gun_types")) allowed.add(element.getAsString());
                        }
                        double capacity = json.has("mount_capacity") ? json.get("mount_capacity").getAsDouble() : 0;
                        int ammo = json.has("ammo_slots") ? json.get("ammo_slots").getAsInt() : 0;
                        result.put(id, new FrameLimits(java.util.Set.copyOf(allowed), capacity, ammo));
                    }
                } catch (Exception malformed) {
                    // A frame without a readable definition gets no gun slot rather than a crash.
                }
            }
            return java.util.Map.copyOf(result);
        }

        static FrameLimits of(String frameId) {
            return CACHE.getOrDefault(Frames.canonical(frameId), new FrameLimits(java.util.Set.of(), 0, 0));
        }
    }

    /** Test hook: what the drone's own mob-hit handler should allow. */
    static boolean mayDroneAttack(DroneEntity drone, net.minecraft.world.entity.LivingEntity target) {
        return target instanceof Mob && Engagement.permitsHit(drone, target);
    }

    private CombatSlots() {}
}
