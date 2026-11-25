package dev.dronepal.power.grid;

import dev.dronepal.Dronepal;
import dev.dronepal.DroneEntity;
import dev.dronepal.Hardware;
import dev.dronepal.power.grid.PowerTowerEntity;
import dev.dronepal.power.grid.TowerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Drone charging through the wireless grid.
 *
 * <p>The real drone power loop runs once per second; these tests drive the same
 * {@link DroneCharger#service} contract plus the per-tick allocator directly, so 100 simulated ticks
 * are deterministic and need no real-time waiting. The controller is an ordinary Corvus battery
 * stack, i.e. the same NBT cell {@code Hardware.setEnergy} writes in game.</p>
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DroneChargeGameTest {
    private DroneChargeGameTest() {}

    private static void prepare(GameTestHelper test) {
        TowerGridService.setRadiusOverride(20);
    }

    private static DroneEntity drone(ServerLevel level, double x, double y, double z) {
        DroneEntity drone = new DroneEntity(Dronepal.ENTITY.get(), level);
        drone.setPos(x, y, z);
        return drone;
    }

    private static ItemStack battery(int energy) {
        ItemStack stack = new ItemStack(Hardware.BATTERIES.get(0).get());
        Hardware.setEnergy(stack, energy);
        return stack;
    }

    private static void tower(GameTestHelper test, BlockPos pos) {
        test.setBlock(pos, TowerRegistry.TOWER.get());
        if (!test.getBlockState(pos.above()).is(TowerRegistry.MID.get())) test.setBlock(pos.above(), TowerRegistry.MID.get());
        if (!test.getBlockState(pos.above(2)).is(TowerRegistry.TOP.get())) test.setBlock(pos.above(2), TowerRegistry.TOP.get());
    }

    private static void clearTowers(GameTestHelper test) {
        int[] xs = {14, 24, 34};
        int[] zs = {24, 26};
        BlockPos base = test.absolutePos(BlockPos.ZERO);
        for (int x : xs) for (int z : zs) for (int y = 0; y <= 110; y++) {
            BlockPos pos = base.offset(x, y, z);
            if (test.getLevel().getBlockState(pos).getBlock() instanceof PowerTowerBlock
                    || test.getLevel().getBlockState(pos).getBlock() instanceof PowerTowerPartBlock) {
                test.getLevel().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** 100 simulated ticks: one allocation per tick, a second-boundary service every 20 ticks. */
    private static void simulate(GameTestHelper test, int ticks, Runnable second) {
        for (int tick = 0; tick < ticks; tick++) {
            GridLink.forceAllocate(test.getLevel());
            if (tick % 20 == 19) second.run();
        }
    }

    // ---------------------------------------------------------------- 1

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void emptyBatteryChargesAtBudgetRate(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        ((PowerTowerEntity) test.getBlockEntity(towerPos)).setEnergy(600_000);
        DroneEntity drone = drone(level, test.absolutePos(towerPos).getX() + 0.5D,
                test.absolutePos(towerPos).getY() + 1.5D, test.absolutePos(towerPos).getZ() + 2.5D);
        ItemStack controller = battery(0);
        int capacity = Hardware.capacity(controller);
        test.assertTrue(capacity > 60_000, "test battery capacity too small: " + capacity);

        DroneCharger.Result registered = DroneCharger.service(level, drone, controller, 0);
        java.util.List<Integer> charges = new java.util.ArrayList<>();
        simulate(test, 100, () -> charges.add(DroneCharger.service(level, drone, controller, 0).charged()));

        int energy = Hardware.energy(controller);
        int towerLeft = ((PowerTowerEntity) test.getBlockEntity(towerPos)).energy();
        // 500 FE/t for 100 ticks, zero hops: exactly 50,000 FE into the cell.
        test.assertTrue(energy == 50_000, "100 ticks charged " + energy + " FE, expected 50,000 (registered="
                + registered + " charges=" + charges + ")");
        test.assertTrue(towerLeft == 550_000, "tower spent " + (600_000 - towerLeft) + " FE, expected 50,000");
        DroneCharger.Result last = DroneCharger.service(level, drone, controller, 0);
        test.assertTrue(last.online(), "drone is not reported as on-grid");
        drone.discard();
        cleanup(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 2

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void fullBatteryDrawsNothing(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        ((PowerTowerEntity) test.getBlockEntity(towerPos)).setEnergy(600_000);
        ItemStack controller = battery(Hardware.capacity(battery(0)));
        int full = Hardware.energy(controller);
        DroneEntity drone = drone(level, test.absolutePos(towerPos).getX() + 0.5D,
                test.absolutePos(towerPos).getY() + 1.5D, test.absolutePos(towerPos).getZ() + 2.5D);
        DroneCharger.service(level, drone, controller, 0);
        simulate(test, 100, () -> DroneCharger.service(level, drone, controller, 0));
        test.assertTrue(Hardware.energy(controller) == full, "full cell changed to " + Hardware.energy(controller));
        test.assertTrue(((PowerTowerEntity) test.getBlockEntity(towerPos)).energy() == 600_000,
                "full battery still drained the tower");
        drone.discard();
        cleanup(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 3

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void outOfRangeOrSixHopsDoesNotCharge(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        ((PowerTowerEntity) test.getBlockEntity(towerPos)).setEnergy(600_000);
        // 30 blocks away: outside the 20-block test coverage.
        DroneEntity far = drone(level, test.absolutePos(towerPos).getX() + 30.5D,
                test.absolutePos(towerPos).getY() + 1.5D, test.absolutePos(towerPos).getZ() + 0.5D);
        ItemStack farCell = battery(0);
        DroneCharger.service(level, far, farCell, 0);
        simulate(test, 100, () -> DroneCharger.service(level, far, farCell, 0));
        test.assertTrue(Hardware.energy(farCell) == 0, "out-of-range drone charged " + Hardware.energy(farCell));

        // Six hops: seven towers 17 blocks apart, only the top one powered. The first tower is
        // emptied so it cannot feed the base drone directly.
        ((PowerTowerEntity) test.getBlockEntity(towerPos)).setEnergy(0);
        int[] ys = {1, 18, 35, 52, 69, 86, 103};
        for (int y : ys) tower(test, new BlockPos(14, y, 24));
        ((PowerTowerEntity) test.getBlockEntity(new BlockPos(14, 103, 24))).setEnergy(1_000_000);
        DroneEntity base = drone(level, test.absolutePos(new BlockPos(14, 1, 24)).getX() + 0.5D,
                test.absolutePos(new BlockPos(14, 1, 24)).getY() + 1.5D,
                test.absolutePos(new BlockPos(14, 1, 24)).getZ() + 2.5D);
        ItemStack baseCell = battery(0);
        DroneCharger.service(level, base, baseCell, 0);
        simulate(test, 100, () -> DroneCharger.service(level, base, baseCell, 0));
        test.assertTrue(Hardware.energy(baseCell) == 0, "six-hop drone charged " + Hardware.energy(baseCell));
        test.assertTrue(!dev.corvus.core.Grid.reachable(6), "rule: six hops is unreachable");
        far.discard();
        base.discard();
        cleanup(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 4

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void twoDronesShareTheTowerBudget(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        ((PowerTowerEntity) test.getBlockEntity(towerPos)).setEnergy(600_000);
        DroneEntity first = drone(level, test.absolutePos(towerPos).getX() + 0.5D,
                test.absolutePos(towerPos).getY() + 1.5D, test.absolutePos(towerPos).getZ() - 1.5D);
        DroneEntity second = drone(level, test.absolutePos(towerPos).getX() + 0.5D,
                test.absolutePos(towerPos).getY() + 1.5D, test.absolutePos(towerPos).getZ() + 2.5D);
        ItemStack firstCell = battery(0);
        ItemStack secondCell = battery(0);
        DroneCharger.service(level, first, firstCell, 0);
        DroneCharger.service(level, second, secondCell, 0);
        simulate(test, 100, () -> {
            DroneCharger.service(level, first, firstCell, 0);
            DroneCharger.service(level, second, secondCell, 0);
        });
        int a = Hardware.energy(firstCell), b = Hardware.energy(secondCell);
        test.assertTrue(a + b == 50_000, "two drones took " + (a + b) + " FE, expected the 500/t budget = 50,000");
        test.assertTrue(Math.abs(a - b) <= 500, "uneven split: " + a + " vs " + b);
        test.assertTrue(((PowerTowerEntity) test.getBlockEntity(towerPos)).energy() == 550_000,
                "tower spent " + (600_000 - ((PowerTowerEntity) test.getBlockEntity(towerPos)).energy()));
        first.discard();
        second.discard();
        cleanup(test);
        test.succeed();
    }

    private static void cleanup(GameTestHelper test) {
        clearTowers(test);
    }
}
