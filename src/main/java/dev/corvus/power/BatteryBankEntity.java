package dev.corvus.power;

import dev.corvus.ServerOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Battery bank: a passive 500,000 FE buffer for a base's power grid.
 *
 * <p>Input and output are both capped at {@link #RATE} FE/t and nothing is lost on the way through.
 * The input ceiling is enforced with the same per-game-tick window the charging pad uses for cable
 * input, so ten generators pointing at one bank cannot pour 4,000 FE/t into it; the output ceiling is
 * the push budget per tick.</p>
 *
 * <p>It is deliberately dumb: a full bank simply refuses energy, an empty one offers none, and there is
 * no GUI — right-click prints the numbers. The stored FE stays with the block: breaking it drops the
 * block item with no charge in it.</p>
 */
public final class BatteryBankEntity extends BlockEntity implements IEnergyStorage {
    /** Both the input and the output ceiling, FE per tick. */
    public static final int RATE = 2_000;
    /**
     * Coarse fill gauge the block model switches on: 0 = empty..33%, 1 = 34..66%, 2 = 67..99%,
     * 3 = full. Four states only, so the model set stays small and the block only re-sends when a
     * segment boundary is crossed.
     */
    public static final IntegerProperty CHARGE = IntegerProperty.create("charge", 0, 3);

    private final net.minecraftforge.common.util.LazyOptional<net.minecraftforge.energy.IEnergyStorage> energyCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> this);
    private int energy;
    /** Per-game-tick input window, so N neighbours cannot each get a full {@link #RATE}. */
    private long inputTick = Long.MIN_VALUE;
    private int inputReceived;

    public BatteryBankEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.BANK_TYPE.get(), pos, state);
    }

    /** Configured capacity, tracked live so an operator edit needs no reload. */
    public int capacity() { return ServerOptions.batteryBankCapacity(); }

    public void tick(ServerLevel world) {
        syncGauge(world);
        int budget = Math.min(RATE, energy);
        if (budget <= 0) return;
        int sent = PowerPush.push(world, worldPosition, budget);
        if (sent > 0) {
            energy -= sent;
            setChanged();
        }
    }

    /**
     * 0..3 gauge segment for a charge level; the blockstate, the model and the status line all use it.
     *
     * <p>Same table as the 1.21.1 build, from {@link dev.corvus.core.Bank#window(int, int)}: the
     * boundaries are applied to the rounded percentage, so 2 of 3 cells (66.7 %) reads as segment 2.</p>
     */
    public static int bucket(int energy, int capacity) {
        return dev.corvus.core.Bank.window(energy, capacity);
    }

    /** 0-100, rounded to the nearest percent; clamps a forged charge above capacity. */
    public static int percent(int energy, int capacity) {
        return dev.corvus.core.Bank.percent(energy, capacity);
    }

    public int percent() { return percent(energy, capacity()); }

    public int gauge() { return bucket(energy, capacity()); }

    /**
     * Pushes the gauge segment into the block state, and only when it actually changed: the boundary is
     * crossed a handful of times per charge cycle, so this never becomes a per-tick block update.
     */
    private void syncGauge(ServerLevel world) {
        BlockState state = getBlockState();
        if (!state.hasProperty(CHARGE)) return;
        int level = gauge();
        if (state.getValue(CHARGE) == level) return;
        world.setBlock(worldPosition, state.setValue(CHARGE, level), Block.UPDATE_CLIENTS);
    }

    public Component status() {
        // Same shape as the 1.21.1 build: percent is filled into "%3$s%%" by the format string, and the
        // gauge is printed as the raw 0..3 segment (the block-state value), not a 1-based "x/4" — the two
        // builds used to print 3/4 and 2/4 for the same charge (R5).
        return Component.translatable("message.dronepal.bank_status", energy, capacity(), percent(), gauge());
    }

    public int storedEnergy() { return energy; }

    // ---- Forge energy ----

    @Override public int receiveEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide || amount <= 0) return 0;
        long now = level.getGameTime();
        int used = inputTick == now ? inputReceived : 0;
        int accepted = Mth.clamp(amount, 0, Math.min(RATE - used, capacity() - energy));
        if (!simulate && accepted > 0) {
            inputTick = now;
            inputReceived = used + accepted;
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override public int extractEnergy(int amount, boolean simulate) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, energy);
        if (!simulate && taken > 0) {
            energy -= taken;
            setChanged();
        }
        return taken;
    }

    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return capacity(); }
    @Override public boolean canReceive() { return energy < capacity(); }
    @Override public boolean canExtract() { return energy > 0; }

    // ---- Forge capability: this block is the storage, exactly like the charging pad ----

    @Override public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction side) {
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
    }

    @Override protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy);
    }

    @Override public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        energy = Mth.clamp(tag.getInt("Energy"), 0, capacity());
    }
}
