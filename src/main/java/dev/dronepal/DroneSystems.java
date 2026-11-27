package dev.dronepal;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.Tags;

/** Server-authoritative module runtime; scan particles are addressed only to the owner. */
final class DroneSystems {
    private final List<ScanSnapshot.Ore> marks = new ArrayList<>();
    private final List<UUID> hostiles = new ArrayList<>();
    private int markTicks, radius, cursor;
    private BlockPos origin;
    private boolean commonOnly, manual, summaryPending, includeHostiles;
    private BlockPos scanCenter;
    private net.minecraft.world.phys.Vec3 observer;
    interface ScanReport { void completed(ServerPlayer owner, ScanSnapshot snapshot, boolean automatic); }
    private final ScanReport report;
    DroneSystems() { this(DialogueServer::scanComplete); }
    DroneSystems(ScanReport report) { this.report = report; }
    private long nextAutomatic = -1;
    private ResourceLocation scanningProduct;
    private float healingLeft;
    private int lastHurt;

    boolean execute(DroneEntity drone, ServerPlayer p, ItemStack stack, String intent) {
        if (stack.isEmpty() || !p.isAlive()) return false;
        if (intent.equals("order.fetch") || intent.equals("order.light")) return CarryLight.execute(p,stack,intent);
        String family = intent.equals("order.heal") ? "medic" : "scan";
        Hardware.Module module = Hardware.installed(stack).stream().filter(m -> m.family().equals(family) && m.intents().contains(intent)).findFirst().orElse(null);
        if (module == null || !Hardware.online(stack, family)) return false;
        return switch (intent) {
            case "order.heal" -> startHealing(p, stack, module);
            case "scan", "scan.hostile", "scan.ore" -> scan(drone, p, stack, module, intent, true);
            default -> false;
        };
    }
    private boolean startHealing(ServerPlayer p, ItemStack stack, Hardware.Module m) {
        if (healingLeft > 0 || p.getHealth() >= p.getMaxHealth()) return false;
        ItemStack supplies = ItemStack.EMPTY;
        if (m.requiresSupply()) {
            var supplyItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "medical_supply"));
            if (supplyItem.isEmpty()) return false;
            for (var candidate : p.getInventory().items) {
                if (candidate.is(supplyItem.get())) { supplies = candidate; break; }
            }
            if (supplies.isEmpty()) return false;
        }
        if (!cooldown(p, stack, "medic", Math.clamp(m.integer("cooldown_ticks"), 20, 72000))) return false;
        healingLeft = Math.clamp(m.decimal("max_heal_per_use"), 0, 40);
        if (!supplies.isEmpty()) { supplies.shrink(1); p.getInventory().setChanged(); }
        if (m.params().has("side_effect_chance") && p.getRandom().nextDouble() < m.decimal("side_effect_chance")
                && "minecraft:nausea".equals(m.params().get("side_effect").getAsString())) {
            p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, Math.clamp(m.integer("side_effect_ticks"), 1, 1200)));
        }
        lastHurt = p.getLastHurtByMobTimestamp();
        return healingLeft > 0;
    }
    private boolean cooldown(ServerPlayer p, ItemStack stack, String family, int duration) {
        long now = p.serverLevel().getGameTime();
        String key = "dronepal_" + family + "_ready";
        if (stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLong(key) > now) return false;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(key, now + duration));
        return true;
    }
    private boolean scan(DroneEntity drone, ServerPlayer p, ItemStack stack, Hardware.Module m, String intent, boolean requested) {
        boolean ore = m.params().get("ore").getAsBoolean() && !intent.equals("scan.hostile");
        boolean hostile = m.params().get("hostile").getAsBoolean() && !intent.equals("scan.ore");
        if (!ore && !hostile || origin != null) return false;
        long now = p.serverLevel().getGameTime();
        boolean malfunction = m.params().has("malfunction_chance") && p.getRandom().nextDouble() < m.decimal("malfunction_chance");
        if ((requested || malfunction) && !cooldown(p, stack, "scan", malfunction ? Math.clamp(m.integer("malfunction_ticks"), 1, 1200) : 100)) return false;
        if (malfunction) {
            if (requested) p.displayClientMessage(Component.translatable("message.dronepal.scan_error"), true);
            return false;
        }
        manual = requested;
        nextAutomatic = now + ServerOptions.AUTO_SCAN_SECONDS.get() * 20L;
        scanningProduct = m.id();
        marks.clear(); hostiles.clear();
        radius = (int) Math.clamp(Math.round(m.integer("radius") * m.trait("scan_radius_multiplier", 1)), 1, 48);
        includeHostiles = hostile;
        scanCenter = drone.blockPosition();
        observer = p.position();
        refreshHostiles(drone, p);
        summaryPending = true;
        origin = ore ? scanCenter : null;
        cursor = 0;
        commonOnly = m.params().has("ore_filter") && "common".equals(m.params().get("ore_filter").getAsString());
        markTicks = Math.clamp(m.integer("mark_ticks"), 20, 1200);
        drone.scanning(true);
        if (origin == null) finishScan(drone, p);
        else publish(drone, p);
        return true;
    }
    void tick(DroneEntity drone, ServerPlayer player) {
        if (healingLeft > 0 && player.hurtTime > 0) healingLeft = 0;
        if (markTicks <= 0) return;
        if (drone.level() != player.level() || drone.isRemoved() || !player.isAlive() || !Hardware.online(drone.controller(), "scan")) {
            clearScan(drone, player);
            nextAutomatic = -1;
            return;
        }
        int published = drone.scanSnapshot == null ? 0 : drone.scanSnapshot.ores().size();
        boolean searching = origin != null;
        scanSlice(player);
        if (--markTicks <= 0) { clearScan(drone, player); return; }
        if (searching && origin == null) finishScan(drone, player);
        else if (searching && published != marks.size() && drone.tickCount % 20 == 0) publish(drone, player);
    }
    private void refreshHostiles(DroneEntity drone, ServerPlayer player) {
        hostiles.clear();
        if (!includeHostiles) return;
        player.serverLevel().getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(scanCenter).inflate(radius),
                mob -> mob instanceof Enemy && mob.isAlive() && scanCenter.distToCenterSqr(mob.position()) <= radius * radius)
                .stream().sorted(Comparator.comparingDouble(mob -> mob.distanceToSqr(player)))
                .limit(ScanSnapshot.LIMIT).forEach(mob -> hostiles.add(mob.getUUID()));
    }
    private void finishScan(DroneEntity drone, ServerPlayer player) {
        if (!summaryPending) return;
        summaryPending = false;
        var controller = Hardware.controller(player);
        if (drone.isRemoved() || player.level() != drone.level() || !player.isAlive() || player.isSpectator()
                || !drone.getUUID().equals(DroneItem.serial(controller)) || !Hardware.online(controller, "scan")
                || Hardware.installed(controller).stream().noneMatch(module -> module.id().equals(scanningProduct))) {
            clearScan(drone, player);
            return;
        }
        var world = player.serverLevel();
        marks.removeIf(mark -> !world.hasChunkAt(mark.pos()) || !BuiltInRegistries.BLOCK.getKey(world.getBlockState(mark.pos()).getBlock()).equals(mark.type()));
        marks.sort(Comparator.comparingDouble(mark -> mark.pos().distToCenterSqr(player.position())));
        refreshHostiles(drone, player);
        publish(drone, player);
        report.completed(player, drone.scanSnapshot, !manual);
    }
    private void publish(DroneEntity drone, ServerPlayer player) {
        var world = player.serverLevel();
        var contacts = new ArrayList<ScanSnapshot.Contact>();
        for (UUID uuid : hostiles) {
            if (world.getEntity(uuid) instanceof Mob mob && mob.isAlive()) contacts.add(new ScanSnapshot.Contact(mob.getId(), uuid,
                    mob.blockPosition(), BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType())));
        }
        var snapshot = new ScanSnapshot(drone.getUUID(), world.dimension().location(), world.getGameTime() + markTicks, marks, contacts);
        drone.scanSnapshot = snapshot;
        PacketDistributor.sendToPlayer(player, snapshot);
    }
    void patrolChanged(DroneEntity drone, ServerPlayer player, boolean enabled) {
        if (!enabled) clearScan(drone, player);
        nextAutomatic = enabled ? player.serverLevel().getGameTime() + ServerOptions.AUTO_SCAN_SECONDS.get() * 20L : -1;
    }
    void clearScan(DroneEntity drone, ServerPlayer player) {
        origin = null;
        markTicks = 0;
        summaryPending = false;
        marks.clear();
        hostiles.clear();
        scanningProduct = null;
        drone.scanning(false);
        if (drone.scanSnapshot == null) return;
        var clear = new ScanSnapshot(drone.getUUID(), drone.scanSnapshot.dimension(), 0, List.of(), List.of());
        drone.scanSnapshot = null;
        if (player != null) PacketDistributor.sendToPlayer(player, clear);
    }
    private void scanSlice(ServerPlayer p) {
        if (origin == null) return;
        int side = radius * 2 + 1;
        int volume = side * side * side;
        var pos = new BlockPos.MutableBlockPos();
        // Search outward in vertical layers, prioritising the owner's elevation. Never generate chunks.
        for (int end = Math.min(cursor + 2500, volume); cursor < end; cursor++) {
            int z = cursor % side - radius, x = cursor / side % side - radius;
            int layer = cursor / (side * side), y = (layer + 1) / 2 * (layer % 2 == 0 ? 1 : -1);
            if (x*x + y*y + z*z > radius * radius) continue;
            pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            var level = p.serverLevel();
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            boolean ore = commonOnly ? state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES) : state.is(Tags.Blocks.ORES);
            if (ore) keepNearest(pos, BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        if (cursor >= volume) origin = null;
    }
    // Keep the closest observations to the pilot's scan position, even in dense ore veins.
    private void keepNearest(BlockPos pos, ResourceLocation type) {
        double distance = pos.distToCenterSqr(observer);
        int size = marks.size();
        if (size == ScanSnapshot.LIMIT && distance >= marks.get(size - 1).pos().distToCenterSqr(observer)) return;
        if (size == ScanSnapshot.LIMIT) marks.remove(size - 1);
        int index = 0;
        while (index < marks.size() && marks.get(index).pos().distToCenterSqr(observer) <= distance) index++;
        marks.add(index, new ScanSnapshot.Ore(pos.immutable(), type));
    }
    private void cure(ServerPlayer p, ItemStack stack, Hardware.Module medic) {
        var effect = p.getActiveEffects().stream().filter(e -> e.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL).findFirst();
        if (effect.isPresent() && medic.params().has("cure_effect_every_ticks")
                && cooldown(p, stack, "cure", Math.clamp(medic.integer("cure_effect_every_ticks"), 20, 72000))) {
            p.removeEffect(effect.get().getEffect());
        }
    }
    /**
     * Is this family doing work during the settlement window that is being billed?
     *
     * <p>This is the half of the idle/active split only the runtime can answer: a fitted lamp switched
     * off, a scanner between pulses, a parked weapon bus and an idle hold all pay their standby rate,
     * while a lit lamp, a running sweep, an engagement, a pickup/hand-off and a treatment pay the full
     * in-use rate. Pickups and link hand-offs are one-shot events, so they leave a short mark on the
     * airframe ({@link DroneEntity#markActive}) that the next settlement still sees.</p>
     */
    private boolean inUse(DroneEntity drone, String family) {
        return switch (family) {
            case "scan" -> drone.isScanActive();
            case "light" -> drone.lightProfile() != 0;
            case "combat" -> drone.getTarget() != null;
            case "medic" -> healingLeft > 0;
            case "carry", "link" -> drone.activeWithin(family);
            // solar makes power; it does not spend any.
            default -> false;
        };
    }
    void second(DroneEntity drone, ServerPlayer p, ItemStack stack) {
        Batteries.tickDeployed(drone, stack);
        if (CombatSlots.available()) TaczLink.second(p, stack);
        var modules = Hardware.installed(stack);
        Hardware.updateReliability(drone, stack, modules);
        int start = Hardware.energy(stack), capacity = Hardware.capacity(stack);
        int charge = 0;
        var level = p.serverLevel();
        for (var m : modules) {
            if (m.family().equals("solar") && !Hardware.faulted(stack, m) && level.canSeeSky(drone.blockPosition())) {
                String key = !level.isDay() ? "night" : level.isRaining() ? "rain" : "clear";
                double jitter = m.params().has("output_jitter") ? Math.clamp(m.decimal("output_jitter"), 0, 1) : 0;
                double scale = jitter > 0 ? 1 + jitter * (p.getRandom().nextDouble() * 2 - 1) : 1;
                charge = (int) Math.round(20 * Math.clamp(m.integer(key), 0, 500) * scale);
            }
        }
        // The whole bill comes from the shared rule: standby rates, in-use rates for the families doing
        // work this second, the deployed baseline, then one lump multiplier and one rounding.
        int drain = Hardware.drainPerSecond(stack, true, family -> inUse(drone, family));
        var medic = modules.stream().filter(m -> m.family().equals("medic")).findFirst().orElse(null);
        if (medic != null && Hardware.online(stack, "medic")) {
            boolean interrupted = p.hurtTime > 0 || p.getLastHurtByMobTimestamp() != lastHurt;
            if (healingLeft > 0 && interrupted) healingLeft = 0;
            if (healingLeft <= 0) startHealing(p, stack, medic);
            if (healingLeft > 0) {
                int extra = 20 * Math.clamp(medic.integer("active_drain_watts") - medic.drain(), 0, 10000);
                if (start + charge >= drain + extra && p.getHealth() < p.getMaxHealth()) {
                    float amount = Math.min(healingLeft, (float) (20 * Math.clamp(medic.decimal("heal_per_tick"), 0F, 1F) * medic.trait("heal_rate_multiplier", 1)));
                    p.heal(amount);
                    healingLeft -= amount;
                    drain += extra;
                    cure(p, stack, medic);
                    if ((healingLeft <= 0 || p.getHealth() >= p.getMaxHealth()) && medic.params().has("bonus")
                            && !medic.params().get("bonus").isJsonNull() && "resistance_0_60t".equals(medic.params().get("bonus").getAsString())) {
                        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60));
                    }
                } else healingLeft = 0;
            }
        } else healingLeft = 0;
        // The wireless grid pays the module drain first and writes the rest into the cell.
        var grid = dev.dronepal.power.grid.DroneCharger.service(level, drone, stack, drain);
        drain -= grid.drainPaid();
        int afterGrid = Hardware.energy(stack);
        int end = Math.clamp(afterGrid + charge - drain, 0, capacity);
        if (end != afterGrid) Hardware.setEnergy(stack, end);
        DroneTelemetry.publish(drone, p, stack, Math.min(Math.max(0, drain), afterGrid + charge), charge,
                grid.online(), grid.ratePerSecond(), grid.queued());
        CarryLight.second(drone,p,stack);
        // The mill's extra 20 FE/t is settled with the rest of the hardware draw, once a second.
        dev.dronepal.mill.DroneWork.second(drone, stack);
        var scanner = modules.stream().filter(m -> m.family().equals("scan")).findFirst().orElse(null);
        if (scanner == null || !Hardware.online(stack, "scan") || p.isSpectator()) {
            if (markTicks > 0) clearScan(drone, p);
            nextAutomatic = -1;
            return;
        }
        if (scanningProduct != null && !scanningProduct.equals(scanner.id())) clearScan(drone, p);
        long now = level.getGameTime();
        if (nextAutomatic < 0) nextAutomatic = now + ServerOptions.AUTO_SCAN_SECONDS.get() * 20L;
        if (now >= nextAutomatic && origin == null && scanner.intents().contains("scan")) {
            nextAutomatic = now + ServerOptions.AUTO_SCAN_SECONDS.get() * 20L;
            scan(drone, p, stack, scanner, "scan", false);
        }
    }
}
