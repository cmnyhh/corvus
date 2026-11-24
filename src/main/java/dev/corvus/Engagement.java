package dev.corvus;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.corvus.data.CorvusData;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;

/**
 * Server-side targeting policy: five packs from {@code data/huhu_dronepal/engage_modes/*.json}.
 *
 * <p>The hard range cap and the ally guards apply to every pack, including packs added by a
 * datapack: a policy can only widen acquisition, never let the drone shoot a friend. The current
 * mode lives in controller NBT ({@value #MODE_KEY}) because 1.20.1 has no data components.</p>
 */
public final class Engagement {
    public static final List<String> IDS = List.of("passive", "hostile_only", "retaliate", "assist", "aggressive");
    /** NBT key on the controller; stores one of {@link #IDS}. */
    static final String MODE_KEY = "dronepal_engage_mode";
    private static final String DEFAULT_MODE = "retaliate";

    private record Rule(int radius, boolean retaliate, boolean assist, boolean monsters) {}

    private static volatile Map<String, Rule> rules = Map.of();

    static void register() {
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(
                new SimpleJsonResourceReloadListener(new Gson(), "engage_modes") {
                    @Override
                    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager,
                                         ProfilerFiller profiler) {
                        Map<String, Rule> next = new HashMap<>();
                        for (String id : IDS) {
                            JsonElement entry = data.get(new ResourceLocation(Corvus.ID, id));
                            if (entry == null || !entry.isJsonObject()) continue;
                            var json = entry.getAsJsonObject();
                            next.put(id, new Rule(
                                    Mth.clamp(json.get("auto_acquire_radius").getAsInt(), 0, 16),
                                    json.get("retaliate").getAsBoolean(),
                                    json.get("attack_owner_target").getAsBoolean(),
                                    "monster".equals(json.get("target_filter").getAsString())));
                        }
                        rules = Map.copyOf(next);
                    }
                }));
    }

    /** Test/ops hook: the currently loaded pack table. */
    static Set<String> loaded() {
        return rules.keySet();
    }

    static boolean known(String mode) {
        return mode != null && IDS.contains(mode);
    }

    public static String mode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return DEFAULT_MODE;
        String stored = CorvusData.of(stack).getString(MODE_KEY);
        return known(stored) ? stored : DEFAULT_MODE;
    }

    public static void setMode(ItemStack stack, String mode) {
        if (stack == null || stack.isEmpty() || !known(mode)) return;
        CorvusData.of(stack).setString(MODE_KEY, mode);
    }

    static String next(String mode) {
        String current = known(mode) ? mode : DEFAULT_MODE;
        return IDS.get((IDS.indexOf(current) + 1) % IDS.size());
    }

    public static boolean cycle(DroneEntity drone) {
        ItemStack stack = drone.controller();
        if (Hardware.tier(stack, "combat") == 0) return false;
        setMode(stack, next(mode(stack)));
        drone.setTarget(null);
        return true;
    }

    static boolean protectedAlly(DroneEntity drone, ServerPlayer owner, LivingEntity target) {
        if (target instanceof Player player) {
            return player == owner || (net.minecraftforge.fml.ModList.get().isLoaded("ftbteams")
                    && FtbTeamAllies.sameTeam(owner, player));
        }
        return target == drone || target.isAlliedTo(owner) || target.isAlliedTo(drone)
                || target instanceof TamableAnimal pet && owner.getUUID().equals(pet.getOwnerUUID())
                || target instanceof DroneEntity companion && owner.getUUID().equals(companion.ownerId())
                || target instanceof AbstractVillager || target instanceof IronGolem || target instanceof Allay;
    }

    static ServerPlayer owner(DroneEntity drone) {
        if (drone.ownerId() == null || drone.getServer() == null) return null;
        return drone.getServer().getPlayerList().getPlayer(drone.ownerId());
    }

    static boolean valid(DroneEntity drone, ServerPlayer owner, LivingEntity target, Rule rule, int range) {
        if (target == null || owner == null || target.level() != drone.level() || !target.isAlive()
                || target.isInvulnerable() || target.isSpectator() || !target.isAttackable()) return false;
        if (protectedAlly(drone, owner, target)
                || target instanceof Player player && !EngagementPreferences.accepts(owner, player)
                || rule.monsters && !(target instanceof Monster)) return false;
        double distance = drone.distanceToSqr(target);
        return distance >= 9 && distance <= range * range && drone.hasLineOfSight(target);
    }

    private static boolean recent(LivingEntity actor, int tick) {
        return actor.tickCount - tick >= 0 && actor.tickCount - tick <= 200;
    }

    /** The single entry point every tick uses: may return null while the policy stays quiet. */
    static LivingEntity select(DroneEntity drone, int moduleRange) {
        ServerPlayer owner = owner(drone);
        Rule rule = rules.get(mode(drone.controller()));
        int range = Math.min(16, moduleRange);
        if (owner == null || rule == null) return null;
        if (rule.assist && recent(owner, owner.getLastHurtMobTimestamp())
                && valid(drone, owner, owner.getLastHurtMob(), rule, range)) {
            return owner.getLastHurtMob();
        }
        if (rule.retaliate) {
            for (LivingEntity actor : List.of(drone, owner)) {
                LivingEntity attacker = actor.getLastHurtByMob();
                if (recent(actor, actor.getLastHurtByMobTimestamp()) && valid(drone, owner, attacker, rule, range)) {
                    return attacker;
                }
            }
        }
        int radius = Math.min(rule.radius, range);
        if (radius < 3) return null;
        return drone.level().getEntitiesOfClass(Monster.class, drone.getBoundingBox().inflate(radius),
                        mob -> valid(drone, owner, mob, rule, radius))
                .stream().min(Comparator.comparingDouble(drone::distanceToSqr)).orElse(null);
    }

    /** True when no protected body sits on the firing line. */
    static boolean clearOfAllies(DroneEntity drone, LivingEntity target) {
        ServerPlayer owner = owner(drone);
        if (owner == null) return false;
        var start = drone.getEyePosition();
        var end = target.getBoundingBox().getCenter();
        return drone.level().getEntitiesOfClass(LivingEntity.class, new AABB(start, end).inflate(1.5),
                ally -> ally != drone && ally != target && !(ally instanceof Player)
                        && protectedAlly(drone, owner, ally)
                        && ally.getBoundingBox().getCenter().subtract(start).dot(end.subtract(start)) > 0
                        && (ally.getBoundingBox().inflate(1.5).contains(start)
                        || ally.getBoundingBox().inflate(1.5).clip(start, end).isPresent())).isEmpty();
    }

    /** Gate for damage events: the target must still be the one this drone acquired. */
    static boolean permitsHit(DroneEntity drone, LivingEntity target) {
        Rule rule = rules.get(mode(drone.controller()));
        return rule != null && (rule.radius > 0 || rule.retaliate || rule.assist)
                && drone.getTarget() == target && valid(drone, owner(drone), target, rule, 16);
    }

    private Engagement() {}
}
