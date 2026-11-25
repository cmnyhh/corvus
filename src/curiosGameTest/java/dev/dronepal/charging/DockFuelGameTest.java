package dev.dronepal.charging;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The dock's fuel slot now takes any furnace fuel; these pin the conversion table and the slot rules. */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DockFuelGameTest {
    private static final BlockPos PAD = new BlockPos(2, 1, 2);

    private static ChargingDockEntity dock(GameTestHelper test, ServerLevel world) {
        return (ChargingDockEntity) world.getBlockEntity(test.absolutePos(PAD));
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void charcoalIsWorthFourThousandFe(GameTestHelper test) {
        test.setBlock(PAD, ChargingDockRegistry.DOCK.get());
        ServerLevel world = test.getLevel();
        var dock = dock(test, world);
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHARCOAL, 1));
        test.useBlock(PAD, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "dock refused charcoal");
        test.assertTrue(dock.coalCount() == 1 && dock.fuelStack().is(Items.CHARCOAL), "charcoal was not stored in the fuel slot");
        for (int i = 0; i < 20; i++) dock.tick(world);
        test.assertTrue(dock.getEnergyStored() == 4_000, "charcoal (1,600 t) should burn for 4,000 FE, got " + dock.getEnergyStored());
        test.assertTrue(dock.coalCount() == 0, "spent charcoal is still in the slot");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void coalBlockIsWorthFortyThousandFe(GameTestHelper test) {
        test.setBlock(PAD, ChargingDockRegistry.DOCK.get());
        ServerLevel world = test.getLevel();
        var dock = dock(test, world);
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL_BLOCK, 1));
        test.useBlock(PAD, player);
        test.assertTrue(player.getMainHandItem().isEmpty(), "dock refused a coal block");
        for (int i = 0; i < 200; i++) dock.tick(world);
        CompoundTag saved = dock.saveWithFullMetadata(world.registryAccess());
        int remaining = saved.getInt("BurnRemaining");
        test.assertTrue(dock.getEnergyStored() == 32_000, "dock buffer did not fill: " + dock.getEnergyStored());
        test.assertTrue(dock.getEnergyStored() + remaining == 40_000,
                "coal block should hold 40,000 FE, buffer " + dock.getEnergyStored() + " + left " + remaining);
        test.assertTrue(dock.coalCount() == 0, "the coal block is still in the slot");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void blazeRodScalesWithBurnTime(GameTestHelper test) {
        test.setBlock(PAD, ChargingDockRegistry.DOCK.get());
        ServerLevel world = test.getLevel();
        var dock = dock(test, world);
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BLAZE_ROD, 1));
        test.useBlock(PAD, player);
        for (int i = 0; i < 30; i++) dock.tick(world);
        test.assertTrue(dock.getEnergyStored() == 6_000, "blaze rod (2,400 t) should burn for 6,000 FE, got " + dock.getEnergyStored());
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void fuelStacksToSixtyFourAndComesBackEmptyHanded(GameTestHelper test) {
        test.setBlock(PAD, ChargingDockRegistry.DOCK.get());
        ServerLevel world = test.getLevel();
        var dock = dock(test, world);
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 64));
        test.useBlock(PAD, player);
        test.assertTrue(player.getMainHandItem().isEmpty() && dock.coalCount() == 64, "dock did not take a full stack");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 10));
        test.useBlock(PAD, player);
        test.assertTrue(player.getMainHandItem().getCount() == 10, "dock accepted coal past its 64-item ceiling");
        test.assertTrue(dock.coalCount() == 64, "fuel count drifted past the ceiling");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        test.useBlock(PAD, player);
        test.assertTrue(player.getMainHandItem().is(Items.COAL) && player.getMainHandItem().getCount() == 64,
                "empty hand did not return the stored fuel");
        test.assertTrue(dock.coalCount() == 0, "the fuel slot still holds coal after being emptied");
        test.succeed();
    }
}
