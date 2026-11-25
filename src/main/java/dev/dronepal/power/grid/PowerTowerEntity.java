package dev.dronepal.power.grid;

import dev.corvus.core.Grid;
import dev.dronepal.ServerOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * The 1,000,000 FE pool of one power tower. The block entity sits in the <b>bottom</b> segment; the
 * middle and top segments are plain blocks, so the whole tower is one block entity and a save round
 * trip has exactly one energy value to preserve.
 *
 * <p>Each tick the tower actively pulls up to {@link Grid#TOWER_PULL_RATE} FE/t out of the six
 * neighbouring FE blocks (battery bank, generator, ...) until its buffer is full. Devices never touch
 * this class directly: {@link TowerGridService} spends the pool when it allocates a grant, which is
 * why a device drawing through four towers pays {@code 0.95^4} of what the supplying tower loses.</p>
 *
 * <p>The top segment's {@code lamp} block state follows the charge in three bands (empty red, half
 * amber, full green). It is a block state, not a synced block-entity field, so the client sees the
 * change through the ordinary block update and the renderer needs no packet of its own.</p>
 */
public final class PowerTowerEntity extends BlockEntity implements IEnergyStorage {
    public static final int CAPACITY = Grid.TOWER_BUFFER;
    private static final int PULL_RATE = Grid.TOWER_PULL_RATE;

    private int energy;
    /** Per-tick receive window, so six neighbours cannot each push the full rate. */
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
            // Client-only index for the coverage overlay; never loaded on a dedicated server.
            dev.dronepal.power.grid.client.TowerClientState.add(level, worldPosition);
        }
    }

    @Override public void setRemoved() {
        if (level instanceof ServerLevel server) {
            TowerGridService.unregisterTower(server, this);
        } else if (level != null && level.isClientSide) {
            dev.dronepal.power.grid.client.TowerClientState.remove(level, worldPosition);
        }
        super.setRemoved();
    }

    void tick(ServerLevel level) {
        if (!registered) {
            TowerGridService.registerTower(level, this);
            registered = true;
        }
        pull(level);
        syncLamp(level);
    }

    /** Active pull from the six neighbours, capped at 2,000 FE/t in total. */
    private void pull(ServerLevel level) {
        int room = CAPACITY - energy;
        if (room <= 0) return;
        int budget = Math.min(PULL_RATE, room);
        int moved = 0;
        for (Direction side : Direction.values()) {
            if (budget <= 0) break;
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK,
                    worldPosition.relative(side), side.getOpposite());
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

    /** Writes the lamp band into the top segment, but only when the band changes. */
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

    /** 0 = empty (red), 1 = half (amber), 2 = full (green). Thresholds match the bank gauge. */
    public int lampBand() {
        int percent = (int) Math.min(100L, (long) energy * 100L / CAPACITY);
        return percent >= 67 ? 2 : percent >= 34 ? 1 : 0;
    }

    public int energy() { return energy; }
    public int capacity() { return CAPACITY; }

    /** Called by the grid service only, after the efficiency-scaled amount was computed. */
    void spend(int amount) {
        if (amount <= 0) return;
        energy = Math.max(0, energy - amount);
        setChanged();
    }

    /** Test / command seam. Clamped to the real buffer. */
    public void setEnergy(int value) {
        energy = Mth.clamp(value, 0, CAPACITY);
        setChanged();
    }

    Component status(ServerLevel level) {
        TowerGridService.NetworkInfo info = TowerGridService.info(level, worldPosition);
        return Component.translatable("message.dronepal.tower_status",
                energy, CAPACITY, info.radius(), info.towers(), info.outputUsed(), info.outputLimit(), info.devices());
    }

    // ---- Forge/NeoForge energy capability: accepting is fine, extracting from outside is not ----

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
    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return CAPACITY; }
    @Override public boolean canReceive() { return true; }
    @Override public boolean canExtract() { return false; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Mth.clamp(tag.getInt("Energy"), 0, CAPACITY);
    }
}
