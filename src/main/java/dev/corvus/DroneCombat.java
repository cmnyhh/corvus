package dev.corvus;

import dev.corvus.data.CorvusData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The drone's weapon bus: pose (stow/deploy + gimbal aim) and the firing schedule.
 *
 * <p><b>1.20.1 status.</b> Pose, target selection, ally protection and the mode cycle are live.
 * Firing is not: this build has no TaCZ dependency, so {@link #fire} reports
 * {@code NO_FIRE_BACKEND} and nothing leaves the barrel. That is the documented degraded path — the
 * aim pose and the whole {@link Engagement} policy stack can be smoke-tested without a gun backend
 * (see {@code devtools.SmokeCombat}).</p>
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

    static void register() {
        Engagement.register();
        EngagementPreferences.register();
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
        if (CombatSlots.available() && !CombatSlots.fits(controller, drone.getMainHandItem())) return false;
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

    /** Called after a physical panel edit: drop stale pose so the next tick re-acquires. */
    public static void refresh(DroneEntity drone) {
        drone.combat.controller = ItemStack.EMPTY;
        drone.combat.drawn = false;
        drone.combat.inBurst = 0;
    }

    void tick(DroneEntity drone) {
        ItemStack current = drone.controller();
        if (current.isEmpty()) {
            controller = ItemStack.EMPTY;
            drone.setTarget(null);
            return;
        }
        controller = current;
        Hardware.Module module = Hardware.installed(current).stream()
                .filter(candidate -> candidate.family().equals("combat")).findFirst().orElse(null);
        boolean armed = module != null && automatic(current) && Hardware.online(current, "combat");
        LivingEntity target = armed ? Engagement.select(drone, Mth.clamp(module.integer("range"), 1, 48)) : null;
        drone.setTarget(target);
        if (target != null) graceUntil = drone.tickCount + 20;
        if (!armed) graceUntil = 0;
        boolean extend = target != null || drone.tickCount < graceUntil;
        int pitch = (int) mountPitch(drone);
        int nextPitch = extend ? Math.max(0, pitch - 13) : Math.min(85, pitch + 10);
        if (nextPitch != pitch) drone.setMountPitch(nextPitch);
        if (!extend) drawn = false;
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
            TaczLink.draw(drone);
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
    }

    /**
     * Firing is the only TaCZ-only step. Returns true when a round actually left the gun.
     */
    private boolean fire(DroneEntity drone, float pitch, float yaw) {
        if (!CombatSlots.available()) {
            lastResult = "NO_FIRE_BACKEND";
            return false;
        }
        boolean fired = TaczLink.shoot(drone, pitch, yaw);
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
