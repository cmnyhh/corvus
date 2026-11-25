package dev.corvus.power;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Pushing FE into the six neighbours through Forge's {@code IEnergyStorage} capability.
 *
 * <p>There is no cable network to plug into: a generator offers power straight to whatever sits next
 * to it, so a charging pad next to a burner is filled without any wiring, and a pipe mod's own block
 * (it also publishes {@code IEnergyStorage}) forwards the energy further.</p>
 *
 * <p>Only the neighbour's own capability is touched — never the global energy net — and every send is
 * a real {@code receiveEnergy(root, false)} call, so a full consumer simply answers 0 and no energy is
 * created or destroyed.</p>
 */
public final class EnergyLink {
    private EnergyLink() {}

    /**
     * Offers {@code amount} FE to the six neighbours in face order and returns what was accepted.
     * Leftover stays with the caller (the generator keeps it in its buffer).
     */
    public static int push(Level level, BlockPos pos, int amount) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        int left = amount;
        for (Direction side : Direction.values()) {
            if (left <= 0) break;
            IEnergyStorage target = neighbour(level, pos, side);
            if (target == null || !target.canReceive()) continue;
            int sent = target.receiveEnergy(left, false);
            if (sent > 0) left -= Math.min(sent, left);
        }
        return amount - left;
    }

    /**
     * Whether any neighbour would take a single FE right now.
     *
     * <p>Generators call this before they light a new fuel item (or before they count daylight), so
     * "no receiver, no burn" is a real rule and not just a full-buffer accident. The probe is a
     * simulation, so asking is free.</p>
     */
    public static boolean receiverPresent(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) return false;
        for (Direction side : Direction.values()) {
            IEnergyStorage target = neighbour(level, pos, side);
            if (target != null && target.canReceive() && target.receiveEnergy(1, true) > 0) return true;
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
