package dev.dronepal.power.io;

import dev.corvus.core.PowerIo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * One cell of an FE run.
 *
 * <p>The cell is a wire, not a battery: it stores <b>nothing</b>. Everything it hands out is pulled on
 * demand from the machines along the run ({@link CableNetwork#draw}), which is why there is no ticker —
 * an idle run costs nothing, and a run that is too long simply delivers zero instead of quietly trapping
 * a buffer's worth of energy. (The spec allowed "at most 200 FE of smoothing"; that option is
 * deliberately not taken, because our generators and banks <em>push</em> into anything that accepts
 * power, so a receiving cable would swallow 2,000 FE/t into a reserve nothing could ever spend.)</p>
 *
 * <p>Right-click reports the three numbers a builder needs: the run's current flow, how many ports sit
 * on it, and how many cells it is. A run longer than {@link PowerIo#CABLE_LENGTH} carries nothing and
 * the status line says so; the length is still printed so the player can see how far over it is.</p>
 */
public final class PowerCableEntity extends BlockEntity implements IEnergyStorage {
    PowerCableEntity(BlockPos pos, BlockState state) {
        super(PowerIoRegistry.CABLE_TYPE.get(), pos, state);
    }

    /** Current numbers for the status line, computed on demand. */
    Component status(ServerLevel level) {
        CableNetwork.Scan scan = CableNetwork.scan(level, worldPosition);
        int flow = CableNetwork.flow(level, scan.root, level.getGameTime());
        return PowerCableBlock.status(flow, scan.ports, scan.cells);
    }

    void readStatus(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
        Component line = status(level);
        if (player == null) return;
        player.displayClientMessage(line, false);
        if (CableNetwork.scan(level, worldPosition).tooLong) {
            player.displayClientMessage(Component.translatable("message.dronepal.power_cable.too_long",
                    PowerIo.CABLE_LENGTH), false);
        }
    }

    /** Length of the run this cell belongs to, in cells. */
    public int runLength(Level level) {
        return CableNetwork.scan(level, worldPosition).cells;
    }

    /** How many ports sit on this cell's run. */
    public int downstreamPorts(Level level) {
        return CableNetwork.scan(level, worldPosition).ports;
    }

    /** True when the run from this cell is longer than the advertised limit. */
    public boolean tooLong(Level level) {
        return CableNetwork.scan(level, worldPosition).tooLong;
    }

    /** A cable is a wire: it never accepts energy, so nothing can be stranded inside it. */
    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }

    /** Hands out FE the run pulls on demand from the machines along it, within the run's tick window. */
    @Override public int extractEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        return CableNetwork.draw(level, worldPosition, amount, simulate);
    }

    @Override public int getEnergyStored() { return 0; }
    @Override public int getMaxEnergyStored() { return 0; }
    @Override public boolean canExtract() { return true; }
    @Override public boolean canReceive() { return false; }
}
