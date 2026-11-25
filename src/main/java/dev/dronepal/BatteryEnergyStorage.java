package dev.dronepal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A battery stack seen through NeoForge's item energy capability: drain for the outside world, charge
 * through the same budgeted path the dock uses.
 *
 * <p>Corvus keeps cell charge in {@code custom_data.energy} so the per-tick ceiling can be enforced.
 * That is invisible to everything else in the pack, which means no other FE consumer — the Stringblade
 * quiver, a charger, a machine — could draw from a battery the player is carrying. This view exposes
 * that charge as a normal {@link IEnergyStorage}:</p>
 * <ul>
 *   <li>{@link #canExtract()} / {@link #extractEnergy} drain the cell directly.</li>
 *   <li>{@link #canReceive()} / {@link #receiveEnergy} delegate to {@link Hardware#charge}, which
 *       enforces the model's FE/t ceiling and the shared {@code charge_tick} bookkeeping. Receiving
 *       here therefore cannot refill a cell faster than the dock can.</li>
 * </ul>
 *
 * <p><b>Registration order matters.</b> NeoForge resolves an item capability by taking the first
 * non-null provider in registration order, and {@code Dronepal}'s constructor registers this class
 * before {@code ChargingDockRegistry} registers its own {@code BatteryPort}. This provider is the one
 * that wins for batteries, so it must cover both directions; the later registration stays harmless
 * (and still serves the drone entity).</p>
 */
public final class BatteryEnergyStorage implements IEnergyStorage {
    private final ItemStack stack;

    public BatteryEnergyStorage(ItemStack stack) {
        this.stack = stack;
    }

    private Batteries.Model model() {
        return Batteries.model(stack);
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        // Budgeted exactly like the dock: Batteries#charge owns the per-tick ceiling and the shared
        // charge_tick counter, so an external charger cannot outrun a dock.
        return Hardware.charge(stack, amount, simulate);
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        if (amount <= 0) return 0;
        Batteries.Model model = model();
        if (model == null) return 0;
        int stored = Batteries.energy(stack, model);
        int moved = Math.min(amount, stored);
        if (moved > 0 && !simulate) {
            // Same key the dock writes, so a cell drained here keeps its identity and tooltip.
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt("energy", stored - moved));
        }
        return Math.max(0, moved);
    }

    @Override
    public int getEnergyStored() {
        Batteries.Model model = model();
        return model == null ? 0 : Batteries.energy(stack, model);
    }

    @Override
    public int getMaxEnergyStored() {
        Batteries.Model model = model();
        return model == null ? 0 : model.capacity();
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
