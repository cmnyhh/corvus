package dev.dronepal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A battery stack seen through NeoForge's item energy capability.
 *
 * <p>Corvus keeps cell charge in {@code custom_data.energy} and charges it through
 * {@link Batteries#charge} so the per-tick budget holds. That is invisible to everything else in the
 * pack, which means no other FE consumer — the Stringblade quiver, a charger, a machine — can draw
 * from a battery the player is carrying.</p>
 *
 * <p>This view exposes that charge as a normal {@link IEnergyStorage}:</p>
 * <ul>
 *   <li>{@link #canExtract()} is {@code true}: anything that understands FE can drain the cell.</li>
 *   <li>{@link #canReceive()} is {@code false}: charging keeps going through the dock / coal path, so
 *       the per-tick ceiling and the {@code charge_tick} bookkeeping cannot be bypassed.</li>
 * </ul>
 *
 * <p>Registered in {@code Dronepal}'s constructor through {@code RegisterCapabilitiesEvent}.</p>
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
        return 0;
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
        return false;
    }
}
