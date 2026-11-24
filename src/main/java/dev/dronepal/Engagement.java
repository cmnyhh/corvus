package dev.dronepal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Server-side targeting policy. The hard range and ally guards apply to every pack. */
final class Engagement {
    static final List<String> IDS = List.of("passive", "hostile_only", "retaliate", "assist", "aggressive");
    private static final DeferredRegister.DataComponents TYPES = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Dronepal.ID);
    private static final java.util.function.Supplier<DataComponentType<ResourceLocation>> MODE = TYPES.registerComponentType("engage_mode",
        b -> b.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));
    private record Rule(int radius, boolean retaliate, boolean assist, boolean monsters) {}
    private static Map<String, Rule> rules = Map.of();
    static void register(IEventBus bus) {
        TYPES.register(bus);
        EngagementPreferences.register();
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new SimpleJsonResourceReloadListener(new Gson(), "engage_modes") {
            @Override protected void apply(Map<ResourceLocation,JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
                var next = new HashMap<String, Rule>();
                for (String id : IDS) {
                    var entry = data.get(key(id));
                    if (entry == null) continue;
                    var o = entry.getAsJsonObject();
                    next.put(id, new Rule(Math.clamp(o.get("auto_acquire_radius").getAsInt(), 0, 16), o.get("retaliate").getAsBoolean(),
                        o.get("attack_owner_target").getAsBoolean(), "monster".equals(o.get("target_filter").getAsString())));
                }
                rules = Map.copyOf(next);
            }
        }));
    }
    private static ResourceLocation key(String id) { return ResourceLocation.fromNamespaceAndPath(Dronepal.ID, id); }
    static String mode(ItemStack stack) {
        var id = stack.getOrDefault(MODE.get(), key("retaliate"));
        return id.getNamespace().equals(Dronepal.ID) && IDS.contains(id.getPath()) ? id.getPath() : "retaliate";
    }
    static boolean cycle(DroneEntity drone) {
        ItemStack stack = drone.controller();
        if (Hardware.tier(stack,"combat") == 0) return false;
        stack.set(MODE.get(), key(IDS.get((IDS.indexOf(mode(stack))+1) % IDS.size())));
        drone.setTarget(null);
        return true;
    }
    static boolean protectedAlly(DroneEntity drone, ServerPlayer owner, LivingEntity target) {
        if (target instanceof Player player) {
            return player == owner || net.neoforged.fml.ModList.get().isLoaded("ftbteams") && FtbTeamAllies.sameTeam(owner, player);
        }
        return target == drone || target.isAlliedTo(owner) || target.isAlliedTo(drone)
            || target instanceof TamableAnimal pet && owner.getUUID().equals(pet.getOwnerUUID())
            || target instanceof DroneEntity companion && owner.getUUID().equals(companion.owner)
            || target instanceof AbstractVillager || target instanceof IronGolem || target instanceof Allay;
    }
    static ServerPlayer owner(DroneEntity drone) { return drone.owner == null || drone.getServer() == null ? null : drone.getServer().getPlayerList().getPlayer(drone.owner); }
    static boolean valid(DroneEntity drone, ServerPlayer owner, LivingEntity target, Rule rule, int range) {
        if (target==null || owner==null || target.level()!=drone.level() || !target.isAlive() || target.isInvulnerable() || target.isSpectator() || !target.isAttackable()) return false;
        if (protectedAlly(drone,owner,target) || target instanceof Player player && !EngagementPreferences.accepts(owner,player) || rule.monsters && !(target instanceof Monster)) return false;
        double distance = drone.distanceToSqr(target);
        return distance>=9 && distance<=range*range && drone.hasLineOfSight(target);
    }
    private static boolean recent(LivingEntity actor, int tick) { return actor.tickCount-tick >= 0 && actor.tickCount-tick <= 200; }
    static LivingEntity select(DroneEntity drone, int moduleRange) {
        var owner = owner(drone);
        Rule rule = rules.get(mode(drone.controller()));
        int range = Math.min(16,moduleRange);
        if (owner == null || rule == null) return null;
        if (rule.assist && recent(owner,owner.getLastHurtMobTimestamp()) && valid(drone,owner,owner.getLastHurtMob(),rule,range)) return owner.getLastHurtMob();
        if (rule.retaliate) {
            for (LivingEntity actor : List.of(drone,owner)) {
                LivingEntity attacker=actor.getLastHurtByMob();
                if (recent(actor,actor.getLastHurtByMobTimestamp()) && valid(drone,owner,attacker,rule,range)) return attacker;
            }
        }
        int radius = Math.min(rule.radius,range);
        if (radius<3) return null;
        return drone.level().getEntitiesOfClass(Monster.class,drone.getBoundingBox().inflate(radius), mob -> valid(drone,owner,mob,rule,radius))
            .stream().min(Comparator.comparingDouble(drone::distanceToSqr)).orElse(null);
    }
    static boolean clearOfAllies(DroneEntity drone, LivingEntity target) {
        ServerPlayer owner = owner(drone);
        if (owner == null) return false;
        var start = drone.getEyePosition();
        var end = target.getBoundingBox().getCenter();
        return drone.level().getEntitiesOfClass(LivingEntity.class,new AABB(start,end).inflate(1.5), ally -> ally != drone && ally != target &&
            !(ally instanceof Player) && protectedAlly(drone,owner,ally) && ally.getBoundingBox().getCenter().subtract(start).dot(end.subtract(start)) > 0
            && (ally.getBoundingBox().inflate(1.5).contains(start) || ally.getBoundingBox().inflate(1.5).clip(start,end).isPresent())).isEmpty();
    }
    static boolean permitsHit(DroneEntity drone, LivingEntity target) {
        Rule rule=rules.get(mode(drone.controller()));
        return rule!=null && (rule.radius>0 || rule.retaliate || rule.assist) && drone.getTarget()==target && valid(drone,owner(drone),target,rule,16);
    }
}
