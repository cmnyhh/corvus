package dev.corvus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import dev.corvus.data.CorvusData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

/**
 * Server-authoritative scan runtime. Ore/hostile marks are sent only to the owner, and only that
 * owner's client draws them.
 *
 * <p>Scanning is incremental: at most {@value #BLOCKS_PER_TICK} candidate blocks per tick, never
 * forcing a chunk load, searching outward in vertical layers around the pilot's elevation. Results
 * keep the {@value ScanSnapshot#LIMIT} nearest observations, so a dense vein cannot push the useful
 * marks out of the payload.</p>
 *
 * <p>Medic/heal and the carry-intent family intentionally do not live here in the 1.20.1 port: they
 * belong to the module power runtime and the cargo panel respectively (see the open-items list in
 * {@code PORT-REQUESTS-C.md}).</p>
 */
final class DroneSystems {
    private static final int BLOCKS_PER_TICK = 2500;

    interface ScanReport {
        void completed(ServerPlayer owner, ScanSnapshot snapshot, boolean automatic);
    }

    private final List<ScanSnapshot.Ore> marks = new ArrayList<>();
    private final List<UUID> hostiles = new ArrayList<>();
    private final ScanReport report;
    private int markTicks;
    private int radius;
    private int cursor;
    private BlockPos origin;
    private boolean commonOnly;
    private boolean manual;
    private boolean summaryPending;
    private boolean includeHostiles;
    private BlockPos scanCenter;
    private Vec3 observer;
    private long nextAutomatic = -1;
    private ResourceLocation scanningProduct;
    /** Remaining points of the current medic use, and the wound the use was started for. */
    private float healingLeft;
    private int lastHurt;

    DroneSystems() {
        this(DialogueServer::scanComplete);
    }

    DroneSystems(ScanReport report) {
        this.report = report;
    }

    /** Merge shim: everything real happens in {@link CorvusNetwork#register()}. */
    public static void register(net.minecraftforge.eventbus.api.IEventBus bus) {
        CorvusNetwork.register();
    }

    /** Hardware's injected intent handler lands here (see {@link CorvusNetwork#register()}). */
    static boolean executeIntent(ServerPlayer player, String intent) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone == null) return false;
        ItemStack stack = drone.controller();
        // The authoritative copy is the terminal in the pilot's slot; drone.controller() only finds it
        // through the player list, which a headless acceptance run has no entry in.
        if (stack.isEmpty() || !drone.getUUID().equals(DroneItem.serial(stack))) {
            ItemStack worn = Hardware.controller(player);
            if (!worn.isEmpty() && drone.getUUID().equals(DroneItem.serial(worn))) stack = worn;
        }
        return drone.systems().execute(drone, player, stack, intent);
    }

    /**
     * One order, dispatched to the module family that owns it: the hold orders go to
     * {@link CarryLight}, {@code order.heal} to the medic runtime below, the scan family to
     * {@link #scan}.
     *
     * <p>Preconditions are the 1.21 set: the family module has to be fitted, it has to declare that
     * intent, and it has to be online (fault-free, charged, and not the module the brown-out rule cut).
     * Everything that is refused returns false, which the dialogue layer answers with the documented
     * "当前无法执行" line instead of a silent no-op.</p>
     */
    boolean execute(DroneEntity drone, ServerPlayer player, ItemStack stack, String intent) {
        if (stack.isEmpty() || !player.isAlive()) return false;
        if (intent.equals("order.work") || intent.equals("order.idle")) {
            return dev.corvus.mill.DroneWork.order(player, intent);
        }
        if (intent.equals("order.fetch") || intent.equals("order.supply")) {
            return CarryLight.execute(player, stack, intent);
        }
        String family = intent.equals("order.heal") ? "medic" : "scan";
        Hardware.Module module = Hardware.installed(stack).stream()
                .filter(candidate -> candidate.family().equals(family) && candidate.intents().contains(intent))
                .findFirst().orElse(null);
        if (module == null || !Hardware.online(stack, family)) return false;
        return switch (intent) {
            case "order.heal" -> startHealing(player, stack, module);
            case "scan", "scan.hostile", "scan.ore" -> scan(drone, player, stack, module, intent, true);
            default -> false;
        };
    }

    /**
     * Starts one healing use and pays everything the module's data file asks for, once: its cooldown, its
     * medical supply (Alba vendors consume one), and its documented side effect. The heal itself is
     * applied in {@link #medicSecond} a little at a time, exactly like the 1.21 runtime.
     */
    private boolean startHealing(ServerPlayer player, ItemStack stack, Hardware.Module module) {
        if (healingLeft > 0 || DroneRemote.disabled(stack, "medic")) return false;
        if (player.getHealth() >= player.getMaxHealth()) return false;
        ItemStack supply = ItemStack.EMPTY;
        if (module.requiresSupply()) {
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new ResourceLocation(Corvus.ID, "medical_supply"));
            if (item == null) return false;
            for (ItemStack candidate : player.getInventory().items) {
                if (candidate.is(item)) {
                    supply = candidate;
                    break;
                }
            }
            if (supply.isEmpty()) return false;
        }
        if (!cooldown(player, stack, "medic", clamp(module.integer("cooldown_ticks"), 20, 72000))) return false;
        healingLeft = clamp(module.decimal("max_heal_per_use"), 0F, 40F);
        if (!supply.isEmpty()) {
            supply.shrink(1);
            player.getInventory().setChanged();
        }
        if (module.params().has("side_effect_chance")
                && player.getRandom().nextDouble() < module.decimal("side_effect_chance")
                && "minecraft:nausea".equals(text(module, "side_effect"))) {
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.CONFUSION,
                    clamp(module.integer("side_effect_ticks"), 1, 1200)));
        }
        lastHurt = player.getLastHurtByMobTimestamp();
        return healingLeft > 0;
    }

    /**
     * Once-per-second medic step, the same shape as the 1.21 runtime: a hit breaks the session, a still
     * hurt pilot restarts it while the module is powered, and each second heals
     * {@code heal_per_tick × heal_rate_multiplier} until {@code max_heal_per_use} is used up.
     *
     * <p>Buying that second costs {@code active_drain_watts − drain_watts} FE/t. The 1.21 loop folds the
     * cost into its energy transaction; this port has no per-tick transaction yet, so the cost is debited
     * here and the session stops when the cell cannot pay — the "耗电" side of the order is real either
     * way, and it is what the acceptance run measures.</p>
     */
    private void medicSecond(ServerPlayer player, ItemStack stack) {
        Hardware.Module medic = Hardware.installed(stack).stream()
                .filter(module -> module.family().equals("medic")).findFirst().orElse(null);
        if (medic == null || !Hardware.online(stack, "medic") || DroneRemote.disabled(stack, "medic")) {
            healingLeft = 0;
            return;
        }
        boolean interrupted = player.hurtTime > 0 || player.getLastHurtByMobTimestamp() != lastHurt;
        if (healingLeft > 0 && interrupted) healingLeft = 0;
        if (healingLeft <= 0) startHealing(player, stack, medic);
        if (healingLeft <= 0) return;
        int cost = activeCost(medic);
        if (player.getHealth() >= player.getMaxHealth() || Hardware.energy(stack) < cost) {
            healingLeft = 0;
            return;
        }
        float amount = Math.min(healingLeft, 20F * clamp(medic.decimal("heal_per_tick"), 0F, 1F)
                * (float) medic.trait("heal_rate_multiplier", 1D));
        player.heal(amount);
        healingLeft -= amount;
        if (cost > 0) Hardware.setEnergy(stack, Hardware.energy(stack) - cost);
        cure(player, stack, medic);
        if ((healingLeft <= 0 || player.getHealth() >= player.getMaxHealth())
                && "resistance_0_60t".equals(text(medic, "bonus"))) {
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 60));
        }
    }

    /** Removes one harmful effect per {@code cure_effect_every_ticks}, on its own cooldown key. */
    private void cure(ServerPlayer player, ItemStack stack, Hardware.Module medic) {
        if (!medic.params().has("cure_effect_every_ticks")) return;
        net.minecraft.world.effect.MobEffectInstance harmful = null;
        for (var effect : player.getActiveEffects()) {
            if (effect.getEffect().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                harmful = effect;
                break;
            }
        }
        if (harmful == null) return;
        if (!cooldown(player, stack, "cure", clamp(medic.integer("cure_effect_every_ticks"), 20, 72000))) return;
        player.removeEffect(harmful.getEffect());
    }

    /** FE the medic charges for one second of healing: the active draw above its idle draw. */
    private static int activeCost(Hardware.Module medic) {
        return 20 * clamp(medic.integer("active_drain_watts") - medic.drain(), 0, 10000);
    }

    private static String text(Hardware.Module module, String key) {
        return module.params().has(key) && !module.params().get(key).isJsonNull()
                ? module.params().get(key).getAsString() : "";
    }

    /** Per-controller, per-family cooldown deadline; the 1.21 key names, kept for save compatibility. */
    private boolean cooldown(ServerPlayer player, ItemStack stack, String family, int duration) {
        long now = player.serverLevel().getGameTime();
        String key = "dronepal_" + family + "_ready";
        if (readyAt(stack, key) > now) return false;
        CorvusData.of(stack).setString(key, Long.toString(now + duration));
        return true;
    }

    private static long readyAt(ItemStack stack, String key) {
        try {
            return Long.parseLong(CorvusData.of(stack).getString(key));
        } catch (NumberFormatException unset) {
            return Long.MIN_VALUE;
        }
    }

    private boolean scan(DroneEntity drone, ServerPlayer player, ItemStack stack, Hardware.Module module,
                         String intent, boolean requested) {
        boolean ore = module.params().get("ore").getAsBoolean() && !intent.equals("scan.hostile");
        boolean hostile = module.params().get("hostile").getAsBoolean() && !intent.equals("scan.ore");
        if (!ore && !hostile || origin != null) return false;
        long now = player.serverLevel().getGameTime();
        boolean malfunction = module.params().has("malfunction_chance")
                && player.getRandom().nextDouble() < module.decimal("malfunction_chance");
        if ((requested || malfunction)
                && !cooldown(player, stack, "scan",
                        malfunction ? clamp(module.integer("malfunction_ticks"), 1, 1200) : 100)) {
            return false;
        }
        if (malfunction) {
            if (requested) player.displayClientMessage(Component.translatable("message.dronepal.scan_error"), true);
            return false;
        }
        manual = requested;
        nextAutomatic = now + ServerOptions.autoScanSeconds() * 20L;
        scanningProduct = module.id();
        marks.clear();
        hostiles.clear();
        radius = (int) clamp(Math.round(module.integer("radius") * module.trait("scan_radius_multiplier", 1)), 1, 48);
        includeHostiles = hostile;
        scanCenter = drone.blockPosition();
        observer = player.position();
        refreshHostiles(drone, player);
        summaryPending = true;
        origin = ore ? scanCenter : null;
        cursor = 0;
        commonOnly = module.params().has("ore_filter") && "common".equals(module.params().get("ore_filter").getAsString());
        markTicks = clamp(module.integer("mark_ticks"), 20, 1200);
        drone.scanning(true);
        if (origin == null) finishScan(drone, player);
        else publish(drone, player);
        return true;
    }

    void tick(DroneEntity drone, ServerPlayer player) {
        if (healingLeft > 0 && player.hurtTime > 0) healingLeft = 0;
        if (markTicks <= 0) return;
        if (drone.level() != player.level() || drone.isRemoved() || !player.isAlive()
                || !Hardware.online(drone.controller(), "scan")) {
            clearScan(drone, player);
            nextAutomatic = -1;
            return;
        }
        int published = drone.scanSnapshot == null ? 0 : drone.scanSnapshot.ores().size();
        boolean searching = origin != null;
        scanSlice(player);
        if (--markTicks <= 0) {
            clearScan(drone, player);
            return;
        }
        if (searching && origin == null) finishScan(drone, player);
        else if (searching && published != marks.size() && drone.tickCount % 20 == 0) publish(drone, player);
    }

    private void refreshHostiles(DroneEntity drone, ServerPlayer player) {
        hostiles.clear();
        if (!includeHostiles) return;
        double squared = radius * radius;
        player.serverLevel().getEntitiesOfClass(Mob.class, new AABB(scanCenter).inflate(radius),
                        mob -> mob instanceof Enemy && mob.isAlive()
                                && mob.position().distanceToSqr(Vec3.atCenterOf(scanCenter)) <= squared)
                .stream().sorted(Comparator.comparingDouble(mob -> mob.distanceToSqr(player)))
                .limit(ScanSnapshot.LIMIT)
                .forEach(mob -> hostiles.add(mob.getUUID()));
    }

    private void finishScan(DroneEntity drone, ServerPlayer player) {
        if (!summaryPending) return;
        summaryPending = false;
        ItemStack controller = Hardware.controller(player);
        if (drone.isRemoved() || player.level() != drone.level() || !player.isAlive() || player.isSpectator()
                || !drone.getUUID().equals(DroneItem.serial(controller)) || !Hardware.online(controller, "scan")
                || Hardware.installed(controller).stream().noneMatch(module -> module.id().equals(scanningProduct))) {
            clearScan(drone, player);
            return;
        }
        var world = player.serverLevel();
        marks.removeIf(mark -> !world.hasChunkAt(mark.pos())
                || !BuiltInRegistries.BLOCK.getKey(world.getBlockState(mark.pos()).getBlock()).equals(mark.type()));
        marks.sort(Comparator.comparingDouble(mark -> player.position().distanceToSqr(Vec3.atCenterOf(mark.pos()))));
        refreshHostiles(drone, player);
        publish(drone, player);
        report.completed(player, drone.scanSnapshot, !manual);
    }

    private void publish(DroneEntity drone, ServerPlayer player) {
        var world = player.serverLevel();
        List<ScanSnapshot.Contact> contacts = new ArrayList<>();
        for (UUID uuid : hostiles) {
            if (world.getEntity(uuid) instanceof Mob mob && mob.isAlive()) {
                contacts.add(new ScanSnapshot.Contact(mob.getId(), uuid, mob.blockPosition(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType())));
            }
        }
        ScanSnapshot snapshot = new ScanSnapshot(drone.getUUID(), world.dimension().location(),
                world.getGameTime() + markTicks, marks, contacts);
        drone.scanSnapshot = snapshot;
        CorvusNetwork.send(player, snapshot);
    }

    void patrolChanged(DroneEntity drone, ServerPlayer player, boolean enabled) {
        if (!enabled) clearScan(drone, player);
        nextAutomatic = enabled ? player.serverLevel().getGameTime() + ServerOptions.autoScanSeconds() * 20L : -1;
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
        ScanSnapshot clear = new ScanSnapshot(drone.getUUID(), drone.scanSnapshot.dimension(), 0, List.of(), List.of());
        drone.scanSnapshot = null;
        if (player != null) CorvusNetwork.send(player, clear);
    }

    private void scanSlice(ServerPlayer player) {
        if (origin == null) return;
        int side = radius * 2 + 1;
        int volume = side * side * side;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Search outward in vertical layers, prioritising the owner's elevation. Never generate chunks.
        for (int end = Math.min(cursor + BLOCKS_PER_TICK, volume); cursor < end; cursor++) {
            int z = cursor % side - radius;
            int x = cursor / side % side - radius;
            int layer = cursor / (side * side);
            int y = (layer + 1) / 2 * (layer % 2 == 0 ? 1 : -1);
            if (x * x + y * y + z * z > radius * radius) continue;
            pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            var level = player.serverLevel();
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            boolean ore = commonOnly
                    ? state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                    : state.is(Tags.Blocks.ORES);
            if (ore) keepNearest(pos, BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        if (cursor >= volume) origin = null;
    }

    // Keep the closest observations to the pilot's scan position, even in dense ore veins.
    private void keepNearest(BlockPos pos, ResourceLocation type) {
        double distance = distanceSqr(pos);
        int size = marks.size();
        if (size == ScanSnapshot.LIMIT && distance >= distanceSqr(marks.get(size - 1).pos())) return;
        if (size == ScanSnapshot.LIMIT) marks.remove(size - 1);
        int index = 0;
        while (index < marks.size() && distanceSqr(marks.get(index).pos()) <= distance) index++;
        marks.add(index, new ScanSnapshot.Ore(pos.immutable(), type));
    }

    private double distanceSqr(BlockPos pos) {
        return observer.distanceToSqr(Vec3.atCenterOf(pos));
    }

    /**
     * Once-per-second bookkeeping: keep the automatic patrol alive while a powered scanner is
     * fitted, and drop the marks as soon as the scanner goes offline or away.
     */
    void second(DroneEntity drone, ServerPlayer player, ItemStack stack) {
        medicSecond(player, stack);
        Hardware.Module scanner = Hardware.installed(stack).stream()
                .filter(module -> module.family().equals("scan")).findFirst().orElse(null);
        if (scanner == null || !Hardware.online(stack, "scan") || player.isSpectator()) {
            if (markTicks > 0) clearScan(drone, player);
            nextAutomatic = -1;
            return;
        }
        if (scanningProduct != null && !scanningProduct.equals(scanner.id())) clearScan(drone, player);
        long now = player.serverLevel().getGameTime();
        if (nextAutomatic < 0) nextAutomatic = now + ServerOptions.autoScanSeconds() * 20L;
        if (now >= nextAutomatic && origin == null && scanner.intents().contains("scan")) {
            nextAutomatic = now + ServerOptions.autoScanSeconds() * 20L;
            scan(drone, player, stack, scanner, "scan", false);
        }
    }

    /** Test hook: how many ore marks the current scan holds. */
    int markCount() {
        return marks.size();
    }

    boolean scanning() {
        return markTicks > 0;
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }

    private static long clamp(long value, long low, long high) {
        return value < low ? low : Math.min(value, high);
    }
}
