package dev.corvus.power.grid;

import dev.corvus.ServerOptions;
import dev.corvus.core.Grid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * The 1,000,000 FE pool of one power tower (1.20.1 / Forge). The block entity lives in the bottom
 * segment, so a save round trip carries a single energy value; the middle and top segments are plain
 * blocks. Every tick the tower pulls up to {@link Grid#TOWER_PULL_RATE} FE/t from the six neighbouring
 * FE blocks, and the grid service spends the pool when it allocates a device grant.
 */
public final class PowerTowerEntity extends BlockEntity implements IEnergyStorage, dev.corvus.power.EnergyLink.Node {
    public static final int CAPACITY = Grid.TOWER_BUFFER;
    private static final int PULL_RATE = Grid.TOWER_PULL_RATE;

    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> this);
    private int energy;
    /** Per-tick receive window so six neighbours cannot each push the whole rate. */
    private long receiveTick = Long.MIN_VALUE;
    private int receivedThisTick;
    private int lamp = -1;
    private boolean registered;

    public PowerTowerEntity(BlockPos pos, BlockState state) {
        super(TowerRegistry.TYPE.get(), pos, state);
    }

    @Override public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel server) {
            TowerGridService.registerTower(server, this);
        } else if (level != null && level.isClientSide) {
            dev.corvus.power.grid.client.TowerClientState.add(level, worldPosition);
        }
    }

    @Override public void setRemoved() {
        if (level instanceof ServerLevel server) {
            TowerGridService.unregisterTower(server, this);
        } else if (level != null && level.isClientSide) {
            dev.corvus.power.grid.client.TowerClientState.remove(level, worldPosition);
        }
        super.setRemoved();
    }

    public void tick(ServerLevel level) {
        if (!registered) {
            TowerGridService.registerTower(level, this);
            registered = true;
        }
        pull(level);
        syncLamp(level);
    }

    private void pull(ServerLevel level) {
        int room = CAPACITY - energy;
        if (room <= 0) return;
        int budget = Math.min(PULL_RATE, room);
        int moved = 0;
        for (Direction side : Direction.values()) {
            if (budget <= 0) break;
            BlockEntity neighbour = level.getBlockEntity(worldPosition.relative(side));
            if (neighbour == null || neighbour == this) continue;
            IEnergyStorage storage = neighbour.getCapability(ForgeCapabilities.ENERGY, side.getOpposite()).resolve().orElse(null);
            if (storage == null || storage == this || !storage.canExtract()) continue;
            int got = storage.extractEnergy(budget, false);
            if (got <= 0) continue;
            moved += got;
            budget -= got;
        }
        if (moved > 0) {
            energy += moved;
            setChanged();
        }
    }

    private void syncLamp(ServerLevel level) {
        int band = lampBand();
        if (band == lamp) return;
        BlockPos topPos = worldPosition.above(2);
        BlockState top = level.getBlockState(topPos);
        if (!(top.getBlock() instanceof PowerTowerPartBlock part) || part.part() != PowerTowerPartBlock.Part.TOP) return;
        if (top.getValue(PowerTowerPartBlock.LAMP) == band) { lamp = band; return; }
        level.setBlock(topPos, top.setValue(PowerTowerPartBlock.LAMP, band), Block.UPDATE_CLIENTS);
        lamp = band;
    }

    /** 0 = empty (red), 1 = half (amber), 2 = full (green). */
    public int lampBand() {
        int percent = (int) Math.min(100L, (long) energy * 100L / CAPACITY);
        return percent >= 67 ? 2 : percent >= 34 ? 1 : 0;
    }

    public int energy() { return energy; }
    public int capacity() { return CAPACITY; }

    void spend(int amount) {
        if (amount <= 0) return;
        energy = Math.max(0, energy - amount);
        setChanged();
    }

    public void setEnergy(int value) {
        energy = Mth.clamp(value, 0, CAPACITY);
        setChanged();
    }

    Component status(ServerLevel level) {
        TowerGridService.NetworkInfo info = TowerGridService.info(level, worldPosition);
        Component line = Component.translatable("message.dronepal.tower_status",
                energy, CAPACITY, info.radius(), info.towers(), info.outputUsed(), info.outputLimit(), info.devices());
        // Drones are priority 0 on this tower's budget, so say how much of the output went to them.
        if (info.droneUsed() > 0) line = line.copy().append(" · ")
                .append(Component.translatable("message.dronepal.tower_drone", info.droneUsed()));
        return line;
    }

    // ---- Forge energy ----

    @Override public int receiveEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide) return 0;
        long now = level.getGameTime();
        int used = receiveTick == now ? receivedThisTick : 0;
        int accepted = Mth.clamp(amount, 0, Math.min(PULL_RATE - used, CAPACITY - energy));
        if (!simulate && accepted > 0) {
            receiveTick = now;
            receivedThisTick = used + accepted;
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }

    // ---- cable network endpoint (loaded by dev.corvus.power.EnergyLink) ----
    //
    // A tower is both a source and a sink of a cable network: a cable may fill its pool (so a base can
    // be kept alive from a battery bank across the yard) and it may also give the cable what its pool
    // holds (so a run of batteries can be charged from a distant tower). `canExtract` stays false on the
    // capability on purpose — devices go through the grid service, the only thing allowed to spend the
    // pool at 0.95^hops — and the cable is served by netSpend/netAccept, so each tick there is exactly
    // one way a battery sees the tower's energy.

    /** FE the pool could hand to a cable network this tick, capped by the tower's own 2,000 FE/t. */
    @Override public int netOffer() {
        if (level == null || level.isClientSide) return 0;
        return Math.max(0, Math.min(energy, PULL_RATE));
    }

    /** Free room this tick: the pool's spare capacity inside the same 2,000 FE/t input window. */
    @Override public int netDemand() {
        if (level == null || level.isClientSide) return 0;
        long now = level.getGameTime();
        int used = receiveTick == now ? receivedThisTick : 0;
        return Math.max(0, Math.min(CAPACITY - energy, PULL_RATE - used));
    }

    /** A tower's buffer is refilled before anything else: an empty tower stops the whole wireless grid. */
    @Override public int netPriority() { return dev.corvus.core.EnergyNet.PRIORITY_TOWER; }

    /** Moves pool FE into the cable; the pool is the same one the grid service spends from. */
    @Override public int netSpend(int amount) {
        int moved = Math.max(0, Math.min(amount, energy));
        if (moved > 0) spend(moved);
        return moved;
    }

    @Override public int netAccept(int amount, boolean simulate) { return receiveEnergy(amount, simulate); }

    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return CAPACITY; }
    @Override public boolean canReceive() { return true; }
    @Override public boolean canExtract() { return false; }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        energy = Mth.clamp(tag.getInt("Energy"), 0, CAPACITY);
    }
}
