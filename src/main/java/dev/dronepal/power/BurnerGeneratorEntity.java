package dev.dronepal.power;

import dev.dronepal.ServerOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Burns any furnace fuel and pushes the result into neighbouring FE blocks.
 *
 * <p>The generator owns a 100,000 FE buffer rather than pushing straight from the flame: that keeps a
 * puff of smoke from vanishing because the neighbour happened to be full for one tick. It only burns
 * while it has somewhere to put the energy, so a generator with nothing attached does not quietly eat
 * a chest of coal — see {@link #tick}.</p>
 */
public final class BurnerGeneratorEntity extends BlockEntity implements IEnergyStorage {
    public static final int BUFFER = 100_000;
    private final FuelBuffer fuel = new FuelBuffer();
    private int energy;

    BurnerGeneratorEntity(BlockPos pos, BlockState state) {
        super(PowerBlocks.BURNER_TYPE.get(), pos, state);
    }

    public int output() { return ServerOptions.burnerOutput(); }

    void tick(ServerLevel level) {
        int moved = 0;
        if (energy > 0) {
            PowerPush.Result pushed = PowerPush.push(level, worldPosition, Math.min(energy, output()));
            moved = pushed.moved();
            if (moved > 0) energy -= moved;
        }
        // Idle when the output is full or nothing can take power: "no fuel burnt into nothing". The
        // receiver test is a one-FE simulated offer (PowerPush#canAccept / Bank#canTakeProbe), the same
        // rule the 1.20.1 burner uses, so a present-but-full neighbour stops the fire on both builds.
        boolean canBurn = energy < BUFFER && PowerPush.canAccept(level, worldPosition);
        int generated = 0;
        if (canBurn && fuel.hasFuel() && fuel.ignite(level, worldPosition)) {
            generated = fuel.draw(Math.min(output(), BUFFER - energy));
            energy += generated;
        }
        if (moved > 0 || generated > 0) setChanged();
    }

    /** True when the block is at least receiving fuel; used by the block's status line. */
    boolean burning() { return fuel.burnRemaining() > 0; }

    int insertFuel(ItemStack held) { return fuel.insert(held, FuelValues.SLOT_LIMIT); }

    ItemStack takeFuel() { return fuel.takeAll(); }

    Component status() {
        Component fuelLabel = fuel.stack().isEmpty()
                ? Component.translatable("message.dronepal.fuel_empty")
                : Component.empty().append(fuel.stack().getHoverName()).append(" ×" + fuel.count());
        return Component.translatable("message.dronepal.burner_status", energy, BUFFER, fuelLabel,
                Component.translatable(burning() ? "message.dronepal.burner_running" : "message.dronepal.burner_idle"));
    }

    void dropContents() {
        if (level == null || level.isClientSide) return;
        ItemStack left = fuel.takeAll();
        if (!left.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.9D,
                    worldPosition.getZ() + 0.5D, left);
        }
    }

    // ---- IEnergyStorage: the buffer is readable/drainable from any side ----

    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }
    @Override public int extractEnergy(int amount, boolean simulate) {
        int moved = Math.clamp(amount, 0, Math.min(energy, 2_000));
        if (!simulate && moved > 0) { energy -= moved; setChanged(); }
        return moved;
    }
    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canReceive() { return false; }
    @Override public boolean canExtract() { return true; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        fuel.save(tag, registries);
        tag.putInt("Energy", energy);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fuel.load(tag, registries);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
    }
}
