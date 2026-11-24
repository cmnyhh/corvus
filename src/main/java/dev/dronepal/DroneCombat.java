package dev.dronepal;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

/** The controller owns the items. The main hand and reserve are the live TaCZ transaction. */
public final class DroneCombat {
    String lastResult = "idle";
    private boolean drawn;
    private int graceUntil;
    private int mode, inBurst, nextAttempt;
    private ItemStack controller = ItemStack.EMPTY, savedGun = ItemStack.EMPTY;
    private ItemStack[] savedAmmo = {};
    private final ItemStackHandler reserve = new ItemStackHandler(0) {
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    static void register(IEventBus bus) {
        Engagement.register(bus);
        if (!CombatSlots.available()) return;
        bus.addListener((RegisterCapabilitiesEvent e) -> e.registerEntity(Capabilities.ItemHandler.ENTITY,
                Dronepal.ENTITY.get(), (drone, side) -> side == null ? drone.combat.reserve : null));
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post e) -> {
            if (e.getEntity() instanceof DroneEntity d && !d.level().isClientSide && !d.isRemoved()) d.combat.tick(d);
        });
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e) -> {
            if (e.getEntity() instanceof DroneEntity d && !d.level().isClientSide) flush(d);
        });
        TaczCombat.register();
    }

    public static float mountPitch(DroneEntity d) { return d.getEntityData().get(DroneEntity.MOUNT_PITCH); }
    public static float gimbalYaw(DroneEntity drone) { return drone.getEntityData().get(DroneEntity.AIM_YAW); }
    public static float aimPitch(DroneEntity drone) { return drone.getEntityData().get(DroneEntity.AIM_PITCH); }
    public static boolean automatic(ItemStack controller) {
        return !controller.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getBoolean("dronepal_weapon_safe");
    }
    public static boolean toggle(DroneEntity d) { return setDeployed(d, !automatic(d.controller())); }
    public static boolean setDeployed(DroneEntity d, boolean value) {
        if (!CombatSlots.available() || !Hardware.online(d.controller(), "combat")) return false;
        if (d.combat.controller.isEmpty()) refresh(d);
        if (!CombatSlots.fits(d.controller(), d.getMainHandItem())) return false;
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, d.controller(),
                tag -> tag.putBoolean("dronepal_weapon_safe", !value));
        if (!value) { d.setTarget(null); d.combat.graceUntil = 0; }
        return true;
    }
    public static boolean cycleMode(DroneEntity d) {
        if (!CombatSlots.available() || Hardware.tier(d.controller(), "combat") != 3 || !Hardware.online(d.controller(), "combat")) return false;
        d.combat.mode = (d.combat.mode + 1) % 3;
        d.combat.inBurst = 0;
        return true;
    }

    /** Called after a physical panel edit. The panel flushes the old configuration first. */
    public static void refresh(DroneEntity d) {
        if (!CombatSlots.available()) return;
        DroneCombat c = d.combat;
        c.controller = d.controller();
        var parts = Hardware.contents(c.controller);
        ItemStack gun = Hardware.tier(c.controller, "combat") > 0
                ? parts.stream().filter(CombatSlots::isGun).findFirst().orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        var ammo = parts.stream().filter(CombatSlots::isAmmo).toList();
        if (!ItemStack.matches(d.getMainHandItem(), gun)) {
            d.setItemSlot(EquipmentSlot.MAINHAND, gun.copy());
            c.drawn = false;
            c.inBurst = 0;
        }
        int slots = Frames.ammoSlots(Frames.id(c.controller));
        c.reserve.setSize(slots);
        c.savedAmmo = new ItemStack[slots];
        for (int i = 0; i < slots; i++) {
            ItemStack stack = i < ammo.size() ? ammo.get(i) : ItemStack.EMPTY;
            c.reserve.setStackInSlot(i, stack.copy());
            c.savedAmmo[i] = stack.copy();
        }
        c.savedGun = gun.copy();
    }
    /** Only actual gun/ammo mutations write components. Preserve concurrent changes to battery and modules. */
    public static void flush(DroneEntity d) {
        DroneCombat c = d.combat;
        if (c.controller.isEmpty()) return;
        ItemStack gun = d.getMainHandItem();
        boolean changed = !ItemStack.matches(c.savedGun, gun);
        for (int i = 0; i < c.savedAmmo.length && !changed; i++) {
            changed = !ItemStack.matches(c.savedAmmo[i], c.reserve.getStackInSlot(i));
        }
        if (!changed) return;
        var parts = Hardware.contents(c.controller);
        int reserveIndex = 0;
        for (int i = 0; i < parts.size(); i++) {
            ItemStack old = parts.get(i);
            if (CombatSlots.isGun(old) && !c.savedGun.isEmpty()) parts.set(i, gun.copy());
            else if (CombatSlots.isAmmo(old) && reserveIndex < c.reserve.getSlots()) {
                parts.set(i, c.reserve.getStackInSlot(reserveIndex++).copy());
            }
        }
        parts.removeIf(ItemStack::isEmpty);
        Hardware.save(c.controller, parts);
        c.savedGun = gun.copy();
        // The controller encodes occupied slots in order. Compact the live view to the same order.
        var remaining = parts.stream().filter(CombatSlots::isAmmo).toList();
        for (int i = 0; i < c.savedAmmo.length; i++) {
            ItemStack stack = i < remaining.size() ? remaining.get(i) : ItemStack.EMPTY;
            c.reserve.setStackInSlot(i, stack.copy());
            c.savedAmmo[i] = stack.copy();
        }
    }

    private void tick(DroneEntity d) {
        ItemStack current = d.controller();
        if (controller != current) { flush(d); refresh(d); }
        if (current.isEmpty()) return;
        flush(d); // catches TaCZ's asynchronous reload/bolt actions as well as completed bursts
        Hardware.Module m = Hardware.installed(current).stream().filter(module -> module.family().equals("combat")).findFirst().orElse(null);
        boolean armed = m != null && automatic(current) && Hardware.online(current, "combat") && CombatSlots.fits(current, d.getMainHandItem());
        LivingEntity target = armed ? Engagement.select(d, Mth.clamp(m.integer("range"), 1, 48)) : null;
        d.setTarget(target);
        if (target != null) graceUntil = d.tickCount + 20;
        if (!armed) graceUntil = 0;
        boolean extend = target != null || d.tickCount < graceUntil;
        int pitch = (int)mountPitch(d);
        int nextPitch = extend ? Math.max(0, pitch - 13) : Math.min(85, pitch + 10);
        if (nextPitch != pitch) d.getEntityData().set(DroneEntity.MOUNT_PITCH, (byte)nextPitch);
        if (!extend) drawn = false;
        if (target == null) {
            inBurst = 0;
            if (!extend) {
                d.getEntityData().set(DroneEntity.AIM_YAW, 0f);
                d.getEntityData().set(DroneEntity.AIM_PITCH, 0f);
            }
            return;
        }
        var direction = target.getBoundingBox().getCenter().subtract(d.getEyePosition());
        float yaw = (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float aimPitch = (float)-Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
        float relative = Mth.wrapDegrees(yaw - d.followHeading());
        float gimbal = Mth.clamp(relative, -75f, 75f);
        d.faceHeading(d.followHeading() + relative - gimbal);
        d.getEntityData().set(DroneEntity.AIM_YAW, gimbal);
        d.getEntityData().set(DroneEntity.AIM_PITCH, aimPitch);
        if (!drawn) { TaczCombat.draw(d); drawn = true; }
        if (nextPitch > 0 || d.tickCount < nextAttempt) return;
        if (!Engagement.clearOfAllies(d,target)) { inBurst = 0; nextAttempt = d.tickCount + 5; return; }
        double stallChance = m.params().has("malfunction_chance") ? m.decimal("malfunction_chance") : m.trait("malfunction_chance", 0);
        if (stallChance > 0 && d.getRandom().nextFloat() < stallChance) {
            int delay = m.params().has("malfunction_ticks") ? m.integer("malfunction_ticks") : (int)m.trait("malfunction_ticks", 60);
            nextAttempt = d.tickCount + Mth.clamp(delay, 1, 1200);
            inBurst = 0;
            lastResult = "MODULE_STALLED";
            return;
        }
        float spread = Mth.clamp((float)(m.decimal("spread_deg") * m.trait("spread_multiplier", 1)), 0, 45);
        boolean fired = TaczCombat.shoot(d, aimPitch + Mth.randomBetween(d.getRandom(), -spread, spread), yaw + Mth.randomBetween(d.getRandom(), -spread, spread));
        int interval = Mth.clamp(m.integer("cooldown_ticks"), 2, 100);
        nextAttempt = d.tickCount + Math.max(2, Math.round(interval * Mth.randomBetween(d.getRandom(), .6f, 1.4f)));
        int max = m.tier() == 3 && mode == 1 ? 1 : Mth.clamp(m.integer("burst"), 1, 8);
        if (fired && ++inBurst >= max) {
            nextAttempt += m.tier() == 3 && mode == 2 ? 12 : 30;
            inBurst = 0;
        }
        flush(d);
    }
    public static boolean mayHitPlayer(DroneEntity drone, net.minecraft.world.entity.player.Player player) {
        return Engagement.permitsHit(drone, player);
    }
    static boolean validTarget(DroneEntity d, LivingEntity mob) {
        return Engagement.permitsHit(d, mob);
    }
}
