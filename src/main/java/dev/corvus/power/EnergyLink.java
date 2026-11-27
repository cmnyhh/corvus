package dev.corvus.power;

import dev.corvus.ServerOptions;
import dev.corvus.core.EnergyNet;
import dev.corvus.power.io.PowerCableBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The cable network, loader side: walk the cables, ask every block they touch for an offer and a demand,
 * hand the arrays to {@link EnergyNet#allocate} and move exactly what the plan names.
 *
 * <p><b>What changed.</b> The cable used to be a one-way feeder for {@code power_port}: a port pulled
 * through the run, and nothing else could. A battery, a generator and a wireless tower on the same run
 * never saw each other. Now a cable is a general conductor — every one of our FE blocks that touches a
 * cable becomes an <em>endpoint</em> and the network moves energy source &rarr; sink once per tick,
 * under one budget and one priority order ({@link EnergyNet}).</p>
 *
 * <h2>The endpoint table</h2>
 *
 * <pre>
 *   block             source  sink  sink priority      how much it may give / take per tick
 *   burner_generator  yes     no    -                  2000 FE/t out of its 100k buffer
 *   solar_generator   yes     no    -                  {@code solarOutput} out of its 50k buffer
 *   battery_bank      yes     yes   battery (1)        2000 FE/t out / 2000 FE/t in
 *   power_tower       yes     yes   tower (0)          2000 FE/t out of its 1M pool / 2000 FE/t in
 *   ore_mill          no      yes   mill (2)           20 FE/t (= OreMillRules.FE_PER_TICK)
 *   charging_dock     no      yes   dock (3)           2000 FE/t x chargeRateMultiplier
 *   power_port        no      yes   port (4)           {@code portOutput} FE/t into its 8k buffer
 * </pre>
 *
 * <p>Every one of those classes implements {@link Node}: {@link Node#netOffer()} and
 * {@link Node#netDemand()} are already window-aware (the block's own per-tick ceiling and what other
 * callers spent this tick are subtracted), {@link Node#netSpend(int)} / {@link Node#netAccept(int,
 * boolean)} are the real moves. That is why a battery that is charging cannot also be drained past its
 * own 2,000 FE/t window by two networks in the same tick, and why a port can never receive more than
 * {@code portOutput} from the grid <em>and</em> the cable together.</p>
 *
 * <h2>One pass per tick, one plan per network</h2>
 *
 * <p>{@link #tick(ServerLevel)} runs from {@code ServerTickEvent.Post}, i.e. after every block entity
 * has ticked, so the offers and the free room are the numbers a player would read off the blocks. Each
 * connected component of cables is walked once ({@link EnergyNet#CELL_LIMIT} cells at most), its
 * endpoints are collected once, and the plan is applied immediately: sources are asked for their
 * {@code spent} amount, sinks receive their {@code granted} amount. A sink that turns out to accept
 * less than planned (its neighbour filled it in the same tick) gives its FE back to a source that can
 * still take it, and anything that cannot be placed is counted in {@link #lost(ServerLevel)} — the
 * acceptance tests assert that number is 0 and that the sum of source decreases equals the sum of sink
 * increases.</p>
 *
 * <p>The cable itself still stores nothing: {@code power_cable} refuses {@code receiveEnergy}, so a
 * generator pushing into a run is a no-op and nothing can be stranded in the wire. A foreign mod may
 * still pull through a cable ({@code extractEnergy} on {@link dev.corvus.power.io.PowerCableEntity}),
 * and that pull shares the network's per-tick budget through the same ledger.</p>
 */
public final class EnergyLink {
    /**
     * One block that can trade FE with a cable network.
     *
     * <p>Implementations are block entities of this mod; the two probe methods are read-only and are
     * polled once per network per tick, the two move methods are the only ones that change state.
     * A block may be a source, a sink or both (battery bank, power tower).</p>
     */
    public interface Node {
        /** FE this block could give a cable network right now, already capped by its own window. */
        int netOffer();

        /** FE this block could take from a cable network right now, window and free room included. */
        int netDemand();

        /** Sink priority, lower served first ({@link EnergyNet#PRIORITY_TOWER} ... {@code PORT}). */
        int netPriority();

        /** Moves at most {@code amount} FE out of this block; returns what actually moved. */
        int netSpend(int amount);

        /** Takes at most {@code amount} FE into this block; {@code simulate} only answers. */
        int netAccept(int amount, boolean simulate);
    }

    /** One endpoint of a network: the block, the node it exposes, and the cable cell that touches it. */
    private record Endpoint(BlockPos pos, Node node, BlockPos cell) {}

    /** Per-network counters for one tick: what the plan moved, and what the external pull already used. */
    private static final class Ledger {
        long tick = Long.MIN_VALUE;
        int used;
        int flow;
    }

    private static final class LevelState {
        final Set<BlockPos> cables = new java.util.LinkedHashSet<>();
        /** Cell -> total sink demand of its network, from the last completed pass (generator gating). */
        final Map<Long, Integer> demandByCell = new HashMap<>();
        final Map<Long, Ledger> ledgers = new HashMap<>();
        long tick = Long.MIN_VALUE;
        int lastMoved;
        int lastLost;
        int networkCount;
    }

    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();

    private EnergyLink() {}

    private static LevelState state(ServerLevel level) {
        return STATES.computeIfAbsent(level, ignored -> new LevelState());
    }

    // ---------------------------------------------------------------- registry

    /** Called by {@link dev.corvus.power.io.PowerCableEntity} when a run is loaded or placed. */
    public static void registerCable(ServerLevel level, BlockPos pos) {
        state(level).cables.add(pos.immutable());
    }

    /** Called when a cable is broken or its chunk unloads. */
    public static void unregisterCable(ServerLevel level, BlockPos pos) {
        LevelState state = state(level);
        state.cables.remove(pos);
        state.demandByCell.remove(pos.asLong());
    }

    // ---------------------------------------------------------------- the tick pass

    /**
     * One allocation pass for every cable network of this level. Idempotent inside one game tick, so a
     * test that ticks a level by hand and the server tick handler can both call it.
     */
    public static void tick(ServerLevel level) {
        LevelState state = state(level);
        long now = level.getGameTime();
        if (state.tick == now) return;
        state.tick = now;
        state.demandByCell.clear();
        state.lastMoved = 0;
        state.lastLost = 0;
        state.networkCount = 0;
        if (state.cables.isEmpty()) return;

        int cap = EnergyNet.limit(state.cables.size()); // generous upper bound before walking
        int levelUsed = 0;
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : new ArrayList<>(state.cables)) {
            if (visited.contains(start)) continue;
            if (!isCable(level, start)) {
                state.cables.remove(start);
                continue;
            }
            List<BlockPos> cells = walk(level, start, visited);
            if (cells.isEmpty()) continue;
            state.networkCount++;
            int budget = Math.min(EnergyNet.clampThroughput(ServerOptions.cableThroughput()), cap - levelUsed);
            int moved = runNetwork(level, state, cells, now, Math.max(0, budget));
            levelUsed += moved;
            state.lastMoved += moved;
        }
    }

    /** Walks one network from {@code start}, marks every cell in {@code visited} and returns them. */
    private static List<BlockPos> walk(Level level, BlockPos start, Set<BlockPos> visited) {
        List<BlockPos> cells = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos origin = start.immutable();
        seen.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && cells.size() < EnergyNet.CELL_LIMIT) {
            BlockPos cell = queue.poll();
            cells.add(cell);
            for (Direction side : Direction.values()) {
                BlockPos next = cell.relative(side);
                if (seen.contains(next) || !isCable(level, next)) continue;
                seen.add(next.immutable());
                queue.add(next.immutable());
            }
        }
        visited.addAll(seen);
        return cells;
    }

    private static boolean isCable(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        return level.getBlockState(pos).getBlock() instanceof PowerCableBlock;
    }

    /**
     * Collects endpoints, works out which sinks are within {@link EnergyNet#MAX_LENGTH} cells of a
     * source, makes the plan and applies it. Returns the FE that actually moved.
     */
    private static int runNetwork(ServerLevel level, LevelState state, List<BlockPos> cells, long now, int budget) {
        Map<BlockPos, Endpoint> endpoints = collect(level, cells);
        if (endpoints.isEmpty()) {
            for (BlockPos cell : cells) state.demandByCell.put(cell.asLong(), 0);
            return 0;
        }
        List<Endpoint> list = new ArrayList<>(endpoints.values());
        int count = list.size();

        // Sink reach: distance (in cable cells) from the nearest cell that touches a source endpoint.
        Map<Long, Integer> distance = sourceDistances(level, cells, list);

        int[] offer = new int[count];
        int[] demand = new int[count];
        int[] priority = new int[count];
        int sinkDemand = 0;
        for (int i = 0; i < count; i++) {
            Endpoint endpoint = list.get(i);
            Node node = endpoint.node();
            offer[i] = Math.max(0, node.netOffer());
            int want = Math.max(0, node.netDemand());
            Integer reach = distance.get(endpoint.cell().asLong());
            if (reach == null || !EnergyNet.withinRun(reach)) want = 0; // too far from any source
            demand[i] = want;
            priority[i] = node.netPriority();
            sinkDemand += want;
        }
        for (BlockPos cell : cells) state.demandByCell.put(cell.asLong(), sinkDemand);

        EnergyNet.Plan plan = EnergyNet.allocate(offer, demand, priority, budget);
        if (plan.isEmpty()) return 0;

        // Probe every sink first, so the FE we pull is exactly the FE that will be accepted.
        int[] deliver = new int[count];
        int planned = 0;
        for (int i = 0; i < count; i++) {
            if (plan.granted(i) <= 0) continue;
            int accepted = list.get(i).node().netAccept(plan.granted(i), true);
            deliver[i] = Math.max(0, Math.min(accepted, plan.granted(i)));
            planned += deliver[i];
        }
        if (planned <= 0) return 0;

        // Pull, in the plan's source order, never more than the sinks can take.
        int need = planned;
        for (int i = 0; i < count && need > 0; i++) {
            int want = Math.min(plan.spent(i), need);
            if (want <= 0) continue;
            need -= Math.max(0, list.get(i).node().netSpend(want));
        }
        int wire = planned - need;

        // Deliver, in priority order (the endpoint list keeps walk order, the plan already ordered it).
        int moved = 0;
        for (int i = 0; i < count && wire > 0; i++) {
            if (deliver[i] <= 0) continue;
            int give = Math.min(deliver[i], wire);
            int accepted = Math.max(0, list.get(i).node().netAccept(give, false));
            wire -= accepted;
            moved += accepted;
        }
        // Anything a sink refused after it probed acceptance goes back to a block that can hold it, so
        // a same-tick race can never destroy FE; what still cannot be placed is reported, not hidden.
        if (wire > 0) {
            for (int i = 0; i < count && wire > 0; i++) {
                wire -= Math.max(0, list.get(i).node().netAccept(wire, false));
            }
        }
        if (wire > 0) state.lastLost += wire;

        Ledger ledger = ledger(state, cells.get(0).asLong(), now);
        ledger.used += moved;
        ledger.flow += moved;
        return moved;
    }

    /** Every block of ours that touches a cell of this network, deduplicated by position. */
    private static Map<BlockPos, Endpoint> collect(ServerLevel level, List<BlockPos> cells) {
        Map<BlockPos, Endpoint> endpoints = new LinkedHashMap<>();
        for (BlockPos cell : cells) {
            for (Direction side : Direction.values()) {
                BlockPos neighbour = cell.relative(side);
                if (endpoints.containsKey(neighbour)) continue;
                if (!level.isLoaded(neighbour)) continue;
                BlockState state = level.getBlockState(neighbour);
                if (state.getBlock() instanceof PowerCableBlock) continue;
                BlockEntity entity = level.getBlockEntity(neighbour);
                if (entity instanceof Node node) {
                    endpoints.put(neighbour.immutable(), new Endpoint(neighbour.immutable(), node, cell));
                }
            }
        }
        return endpoints;
    }

    /**
     * Breadth-first distance, in cable cells, from the nearest cell that touches a source endpoint.
     * A sink whose distance is missing or longer than {@link EnergyNet#MAX_LENGTH} is out of reach.
     */
    private static Map<Long, Integer> sourceDistances(ServerLevel level, List<BlockPos> cells, List<Endpoint> endpoints) {
        Map<Long, Integer> distance = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (Endpoint endpoint : endpoints) {
            if (endpoint.node().netOffer() <= 0) continue;
            long key = endpoint.cell().asLong();
            if (distance.containsKey(key)) continue;
            distance.put(key, 0);
            queue.add(endpoint.cell());
        }
        if (queue.isEmpty()) return distance;
        Set<Long> cellsOfNetwork = new HashSet<>();
        for (BlockPos cell : cells) cellsOfNetwork.add(cell.asLong());
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            int here = distance.get(cell.asLong());
            if (here >= EnergyNet.MAX_LENGTH) continue;
            for (Direction side : Direction.values()) {
                BlockPos next = cell.relative(side);
                long key = next.asLong();
                if (!cellsOfNetwork.contains(key) || distance.containsKey(key)) continue;
                distance.put(key, here + 1);
                queue.add(next);
            }
        }
        return distance;
    }

    // ---------------------------------------------------------------- external pull + readouts

    /**
     * A foreign consumer pulling through a cable, capped by what is left of the network's per-tick
     * budget. Our own blocks never take this path: they are endpoints of the pass above.
     */
    public static int pull(Level level, BlockPos cablePos, int want, boolean simulate) {
        if (!(level instanceof ServerLevel server) || want <= 0) return 0;
        LevelState state = state(server);
        long now = server.getGameTime();
        List<BlockPos> cells = walk(server, cablePos, new HashSet<>());
        if (cells.isEmpty()) return 0;
        Ledger ledger = ledger(state, cells.get(0).asLong(), now);
        int budget = EnergyNet.clampThroughput(ServerOptions.cableThroughput());
        int allowed = Math.min(want, Math.max(0, budget - ledger.used));
        if (allowed <= 0) return 0;
        int moved = 0;
        for (Endpoint endpoint : collect(server, cells).values()) {
            if (moved >= allowed) break;
            int offer = Math.max(0, endpoint.node().netOffer());
            if (offer <= 0) continue;
            moved += Math.max(0, endpoint.node().netSpend(Math.min(allowed - moved, offer)));
        }
        if (moved > 0 && !simulate) {
            ledger.used += moved;
            ledger.flow += moved;
        }
        return Math.min(moved, allowed);
    }

    /** FE/t the cell's network moved during the most recent tick (status line). */
    public static int flow(Level level, BlockPos cablePos) {
        if (!(level instanceof ServerLevel server)) return 0;
        List<BlockPos> cells = walk(server, cablePos, new HashSet<>());
        if (cells.isEmpty()) return 0;
        Ledger ledger = state(server).ledgers.get(cells.get(0).asLong());
        return ledger == null || ledger.tick != server.getGameTime() ? 0 : ledger.flow;
    }

    /**
     * Whether a machine should consider itself wanted: true when one of its faces touches a cable whose
     * network had demand last tick (or has not been ticked yet). Generators use this before burning a
     * new fuel item, so "a cable is attached" is not mistaken for "somebody wants the power".
     */
    public static boolean wantsPower(Level level, BlockPos machinePos) {
        if (!(level instanceof ServerLevel server)) return false;
        LevelState state = state(server);
        for (Direction side : Direction.values()) {
            BlockPos neighbour = machinePos.relative(side);
            if (!isCable(server, neighbour)) continue;
            Integer demand = state.demandByCell.get(neighbour.asLong());
            if (demand == null || demand > 0) return true; // unknown network: let it burn, it self-corrects
        }
        return false;
    }

    /** Whether a cable network touches this position at all (status/tests). */
    public static boolean hasNetworkNear(Level level, BlockPos pos) {
        if (level == null) return false;
        for (Direction side : Direction.values()) {
            if (isCable(level, pos.relative(side))) return true;
        }
        return false;
    }

    /** FE/t the last pass moved across every network of this level (diagnostics). */
    public static int movedThisTick(ServerLevel level) {
        LevelState state = state(level);
        return state.tick == level.getGameTime() ? state.lastMoved : 0;
    }

    /** FE the last pass could not place anywhere; a non-zero value is a bug, never a saving. */
    public static int lost(ServerLevel level) {
        LevelState state = state(level);
        return state.tick == level.getGameTime() ? state.lastLost : 0;
    }

    /** Networks walked in the last pass (diagnostics). */
    public static int networks(ServerLevel level) {
        LevelState state = state(level);
        return state.tick == level.getGameTime() ? state.networkCount : 0;
    }

    private static Ledger ledger(LevelState state, long root, long now) {
        Ledger ledger = state.ledgers.computeIfAbsent(root, ignored -> new Ledger());
        if (ledger.tick != now) {
            ledger.tick = now;
            ledger.used = 0;
            ledger.flow = 0;
        }
        if (state.ledgers.size() > 512) {
            state.ledgers.entrySet().removeIf(entry -> entry.getValue().tick != now);
        }
        return ledger;
    }
}
