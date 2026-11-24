package dev.dronepal;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class BatteryModelsGameTest {
    @GameTest(template = "curios_test_room")
    public static void batteryHoverAndIcon(GameTestHelper test) {
        for (var item : Hardware.BATTERIES) {
            var cell = item.toStack();
            int capacity = Hardware.capacity(cell);
            for (int percent : new int[]{100, 0, 50}) {
                if (percent != 100) Hardware.setEnergy(cell, capacity * percent / 100);
                var gauge = (BatteryItem.Gauge) cell.getTooltipImage().orElseThrow();
                test.assertTrue(gauge.capacity() == capacity && gauge.percent() == percent, "incorrect native tooltip gauge");
                test.assertTrue(cell.isBarVisible() && cell.getBarWidth() == Math.round(percent * .13f), "wrong inventory bar");
                var lines = cell.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(test.getLevel()), null, net.minecraft.world.item.TooltipFlag.NORMAL);
                boolean found = lines.stream().anyMatch(line -> {
                    if (!(line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text)) return false;
                    if (!text.getKey().equals("dronepal.tip.battery_charge")) return false;
                    var numbers = java.text.NumberFormat.getIntegerInstance(java.util.Locale.ROOT);
                    return java.util.Arrays.equals(text.getArgs(), new Object[]{numbers.format(gauge.stored()), numbers.format(capacity), percent});
                });
                test.assertTrue(found, "native item hover omitted FE or percent: " + cell);
            }
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room")
    public static void vendorLimitsFollowPhysicalCell(GameTestHelper test) {
        int[] capacities = {250000,600000,1200000,180000,350000,600000,150000,300000,280000,560000};
        int[] rates = {600,800,1000,300,400,500,400,500,420,560};
        test.assertTrue(Hardware.BATTERIES.size() == 10, "expected ten battery models");
        for (int i = 0; i < rates.length; i++) {
            var cell = Hardware.BATTERIES.get(i).toStack();
            var port = cell.getCapability(Capabilities.EnergyStorage.ITEM);
            Hardware.setEnergy(cell, 0);
            test.assertTrue(port != null && port.getMaxEnergyStored() == capacities[i], "wrong capacity for " + cell);
            test.assertTrue(port.receiveEnergy(10000, true) == rates[i] && port.getEnergyStored() == 0, "simulation changed cell");
            test.assertTrue(port.receiveEnergy(100, false) == 100, "first charge failed");
            var terminal = Dronepal.DRONE.toStack();
            Hardware.save(terminal, List.of(cell));
            test.assertTrue(Hardware.charge(terminal, 10000, false) == rates[i] - 100, "installation reset physical charge budget");
            test.assertTrue(Hardware.charge(terminal, 10000, false) == 0 && Hardware.energy(terminal) == rates[i], "exceeded model rate");
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room")
    public static void oldBatteryStacksMigrate(GameTestHelper test) {
        var provider = test.getLevel().registryAccess();
        for (int tier = 1; tier <= 3; tier++) {
            var cell = Hardware.BATTERIES.get(tier - 1).toStack();
            int original = tier == 3 ? 3000000 : 71234;
            CustomData.update(DataComponents.CUSTOM_DATA, cell, data -> { data.putInt("energy", original); data.putString("marker", "saved"); });
            var serialized = (CompoundTag) cell.save(provider);
            serialized.putString("id", "huhu_dronepal:battery_mk" + tier);
            ItemStack restored = ItemStack.parseOptional(provider, serialized);
            test.assertTrue(restored.is(cell.getItem()) && Hardware.batteryTier(restored) == tier, "legacy registry entry did not migrate: " + tier);
            test.assertTrue(((BatteryItem.Gauge)restored.getTooltipImage().orElseThrow()).stored() == Hardware.energy(restored), "legacy tooltip did not migrate");
            test.assertTrue(Hardware.energy(restored) == Math.min(original, Hardware.capacity(restored)), "migration lost stored FE");
            test.assertTrue("saved".equals(restored.get(DataComponents.CUSTOM_DATA).copyTag().getString("marker")), "lost attached metadata");
            var terminal = Dronepal.DRONE.toStack();
            Hardware.save(terminal, List.of(restored));
            var roundTrip = ItemStack.parseOptional(provider, (CompoundTag) terminal.save(provider));
            test.assertTrue(Hardware.energy(roundTrip) == Hardware.energy(restored), "nested cell failed serialization");
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room")
    public static void chargeRateMultiplierScalesReceive(GameTestHelper test) {
        // Pure arithmetic: no config mutation, so parallel tests never observe a different rate.
        var model = Batteries.model(Hardware.BATTERIES.get(3).toStack()); // 万向 100, 300 FE/t
        test.assertTrue(model != null && Batteries.receiveRate(model, 1.0D) == 300, "baseline rate changed");
        test.assertTrue(Batteries.receiveRate(model, 2.0D) == 600, "2x multiplier does not double the rate");
        test.assertTrue(Batteries.receiveRate(model, 4.0D) == 1200, "4x multiplier does not scale the rate");
        test.assertTrue(Batteries.receiveRate(model, 20.0D) == 6000, "upper bound of the multiplier is wrong");
        test.assertTrue(Math.abs(ServerOptions.chargeMultiplier() - 1.0D) < 1e-9, "default multiplier should be 1.0");
        test.succeed();
    }
}
