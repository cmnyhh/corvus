package dev.dronepal.power;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Sends FE from a generator or buffer into every neighbour that exposes an energy capability.
 *
 * <p>Shared by the burner generator, the solar generator and the battery bank so all three act the
 * same way: no cables required, an even split between accepting neighbours, and a report of how many
 * receivers were found so the caller can idle instead of burning fuel into a wall.</p>
 */
public final class PowerPush {
    /**
     * @param moved     FE actually accepted this call
     * @param receivers neighbours that expose a receive-capable energy capability (full ones included,
     *                  so a caller can tell "nobody is there" from "everybody is full")
     */
    public record Result(int moved, int receivers) {
        public boolean anyReceiver() { return receivers > 0; }
    }

    private PowerPush() {}

    /** Neighbours on all six faces that can take power, in a stable order. */
    public static List<IEnergyStorage> targets(ServerLevel level, BlockPos pos) {
        List<IEnergyStorage> targets = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos.relative(side), side.getOpposite());
            if (storage == null || !storage.canReceive()) continue;
            targets.add(storage);
        }
        return targets;
    }

    /** How many neighbours could accept power at all, regardless of how full they are. */
    public static int receivers(ServerLevel level, BlockPos pos) { return targets(level, pos).size(); }

    public static Result push(ServerLevel level, BlockPos pos, int budget) {
        if (budget <= 0) return new Result(0, receivers(level, pos));
        List<IEnergyStorage> targets = targets(level, pos);
        if (targets.isEmpty()) return new Result(0, 0);
        int movedTotal = 0;
        int share = budget / targets.size();
        int extra = budget % targets.size();
        for (IEnergyStorage storage : targets) {
            int offer = share + (extra > 0 ? 1 : 0);
            if (extra > 0) extra--;
            if (offer <= 0) continue;
            movedTotal += Math.max(0, storage.receiveEnergy(offer, false));
        }
        return new Result(movedTotal, targets.size());
    }
}
