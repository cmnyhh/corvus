package dev.dronepal;

import dev.dronepal.mill.DroneWork;
import dev.dronepal.mill.MillItems;
import dev.dronepal.mill.OreKind;
import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.mill.OreMillRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Drone-side milling: speed by airframe, doubled output, water cost, module gates and the battery contract. */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DroneMillGameTest {
    private static OreMillEntity mill(GameTestHelper test, BlockPos relative) {
        var level = test.getLevel();
        var pos = test.absolutePos(relative);
        level.setBlockAndUpdate(pos, OreMillRegistry.MILL.get().defaultBlockState());
        var mill = (OreMillEntity) level.getBlockEntity(pos);
        mill.setRandom(RandomSource.create(424242L));
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        return mill;
    }

    private static int count(OreMillEntity mill, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < dev.dronepal.mill.OreMillMenu.OUTPUT_COUNT; i++)
            if (mill.output(i).is(item)) count += mill.output(i).getCount();
        return count;
    }

    private static List<ItemStack> refine(GameTestHelper test, OreMillEntity mill, int ticks) {
        for (int i = 0; i < ticks; i++) mill.tick(test.getLevel());
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < dev.dronepal.mill.OreMillMenu.OUTPUT_COUNT; i++) if (!mill.output(i).isEmpty()) out.add(mill.output(i).copy());
        return out;
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void droneDoublesOutputAndDrinksThreeHundred(GameTestHelper test) {
        var level = test.getLevel();
        var player = GameTestPlayers.join(test);
        var mill = mill(test, new BlockPos(2, 2, 2));
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        var controller = MillKit.controller("peregrine", true, true, 100);
        var drone = MillKit.deploy(test, player, controller, mill.getBlockPos());
        mill.assignDrone(drone.getUUID(), player.getUUID());
        mill.tick(level);
        test.assertTrue(mill.link() == OreMillEntity.Link.WORKING, "drone is not on station: " + mill.link());
        test.assertTrue(mill.currentDuration() == 30, "peregrine+carry should be 30 ticks, was " + mill.currentDuration());
        refine(test, mill, 30);
        test.assertTrue(count(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 2, "drone did not double the concentrate: " + count(mill, MillItems.refined(OreKind.IRON, 1).getItem()));
        test.assertTrue(mill.water() == OreMillRules.WATER_CAPACITY - OreMillRules.WATER_PER_ITEM_DRONE,
                "drone job used " + (OreMillRules.WATER_CAPACITY - mill.water()) + " mB");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 120)
    public static void airframeAndCarryChangeTheSpeed(GameTestHelper test) {
        var expectations = List.of(
                new Object[]{"owl", true, 24},
                new Object[]{"bedrock", true, 20},
                new Object[]{"scrap", true, 40},
                new Object[]{"peregrine", false, 60});
        int index = 0;
        for (var expectation : expectations) {
            String frame = (String) expectation[0];
            boolean carry = (Boolean) expectation[1];
            int ticks = (Integer) expectation[2];
            var player = GameTestPlayers.join(test);
            var mill = mill(test, new BlockPos(2 + index, 2, 3));
            mill.inventory().setItem(0, new ItemStack(Items.RAW_COPPER, 2));
            var controller = MillKit.controller(frame, true, carry, 100);
            var drone = MillKit.deploy(test, player, controller, mill.getBlockPos());
            mill.assignDrone(drone.getUUID(), player.getUUID());
            mill.tick(test.getLevel());
            test.assertTrue(mill.link() == OreMillEntity.Link.WORKING, frame + " link " + mill.link());
            test.assertTrue(mill.currentDuration() == ticks,
                    frame + (carry ? " with carry" : " without carry") + " should take " + ticks + " ticks, was " + mill.currentDuration());
            index++;
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void missingScanModuleStopsTheMill(GameTestHelper test) {
        var level = test.getLevel();
        var player = GameTestPlayers.join(test);
        var mill = mill(test, new BlockPos(2, 2, 2));
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        var controller = MillKit.controller("peregrine", false, true, 100);
        var drone = MillKit.deploy(test, player, controller, mill.getBlockPos());
        mill.assignDrone(drone.getUUID(), player.getUUID());
        mill.tick(level);
        test.assertTrue(mill.link() == OreMillEntity.Link.NO_MODULE, "expected NO_MODULE, got " + mill.link());
        refine(test, mill, OreMillRules.MANUAL_TICKS + 5);
        test.assertTrue(mill.output(0).isEmpty(), "mill ran without a scan module");
        test.assertTrue(!DroneWork.order(player, "order.work"), "work order accepted a drone without a scan module");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void batteryStopsAtFifteenAndResumesAtForty(GameTestHelper test) {
        var level = test.getLevel();
        var player = GameTestPlayers.join(test);
        var mill = mill(test, new BlockPos(2, 2, 2));
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        var controller = MillKit.controller("peregrine", true, true, 100);
        var drone = MillKit.deploy(test, player, controller, mill.getBlockPos());
        mill.assignDrone(drone.getUUID(), player.getUUID());
        int capacity = MillKit.batteryCapacity(controller);
        // 14%: the work draw (400 FE per settlement) can only make this worse.
        MillKit.setBattery(controller, (int)(capacity * 0.14));
        DroneWork.second(drone, controller);
        test.assertTrue(drone.workStalled(), "drone did not stall below 15%");
        mill.tick(level);
        test.assertTrue(mill.link() == OreMillEntity.Link.LOW_BATTERY, "expected LOW_BATTERY, got " + mill.link());
        refine(test, mill, 40);
        test.assertTrue(count(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 0, "stalled drone kept the mill running");
        // 39% after the settlement is still below the 40% resume line.
        MillKit.setBattery(controller, (int)(capacity * 0.39) + 400);
        DroneWork.second(drone, controller);
        test.assertTrue(drone.workStalled(), "drone resumed below 40%");
        // Exactly 40% after the settlement resumes.
        MillKit.setBattery(controller, (int)(capacity * 0.40) + 400);
        DroneWork.second(drone, controller);
        test.assertTrue(!drone.workStalled(), "drone did not resume at 40%");
        mill.tick(level);
        test.assertTrue(mill.link() == OreMillEntity.Link.WORKING, "mill did not resume: " + mill.link());
        refine(test, mill, 30);
        test.assertTrue(count(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 2, "resumed mill produced " + count(mill, MillItems.refined(OreKind.IRON, 1).getItem()));
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 120)
    public static void droneByproductIsAboutThirtyFivePercent(GameTestHelper test) {
        var level = test.getLevel();
        var player = GameTestPlayers.join(test);
        var mill = mill(test, new BlockPos(2, 2, 2));
        var controller = MillKit.controller("peregrine", true, true, 100);
        var drone = MillKit.deploy(test, player, controller, mill.getBlockPos());
        mill.assignDrone(drone.getUUID(), player.getUUID());
        mill.tick(level);
        int jobs = 100, coarse = 0;
        for (int job = 0; job < jobs; job++) {
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM_DRONE);
            mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
            refine(test, mill, 30);
            coarse += count(mill, MillItems.coarse(OreKind.IRON, 1).getItem());
            for (int i = 0; i < dev.dronepal.mill.OreMillMenu.OUTPUT_COUNT; i++)
                mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.OUTPUT_FIRST + i, ItemStack.EMPTY);
        }
        test.assertTrue(coarse > jobs * 0.25 && coarse < jobs * 0.45, "drone by-product rate off: " + coarse + "/" + jobs);
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void orderWorkPicksTheNearestMillAndIdleReleasesIt(GameTestHelper test) {
        var level = test.getLevel();
        var player = GameTestPlayers.join(test);
        var origin = test.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        var near = mill(test, new BlockPos(3, 2, 2));
        var far = mill(test, new BlockPos(9, 2, 2));
        player.teleportTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        var controller = MillKit.controller("peregrine", true, true, 100);
        var drone = MillKit.deploy(test, player, controller, near.getBlockPos());
        drone.clearWork();
        test.assertTrue(DroneWork.order(player, "order.work"), "work order was refused");
        test.assertTrue(near.isAssigned(drone.getUUID()), "nearest mill did not take the drone");
        test.assertTrue(near.getBlockPos().equals(drone.workTarget()), "drone target is not the nearest mill");
        test.assertTrue(!far.isAssigned(drone.getUUID()), "far mill also claimed the drone");
        test.assertTrue(DroneWork.order(player, "order.idle"), "idle order was refused");
        test.assertTrue(drone.workTarget() == null, "idle order left a work target");
        mill(test, new BlockPos(1, 2, 3)); // keep a mill in range for the tick below
        test.succeed();
    }
}
