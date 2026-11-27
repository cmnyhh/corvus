package dev.dronepal.charging;

import dev.dronepal.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class ChargingDockGameTest {
    /**
     * Builds the entity the client would end up with: a fresh dock that has only ever seen the update
     * tag. Reading it is the same as reading the hologram, so a mismatch here is a visible mismatch.
     */
    private static ChargingDockEntity clientView(ChargingDockEntity dock, net.minecraft.server.level.ServerLevel world) {
        ChargingDockEntity view = new ChargingDockEntity(dock.getBlockPos(), dock.getBlockState());
        view.handleUpdateTag(dock.getUpdateTag(world.registryAccess()), world.registryAccess());
        return view;
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void coalFillsClientVisibleBufferTickByTick(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var world = test.getLevel();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = test.absolutePos(pad);
        var dock = (ChargingDockEntity) world.getBlockEntity(pos);
        test.assertTrue(dock.getUpdatePacket() != null, "dock publishes no update packet, clients can never see it");
        var empty = clientView(dock, world);
        test.assertTrue(empty.storedEnergy() == 0 && empty.buffer() == 32_000, "empty dock does not report its buffer");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 2));
        test.useBlock(pad, player);
        var fuelled = clientView(dock, world);
        test.assertTrue(fuelled.coalCount() == 2, "coal was not mirrored immediately on insertion");
        test.runAfterDelay(20, () -> {
            var charged = clientView(dock, world);
            test.assertTrue(charged.storedEnergy() > fuelled.storedEnergy(), "buffer did not grow between ticks");
            test.assertTrue(charged.storedEnergy() == dock.getEnergyStored(), "client buffer differs from the server");
            test.assertTrue(charged.coalCount() == dock.coalCount() && charged.coalCount() == 0,
                    "coal count was not mirrored");
            // The pad releases 800 FE/t now, so two coals (2 x 4,000) are spent inside 20 ticks and the
            // whole yield ends up in the buffer. Before, 20 ticks only got through the first one.
            test.assertTrue(charged.storedEnergy() == 8_000, "two coals should buffer 8000 FE, got " + charged.storedEnergy());
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void dockedCellAppearsChargesAndClearsOnTake(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var world = test.getLevel();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = test.absolutePos(pad);
        var dock = (ChargingDockEntity) world.getBlockEntity(pos);
        var port = world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        test.assertTrue(port != null && port.receiveEnergy(2_000, false) == 2_000, "dock did not accept cable FE");
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> tag.putInt("energy", 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, cell);
        test.useBlock(pad, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "dock rejected the cell");
        var inserted = clientView(dock, world);
        test.assertTrue(inserted.targetStack().is(Hardware.BATTERIES.getFirst().get()), "client view lost the docked cell");
        test.assertTrue(inserted.targetCapacity() == 500_000 && inserted.targetEnergy() == 0, "client view lost the cell charge stats");
        test.assertTrue(inserted.storedEnergy() == 2_000, "client buffer lost the cable charge");
        test.runAfterDelay(10, () -> {
            var charging = clientView(dock, world);
            test.assertTrue(charging.targetEnergy() > inserted.targetEnergy(), "docked cell charge did not move between ticks");
            test.assertTrue(charging.targetEnergy() == Hardware.energy(dock.targetStack()), "client cell charge differs from the server");
            test.assertTrue(charging.storedEnergy() < inserted.storedEnergy(), "buffer did not feed the docked cell");
            test.assertTrue(charging.storedEnergy() == dock.getEnergyStored(), "client buffer differs from the server");
            test.useBlock(pad, player);
            var taken = clientView(dock, world);
            test.assertTrue(taken.targetStack().isEmpty(), "taken cell is still shown by the client view");
            test.assertTrue(taken.targetEnergy() == 0 && taken.targetCapacity() == 0, "taken cell stats survive in the client view");
            test.assertTrue(player.getMainHandItem().is(Hardware.BATTERIES.getFirst().get()), "cell was not returned");
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void clientViewIsRestoredAfterReload(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var world = test.getLevel();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = test.absolutePos(pad);
        var dock = (ChargingDockEntity) world.getBlockEntity(pos);
        var port = world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
        test.assertTrue(port != null && port.receiveEnergy(2_000, false) == 2_000, "dock did not accept cable FE");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 8));
        test.useBlock(pad, player);
        test.runAfterDelay(6, () -> {
            test.assertTrue(clientView(dock, world).storedEnergy() > 2_000, "coal did not buffer FE while the pad was empty");
            ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
            CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> tag.putInt("energy", 0));
            player.setItemInHand(InteractionHand.MAIN_HAND, cell);
            test.useBlock(pad, player);
            test.runAfterDelay(10, () -> {
                int stored = dock.getEnergyStored();
                int cellCharge = Hardware.energy(dock.targetStack());
                int coal = dock.coalCount();
                test.assertTrue(cellCharge > 0 && coal > 0, "dock did not charge before the reload");
                var saved = dock.saveWithFullMetadata(world.registryAccess());
                var restored = new ChargingDockEntity(pos, world.getBlockState(pos));
                restored.loadWithComponents(saved, world.registryAccess());
                test.assertTrue(restored.getEnergyStored() == stored && Hardware.energy(restored.targetStack()) == cellCharge
                        && restored.coalCount() == coal, "reload lost the docked charge");
                var restoredView = clientView(restored, world);
                test.assertTrue(restoredView.storedEnergy() == stored, "reloaded buffer differs from the saved value");
                test.assertTrue(restoredView.targetEnergy() == cellCharge, "reloaded cell charge differs from the saved value");
                test.assertTrue(restoredView.coalCount() == coal && restoredView.targetCapacity() == 500_000, "reloaded view lost coal or capacity");
                world.removeBlockEntity(pos);
                world.setBlockEntity(restored);
                test.runAfterDelay(10, () -> {
                    var resumed = clientView(restored, world);
                    test.assertTrue(resumed.targetEnergy() > restoredView.targetEnergy(), "reloaded dock stopped charging");
                    test.assertTrue(resumed.storedEnergy() == restored.getEnergyStored(), "client buffer differs after resume");
                    test.succeed();
                });
            });
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void updateTagCarriesOnlyDisplayState(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var world = test.getLevel();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = test.absolutePos(pad);
        var dock = (ChargingDockEntity) world.getBlockEntity(pos);
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> tag.putInt("energy", 1_000));
        player.setItemInHand(InteractionHand.MAIN_HAND, cell);
        test.useBlock(pad, player);
        var tag = dock.getUpdateTag(world.registryAccess());
        for (String key : new String[]{"Energy", "Buffer", "Coal", "Target", "TargetEnergy", "TargetCapacity"}) {
            test.assertTrue(tag.contains(key), "update tag is missing " + key);
        }
        test.assertTrue(!tag.contains("BurnRemaining"), "update tag should not ship burn timers");
        test.assertTrue(tag.getInt("TargetEnergy") == 1_000, "update tag reports the wrong cell charge");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void coalChargesAndSurvivesSave(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        var world = test.getLevel();
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> { tag.putInt("energy", 0); tag.putString("marker", "original"); });
        player.setItemInHand(InteractionHand.MAIN_HAND, cell);
        test.useBlock(pad, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "dock did not accept original cell");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
        test.useBlock(pad, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "coal not consumed");
        test.runAfterDelay(2, () -> {
            BlockPos pos = test.absolutePos(pad);
            var dock = (ChargingDockEntity) world.getBlockEntity(pos);
            var saved = dock.saveWithFullMetadata(world.registryAccess());
            int remaining = saved.getInt("BurnRemaining");
            // Two ticks of an 800 FE/t burn leave 2,400 of the coal's 4,000 FE unspent.
            test.assertTrue(remaining > 0 && remaining < 4000 && remaining % 800 == 0, "incorrect partial burn");
            var restored = new ChargingDockEntity(pos, world.getBlockState(pos));
            restored.loadWithComponents(saved, world.registryAccess());
            world.removeBlockEntity(pos);
            world.setBlockEntity(restored);
        });
        test.runAfterDelay(28, () -> {
            test.useBlock(pad, player);
            ItemStack result = player.getMainHandItem();
            test.assertTrue(result.getCount() == 1 && result.is(Hardware.BATTERIES.getFirst().get()), "missing removed cell");
            test.assertTrue(Hardware.energy(result) == 4000, "one coal did not store exactly 4000 FE");
            test.assertTrue("original".equals(result.get(DataComponents.CUSTOM_DATA).copyTag().getString("marker")), "lost item components");
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void breakingDockReturnsChargedTerminalAndFuel(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var world = test.getLevel();
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        ItemStack terminal = dev.dronepal.Dronepal.DRONE.toStack();
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> tag.putInt("energy", 0));
        terminal.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(cell)));
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        test.useBlock(pad, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "dock rejected a terminal with a battery");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 3));
        test.useBlock(pad, player);
        // Two ticks at 800 FE/t is 1,600 FE: the second lump has not been touched yet, so two must come back.
        test.runAfterDelay(2, () -> {
            BlockPos pos = test.absolutePos(pad);
            world.destroyBlock(pos, false);
            var drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(pos).inflate(1));
            var terminals = drops.stream().map(e -> e.getItem()).filter(s -> s.is(dev.dronepal.Dronepal.DRONE.get())).toList();
            test.assertTrue(terminals.size() == 1 && Hardware.energy(terminals.getFirst()) > 0, "terminal lost charge or duplicated on break");
            int unusedCoal = drops.stream().map(e -> e.getItem()).filter(s -> s.is(Items.COAL)).mapToInt(ItemStack::getCount).sum();
            test.assertTrue(unusedCoal == 2, "unused coal lost or duplicated on break");
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void cableAndItemBudgets(GameTestHelper test) {
        BlockPos pad = new BlockPos(2, 1, 2);
        test.setBlock(pad, ChargingDockRegistry.DOCK.get());
        var world = test.getLevel();
        var port = world.getCapability(Capabilities.EnergyStorage.BLOCK, test.absolutePos(pad), Direction.NORTH);
        test.assertTrue(port != null && port.canReceive() && !port.canExtract(), "missing input-only FE port");
        test.assertTrue(port.receiveEnergy(10000, true) == 2000 && port.getEnergyStored() == 0, "simulation mutated cable buffer");
        test.assertTrue(port.receiveEnergy(1500, false) == 1500 && port.receiveEnergy(1500, false) == 500, "repeated cable calls bypassed 2000 FE/t");
        test.assertTrue(port.extractEnergy(2000, false) == 0 && port.getEnergyStored() == 2000, "cable extraction unexpectedly succeeded");
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, cell, tag -> tag.putInt("energy", 0));
        var item = cell.getCapability(Capabilities.EnergyStorage.ITEM);
        // Mk.I accepts 1,200 FE/t after the rebalance (was 600), matching its 500,000 FE cell.
        int cellRate = dev.dronepal.Batteries.model(cell).maxReceive();
        test.assertTrue(cellRate == 1_200, "Mk.I receive rate is not 1,200 FE/t: " + cellRate);
        test.assertTrue(item != null && item.receiveEnergy(9999, true) == cellRate && item.getEnergyStored() == 0, "battery FE simulation invalid");
        test.assertTrue(item.receiveEnergy(9999, false) == cellRate && item.receiveEnergy(9999, false) == 0,
                "battery exceeded Mk.I " + cellRate + " FE/t");
        test.runAfterDelay(2, () -> {
            test.assertTrue(port.receiveEnergy(9999, false) == 2000 && item.receiveEnergy(9999, false) == cellRate,
                    "tick budgets did not reset");
            test.succeed();
        });
    }
}
