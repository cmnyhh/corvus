package dev.dronepal.power.grid;

import dev.corvus.core.Grid;
import dev.dronepal.power.BatteryBankEntity;
import dev.dronepal.power.PowerBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The wireless-grid acceptance table, one method per rule in the spec.
 *
 * <p>Everything runs against the real server tick and real block entities: towers are placed as
 * blocks, energy is moved through the actual capability/Forge-energy calls, and the probes below are
 * the same {@link GridLoad} contract the ore mill, charging dock and deployed drone use. The custom
 * 144x8x48 template is large because a six-hop chain of towers (17 blocks apart, so no shortcut is
 * within the 32-block link radius) is 102 blocks long.</p>
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class PowerGridGameTest {
    private PowerGridGameTest() {}

    /** A registered device the test can move and ask for power, with no block behind it. */
    private static final class Probe implements GridLoad {
        private final BlockPos pos;
        private final int priority;
        private final int demand;
        Probe(BlockPos pos, int priority, int demand) { this.pos = pos; this.priority = priority; this.demand = demand; }
        @Override public BlockPos gridPos() { return pos; }
        @Override public int gridPriority() { return priority; }
        @Override public int gridDemandFe() { return demand; }
    }

    /**
     * Resets this test's own 48x8x48 cell. GameTest reuses the same absolute area for later batches
     * (the server spawner does not clear between batches), so a tower from an earlier batch would
     * otherwise sit in the cell, keep its block entity registered and both hide the new segments and
     * join the new network. Clearing tower blocks only — never terrain — makes each test independent.
     */
    private static void prepare(GameTestHelper test) {
        TowerGridService.setRadiusOverride(20);
        clearGridColumns(test);
    }

    /** The x/z columns every grid test can use; clearing just these is enough and stays cheap. */
    private static final int[] GRID_X = {14, 16, 17, 24, 26, 34};
    private static final int[] GRID_Z = {8, 10, 22, 24, 26, 32, 34};

    private static void clearGridColumns(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        BlockPos base = test.absolutePos(BlockPos.ZERO);
        for (int x : GRID_X) {
            for (int z : GRID_Z) {
                for (int y = 0; y <= 120; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (block instanceof PowerTowerBlock || block instanceof PowerTowerPartBlock) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /** Removes this test's towers again, so a later batch never inherits a powered grid. */
    private static void cleanup(GameTestHelper test) {
        clearGridColumns(test);
    }

    private static void tower(GameTestHelper test, BlockPos pos) {
        test.setBlock(pos, TowerRegistry.TOWER.get());
        // Explicit segments as well: the placement path normally grows them, but a test must not
        // depend on what was in the cell before.
        if (!test.getBlockState(pos.above()).is(TowerRegistry.MID.get())) test.setBlock(pos.above(), TowerRegistry.MID.get());
        if (!test.getBlockState(pos.above(2)).is(TowerRegistry.TOP.get())) test.setBlock(pos.above(2), TowerRegistry.TOP.get());
        test.assertTrue(test.getBlockState(pos.above()).is(TowerRegistry.MID.get()), "middle segment missing at " + pos);
        test.assertTrue(test.getBlockState(pos.above(2)).is(TowerRegistry.TOP.get()), "top segment missing at " + pos);
    }

    private static BlockPos abs(GameTestHelper test, BlockPos pos) { return test.absolutePos(pos); }

    private static PowerTowerEntity towerEntity(GameTestHelper test, BlockPos pos) {
        return (PowerTowerEntity) test.getBlockEntity(pos);
    }

    // ---------------------------------------------------------------- 1

    @GameTest(template = "grid_test_room", timeoutTicks = 120)
    public static void twoTowersTwentyApartFormOneGridAndSupplyAcross(GameTestHelper test) {
        prepare(test);
        BlockPos near = new BlockPos(14, 1, 24);
        BlockPos far = new BlockPos(34, 1, 24);
        tower(test, near);
        tower(test, far);
        towerEntity(test, far).setEnergy(600_000);
        Probe probe = new Probe(abs(test, new BlockPos(14, 1, 26)), GridLink.PRIORITY_MILL, 200);
        GridLink.registerLoad(test.getLevel(), probe);
        try {
            GridLink.forceAllocate(test.getLevel());
            int got = GridLink.withdraw(test.getLevel(), probe, 200);
            TowerGridService.NetworkInfo info = GridLink.info(test.getLevel(), abs(test, near));
            test.assertTrue(info.towers() == 2, "20 blocks apart must be one 2-tower grid, got " + info.towers());
            test.assertTrue(got == 190, "device at the empty tower drew " + got + " FE, expected 190 (one hop)");
            test.assertTrue(towerEntity(test, far).energy() == 600_000 - 200, "far tower spend");
            test.assertTrue(towerEntity(test, far).energy() < 600_000, "the far tower was not the source");
            test.succeed();
        } finally {
            GridLink.unregisterLoad(test.getLevel(), probe);
            cleanup(test);
        }
    }

    // ---------------------------------------------------------------- 2

    @GameTest(template = "grid_test_room", timeoutTicks = 140)
    public static void hopEfficiencyIsNinetyFivePercentPerHop(GameTestHelper test) {
        prepare(test);
        // One hop: the device is attached to A (empty); only B, 20 blocks away, holds energy.
        BlockPos oneNear = new BlockPos(14, 1, 8);
        BlockPos oneFar = new BlockPos(34, 1, 8);
        tower(test, oneNear);
        tower(test, oneFar);
        towerEntity(test, oneFar).setEnergy(600_000);
        Probe one = new Probe(abs(test, new BlockPos(14, 1, 10)), GridLink.PRIORITY_DOCK, 400);
        GridLink.registerLoad(test.getLevel(), one);

        // Three hops: a vertical chain with 17-block edges, so no shortcut is within the 20-block
        // test radius. Only the top tower is powered.
        int[] ys = {1, 18, 35, 52};
        for (int y : ys) tower(test, new BlockPos(14, y, 32));
        towerEntity(test, new BlockPos(14, 52, 32)).setEnergy(600_000);
        Probe three = new Probe(abs(test, new BlockPos(14, 1, 34)), GridLink.PRIORITY_DOCK, 400);
        GridLink.registerLoad(test.getLevel(), three);
        try {
            GridLink.forceAllocate(test.getLevel());
            int oneHop = GridLink.withdraw(test.getLevel(), one, 400);
            int threeHops = GridLink.withdraw(test.getLevel(), three, 400);
            test.assertTrue(towerEntity(test, oneNear).energy() == 0,
                    "the near tower started powered: " + towerEntity(test, oneNear).energy());
            double oneRatio = oneHop / 400.0D;
            double threeRatio = threeHops / 400.0D;
            test.assertTrue(Math.abs(oneRatio - 0.95D) <= 0.01D,
                    "1 hop ratio " + oneRatio + " (expected 0.95 +/-0.01) " + GridLink.debug(test.getLevel(), one));
            test.assertTrue(Math.abs(threeRatio - 0.86D) <= 0.01D,
                    "3 hop ratio " + threeRatio + " (expected 0.86 +/-0.01) " + GridLink.debug(test.getLevel(), three));
            test.assertTrue(threeHops < oneHop, "three hops must deliver less than one hop");
            test.succeed();
        } finally {
            GridLink.unregisterLoad(test.getLevel(), one);
            GridLink.unregisterLoad(test.getLevel(), three);
            cleanup(test);
        }
    }

    // ---------------------------------------------------------------- 3

    @GameTest(template = "grid_test_room", timeoutTicks = 160)
    public static void sixHopsIsOutOfReach(GameTestHelper test) {
        prepare(test);
        // Six hops: seven towers, each 17 blocks above the previous one.
        int[] ys = {1, 18, 35, 52, 69, 86, 103};
        for (int y : ys) tower(test, new BlockPos(14, y, 24));
        towerEntity(test, new BlockPos(14, 103, 24)).setEnergy(1_000_000);
        Probe probe = new Probe(abs(test, new BlockPos(14, 1, 26)), GridLink.PRIORITY_MILL, 400);
        GridLink.registerLoad(test.getLevel(), probe);
        try {
            GridLink.forceAllocate(test.getLevel());
            int got = GridLink.withdraw(test.getLevel(), probe, 400);
            test.assertTrue(!dev.corvus.core.Grid.reachable(6), "the shared rule must reject six hops");
            test.assertTrue(got == 0, "six hops delivered " + got + " FE");
            test.succeed();
        } finally {
            GridLink.unregisterLoad(test.getLevel(), probe);
            cleanup(test);
        }
    }

    // ---------------------------------------------------------------- 4

    @GameTest(template = "grid_test_room", timeoutTicks = 120)
    public static void oneTowerSharesFiveHundredFePerTickByPriority(GameTestHelper test) {
        prepare(test);
        BlockPos tower = new BlockPos(24, 1, 24);
        tower(test, tower);
        towerEntity(test, tower).setEnergy(1_000_000);
        // Three devices on the same tower, each asking for 200 FE/t for this single tick.
        Probe drone = new Probe(abs(test, new BlockPos(24, 1, 22)), Grid.PRIORITY_DRONE, 200);
        Probe mill = new Probe(abs(test, new BlockPos(26, 1, 24)), GridLink.PRIORITY_MILL, 200);
        Probe dock = new Probe(abs(test, new BlockPos(24, 1, 26)), GridLink.PRIORITY_DOCK, 200);
        GridLink.registerLoad(test.getLevel(), drone);
        GridLink.registerLoad(test.getLevel(), mill);
        GridLink.registerLoad(test.getLevel(), dock);
        try {
            GridLink.forceAllocate(test.getLevel());
            int a = GridLink.withdraw(test.getLevel(), drone, 200);
            int b = GridLink.withdraw(test.getLevel(), mill, 200);
            int c = GridLink.withdraw(test.getLevel(), dock, 200);
            test.assertTrue(a == 200, "drone grant " + a + " (expected 200)");
            test.assertTrue(b == 200, "mill grant " + b + " (expected 200)");
            test.assertTrue(c == 100, "dock grant " + c + " (expected the remaining 100); drone["
                    + GridLink.debug(test.getLevel(), drone) + "] mill[" + GridLink.debug(test.getLevel(), mill)
                    + "] dock[" + GridLink.debug(test.getLevel(), dock) + "]");
            test.assertTrue(a + b + c == 500, "the tower shared " + (a + b + c) + " FE/t, cap is 500");
            test.succeed();
        } finally {
            GridLink.unregisterLoad(test.getLevel(), drone);
            GridLink.unregisterLoad(test.getLevel(), mill);
            GridLink.unregisterLoad(test.getLevel(), dock);
            cleanup(test);
        }
    }

    // ---------------------------------------------------------------- 5

    @GameTest(template = "grid_test_room", timeoutTicks = 60)
    public static void towerPullsFromAdjacentBatteryBank(GameTestHelper test) {
        prepare(test);
        BlockPos bankPos = new BlockPos(16, 1, 24);
        BlockPos towerPos = new BlockPos(17, 1, 24);
        test.setBlock(bankPos, PowerBlocks.BANK.get());
        test.setBlock(towerPos, TowerRegistry.TOWER.get());
        ServerLevel level = test.getLevel();
        BatteryBankEntity bank = (BatteryBankEntity) test.getBlockEntity(bankPos);
        CompoundTag full = bank.saveWithFullMetadata(level.registryAccess());
        full.putInt("Energy", 500_000);
        bank.loadWithComponents(full, level.registryAccess());
        PowerTowerEntity tower = towerEntity(test, towerPos);
        tower.setEnergy(0);
        // Direct tick, before the bank's own push runs, so the measured 2,000 FE is the tower's pull.
        tower.tick(level);
        test.assertTrue(tower.energy() == 2_000, "tower pulled " + tower.energy() + " FE, expected 2,000");
        test.assertTrue(bank.getEnergyStored() == 498_000, "bank lost " + (500_000 - bank.getEnergyStored()));
        cleanup(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 6

    @GameTest(template = "grid_test_room", timeoutTicks = 60)
    public static void breakingAnySegmentRemovesTowerAndDropsOnce(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();

        // Bottom broken directly: the bottom's own loot table must drop once.
        BlockPos baseA = new BlockPos(24, 1, 24);
        tower(test, baseA);
        var mainState = test.getBlockState(baseA);
        var preview = Block.getDrops(mainState, level, abs(test, baseA), null);
        level.destroyBlock(abs(test, baseA), true);
        test.assertTrue(test.getBlockState(baseA).isAir() && test.getBlockState(baseA.above()).isAir()
                && test.getBlockState(baseA.above(2)).isAir(), "bottom break left a segment behind");
        int fromBottom = towerDrops(level, abs(test, baseA));
        test.assertTrue(fromBottom == 1, "bottom break dropped " + fromBottom + " towers (loot preview " + preview + ")");

        // Middle broken: all three must disappear and exactly one tower item must drop.
        BlockPos baseB = new BlockPos(34, 1, 24);
        tower(test, baseB);
        level.destroyBlock(abs(test, baseB.above()), true);
        test.assertTrue(test.getBlockState(baseB).isAir() && test.getBlockState(baseB.above()).isAir()
                && test.getBlockState(baseB.above(2)).isAir(), "middle break left a segment behind");
        int fromMiddle = towerDrops(level, abs(test, baseB));
        test.assertTrue(fromMiddle == 1, "middle break dropped " + fromMiddle + " towers");
        cleanup(test);
        test.succeed();
    }

    /** Counts and discards power-tower items near {@code pos}. */
    private static int towerDrops(ServerLevel level, BlockPos pos) {
        int towers = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(6.0D))) {
            if (entity.getItem().is(TowerRegistry.ITEM.get())) towers += entity.getItem().getCount();
            entity.discard();
        }
        return towers;
    }

    // ---------------------------------------------------------------- 7

    @GameTest(template = "grid_test_room", timeoutTicks = 60)
    public static void chargeSurvivesSaveRoundTrip(GameTestHelper test) {
        prepare(test);
        BlockPos pos = new BlockPos(24, 1, 24);
        tower(test, pos);
        PowerTowerEntity tower = towerEntity(test, pos);
        ServerLevel level = test.getLevel();
        tower.setEnergy(654_321);
        CompoundTag saved = tower.saveWithFullMetadata(level.registryAccess());
        tower.setEnergy(0);
        tower.loadWithComponents(saved, level.registryAccess());
        test.assertTrue(tower.energy() == 654_321, "charge after reload " + tower.energy());
        cleanup(test);
        test.succeed();
    }
}
