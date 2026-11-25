package dev.dronepal.power.io;

import dev.corvus.core.PowerIo;
import dev.dronepal.ServerOptions;
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
 * <p>It fills itself from the energy blocks of this mod that touch it — battery banks, generators, a
 * future power tower, and our own {@code power_cable} runs, which draw from wherever the run starts —
 * at most {@code portOutput} FE/t (default 1,000, config range 100–4000) and at most one buffer's worth.
 * The facing side is the outlet and is therefore <b>not</b> a supply side; every face still answers the
 * energy capability, so a foreign cable can attach wherever it likes.</p>
 *
 * <p>The same per-tick ceiling governs what the port hands out, so two consumers pulling in the same
 * tick cannot each take a full {@code portOutput}: the first caller is filled, the second sees the
 * remainder ({@link PowerIo#remainder(int, int)}), which is the "顺序喂饱" rule the smoke test pins.</p>
 */
public final class PowerPortEntity extends BlockEntity implements IEnergyStorage {
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
        int rate = outputRate();
        int left = Math.min(PowerIo.intake(energy, BUFFER, rate), PowerIo.remainder(rate, pulledThisTick));
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
