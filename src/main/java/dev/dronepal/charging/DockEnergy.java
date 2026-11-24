package dev.dronepal.charging;

import dev.dronepal.Hardware;
import dev.dronepal.ServerOptions;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Energy lookup for whatever sits in the charging dock.
 *
 * <p>Corvus' own cells are handled by {@link Hardware}: they keep FE in {@code custom_data.energy}
 * and know their tier, capacity and per-tick ceiling. Anything else falls back to NeoForge's item
 * energy capability, so a foreign FE item (Mekanism energy tablet, Immersive Engineering capacitor,
 * ...) charges on the pad too instead of being rejected.</p>
 *
 * <p>Foreign items are capped to the pad's cable rate per tick ({@link ChargingDockEntity#BASE_CABLE_RATE}
 * scaled by {@link ServerOptions#chargeMultiplier()}), mirroring how the buffer treats cable input, so a
 * huge cell cannot be filled out of the 32,000 FE buffer within a single tick.</p>
 */
final class DockEnergy {
    private DockEnergy() {}

    /** True when the pad can meaningfully accept this stack at all. */
    static boolean chargeable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (Hardware.capacity(stack) > 0) return true;
        return itemCell(stack) != null;
    }

    static int energy(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (Hardware.capacity(stack) > 0) return Math.max(0, Hardware.energy(stack));
        IEnergyStorage cell = itemCell(stack);
        return cell == null ? 0 : Math.max(0, cell.getEnergyStored());
    }

    static int capacity(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (Hardware.capacity(stack) > 0) return Math.max(0, Hardware.capacity(stack));
        IEnergyStorage cell = itemCell(stack);
        return cell == null ? 0 : Math.max(0, cell.getMaxEnergyStored());
    }

    static int charge(ItemStack stack, int amount, boolean simulate) {
        if (stack.isEmpty() || amount <= 0) return 0;
        if (Hardware.capacity(stack) > 0) return Hardware.charge(stack, amount, simulate);
        IEnergyStorage cell = itemCell(stack);
        if (cell == null || !cell.canReceive()) return 0;
        int budget = Math.clamp(Math.round(ChargingDockEntity.BASE_CABLE_RATE * ServerOptions.chargeMultiplier()), 1, 1_000_000);
        int received = cell.receiveEnergy(Math.min(amount, budget), simulate);
        return Math.max(0, received);
    }

    /** NeoForge item energy capability of a non-Corvus stack, or {@code null} when it has none. */
    private static IEnergyStorage itemCell(ItemStack stack) {
        return stack.getCapability(Capabilities.EnergyStorage.ITEM);
    }
}
