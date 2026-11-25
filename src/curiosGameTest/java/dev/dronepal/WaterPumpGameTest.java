package dev.dronepal;

import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.mill.OreMillRules;
import dev.dronepal.mill.WaterPumpEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Water pump: source gating, 200 mB/t fill and push, "full means stop", persistence and drops. */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class WaterPumpGameTest {
    private record Rig(WaterPumpEntity pump, OreMillEntity mill, BlockPos water, BlockPos pumpPos) {}

    private static Rig rig(GameTestHelper test, BlockPos relative) {
        var level = test.getLevel();
        var pumpPos = test.absolutePos(relative);
        var millPos = pumpPos.east();
        var waterPos = pumpPos.north();
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        level.setBlockAndUpdate(millPos, OreMillRegistry.MILL.get().defaultBlockState());
        level.setBlockAndUpdate(waterPos, Blocks.WATER.defaultBlockState());
        // A source with a source above counts as infinite, so this rig never empties its pool.
        level.setBlockAndUpdate(waterPos.above(), Blocks.WATER.defaultBlockState());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        var mill = (OreMillEntity) level.getBlockEntity(millPos);
        mill.setWater(0);
        return new Rig(pump, mill, waterPos, pumpPos);
    }

    private static void tick(GameTestHelper test, WaterPumpEntity pump, int ticks) {
        for (int i = 0; i < ticks; i++) pump.tick(test.getLevel());
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void pumpFillsTheMillTankAndThenStops(GameTestHelper test) {
        var rig = rig(test, new BlockPos(2, 1, 2));
        test.assertTrue(rig.pump().source(), "pump did not see the adjacent water source");
        tick(test, rig.pump(), 25);
        test.assertTrue(rig.mill().water() == OreMillRules.WATER_CAPACITY,
                "mill tank should be full, was " + rig.mill().water());
        test.assertTrue(rig.pump().water() == 1_000, "pump buffer after 25 ticks was " + rig.pump().water());
        tick(test, rig.pump(), 40);
        test.assertTrue(rig.pump().water() == OreMillRules.PUMP_BUFFER, "pump buffer did not stop at 8,000: " + rig.pump().water());
        test.assertTrue(rig.mill().water() == OreMillRules.WATER_CAPACITY, "mill lost water while full");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void pumpStopsWithoutWaterAndRestarts(GameTestHelper test) {
        var level = test.getLevel();
        var pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        level.setBlockAndUpdate(pumpPos.east(), OreMillRegistry.MILL.get().defaultBlockState());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        var mill = (OreMillEntity) level.getBlockEntity(pumpPos.east());
        mill.setWater(0);
        tick(test, pump, 30);
        test.assertTrue(!pump.source() && pump.water() == 0 && mill.water() == 0, "pump ran without water");
        level.setBlockAndUpdate(pumpPos.north(), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(pumpPos.north().above(), Blocks.WATER.defaultBlockState());
        tick(test, pump, 20);
        test.assertTrue(pump.source() && mill.water() == OreMillRules.WATER_CAPACITY, "pump did not restart after water appeared");
        level.setBlockAndUpdate(pumpPos.north(), Blocks.AIR.defaultBlockState());
        tick(test, pump, 1);
        int held = pump.water();
        tick(test, pump, 20);
        test.assertTrue(!pump.source() && pump.water() == held, "pump kept drawing after the water was removed");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void pumpAndTankSurviveSaveLoad(GameTestHelper test) {
        var level = test.getLevel();
        var rig = rig(test, new BlockPos(2, 1, 2));
        tick(test, rig.pump(), 12);
        rig.mill().setWater(2_500);
        var pumpTag = rig.pump().saveWithoutMetadata(level.registryAccess());
        var millTag = rig.mill().saveWithoutMetadata(level.registryAccess());
        var pump = new WaterPumpEntity(rig.pumpPos(), rig.pump().getBlockState());
        pump.setLevel(level);
        pump.loadWithComponents(pumpTag, level.registryAccess());
        var mill = new OreMillEntity(rig.mill().getBlockPos(), rig.mill().getBlockState());
        mill.setLevel(level);
        mill.loadWithComponents(millTag, level.registryAccess());
        test.assertTrue(pump.water() == rig.pump().water(), "pump buffer was lost on save/load");
        test.assertTrue(mill.water() == 2_500, "mill tank was lost on save/load: " + mill.water());
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void infinitePoolNeverDrains(GameTestHelper test) {
        var level = test.getLevel();
        var pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        var origin = pumpPos.north().west();
        var pool = java.util.List.of(origin, origin.east(), origin.north(), origin.east().north());
        for (var pos : pool) level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        tick(test, pump, 100);
        test.assertTrue(pump.infiniteSource() && pump.source(), "a 2x2 pool was not treated as infinite");
        for (var pos : pool) test.assertTrue(level.getBlockState(pos).is(Blocks.WATER), "an infinite pool lost a source block");
        test.assertTrue(pump.water() == OreMillRules.PUMP_BUFFER, "pump buffer after 20,000 mB was " + pump.water());
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void singleSourceDrainsAndStops(GameTestHelper test) {
        var level = test.getLevel();
        var pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        // The room template is reused between tests; clear any water a previous case left behind.
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
            level.setBlockAndUpdate(pumpPos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        var source = pumpPos.north();
        level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        tick(test, pump, 1);
        test.assertTrue(!pump.infiniteSource(), "a lone source block was treated as infinite; source="
                + level.getBlockState(source) + " above=" + level.getBlockState(source.above())
                + " north=" + level.getBlockState(source.north()) + " east=" + level.getBlockState(source.east())
                + " west=" + level.getBlockState(source.west()) + " south=" + level.getBlockState(source.south()));
        tick(test, pump, 4);
        test.assertTrue(level.getBlockState(source).isAir(), "finite source was not drained after 1,000 mB");
        test.assertTrue(!pump.source() && pump.water() == 1_000, "pump kept pumping an exhausted source: " + pump.water());
        tick(test, pump, 10);
        test.assertTrue(pump.water() == 1_000, "pump kept drawing after the source was gone");
        level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        tick(test, pump, 1);
        test.assertTrue(pump.source() && pump.water() == 1_200, "refilled source did not restart the pump");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void pumpDropsItselfWhenBroken(GameTestHelper test) {
        var level = test.getLevel();
        var pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        level.destroyBlock(pumpPos, true);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pumpPos).inflate(2)).stream().map(ItemEntity::getItem).toList();
        test.assertTrue(drops.stream().anyMatch(stack -> stack.is(OreMillRegistry.WATER_PUMP_ITEM.get())), "pump did not drop itself");
        test.succeed();
    }
}
