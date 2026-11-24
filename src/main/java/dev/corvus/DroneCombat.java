package dev.corvus;

import dev.corvus.data.CorvusData;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

/**
 * The drone's weapon bus: pose (stow/deploy + gimbal aim), the firing schedule, and — when TaCZ is
 * installed — the live gun/ammo transaction.
 *
 * <p><b>How firing works in 1.20.1.</b> TaCZ operates any {@code LivingEntity}, so the drone itself is
 * the shooter: {@link #refresh} puts the mounted gun in its main hand, {@link TaczLink#shoot} asks the
 * operator to fire, and the magazine lives in the gun's own NBT where TaCZ decrements it.</p>
 *
 * <p><b>Why the reload is ours.</b> TaCZ 1.1.8 refills a magazine out of the shooter's
 * {@code ITEM_HANDLER} capability, which on Forge 1.20.1 a {@code LivingEntity} can never expose:
 * {@code LivingEntity#getCapability} answers that capability with its own equipment handler before any
 * {@code AttachCapabilitiesEvent} provider is consulted (verified against the patched
 * {@code forge-1.20.1-47.2.0} bytecode). A provider attached to the drone is therefore unreachable, and
 * the 1.21 capability trick does not port. Instead the bus performs the magazine transaction itself:
 * {@link TaczLink#feed} moves rounds out of the controller's ammo parts into the gun's magazine when
 * TaCZ reports {@code NO_AMMO}. Every round is still a real TaCZ round — only the refill bookkeeping is
 * local — and {@link #flush} writes every change back into the controller's parts list, so the terminal
 * always shows the real ammunition left.</p>
 *
 * <p>Without TaCZ ({@link CombatSlots#available()} false) the bus keeps its documented degraded
 * behaviour: pose, target selection, ally protection and the mode cycle are live, {@link #fire}
 * records {@code NO_FIRE_BACKEND}, and nothing leaves the barrel.</p>
 */
public final class DroneCombat {
    /** NBT flag on the controller: true = weapon bus safed (will not engage). */
    public static final String SAFE_KEY = "dronepal_weapon_safe";

    /** Fire modes for a Mk3 combat module: burst / single / sustained. */
    private static final int MODES = 3;

    public String lastResult = "idle";
    private boolean drawn;
    private int graceUntil;
    private int mode;
    private int inBurst;
    private int nextAttempt;
    private ItemStack controller = ItemStack.EMPTY;
    /** The gun/ammo view last written back to the controller; also the "was this edited?" mark. */
    private ItemStack savedGun = ItemStack.EMPTY;
    private ItemStack[] savedAmmo = {};
    /**
     * Ammunition the drone itself carries while deployed — mutable working copies of the controller's
     * ammo parts, in slot order. {@link TaczLink#feed} draws from these; {@link #flush} writes them back.
     */
    private ItemStack[] reserve = {};
    /** Combat-module tier the current gun/ammo view was adopted for; -1 = not adopted yet. */
    private int combatTier = -1;

    static void register() {
        Engagement.register();
        EngagementPreferences.register();
        // The gun layer is optional: without TaCZ there are no events to hook and nothing to fire.
        if (!CombatSlots.available()) return;
        TaczLink.register();
    }

    /** Merge shim: {@code Corvus} may call either this or {@link CorvusNetwork#register()}. */
    public static void register(net.minecraftforge.eventbus.api.IEventBus bus) {
        CorvusNetwork.register();
    }

    public static float mountPitch(DroneEntity drone) {
        return drone.mountPitch();
    }

    public static float gimbalYaw(DroneEntity drone) {
        return drone.aimYaw();
    }

    public static float aimPitch(DroneEntity drone) {
        return drone.aimPitch();
    }

    public static boolean automatic(ItemStack controller) {
        return controller != null && !controller.isEmpty() && !CorvusData.of(controller).getBoolean(SAFE_KEY);
    }

    public static boolean toggle(DroneEntity drone) {
        return setDeployed(drone, !automatic(drone.controller()));
    }

    public static boolean setDeployed(DroneEntity drone, boolean deployed) {
        ItemStack controller = drone.controller();
        if (controller.isEmpty() || !Hardware.online(controller, "combat")) return false;
        // With a gun backend the mounted gun must fit the frame; without one the bus can be armed
        // but will only aim, so the fit check would otherwise make the switch dead.
        if (CombatSlots.available()) {
            if (drone.combat.controller.isEmpty()) refresh(drone);
            if (!CombatSlots.fits(controller, drone.getMainHandItem())) return false;
        }
        CorvusData.of(controller).setBoolean(SAFE_KEY, !deployed);
        if (!deployed) {
            drone.setTarget(null);
            drone.combat.graceUntil = 0;
        }
        return true;
    }

    public static boolean cycleMode(DroneEntity drone) {
        ItemStack controller = drone.controller();
        if (!CombatSlots.available() || Hardware.tier(controller, "combat") != 3
                || !Hardware.online(controller, "combat")) return false;
        drone.combat.mode = (drone.combat.mode + 1) % MODES;
        drone.combat.inBurst = 0;
        return true;
    }

    /** Human-readable fire mode, for the terminal/wheel and the smoke report. */
    public String modeName() {
        return switch (mode) {
            case 1 -> "single";
            case 2 -> "sustained";
            default -> "burst";
        };
    }

    /**
     * Adopts the controller's parts into the live gun/ammo view: gun into the main hand, ammunition
     * into {@link #reserve}. Called after a physical panel edit and whenever the drone picks up a new
     * controller.
     */
    public static void refresh(DroneEntity drone) {
        DroneCombat combat = drone.combat;
        ItemStack current = drone.controller();
        combat.controller = current;
        combat.drawn = false;
        combat.inBurst = 0;
        combat.combatTier = current.isEmpty() ? -1 : Hardware.tier(current, "combat");
        if (!CombatSlots.available() || current.isEmpty()) return;
        ItemStack gun = Hardware.tier(current, "combat") > 0
                ? CombatSlots.mounted(current) : ItemStack.EMPTY;
        if (!ItemStack.matches(drone.getMainHandItem(), gun)) {
            drone.setItemSlot(EquipmentSlot.MAINHAND, gun.copy());
        }
        int slots = Frames.ammoSlots(Frames.id(current));
        combat.reserve = new ItemStack[slots];
        combat.savedAmmo = new ItemStack[slots];
        List<ItemStack> ammo = CombatSlots.ammo(current);
        for (int i = 0; i < slots; i++) {
            ItemStack stack = i < ammo.size() ? ammo.get(i) : ItemStack.EMPTY;
            combat.reserve[i] = stack.copy();
            combat.savedAmmo[i] = stack.copy();
        }
        combat.savedGun = gun.copy();
    }

    /**
     * Writes the live gun and reserve back into the controller, but only when something actually
     * changed: a shot, a finished reload, or TaCZ bolting the gun. Returning early otherwise keeps
     * the battery and module tags untouched while the gun is idle.
     */
    public static void flush(DroneEntity drone) {
        DroneCombat combat = drone.combat;
        if (!CombatSlots.available() || combat.controller.isEmpty()) return;
        ItemStack gun = drone.getMainHandItem();
        boolean changed = !ItemStack.matches(combat.savedGun, gun);
        for (int i = 0; i < combat.savedAmmo.length && !changed; i++) {
            changed = !ItemStack.matches(combat.savedAmmo[i], combat.reserve[i]);
        }
        if (!changed) return;
        List<ItemStack> parts = Hardware.contents(combat.controller);
        int reserveIndex = 0;
        for (int i = 0; i < parts.size(); i++) {
            ItemStack old = parts.get(i);
            if (CombatSlots.isGun(old) && !combat.savedGun.isEmpty()) parts.set(i, gun.copy());
            else if (CombatSlots.isAmmo(old) && reserveIndex < combat.reserve.length) {
                parts.set(i, combat.reserve[reserveIndex++].copy());
            }
        }
        parts.removeIf(ItemStack::isEmpty);
        Hardware.save(combat.controller, parts);
        combat.savedGun = gun.copy();
        // The controller encodes occupied slots in order. Compact the live view to the same order.
        List<ItemStack> remaining = parts.stream().filter(CombatSlots::isAmmo).toList();
        for (int i = 0; i < combat.savedAmmo.length; i++) {
            ItemStack stack = i < remaining.size() ? remaining.get(i) : ItemStack.EMPTY;
            combat.reserve[i] = stack.copy();
            combat.savedAmmo[i] = stack.copy();
        }
    }

    /**
     * True when someone edited the controller's gun/ammo parts since the last flush (a terminal
     * install, a module swap). Comparing the stored parts against the last written snapshot avoids
     * mistaking TaCZ's own magazine changes for an edit: those only appear in the main hand until
     * {@link #flush} writes them back.
     */
    private boolean edited(ItemStack current) {
        if (!CombatSlots.available()) return false;
        if (!ItemStack.matches(CombatSlots.mounted(current), savedGun)) return true;
        List<ItemStack> ammo = CombatSlots.ammo(current);
        // savedAmmo is padded to the frame's slot count, so compare with the same padding rather
        // than with the (usually shorter) list of ammo stacks that are actually fitted.
        if (ammo.size() > savedAmmo.length) return true;
        for (int i = 0; i < savedAmmo.length; i++) {
            ItemStack part = i < ammo.size() ? ammo.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(part, savedAmmo[i])) return true;
        }
        return false;
    }

    void tick(DroneEntity drone) {
        ItemStack current = drone.controller();
        if (current.isEmpty()) {
            controller = ItemStack.EMPTY;
            drone.setTarget(null);
            return;
        }
        // A new controller, an edited gun/ammo list, or a combat module that appeared or went away all
        // invalidate the live view: the gun must leave the drone's hand the moment its module does.
        if (controller != current) {
            controller = current;
            refresh(drone);
        } else if (edited(current) || Hardware.tier(current, "combat") != combatTier) {
            refresh(drone);
        } else {
            flush(drone);
        }
        controller = current;
        Hardware.Module module = Hardware.installed(current).stream()
                .filter(candidate -> candidate.family().equals("combat")).findFirst().orElse(null);
        boolean armed = module != null && automatic(current) && Hardware.online(current, "combat")
                // With a backend the mounted gun also has to fit the frame; without one the bus keeps
                // aiming (the documented degraded path), so the fit check is skipped there.
                && (!CombatSlots.available() || CombatSlots.fits(current, drone.getMainHandItem()));
        LivingEntity target = armed ? Engagement.select(drone, Mth.clamp(module.integer("range"), 1, 48)) : null;
        drone.setTarget(target);
        if (target != null) graceUntil = drone.tickCount + 20;
        if (!armed) graceUntil = 0;
        boolean extend = target != null || drone.tickCount < graceUntil;
        int pitch = (int) mountPitch(drone);
        int nextPitch = extend ? Math.max(0, pitch - 13) : Math.min(85, pitch + 10);
        if (nextPitch != pitch) drone.setMountPitch(nextPitch);
        if (!extend) drawn = false;
        if (CombatSlots.available() && drone.tickCount % 20 == 0) second(drone, current);
        if (target == null) {
            inBurst = 0;
            if (!extend) {
                drone.setAimYaw(0F);
                drone.setAimPitch(0F);
            }
            return;
        }
        var direction = target.getBoundingBox().getCenter().subtract(drone.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitchToTarget = (float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
        float relative = Mth.wrapDegrees(yaw - drone.followHeading());
        // The body yaws to face the target as far as the gimbal allows, and the gimbal covers the rest.
        float gimbal = Mth.clamp(relative, -75F, 75F);
        drone.faceHeading(drone.followHeading() + relative - gimbal);
        drone.setAimYaw(gimbal);
        drone.setAimPitch(pitchToTarget);
        if (!drawn) {
            if (CombatSlots.available()) TaczLink.draw(drone);
            drawn = true;
        }
        if (nextPitch > 0 || drone.tickCount < nextAttempt) return;
        if (!Engagement.clearOfAllies(drone, target)) {
            inBurst = 0;
            nextAttempt = drone.tickCount + 5;
            lastResult = "FRIENDLY_BLOCK";
            return;
        }
        double stallChance = module.params().has("malfunction_chance")
                ? module.decimal("malfunction_chance") : module.trait("malfunction_chance", 0);
        if (stallChance > 0 && drone.getRandom().nextFloat() < stallChance) {
            int delay = module.params().has("malfunction_ticks")
                    ? module.integer("malfunction_ticks") : (int) module.trait("malfunction_ticks", 60);
            nextAttempt = drone.tickCount + Mth.clamp(delay, 1, 1200);
            inBurst = 0;
            lastResult = "MODULE_STALLED";
            return;
        }
        float spread = Mth.clamp(module.decimal("spread_deg") * (float) module.trait("spread_multiplier", 1), 0, 45);
        boolean fired = fire(drone, pitchToTarget + Mth.randomBetween(drone.getRandom(), -spread, spread),
                yaw + Mth.randomBetween(drone.getRandom(), -spread, spread));
        int interval = Mth.clamp(module.integer("cooldown_ticks"), 2, 100);
        nextAttempt = drone.tickCount
                + Math.max(2, Math.round(interval * Mth.randomBetween(drone.getRandom(), .6F, 1.4F)));
        int max = module.tier() == 3 && mode == 1 ? 1 : Mth.clamp(module.integer("burst"), 1, 8);
        if (fired && ++inBurst >= max) {
            nextAttempt += module.tier() == 3 && mode == 2 ? 12 : 30;
            inBurst = 0;
        }
        // TaCZ's reload/bolt runs between ticks; this picks its magazine change up immediately.
        flush(drone);
    }

    /**
     * Ammunition telemetry for the worn controller (the link module's {@code second}). 1.20.1 has no
     * per-owner hook for the gun layer, so the weapon bus itself reports, once a second, off the
     * drone's own owner lookup.
     */
    private static void second(DroneEntity drone, ItemStack controller) {
        ServerPlayer pilot = drone.getServer() == null || drone.ownerId() == null
                ? null : drone.getServer().getPlayerList().getPlayer(drone.ownerId());
        if (pilot == null) return;
        TaczLink.second(pilot, controller);
    }

    /**
     * Firing is the only TaCZ-only step. Returns true when a round actually left the gun — TaCZ's
     * own {@code ShootResult} decides, so a cooldown, a reload or an empty magazine is not a shot.
     */
    private boolean fire(DroneEntity drone, float pitch, float yaw) {
        if (!CombatSlots.available()) {
            lastResult = "NO_FIRE_BACKEND";
            return false;
        }
        boolean fired = TaczLink.shoot(drone, pitch, yaw);
        if (!fired && "NO_AMMO".equals(TaczLink.lastResult(drone)) && TaczLink.feed(drone, reserve) > 0) {
            // The drone's own ammunition just went into the magazine: shoot the round we just loaded.
            fired = TaczLink.shoot(drone, pitch, yaw);
        }
        lastResult = fired ? "SUCCESS" : TaczLink.lastResult(drone);
        return fired;
    }

    public static boolean mayHitPlayer(DroneEntity drone, net.minecraft.world.entity.player.Player player) {
        return Engagement.permitsHit(drone, player);
    }

    static boolean validTarget(DroneEntity drone, LivingEntity mob) {
        return Engagement.permitsHit(drone, mob);
    }

    public ItemStack controller() {
        return controller;
    }
}
