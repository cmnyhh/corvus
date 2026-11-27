package dev.dronepal.power.io;

import dev.corvus.core.PowerIo;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A cable run as seen from one cell, for the right-click status line only.
 *
 * <p>The energy itself no longer moves through this class: {@link dev.dronepal.power.EnergyLink} owns
 * the walk, the per-tick budget and the source &rarr; sink allocation, and the cable entity's
 * {@code extractEnergy} serves foreign pulls from there. What is left here is the cheap display walk a
 * player triggers by right-clicking a cable — how many cells the run has, how many ports sit on it, and
 * whether it reaches further than {@link PowerIo#CABLE_LENGTH} blocks (a longer path carries nothing).</p>
 */
final class CableNetwork {
    private CableNetwork() {}

    /** Result of a walk from one cell. */
    static final class Scan {
        final int cells;
        final int ports;
        final boolean tooLong;

        Scan(int cells, int ports, boolean tooLong) {
            this.cells = cells;
            this.ports = ports;
            this.tooLong = tooLong;
        }
    }

    /**
     * Walks the run from {@code origin}.
     *
     * <p>A run that continues past {@link PowerIo#CABLE_LENGTH} cells (or past
     * {@link PowerIo#CABLE_SCAN_LIMIT} cells) is flagged {@link Scan#tooLong}: the cells beyond it are
     * not visited, so a player is told the run is dead instead of being shown a number that pretends
     * otherwise.</p>
     */
    static Scan scan(Level level, BlockPos origin) {
        Map<BlockPos, Integer> depth = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        int ports = 0;
        boolean tooLong = false;
        depth.put(origin, 0);
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            int here = depth.get(cell);
            for (Direction side : Direction.values()) {
                BlockPos neighbour = cell.relative(side);
                if (!level.isLoaded(neighbour)) continue;
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
                } else if (here + 1 > PowerIo.CABLE_LENGTH && PowerIoSources.machine(state)) {
                    tooLong = true;
                }
            }
        }
        return new Scan(depth.size(), ports, tooLong);
    }
}
