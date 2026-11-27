package dev.corvus.power;

import dev.corvus.core.Bank;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Pushing FE into the six neighbours through Forge's {@code IEnergyStorage} capability.
 *
 * <p>This is the direct, no-cable path: a generator offers power straight to whatever sits next to it,
 * so a charging pad next to a burner is filled without any wiring, and a pipe mod's own block (it also
 * publishes {@code IEnergyStorage}) forwards the energy further. The cable network is a different path
 * ({@link EnergyLink}): it collects endpoints and pushes source &rarr; sink once per tick. A block may
 * use both — a battery bank pushes to its direct neighbours and is also an endpoint of a run.</p>
 *
 * <p>Only the neighbour's own capability is touched — never the global energy net — and every send is
 * a real {@code receiveEnergy(root, false)} call, so a full consumer simply answers 0 and no energy is
 * created or destroyed.</p>
 *
 * <p>The split itself is the shared rule ({@link Bank#pushTo}): neighbours are filled <b>in face
 * order</b>, the first one taking as much as it has room for. The class used to be called
 * {@code EnergyLink}, which is now the name of the cable-network engine — the two paths are separate
 * and this file only knows about six neighbours.</p>
 */
public final class PowerPush {
    private PowerPush() {}

    /**
     * Offers {@code amount} FE to the six neighbours in face order and returns what was accepted.
     * Leftover stays with the caller (the generator keeps it in its buffer).
     */
    public static int push(Level level, BlockPos pos, int amount) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        List<IEnergyStorage> targets = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            IEnergyStorage target = neighbour(level, pos, side);
            if (target == null || !target.canReceive()) continue;
            targets.add(target);
        }
        if (targets.isEmpty()) return 0;
        int[] capacities = new int[targets.size()];
        int[] stored = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            IEnergyStorage target = targets.get(i);
            capacities[i] = target.getMaxEnergyStored();
            stored[i] = target.getEnergyStored();
        }
        int[] plan = Bank.pushTo(amount, capacities, stored);
        int moved = 0;
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] <= 0) continue;
            moved += Math.max(0, targets.get(i).receiveEnergy(plan[i], false));
        }
        return moved;
    }

    /**
     * Whether any neighbour would take a single FE right now ({@link Bank#PROBE_FE}).
     *
     * <p>Generators call this before they light a new fuel item (or before they count daylight), so
     * "no receiver, no burn" is a real rule and not just a full-buffer accident. The probe is a
     * simulation, so asking is free, and it is the shared predicate: a present-but-full neighbour
     * answers 0 and the fire stops on both builds.</p>
     */
    public static boolean receiverPresent(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) return false;
        for (Direction side : Direction.values()) {
            IEnergyStorage target = neighbour(level, pos, side);
            if (target != null && target.canReceive()
                    && Bank.canTakeProbe(target.receiveEnergy(Bank.PROBE_FE, true))) {
                return true;
            }
        }
        return false;
    }

    /** The neighbour's storage as seen from this block, or {@code null} when it has none. */
    private static IEnergyStorage neighbour(Level level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos.relative(side))) return null;
        BlockEntity entity = level.getBlockEntity(pos.relative(side));
        if (entity == null) return null;
        return entity.getCapability(ForgeCapabilities.ENERGY, side.getOpposite()).resolve().orElse(null);
    }
}
