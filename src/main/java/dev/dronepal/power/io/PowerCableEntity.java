package dev.dronepal.power.io;

import dev.corvus.core.EnergyNet;
import dev.corvus.core.PowerIo;
import dev.dronepal.power.EnergyLink;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * One cell of an FE network.
 *
 * <p>The cell is a wire, not a battery: it stores <b>nothing</b>. The energy a network moves is pulled
 * from its source endpoints and delivered to its sinks by {@link EnergyLink#tick(ServerLevel)}, once per
 * tick, under one budget and one priority order. The cable is only the medium: it refuses
 * {@code receiveEnergy}, so a generator pushing into a run is a no-op and nothing can be stranded in
 * the wire (the spec allowed a 200 FE smoothing reserve; it is deliberately not taken for exactly that
 * reason).</p>
 *
 * <p>The energy capability is still published, because another mod's machine may want to draw from our
 * run: {@link #extractEnergy} serves that pull out of the network's sources, inside whatever is left of
 * the same per-tick budget the network's own pass uses ({@link EnergyLink#pull}). Our own blocks never
 * take that path — they are endpoints of the pass.</p>
 *
 * <p>Right-click reports the run's current flow, how many ports sit on it and how many cells it is. A
 * path longer than {@link EnergyNet#MAX_LENGTH} cells from source to sink carries nothing and the
 * status line says so; the length is still printed so the player can see how far over it is.</p>
 */
public final class PowerCableEntity extends BlockEntity implements IEnergyStorage {
    PowerCableEntity(BlockPos pos, BlockState state) {
        super(PowerIoRegistry.CABLE_TYPE.get(), pos, state);
    }

    @Override public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel server) EnergyLink.registerCable(server, worldPosition);
    }

    @Override public void setRemoved() {
        if (level instanceof ServerLevel server) EnergyLink.unregisterCable(server, worldPosition);
        super.setRemoved();
    }

    /** Current numbers for the status line, computed on demand. */
    Component status(ServerLevel level) {
        CableNetwork.Scan scan = CableNetwork.scan(level, worldPosition);
        return PowerCableBlock.status(EnergyLink.flow(level, worldPosition), scan.ports, scan.cells);
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

    /**
     * Serves a foreign pull out of this network's sources, inside the per-tick budget that is left after
     * the network's own source &rarr; sink pass. A network with nothing left to carry answers 0.
     */
    @Override public int extractEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        return EnergyLink.pull(level, worldPosition, amount, simulate);
    }

    @Override public int getEnergyStored() { return 0; }
    @Override public int getMaxEnergyStored() { return 0; }
    @Override public boolean canExtract() { return true; }
    @Override public boolean canReceive() { return false; }
}
