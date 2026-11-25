package dev.dronepal.power;

import dev.corvus.core.Bank;
import dev.dronepal.ServerOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * The middle of the power chain: a 500,000 FE buffer that both accepts and pushes 2,000 FE/t.
 *
 * <p>Charge belongs to the block, not the item. Breaking it drops the housing and the energy is gone —
 * that is deliberate: a portable 500k FE battery would make every other power block pointless.
 * Right-clicking reports the charge, because a block with no GUI still has to be readable.</p>
 */
public final class BatteryBankEntity extends BlockEntity implements IEnergyStorage {
    public static final int RATE = 2_000;

    private int energy;
    private long inputTick = Long.MIN_VALUE;
    private long outputTick = Long.MIN_VALUE;
    private int inputThisTick;
    private int outputThisTick;

    BatteryBankEntity(BlockPos pos, BlockState state) {
        super(PowerBlocks.BANK_TYPE.get(), pos, state);
    }

    public int capacity() { return ServerOptions.batteryBankCapacity(); }

    void tick(ServerLevel level) {
        if (energy > 0) {
            int moved = PowerPush.push(level, worldPosition, Math.min(energy, RATE)).moved();
            if (moved > 0) {
                energy -= moved;
                setChanged();
            }
        }
        syncChargeWindow(level);
    }

    /**
     * 0-100, rounded to the nearest percent ({@link Bank#percent}); capacity is a config value and
     * always positive. Both builds round, so 66.7 % reads 67 % and selects the third segment.
     */
    public int percent() {
        return Bank.percent(energy, capacity());
    }

    /**
     * 0 = 0-33%, 1 = 34-66%, 2 = 67-99%, 3 = 100%. Mirrors {@link BatteryBankBlock#CHARGE} and is
     * printed as-is by the status line (the display domain is 0..3 on both builds).
     */
    public int chargeWindow() {
        return Bank.window(energy, capacity());
    }

    /**
     * Only writes the block state when the window actually changes (at most a handful of times over a
     * charge cycle), so the model swap never becomes a per-tick block update.
     */
    private void syncChargeWindow(ServerLevel level) {
        BlockState state = getBlockState();
        if (!state.hasProperty(BatteryBankBlock.CHARGE)) return;
        int window = chargeWindow();
        if (state.getValue(BatteryBankBlock.CHARGE) == window) return;
        level.setBlock(worldPosition, state.setValue(BatteryBankBlock.CHARGE, window), 3);
    }

    Component status() {
        return Component.translatable("message.dronepal.bank_status", energy, capacity(), percent(), chargeWindow());
    }

    @Override public int receiveEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide) return 0;
        long now = level.getGameTime();
        int used = inputTick == now ? inputThisTick : 0;
        int accepted = Math.clamp(amount, 0, Math.min(RATE - used, capacity() - energy));
        if (!simulate && accepted > 0) {
            inputTick = now;
            inputThisTick = used + accepted;
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override public int extractEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide) return 0;
        long now = level.getGameTime();
        int used = outputTick == now ? outputThisTick : 0;
        int moved = Math.clamp(amount, 0, Math.min(RATE - used, energy));
        if (!simulate && moved > 0) {
            outputTick = now;
            outputThisTick = used + moved;
            energy -= moved;
            setChanged();
        }
        return moved;
    }

    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return capacity(); }
    @Override public boolean canReceive() { return true; }
    @Override public boolean canExtract() { return true; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Mth.clamp(tag.getInt("Energy"), 0, capacity());
    }
}
