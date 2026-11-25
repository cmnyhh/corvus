package dev.corvus.power.io;

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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * The cable run as seen from one cell: length, downstream interfaces, the machines that can feed it,
 * and the per-tick throughput ledger shared by every consumer drawing through the same run.
 *
 * <p>Mirror of the 1.21.1 walk. There is no network tick: a cable only moves energy when somebody pulls
 * ({@code extractEnergy} on the cable next to a port), so an idle grid costs nothing. The scan is a
 * breadth-first walk bounded by {@link PowerIo#CABLE_LENGTH} hops and {@link PowerIo#CABLE_SCAN_LIMIT}
 * cells, and the run carries at most {@link PowerIo#CABLE_RATE} FE/t in total.</p>
 */
final class CableNetwork {
    /** Per-level ledger, one entry per run, keyed by the run's smallest cell so every member agrees. */
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

    /** Walks the run from {@code origin}, collecting the machines that can feed it. */
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
     * Pulls up to {@code want} FE out of the machines along the run, in walk order ("顺序喂饱"), never
     * more than what is left of the run's {@code CABLE_RATE} window this tick.
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
            IEnergyStorage storage = storage(level, source.pos(), source.side().getOpposite());
            if (storage == null || !storage.canExtract()) continue;
            moved += storage.extractEnergy(allowed - moved, simulate);
        }
        if (!simulate && moved > 0) {
            ledger.used += moved;
            ledger.flow += moved;
        }
        return Math.min(moved, allowed);
    }

    private static IEnergyStorage storage(Level level, BlockPos pos, Direction side) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return null;
        return entity.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
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
        if (ledgers.size() > 512) {
            ledgers.entrySet().removeIf(entry -> entry.getValue().tick != now);
        }
        return ledger;
    }
}
