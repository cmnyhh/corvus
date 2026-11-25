package dev.corvus;

import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * A battery stack seen through Forge's item energy capability.
 *
 * <p>Corvus keeps cell charge in its own NBT key ({@link CorvusKeys#ENERGY}) and charges it through
 * {@link Batteries#charge} so the per-tick budget holds. That is invisible to everything else in the
 * pack, which means no other FE consumer — the Stringblade quiver, a Mekanism charger, a machine —
 * can draw from a battery you are carrying.</p>
 *
 * <p>This provider exposes that charge as a normal {@link IEnergyStorage}:</p>
 * <ul>
 *   <li>{@link #canExtract()} is {@code true}: the cell can be drained by anything that understands FE.</li>
 *   <li>{@link #canReceive()} is {@code true}: filling goes through {@link Hardware#charge}, the same
 *       call the dock uses, so the per-tick ceiling and the {@code charge_tick} budget still hold.</li>
 * </ul>
 *
 * <p>Both directions have to live here. This provider comes from {@code Item#initCapabilities}, which
 * Forge consults <em>before</em> the ones attached through {@code AttachCapabilitiesEvent} (the dock's
 * charge-only port). A receive-disabled view here would therefore shadow that port and make the cell
 * look unfillable to every FE charger in the pack. Delegating instead keeps the first answer complete.</p>
 *
 * <p>Attached per stack through {@link net.minecraft.world.item.Item#initCapabilities}; 1.20.1 has no
 * {@code RegisterCapabilitiesEvent} for items.</p>
 */
public final class BatteryEnergyCapability implements ICapabilityProvider, IEnergyStorage {
    private final ItemStack stack;
    private final LazyOptional<IEnergyStorage> holder = LazyOptional.of(() -> this);

    public BatteryEnergyCapability(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        return capability == ForgeCapabilities.ENERGY ? holder.cast() : LazyOptional.empty();
    }

    private Batteries.Model model() {
        return Batteries.model(stack);
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
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
            CorvusData.of(stack).setInt(CorvusKeys.ENERGY, stored - moved);
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
        Batteries.Model model = model();
        return model != null && Batteries.energy(stack, model) < model.capacity();
    }
}
