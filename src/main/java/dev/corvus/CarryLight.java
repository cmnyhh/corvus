package dev.corvus;

import dev.corvus.data.CorvusData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The controller's cargo hold.
 *
 * <p>The hold lives in the NBT tag of the physical controller stack and nowhere else: the projection
 * has no inventory, and the equipment slot is never written. That single rule is what keeps a
 * pickup, a delivery and an open cargo screen from duplicating a stack.</p>
 *
 * <p>1.20.1 has no data components, so the tag is read and written through {@link CorvusData}. The
 * key is the same one the 1.21 build used, so a save moved between the versions keeps its hold.</p>
 */
public final class CarryLight {
    /** Same key as the 1.21 build. */
    public static final String CARGO = "dronepal_cargo";
    /** Hard ceiling for any data pack; the shipped modules stop at 27. */
    public static final int MAX_SLOTS = 27;

    private CarryLight() {}

    /** Ordered contents of the hold; empty slots are not stored. */
    public static List<ItemStack> read(ItemStack controller) {
        if (controller == null || controller.isEmpty()) return List.of();
        return CorvusData.of(controller).getItemList(CARGO);
    }

    public static void save(ItemStack controller, List<ItemStack> contents) {
        if (controller == null || controller.isEmpty()) return;
        CorvusData.of(controller).setItemList(CARGO, contents);
    }

    /** Capacity of the fitted cargo module, 0 when there is none. */
    public static int slots(ItemStack controller) {
        if (controller == null || controller.isEmpty()) return 0;
        for (Hardware.Module module : Hardware.installed(controller)) {
            if ("carry".equals(module.family())) return Mth.clamp(module.integer("slots"), 1, MAX_SLOTS);
        }
        return 0;
    }

    /** The hold can only be opened or edited while its module is fitted and powered. */
    public static boolean available(ItemStack controller) {
        return slots(controller) > 0 && Hardware.online(controller, "carry");
    }

    /**
     * True while the owner has this controller's hold open. The screen owns the tag then: a pickup or
     * delivery cycle writing behind it would overwrite the view and lose or duplicate stacks.
     */
    public static boolean viewing(ServerPlayer owner, ItemStack controller) {
        return owner.containerMenu instanceof CargoMenu menu && menu.controls(controller);
    }

    // ---------------------------------------------------------------------------------------------
    // Orders: cargo delivery (the module runtime calls these, and only these)
    // ---------------------------------------------------------------------------------------------

    /**
     * Order entry for {@code order.fetch} and {@code order.supply}.
     *
     * <p>Both are cargo transactions, so the hold has to be fitted <em>and</em> powered — the same gate
     * that lets the owner open it. {@code order.fetch} hands the whole hold over (anything the inventory
     * cannot take is dropped at the owner's feet, marked so the drone will not pick it straight back up);
     * {@code order.supply} hands over one compatible stack and leaves the rest alone.</p>
     *
     * <p>The stack is checked against the owner's worn, currently flying controller by serial, so a stale
     * copy of the item can never deliver from a hold that is not the one in the Curios slot.</p>
     */
    public static boolean execute(ServerPlayer player, ItemStack controller, String intent) {
        if (player == null || controller == null || controller.isEmpty()) return false;
        if (!player.isAlive() || player.isSpectator()) return false;
        UUID serial = DroneItem.serial(controller);
        ItemStack worn = Hardware.controller(player);
        if (serial == null || worn.isEmpty() || !serial.equals(DroneItem.serial(worn))) return false;
        if (viewing(player, controller) || !Hardware.powered(controller, "carry")) return false;
        return switch (intent) {
            case "order.fetch" -> deliver(player, controller, read(controller), true);
            case "order.supply" -> supply(player, controller);
            default -> false;
        };
    }

    /**
     * 递弹: one ammo stack out of the hold.
     *
     * <p>When a gun is actually mounted the matcher is the gun backend's own ammo predicate — the 1.21
     * rule, which asks TaCZ whether the stack is ammunition for the mounted gun. With no gun fitted (and
     * in a build with no backend at all) "ammo" has no definition, so the degraded rule hands over a hold
     * stack that matches what the owner is currently holding. Gating on a mounted gun matters: merely
     * having TaCZ installed must not turn a plain arrow delivery into "no match".</p>
     */
    private static boolean supply(ServerPlayer player, ItemStack controller) {
        Predicate<ItemStack> matches;
        if (CombatSlots.available() && !CombatSlots.mounted(controller).isEmpty()) {
            matches = CombatSlots.ammoFor(player.getMainHandItem());
        } else {
            ItemStack held = player.getMainHandItem();
            matches = candidate -> !held.isEmpty() && ItemStack.isSameItemSameTags(candidate, held);
        }
        int moved = deliverMatching(player, controller, matches);
        if (moved <= 0) return false;
        player.displayClientMessage(Component.translatable("dronepal.link.supplied", moved), true);
        return true;
    }

    /**
     * Moves at most one compatible hold stack into the owner's inventory.
     *
     * <p>Copied from the 1.21 behaviour on purpose: a delivery cycle moves <em>one</em> stack and leaves
     * every overflow in the hold, so a full inventory can never destroy cargo.</p>
     */
    public static int deliverMatching(ServerPlayer player, ItemStack controller, Predicate<ItemStack> matches) {
        if (player == null || controller == null || controller.isEmpty() || viewing(player, controller)) return 0;
        List<ItemStack> cargo = read(controller);
        for (ItemStack item : cargo) {
            if (item.isEmpty() || !matches.test(item)) continue;
            int before = item.getCount();
            moveIntoInventory(player, item);
            int moved = before - item.getCount();
            if (moved <= 0) continue;
            save(controller, nonEmpty(cargo));
            // A hand-off is the hold doing work, not idling.
            Hardware.markActive(player, "carry");
            return moved;
        }
        return 0;
    }

    /**
     * Moves every hold stack into the inventory; with {@code dropOverflow} whatever does not fit lands at
     * the owner's feet as delivered loot. Returns true when anything moved.
     */
    public static boolean deliver(ServerPlayer player, ItemStack controller, List<ItemStack> cargo,
                                  boolean dropOverflow) {
        if (player == null || controller == null || controller.isEmpty() || viewing(player, controller)) return false;
        boolean changed = false;
        for (ItemStack item : cargo) {
            if (item.isEmpty()) continue;
            int before = item.getCount();
            moveIntoInventory(player, item);
            if (dropOverflow && !item.isEmpty()) {
                ItemEntity drop = new ItemEntity(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                        item.copy());
                drop.setTarget(player.getUUID());
                drop.setNoPickUpDelay();
                drop.getPersistentData().putBoolean(DELIVERED, true);
                if (player.serverLevel().addFreshEntity(drop)) item.setCount(0);
            }
            changed |= item.getCount() != before;
        }
        if (changed) save(controller, nonEmpty(cargo));
        if (changed) Hardware.markActive(player, "carry");
        return changed;
    }

    /** Two passes: top up matching stacks first, then take empty slots. Never drops what does not fit. */
    private static void moveIntoInventory(ServerPlayer player, ItemStack item) {
        var inventory = player.getInventory();
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < 36 && !item.isEmpty(); slot++) {
                ItemStack target = inventory.getItem(slot);
                if (pass == 0 && !target.isEmpty() && ItemStack.isSameItemSameTags(target, item)) {
                    int count = Math.min(item.getCount(), Math.max(0, target.getMaxStackSize() - target.getCount()));
                    target.grow(count);
                    item.shrink(count);
                } else if (pass == 1 && target.isEmpty()) {
                    inventory.setItem(slot, item.split(item.getMaxStackSize()));
                }
            }
        }
        inventory.setChanged();
    }

    private static List<ItemStack> nonEmpty(List<ItemStack> contents) {
        List<ItemStack> kept = new ArrayList<>(contents.size());
        for (ItemStack stack : contents) if (!stack.isEmpty()) kept.add(stack);
        return kept;
    }

    /** Marks a dropped delivery so the pickup loop never swallows its own delivery. 1.21 key. */
    private static final String DELIVERED = "dronepal_delivered";
}
