package dev.dronepal.power.io;

import dev.corvus.core.EnergyNet;
import dev.corvus.core.PowerIo;
import dev.dronepal.ServerOptions;
import dev.dronepal.power.EnergyLink;
import dev.dronepal.power.grid.GridLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Energy outlet for other mods: an 8,000 FE buffer that other mods' cables and machines may drain
 * through a plain {@link IEnergyStorage} ({@code canExtract}, never {@code canReceive}).
 *
 * <p>It fills itself, in this order, at most {@code portOutput} FE/t (default 1,000, config range
 * 100–4000) and at most one buffer's worth:</p>
 *
 * <ol>
 *   <li><b>the wireless grid</b> — if the port stands inside a tower's coverage radius it draws a
 *       {@link GridLink} grant (priority {@code PORT}, so our own machines are served first) at
 *       {@code 0.95^hops} efficiency;</li>
 *   <li><b>the cable network</b> — the port is a sink endpoint ({@link EnergyLink.Node}, priority
 *       {@code PORT}) and the network delivers into the buffer during its own tick;</li>
 *   <li><b>the blocks touching it</b> — a battery bank or a generator on a face other than the outlet.</li>
 * </ol>
 *
 * <p>A cable is <em>not</em> in that neighbour list any more: a run used to be drained by the port with
 * a pull loop, which is exactly what made a cable useless to every other block. Now the network pushes
 * and the port is one of its sinks, so "the tower is empty, fall back to the cable" happens without the
 * port knowing where the energy came from. The facing side is the outlet and is therefore <b>not</b> a
 * supply side; every face still answers the energy capability, so a foreign cable can attach wherever
 * it likes, and the port never accepts energy through it ({@code receiveEnergy} stays 0).</p>
 *
 * <p>The same per-tick ceiling governs what the port hands out, so two consumers pulling in the same
 * tick cannot each take a full {@code portOutput}: the first caller is filled, the second sees the
 * remainder ({@link PowerIo#remainder(int, int)}), which is the "顺序喂饱" rule the smoke test pins.</p>
 */
public final class PowerPortEntity extends BlockEntity implements IEnergyStorage, EnergyLink.Node {
    /** Buffer, FE. A socket, not a battery: the block loses what it holds when broken. */
    public static final int BUFFER = PowerIo.PORT_BUFFER;

    private int energy;
    /** Per-tick intake window: how much this port already pulled out of its neighbours. */
    private long pullTick = Long.MIN_VALUE;
    private int pulledThisTick;
    /** Per-tick output window: how much this port already handed to consumers. */
    private long outputTick = Long.MIN_VALUE;
    private int outputThisTick;
    /** FE handed out during the most recent tick, for the status line. */
    private long lastOutputTick = Long.MIN_VALUE;
    private int lastOutput;
    /** Supply blocks seen on the five non-outlet faces during the last tick. */
    private int suppliers;

    PowerPortEntity(BlockPos pos, BlockState state) {
        super(PowerIoRegistry.PORT_TYPE.get(), pos, state);
    }

    /** Configured FE/t, clamped to the advertised 100..4000 so display and behaviour agree. */
    public int outputRate() { return PowerIo.portOutput(ServerOptions.portOutput()); }

    public int storedEnergy() { return energy; }
    public int suppliers() { return suppliers; }

    /** FE/t handed out over the last completed tick; 0 once the world has ticked past it. */
    public int outputNow() {
        if (level == null) return 0;
        return lastOutputTick == level.getGameTime() ? lastOutput : 0;
    }

    void tick(ServerLevel level) {
        suppliers = countSuppliers(level);
        if (energy >= BUFFER) return;
        long now = level.getGameTime();
        if (pullTick != now) {
            pullTick = now;
            pulledThisTick = 0;
        }
        // 1) wireless grid first: a port inside a tower's radius is fed by the tower, at 0.95^hops.
        int left = room(level);
        if (left > 0) {
            int fromGrid = GridLink.draw(level, this, left, GridLink.PRIORITY_PORT,
                    () -> Math.min(BUFFER - energy, outputRate()));
            if (fromGrid > 0) {
                energy += fromGrid;
                pulledThisTick += fromGrid;
                setChanged();
                left = room(level);
            }
        }
        // 2) then the blocks that touch it. A cable is not one of them any more: the network delivers
        //    into this buffer during its own tick (see netDemand/netAccept below).
        if (left <= 0) return;
        Direction outlet = outletFacing();
        int moved = 0;
        for (Direction side : Direction.values()) {
            if (side == outlet) continue;
            BlockPos neighbour = worldPosition.relative(side);
            if (!PowerIoSources.supplying(level.getBlockState(neighbour))) continue;
            IEnergyStorage source = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbour, side.getOpposite());
            if (source == null || !source.canExtract()) continue;
            moved += source.extractEnergy(left - moved, false);
        }
        if (moved > 0) {
            energy += moved;
            pulledThisTick += moved;
            setChanged();
        }
    }

    /** Free buffer room left in this tick's intake window (neighbours, grid and cable share it). */
    private int room(ServerLevel level) {
        int used = pullTick == level.getGameTime() ? pulledThisTick : 0;
        return Math.min(PowerIo.intake(energy, BUFFER, outputRate()), PowerIo.remainder(outputRate(), used));
    }

    /** How many touching blocks could feed this port, regardless of how full they are. */
    private int countSuppliers(ServerLevel level) {
        Direction outlet = outletFacing();
        int count = 0;
        for (Direction side : Direction.values()) {
            if (side == outlet) continue;
            if (PowerIoSources.supplying(level.getBlockState(worldPosition.relative(side)))) count++;
        }
        return count;
    }

    private Direction outletFacing() {
        BlockState state = getBlockState();
        return state.hasProperty(PowerPortBlock.FACING) ? state.getValue(PowerPortBlock.FACING) : Direction.NORTH;
    }

    Component status() {
        return Component.translatable("message.dronepal.power_port.status",
                energy, BUFFER, outputRate(), suppliers);
    }

    // ---- Cable network endpoint (loaded by dev.dronepal.power.EnergyLink) ----
    //
    // The port is a sink only: it never gives energy back to the cable (that would be a loop through
    // another mod's machines). Its demand is the free buffer room inside the same portOutput window the
    // grid draw and the neighbour pull use, so all three sources together cannot exceed one window.

    @Override public int netOffer() { return 0; }

    @Override public int netDemand() {
        if (!(level instanceof ServerLevel server)) return 0;
        return room(server);
    }

    /** Served last: another mod's machines come after this mod's own towers, banks, mills and docks. */
    @Override public int netPriority() { return EnergyNet.PRIORITY_PORT; }

    @Override public int netSpend(int amount) { return 0; }

    @Override public int netAccept(int amount, boolean simulate) {
        if (!(level instanceof ServerLevel server) || amount <= 0) return 0;
        long now = server.getGameTime();
        int used = pullTick == now ? pulledThisTick : 0;
        int give = Math.min(amount, Math.min(PowerIo.intake(energy, BUFFER, outputRate()),
                PowerIo.remainder(outputRate(), used)));
        if (give <= 0) return 0;
        if (simulate) return give;
        if (pullTick != now) {
            pullTick = now;
            pulledThisTick = 0;
        }
        energy += give;
        pulledThisTick += give;
        setChanged();
        return give;
    }

    // ---- Energy capability: outlet only ----

    @Override public int extractEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        long now = level.getGameTime();
        if (outputTick != now) {
            outputTick = now;
            outputThisTick = 0;
        }
        int allowed = Math.min(Math.min(amount, energy), PowerIo.remainder(outputRate(), outputThisTick));
        if (allowed <= 0) return 0;
        if (!simulate) {
            outputThisTick += allowed;
            energy -= allowed;
            if (lastOutputTick != now) {
                lastOutputTick = now;
                lastOutput = 0;
            }
            lastOutput += allowed;
            setChanged();
        }
        return allowed;
    }

    /** The port never takes energy back: it is an outlet, so a foreign cable cannot push into it. */
    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }

    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canExtract() { return true; }
    @Override public boolean canReceive() { return false; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
    }
}
