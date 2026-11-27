package dev.dronepal.charging;

import dev.dronepal.Hardware;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The item energy capability of a battery must serve both directions without becoming a way around the
 * per-tick ceiling: NeoForge only consults the first registered provider, and that provider is
 * {@code BatteryEnergyStorage}, not the dock's charging port.
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class BatteryPortGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void batteryCapabilityChargesAndDrainsWithinBudget(GameTestHelper test) {
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        // The per-tick ceiling is the model's own receive rate, which the rebalance doubled with the cell
        // (ahi_bat_mk1: 500,000 FE / 1,200 FE/t instead of 250,000 / 600), so derive it here.
        int rate = dev.dronepal.Batteries.model(cell).maxReceive();
        test.assertTrue(rate == 1_200, "Mk.I receive rate is not the rebalanced 1,200 FE/t: " + rate);
        Hardware.setEnergy(cell, 0);
        var port = cell.getCapability(Capabilities.EnergyStorage.ITEM);
        test.assertTrue(port != null && port.canReceive() && port.canExtract(),
                "the battery item capability must both charge and drain");
        test.assertTrue(port.receiveEnergy(9_999, true) == rate && Hardware.energy(cell) == 0,
                "simulation wrote energy to the cell");
        test.assertTrue(port.receiveEnergy(rate, false) == rate, "first charge of the tick was not accepted");
        test.assertTrue(port.receiveEnergy(rate, false) == 0,
                "a second charge in the same tick exceeded the " + rate + " FE/t ceiling");
        test.assertTrue(port.extractEnergy(200, false) == 200 && Hardware.energy(cell) == rate - 200,
                "draining through the item capability stopped working");
        test.runAfterDelay(2, () -> {
            test.assertTrue(port.receiveEnergy(9_999, false) == rate, "the per-tick charge budget did not reset");
            test.succeed();
        });
    }
}
