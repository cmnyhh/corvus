package dev.dronepal.power;

import dev.corvus.core.Bank;
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
 * same way: no cables required, <b>receivers filled in face order</b> (the same rule the 1.20.1 build
 * always used, computed by {@link Bank#pushTo}) and a report of how many receivers were found so the
 * caller can idle instead of burning fuel into a wall. The 1.21.1 build used to split a push evenly
 * between neighbours, which made two pads next to a 40 FE/t burner take 20 FE/t each where the 1.20.1
 * build fed the first one 40 FE/t — the split is gone, both builds fill in order now.</p>
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
        List<IEnergyStorage> targets = targets(level, pos);
        if (targets.isEmpty()) return new Result(0, 0);
        if (budget <= 0) return new Result(0, targets.size());
        int[] capacities = new int[targets.size()];
        int[] stored = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            IEnergyStorage storage = targets.get(i);
            capacities[i] = storage.getMaxEnergyStored();
            stored[i] = storage.getEnergyStored();
        }
        int[] plan = Bank.pushTo(budget, capacities, stored);
        int moved = 0;
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0) continue;
            moved += Math.max(0, targets.get(i).receiveEnergy(plan[i], false));
        }
        return new Result(moved, targets.size());
    }

    /**
     * Whether any neighbour would accept a single FE right now ({@link Bank#PROBE_FE}).
     *
     * <p>The probe is a simulation, so asking costs nothing, and it is the same predicate the 1.20.1
     * link used before it lit a new fuel item: a full neighbour is not a reason to burn coal.</p>
     */
    public static boolean canAccept(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos.relative(side), side.getOpposite());
            if (storage != null && storage.canReceive() && Bank.canTakeProbe(storage.receiveEnergy(Bank.PROBE_FE, true))) {
                return true;
            }
        }
        return false;
    }
}
