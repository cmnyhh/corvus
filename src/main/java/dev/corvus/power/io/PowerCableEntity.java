package dev.corvus.power.io;

import dev.corvus.core.PowerIo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * One cell of an FE run.
 *
 * <p>Mirror of the 1.21.1 cell: a wire, not a battery. It stores nothing and pulls on demand from the
 * machines along the run ({@link CableNetwork#draw}), so an idle run is free and a run that is too long
 * delivers zero instead of trapping a buffer's worth of energy.</p>
 *
 * <p>Right-click reports the run's current flow, how many ports sit on it and how many cells it is; a
 * run longer than {@link PowerIo#CABLE_LENGTH} carries nothing and says so.</p>
 */
public final class PowerCableEntity extends BlockEntity implements IEnergyStorage {
    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> this);
    PowerCableEntity(BlockPos pos, BlockState state) {
        super(PowerIoRegistry.CABLE_TYPE.get(), pos, state);
    }

    /** Current numbers for the status line, computed on demand. */
    Component status(ServerLevel level) {
        CableNetwork.Scan scan = CableNetwork.scan(level, worldPosition);
        int flow = CableNetwork.flow(level, scan.root, level.getGameTime());
        return PowerCableBlock.status(flow, scan.ports, scan.cells);
    }

    void readStatus(ServerLevel level, ServerPlayer player) {
        if (player == null) return;
        player.displayClientMessage(status(level), false);
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

    @Override public int extractEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        return CableNetwork.draw(level, worldPosition, amount, simulate);
    }

    @Override public int getEnergyStored() { return 0; }
    @Override public int getMaxEnergyStored() { return 0; }
    @Override public boolean canExtract() { return true; }
    @Override public boolean canReceive() { return false; }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
    }

}
