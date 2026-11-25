package dev.dronepal;

import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.mill.OreMillRules;
import dev.dronepal.mill.WaterPipeEntity;
import dev.dronepal.mill.WaterPumpEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Water pipe: transport to a mill, the 32-block limit, even splitting and the terminal readout. */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class WaterPipeGameTest {
    private static void infiniteSource(GameTestHelper test, BlockPos north) {
        var level = test.getLevel();
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++)
            level.setBlockAndUpdate(north.offset(dx, 0, -dz), Blocks.WATER.defaultBlockState());
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void pumpThroughPipeFillsTheMill(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        level.setBlockAndUpdate(pumpPos.east(), OreMillRegistry.WATER_PIPE.get().defaultBlockState());
        level.setBlockAndUpdate(pumpPos.east(2), OreMillRegistry.MILL.get().defaultBlockState());
        infiniteSource(test, pumpPos.north().west());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        var pipe = (WaterPipeEntity) level.getBlockEntity(pumpPos.east());
        var mill = (OreMillEntity) level.getBlockEntity(pumpPos.east(2));
        mill.setWater(0);
        for (int i = 0; i < 5; i++) { pump.tick(level); pipe.tick(level); }
        test.assertTrue(pipe.flow() > 0 && pipe.downstreamMachines() == 1 && pipe.networkLength() >= 1,
                "pipe readout wrong: flow=" + pipe.flow() + " downstream=" + pipe.downstreamMachines() + " length=" + pipe.networkLength());
        for (int i = 0; i < 25; i++) { pump.tick(level); pipe.tick(level); }
        test.assertTrue(mill.water() == OreMillRules.WATER_CAPACITY, "pipe did not fill the mill: " + mill.water());
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void runsLongerThanThirtyTwoBlocksDoNotTransfer(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        java.util.List<WaterPipeEntity> pipes = new java.util.ArrayList<>();
        for (int i = 1; i <= 34; i++) {
            BlockPos pos = pumpPos.east(i);
            level.setBlockAndUpdate(pos, OreMillRegistry.WATER_PIPE.get().defaultBlockState());
            pipes.add((WaterPipeEntity) level.getBlockEntity(pos));
        }
        BlockPos millPos = pumpPos.east(35);
        level.setBlockAndUpdate(millPos, OreMillRegistry.MILL.get().defaultBlockState());
        infiniteSource(test, pumpPos.north().west());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        var mill = (OreMillEntity) level.getBlockEntity(millPos);
        mill.setWater(0);
        for (int i = 0; i < 60; i++) {
            pump.tick(level);
            for (var pipe : pipes) pipe.tick(level);
        }
        test.assertTrue(mill.water() == 0, "water crossed a 34-block run: " + mill.water());
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void onePumpSplitsBetweenTwoMills(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos pumpPos = test.absolutePos(new BlockPos(2, 1, 2));
        BlockPos pipePos = pumpPos.east();
        level.setBlockAndUpdate(pumpPos, OreMillRegistry.WATER_PUMP.get().defaultBlockState());
        level.setBlockAndUpdate(pipePos, OreMillRegistry.WATER_PIPE.get().defaultBlockState());
        level.setBlockAndUpdate(pipePos.north(), OreMillRegistry.MILL.get().defaultBlockState());
        level.setBlockAndUpdate(pipePos.south(), OreMillRegistry.MILL.get().defaultBlockState());
        infiniteSource(test, pumpPos.north().west());
        var pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        var pipe = (WaterPipeEntity) level.getBlockEntity(pipePos);
        var north = (OreMillEntity) level.getBlockEntity(pipePos.north());
        var south = (OreMillEntity) level.getBlockEntity(pipePos.south());
        north.setWater(0);
        south.setWater(0);
        for (int i = 0; i < 20; i++) { pump.tick(level); pipe.tick(level); }
        test.assertTrue(Math.abs(north.water() - south.water()) <= 200,
                "split is uneven: " + north.water() + " / " + south.water());
        test.assertTrue(north.water() > 0 && south.water() > 0, "one branch got no water");
        test.succeed();
    }
}
