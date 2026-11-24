package dev.dronepal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/** Cargo is stored only on the physical controller, never on the projection or the equipment slots. */
final class CarryLight {
    private static final String CARGO = "dronepal_cargo";
    static List<ItemStack> read(ServerPlayer owner, ItemStack controller) {
        var list = controller.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getList(CARGO,Tag.TAG_COMPOUND);
        List<ItemStack> contents = new ArrayList<>();
        for (Tag item : list) contents.add(ItemStack.parse(owner.registryAccess(),item).orElseThrow());
        return contents;
    }
    static void save(ServerPlayer owner, ItemStack controller, List<ItemStack> contents) {
        ListTag list = new ListTag();
        for (ItemStack s : contents) if (!s.isEmpty()) list.add(s.save(owner.registryAccess()));
        CustomData.update(DataComponents.CUSTOM_DATA,controller,t -> { if (list.isEmpty()) t.remove(CARGO); else t.put(CARGO,list); });
        owner.getInventory().setChanged();
    }
    /** Capacity of the fitted cargo module, 0 when there is none. */
    static int slots(ItemStack controller) {
        for (var m : Hardware.installed(controller)) if (m.family().equals("carry")) return Math.clamp(m.integer("slots"),1,27);
        return 0;
    }
    /** The hold can only be opened or edited while its module is fitted and powered. */
    static boolean available(ItemStack controller) { return slots(controller) > 0 && Hardware.online(controller,"carry"); }
    /**
     * True while the owner has this controller's hold open. The screen owns the tag then: a pickup
     * or delivery cycle writing behind it would overwrite the view and lose or duplicate stacks.
     */
    static boolean viewing(ServerPlayer owner, ItemStack controller) {
        return owner.containerMenu instanceof CargoMenu menu && menu.controls(controller);
    }
    static void second(DroneEntity drone, ServerPlayer owner, ItemStack controller) {
        int light = 0;
        for (var m : Hardware.installed(controller)) {
            if (!Hardware.online(controller,m.family())) continue;
            if (m.family().equals("light") && (!drone.isInWater() || m.params().get("underwater").getAsBoolean()))
                light = (Math.clamp(m.integer("radius"),1,16)<<4) | Math.clamp(m.integer("light_level"),0,15)
                        | (m.params().has("flicker") && m.params().get("flicker").getAsBoolean() ? 512 : 0);
            if (!m.family().equals("carry") || viewing(owner,controller)) continue;
            int slots = Math.clamp(m.integer("slots"),1,27), radius = Math.clamp(m.integer("pickup_radius"),1,16);
            List<ItemStack> cargo = read(owner,controller);
            boolean changed = false;
            for (ItemEntity drop : owner.serverLevel().getEntitiesOfClass(ItemEntity.class,drone.getBoundingBox().inflate(radius),i -> i.isAlive() && i.distanceToSqr(drone)<=radius*radius && !i.hasPickUpDelay() && (i.getTarget()==null || i.getTarget().equals(owner.getUUID())))) {
                if (drop.getItem().is(Dronepal.DRONE.get()) || drop.getPersistentData().getBoolean("dronepal_delivered")) continue;
                if (NeoForge.EVENT_BUS.post(new ItemEntityPickupEvent.Pre(owner,drop)).canPickup()==TriState.FALSE || !drop.isAlive()) continue;
                ItemStack source = drop.getItem().copy();
                int before = source.getCount();
                store(cargo,source,slots);
                if (source.getCount()==before) continue;
                changed = true;
                if (source.isEmpty()) drop.discard(); else drop.setItem(source);
            }
            if (changed) {
                if (m.params().get("sort").getAsBoolean()) cargo.sort(Comparator.comparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem())));
                save(owner,controller,cargo);
            }
            if (m.params().get("auto_deliver").getAsBoolean() && !cargo.isEmpty()) deliver(owner,controller,cargo,false);
        }
        drone.setLightProfile(light);
    }
    // Moving items uses split/shrink. A downgraded hold never erases pre-existing overflow slots.
    static void store(List<ItemStack> slots, ItemStack source, int limit) {
        for (int i=0; i<Math.min(slots.size(),limit) && !source.isEmpty(); i++) {
            ItemStack target = slots.get(i);
            if (!ItemStack.isSameItemSameComponents(target,source)) continue;
            int n=Math.min(source.getCount(),Math.max(0,target.getMaxStackSize()-target.getCount()));
            target.grow(n); source.shrink(n);
        }
        while (!source.isEmpty() && slots.size()<limit) slots.add(source.split(source.getMaxStackSize()));
    }
    /** Move at most one compatible cargo stack. All overflow remains in the hold. */
    static int deliverMatching(ServerPlayer p, ItemStack controller, java.util.function.Predicate<ItemStack> matches) {
        if (viewing(p, controller)) return 0;
        List<ItemStack> cargo = read(p, controller);
        for (ItemStack item : cargo) {
            if (!matches.test(item)) continue;
            int before = item.getCount();
            moveIntoInventory(p, item);
            int moved = before - item.getCount();
            if (moved > 0) save(p, controller, cargo);
            if (moved > 0) return moved;
        }
        return 0;
    }
    private static void moveIntoInventory(ServerPlayer p, ItemStack item) {
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < 36 && !item.isEmpty(); slot++) {
            var target = p.getInventory().getItem(slot);
            if (pass == 0 && !target.isEmpty() && ItemStack.isSameItemSameComponents(target, item)) {
                int count = Math.min(item.getCount(), Math.max(0, target.getMaxStackSize() - target.getCount()));
                target.grow(count);
                item.shrink(count);
            } else if (pass == 1 && target.isEmpty()) p.getInventory().setItem(slot, item.split(item.getMaxStackSize()));
        }
    }
    private static boolean deliver(ServerPlayer p, ItemStack controller, List<ItemStack> cargo, boolean dropOverflow) {
        boolean changed=false;
        for (ItemStack item : cargo) {
            int before=item.getCount();
            moveIntoInventory(p, item);
            if (dropOverflow && !item.isEmpty()) {
                var drop = new ItemEntity(p.level(),p.getX(),p.getY(),p.getZ(),item.copy());
                drop.setTarget(p.getUUID()); drop.setNoPickUpDelay();
                drop.getPersistentData().putBoolean("dronepal_delivered",true);
                if (p.serverLevel().addFreshEntity(drop)) item.setCount(0);
            }
            changed |= item.getCount()!=before;
        }
        if (changed) save(p,controller,cargo);
        return changed;
    }
    static boolean execute(ServerPlayer p, ItemStack stack, String intent) {
        if (p.isSpectator() || Hardware.controller(p)!=stack) return false;
        if (intent.equals("order.fetch") && viewing(p,stack)) return false;
        if (intent.equals("order.light")) {
            if (Hardware.energy(stack)<=0 || Hardware.tier(stack,"light")==0) return false;
            DroneRemote.enable(stack,"light"); return Hardware.online(stack,"light");
        }
        return intent.equals("order.fetch") && Hardware.powered(stack,"carry") && deliver(p,stack,read(p,stack),true);
    }
}
