package dev.dronepal.power.grid;

import dev.corvus.core.Grid;
import dev.dronepal.ServerOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side wireless grid: tower membership, connectivity, per-tick budget allocation and delivery.
 *
 * <p><b>Topology.</b> Every loaded tower registers here. Two towers are linked when the distance
 * between their blocks is at most {@code towerRadius}; connectivity is the transitive closure of that
 * relation, computed by breadth-first search and cached until a tower is placed/removed or the radius
 * config changes. A device is attached to its nearest tower inside the same radius, then a BFS from
 * that tower finds the nearest tower that still holds energy. The number of edges on that path is the
 * hop count; the delivered ratio is {@code efficiency^hops} ({@code dev.corvus.core.Grid}), and a path
 * longer than five hops is not usable.</p>
 *
 * <p><b>Budget.</b> Each tower has its own {@code towerThroughput} FE/t. All devices attached to one
 * tower are polled at the start of the tick, grouped by that tower and split with
 * {@link Grid#allocate(int, int[], int[])}: priority first (drone &gt; mill &gt; dock), then equal
 * water-filling inside a priority level. The delivered grant is deducted from the supplying tower's
 * pool immediately (at {@code efficiency^hops} cost per delivered FE) and banked as credit for the
 * device, so a device that ticks later in the same tick — or once per second, like the drone — can
 * still withdraw exactly what was allocated. Nothing is created or duplicated: the tower's energy is
 * the only source, and a grant that cannot be covered by the supplying tower is cut down.</p>
 *
 * <p>All state is per {@link ServerLevel} and touched only on the server thread. The class is a
 * singleton service rather than saved data: tower energy itself lives in each tower's block entity
 * NBT, and the topology is rebuilt from the loaded towers after a reload.</p>
 */
public final class TowerGridService {
    /** Live readout for the block's right-click message and the client overlay. */
    public record NetworkInfo(int towers, int devices, int outputUsed, int outputLimit, int radius) {}

    /** One device's state for the current tick. */
    private static final class Binding {
        final GridLoad load;
        int demand;
        int grant;
        int credit;
        int hops = -1;
        double efficiency;
        BlockPos attached;
        BlockPos supplier;

        Binding(GridLoad load) { this.load = load; }
    }

    private static final class LevelState {
        final ServerLevel level;
        final Map<BlockPos, PowerTowerEntity> towers = new LinkedHashMap<>();
        final IdentityHashMap<GridLoad, Binding> loads = new IdentityHashMap<>();
        final Map<BlockPos, BlockLoad> blockLoads = new HashMap<>();
        final Map<java.util.UUID, DroneLoad> droneLoads = new HashMap<>();
        final Map<BlockPos, Integer> outputUsed = new HashMap<>();
        List<List<BlockPos>> components = List.of();
        Map<BlockPos, Integer> componentOf = Map.of();
        int builtRadius = -1;
        boolean dirty = true;
        long tick = Long.MIN_VALUE;

        LevelState(ServerLevel level) { this.level = level; }
    }

    /** A block-entity-backed load. The owning block entity keeps the demand supplier alive. */
    static final class BlockLoad implements GridLoad {
        final BlockEntity owner;
        final int priority;
        final IntSupplier demand;
        BlockLoad(BlockEntity owner, int priority, IntSupplier demand) {
            this.owner = owner; this.priority = priority; this.demand = demand;
        }
        @Override public BlockPos gridPos() { return owner.getBlockPos(); }
        @Override public int gridPriority() { return priority; }
        @Override public int gridDemandFe() { return Math.max(0, demand.getAsInt()); }
        @Override public boolean gridValid() { return !owner.isRemoved() && owner.getLevel() != null; }
    }

    /** A deployed drone: draws once per second, so its grant is banked (cap = one second's draw). */
    static final class DroneLoad implements GridLoad {
        final net.minecraft.world.entity.Entity drone;
        int perSecond;
        DroneLoad(net.minecraft.world.entity.Entity drone) { this.drone = drone; }
        @Override public BlockPos gridPos() { return drone.blockPosition(); }
        @Override public int gridPriority() { return Grid.PRIORITY_DRONE; }
        @Override public int gridDemandFe() { return Math.max(1, (perSecond + 19) / 20); }
        @Override public int gridCreditCapFe() { return Math.max(1, perSecond); }
        @Override public boolean gridValid() { return !drone.isRemoved() && drone.isAlive(); }
    }

    private record Supplier(BlockPos pos, int hops) {}

    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();

    /**
     * Test seam: the acceptance structures are small, so the game-test table narrows the link radius
     * to 20 blocks. Production always reads the server config; the rule (a tower links inside the
     * radius) is unchanged and the vertical chains still need real 17-block edges.
     */
    private static int radiusOverride = -1;

    static void setRadiusOverride(int radius) { radiusOverride = radius; }

    private static int radius() {
        return radiusOverride > 0 ? radiusOverride : ServerOptions.towerRadius();
    }

    private TowerGridService() {}

    private static LevelState state(ServerLevel level) {
        return STATES.computeIfAbsent(level, LevelState::new);
    }

    // ---------------------------------------------------------------- tower registration

    static void registerTower(ServerLevel level, PowerTowerEntity tower) {
        LevelState state = state(level);
        if (state.towers.putIfAbsent(tower.getBlockPos(), tower) == null) state.dirty = true;
    }

    static void unregisterTower(ServerLevel level, PowerTowerEntity tower) {
        LevelState state = state(level);
        if (state.towers.remove(tower.getBlockPos()) != null) state.dirty = true;
    }

    // ---------------------------------------------------------------- load registration

    static void register(ServerLevel level, GridLoad load) {
        LevelState state = state(level);
        state.loads.putIfAbsent(load, new Binding(load));
    }

    static void unregister(ServerLevel level, GridLoad load) {
        state(level).loads.remove(load);
    }

    /**
     * One allocation per server tick. The tick handler calls {@link #newTick} at the start of the tick;
     * a draw or a readout between ticks lazily allocates once, so a test or a command that runs outside
     * the normal order still sees consistent numbers without ever double-spending a tower.
     */
    static void beginTick(ServerLevel level) {
        LevelState state = state(level);
        long now = level.getGameTime();
        if (state.tick == now) return;
        state.tick = now;
        allocate(state);
    }

    /**
     * Called by the server tick handler. It runs before the level increments its game time, so it
     * reserves the upcoming tick number; a device drawing later in that tick then sees the same
     * number and cannot trigger a second allocation. When the handler is absent (a unit test or a
     * gametest harness that ticks a level directly), {@link #beginTick} allocates lazily once per
     * game tick instead.
     */
    static void newTick(ServerLevel level) {
        LevelState state = state(level);
        state.tick = level.getGameTime() + 1;
        allocate(state);
    }

    /** Test/command seam: allocate immediately, ignoring the normal once-per-tick guard. */
    static void forceAllocate(ServerLevel level) {
        LevelState state = state(level);
        state.tick = level.getGameTime();
        allocate(state);
    }

    private static void allocate(LevelState state) {
        ServerLevel level = state.level;
        state.outputUsed.clear();
        state.towers.entrySet().removeIf(entry -> {
            PowerTowerEntity tower = entry.getValue();
            return tower.isRemoved() || tower.getLevel() != level || level.getBlockEntity(entry.getKey()) != tower;
        });
        state.blockLoads.entrySet().removeIf(entry -> {
            BlockLoad load = entry.getValue();
            return !load.gridValid() || state.level.getBlockEntity(entry.getKey()) != load.owner;
        });
        state.loads.entrySet().removeIf(entry -> !entry.getKey().gridValid());
        int radius = radius();
        if (radius != state.builtRadius) { state.builtRadius = radius; state.dirty = true; }
        if (state.dirty) rebuild(state, radius);

        // Poll every load once, attach it to its nearest tower and group by that tower.
        Map<BlockPos, List<Binding>> attached = new LinkedHashMap<>();
        for (Binding binding : state.loads.values()) {
            binding.grant = 0;
            binding.supplier = null;
            binding.hops = -1;
            binding.efficiency = 0.0D;
            binding.demand = Math.max(0, binding.load.gridDemandFe());
            int cap = Math.max(binding.demand, binding.load.gridCreditCapFe());
            int want = Math.max(0, cap - binding.credit);
            binding.attached = nearestTower(state, binding.load.gridPos(), radius);
            if (binding.attached == null || want <= 0) continue;
            attached.computeIfAbsent(binding.attached, key -> new ArrayList<>()).add(binding);
        }

        int throughput = ServerOptions.towerThroughput();
        double configured = ServerOptions.towerEfficiency();
        for (Map.Entry<BlockPos, List<Binding>> entry : attached.entrySet()) {
            List<Binding> group = entry.getValue();
            int[] demands = new int[group.size()];
            int[] priorities = new int[group.size()];
            for (int i = 0; i < group.size(); i++) {
                Binding binding = group.get(i);
                int cap = Math.max(binding.demand, binding.load.gridCreditCapFe());
                demands[i] = Math.max(0, cap - binding.credit);
                priorities[i] = binding.load.gridPriority();
            }
            int[] grants = Grid.allocate(throughput, demands, priorities);
            for (int i = 0; i < group.size(); i++) {
                int grant = grants[i];
                if (grant <= 0) continue;
                Binding binding = group.get(i);
                Supplier supplier = nearestPoweredTower(state, entry.getKey(), radius);
                if (supplier == null) continue;
                PowerTowerEntity source = towerAt(state, supplier.pos);
                if (source == null || source.energy() <= 0) continue;
                double efficiency = Grid.hopsCost(supplier.hops, configured);
                // The grant is tower-side output: the tower spends up to that much, and the device
                // receives efficiency^hops of it. A 400 FE/t grant over one hop delivers 380.
                int spend = Math.min(grant, source.energy());
                int delivered = Grid.delivered(spend, supplier.hops, configured);
                if (delivered <= 0) continue;
                source.spend(spend);
                binding.grant = delivered;
                binding.credit += delivered;
                binding.supplier = supplier.pos;
                binding.hops = supplier.hops;
                binding.efficiency = efficiency;
                state.outputUsed.merge(supplier.pos, delivered, Integer::sum);
            }
        }
    }

    /**
     * Withdraws up to {@code max} FE from a load's banked credit. Returns the delivered FE; the tower
     * energy was already deducted during the allocation, so this never creates energy.
     */
    static int draw(ServerLevel level, GridLoad load, int max) {
        beginTick(level);
        Binding binding = state(level).loads.get(load);
        if (binding == null) { register(level, load); return 0; }
        int taken = Math.min(Math.max(0, max), binding.credit);
        if (taken <= 0) return 0;
        binding.credit -= taken;
        return taken;
    }

    // ---------------------------------------------------------------- block / drone adapters

    static int drawBlock(ServerLevel level, BlockEntity owner, int max, int priority, IntSupplier demand) {
        LevelState state = state(level);
        BlockLoad load = state.blockLoads.get(owner.getBlockPos());
        if (load == null || load.owner != owner) {
            load = new BlockLoad(owner, priority, demand);
            state.blockLoads.put(owner.getBlockPos(), load);
            state.loads.putIfAbsent(load, new Binding(load));
        }
        return draw(level, load, max);
    }

    static int drawDrone(ServerLevel level, net.minecraft.world.entity.Entity drone, int perSecondFe) {
        LevelState state = state(level);
        DroneLoad load = state.droneLoads.computeIfAbsent(drone.getUUID(), id -> {
            DroneLoad created = new DroneLoad(drone);
            state.loads.putIfAbsent(created, new Binding(created));
            return created;
        });
        load.perSecond = Math.max(0, perSecondFe);
        return draw(level, load, load.perSecond);
    }

    static boolean droneInGrid(ServerLevel level, net.minecraft.world.entity.Entity drone) {
        LevelState state = state(level);
        DroneLoad load = state.droneLoads.get(drone.getUUID());
        if (load == null) return false;
        Binding binding = state.loads.get(load);
        return binding != null && binding.supplier != null && Grid.reachable(binding.hops);
    }

    /** Test/command readout of one device's current attachment and grant. */
    static String debug(ServerLevel level, GridLoad load) {
        beginTick(level);
        Binding binding = state(level).loads.get(load);
        if (binding == null) return "unregistered";
        return "attached=" + binding.attached + " supplier=" + binding.supplier + " hops=" + binding.hops
                + " efficiency=" + binding.efficiency + " credit=" + binding.credit + " demand=" + binding.demand;
    }

    /** Whether a block position currently has a tower within the configured radius. */
    public static boolean hasTowerNear(ServerLevel level, BlockPos pos) {
        LevelState state = state(level);
        beginTick(level);
        return nearestTower(state, pos, radius()) != null;
    }

    /** Right-click readout; refreshes the tick first so the numbers are from the current world state. */
    public static NetworkInfo info(ServerLevel level, BlockPos towerPos) {
        LevelState state = state(level);
        beginTick(level);
        Integer component = state.componentOf.get(towerPos);
        int towers = 0, devices = 0, used = 0;
        if (component != null) {
            for (BlockPos pos : state.towers.keySet()) {
                if (!component.equals(state.componentOf.get(pos))) continue;
                towers++;
                used += state.outputUsed.getOrDefault(pos, 0);
            }
            for (Binding binding : state.loads.values()) {
                if (binding.attached == null) continue;
                Integer attachedComponent = state.componentOf.get(binding.attached);
                if (component.equals(attachedComponent)) devices++;
            }
        }
        return new NetworkInfo(towers, devices, used, ServerOptions.towerThroughput(), radius());
    }

    // ---------------------------------------------------------------- topology

    private static void rebuild(LevelState state, int radius) {
        state.dirty = false;
        Map<BlockPos, Integer> componentOf = new HashMap<>();
        List<List<BlockPos>> components = new ArrayList<>();
        long radiusSq = (long) radius * radius;
        for (BlockPos start : state.towers.keySet()) {
            if (componentOf.containsKey(start)) continue;
            int id = components.size();
            List<BlockPos> members = new ArrayList<>();
            java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            componentOf.put(start, id);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                members.add(current);
                Vec3 here = Vec3.atCenterOf(current);
                for (BlockPos other : state.towers.keySet()) {
                    if (componentOf.containsKey(other)) continue;
                    if (here.distanceToSqr(Vec3.atCenterOf(other)) <= radiusSq) {
                        componentOf.put(other, id);
                        queue.add(other);
                    }
                }
            }
            components.add(members);
        }
        state.components = components;
        state.componentOf = componentOf;
    }

    private static PowerTowerEntity towerAt(LevelState state, BlockPos pos) {
        PowerTowerEntity tower = state.towers.get(pos);
        if (tower == null || tower.isRemoved()) return null;
        return tower;
    }

    private static BlockPos nearestTower(LevelState state, BlockPos pos, int radius) {
        if (state.towers.isEmpty()) return null;
        long limit = (long) radius * radius;
        Vec3 here = Vec3.atCenterOf(pos);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos tower : state.towers.keySet()) {
            double distance = here.distanceToSqr(Vec3.atCenterOf(tower));
            if (distance > limit || distance >= bestDistance) continue;
            best = tower;
            bestDistance = distance;
        }
        return best;
    }

    /**
     * BFS from the attached tower to the nearest tower with energy. The attached tower is hop 0, so a
     * local supply keeps 100%; every tower edge adds one hop and multiplies the configured efficiency.
     */
    private static Supplier nearestPoweredTower(LevelState state, BlockPos attached, int radius) {
        if (state.towers.isEmpty()) return null;
        long limit = (long) radius * radius;
        Map<BlockPos, Integer> seen = new HashMap<>();
        java.util.ArrayDeque<Supplier> queue = new java.util.ArrayDeque<>();
        queue.add(new Supplier(attached, 0));
        seen.put(attached, 0);
        while (!queue.isEmpty()) {
            Supplier current = queue.poll();
            PowerTowerEntity tower = towerAt(state, current.pos);
            if (tower != null && tower.energy() > 0) return current;
            if (current.hops >= Grid.MAX_HOPS) continue;
            Vec3 here = Vec3.atCenterOf(current.pos);
            for (BlockPos other : state.towers.keySet()) {
                if (seen.containsKey(other)) continue;
                if (here.distanceToSqr(Vec3.atCenterOf(other)) > limit) continue;
                seen.put(other, current.hops + 1);
                queue.add(new Supplier(other, current.hops + 1));
            }
        }
        return null;
    }
}
