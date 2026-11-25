package dev.dronepal.power.io;

import dev.corvus.core.PowerIo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * The cable run as seen from one cell: length, downstream interfaces, the machines that can feed it,
 * and the per-tick throughput ledger shared by every consumer drawing through the same run.
 *
 * <p>There is no network tick. A cable only moves energy when somebody pulls ({@code extractEnergy} on
 * the cable next to a port), which means an idle grid costs nothing and a run of 32 blocks does not
 * turn into 32 block-entity ticks. The scan is a breadth-first walk from the drawing cell, bounded by
 * {@link PowerIo#CABLE_LENGTH} hops and {@link PowerIo#CABLE_SCAN_LIMIT} cells.</p>
 *
 * <p>Two budgets apply, both in {@link PowerIo}: a run carries at most {@code CABLE_RATE} FE/t in total
 * (the ledger below, keyed by the run's canonical cell) and the walk stops after {@code CABLE_LENGTH}
 * blocks, so a longer run carries nothing and says so in its status line.</p>
 */
final class CableNetwork {
    /** Per-server-level ledger, one entry per run. Keyed by the run's smallest cell so every member agrees. */
    private static final Map<Level, Map<Long, Ledger>> LEDGERS = new WeakHashMap<>();

    private CableNetwork() {}

    /** One machine the run can draw from, seen through the cell that touches it. */
    record Source(BlockPos pos, Direction side, int distance) {}

    /** Result of a walk from one cell. */
    static final class Scan {
        final long root;
        final int cells;
        final int ports;
        final boolean tooLong;
        final List<Source> sources;

        Scan(long root, int cells, int ports, boolean tooLong, List<Source> sources) {
            this.root = root;
            this.cells = cells;
            this.ports = ports;
            this.tooLong = tooLong;
            this.sources = sources;
        }
    }

    /** Tick-scoped counters for one run: throughput already used, and the last tick's flow for display. */
    static final class Ledger {
        long tick = Long.MIN_VALUE;
        int used;
        int flow;
    }

    /**
     * Walks the run from {@code origin}, collecting the machines that can feed it.
     *
     * <p>A machine is in reach when its distance from the drawing cell is at most
     * {@link PowerIo#CABLE_LENGTH}. A run that continues past that boundary (or past
     * {@link PowerIo#CABLE_SCAN_LIMIT} cells) is flagged {@link Scan#tooLong}: the cells beyond it are
     * not visited, so the run simply carries nothing instead of carrying power an unbounded distance.</p>
     */
    static Scan scan(Level level, BlockPos origin) {
        Map<BlockPos, Integer> depth = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<Source> sources = new ArrayList<>();
        long root = origin.asLong();
        int ports = 0;
        boolean tooLong = false;
        depth.put(origin, 0);
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            int here = depth.get(cell);
            root = Math.min(root, cell.asLong());
            for (Direction side : Direction.values()) {
                BlockPos neighbour = cell.relative(side);
                BlockState state = level.getBlockState(neighbour);
                if (state.getBlock() instanceof PowerCableBlock) {
                    if (depth.containsKey(neighbour)) continue;
                    if (here + 1 > PowerIo.CABLE_LENGTH || depth.size() >= PowerIo.CABLE_SCAN_LIMIT) {
                        tooLong = true;
                        continue;
                    }
                    depth.put(neighbour, here + 1);
                    queue.add(neighbour);
                } else if (state.getBlock() instanceof PowerPortBlock) {
                    ports++;
                } else if (PowerIoSources.machine(state)) {
                    if (PowerIo.withinRun(here + 1)) {
                        sources.add(new Source(neighbour, side, here + 1));
                    } else {
                        tooLong = true;
                    }
                }
            }
        }
        return new Scan(root, depth.size(), ports, tooLong, sources);
    }

    /** Reads a run's ledger without creating one (status display). */
    static int flow(Level level, long root, long now) {
        Map<Long, Ledger> ledgers = LEDGERS.get(level);
        if (ledgers == null) return 0;
        Ledger ledger = ledgers.get(root);
        return ledger == null || ledger.tick != now ? 0 : ledger.flow;
    }

    /**
     * Pulls up to {@code want} FE out of the machines along the run, in walk order ("顺序喂饱": the
     * first machine is drained before the second is asked), never more than what is left of the run's
     * {@code CABLE_RATE} window this tick.
     *
     * <p>{@code simulate} answers the same question without moving anything or touching the ledger, so
     * a mod that probes the cable before pulling gets a truthful number.</p>
     */
    static int draw(Level level, BlockPos origin, int want, boolean simulate) {
        if (want <= 0) return 0;
        Scan scan = scan(level, origin);
        long now = level.getGameTime();
        Ledger ledger = ledger(level, scan.root, now);
        int allowed = Math.min(want, PowerIo.remainder(PowerIo.CABLE_RATE, ledger.used));
        if (allowed <= 0) return 0;
        int moved = 0;
        for (Source source : scan.sources) {
            if (moved >= allowed) break;
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK,
                    source.pos(), source.side().getOpposite());
            if (storage == null || !storage.canExtract()) continue;
            moved += storage.extractEnergy(allowed - moved, simulate);
        }
        if (!simulate && moved > 0) {
            ledger.used += moved;
            ledger.flow += moved;
        }
        return Math.min(moved, allowed);
    }

    /** The run's ledger for this tick, reset when the world ticks over. */
    private static Ledger ledger(Level level, long root, long now) {
        Map<Long, Ledger> ledgers = LEDGERS.computeIfAbsent(level, ignored -> new HashMap<>());
        Ledger ledger = ledgers.computeIfAbsent(root, ignored -> new Ledger());
        if (ledger.tick != now) {
            ledger.tick = now;
            ledger.used = 0;
            ledger.flow = 0;
        }
        // Runs come and go as players build; drop the bookkeeping of runs that stopped moving power.
        if (ledgers.size() > 512) {
            ledgers.entrySet().removeIf(entry -> entry.getValue().tick != now);
        }
        return ledger;
    }
}
