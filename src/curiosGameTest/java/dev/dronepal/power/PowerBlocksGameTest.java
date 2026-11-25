package dev.dronepal.power;

import dev.dronepal.charging.ChargingDockEntity;
import dev.dronepal.charging.ChargingDockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side smoke tests for the power chain. The blocks are ticked directly so a 900-tick fill is
 * instant while still exercising the real tick bodies, capability push and NBT paths.
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class PowerBlocksGameTest {
    private static final BlockPos DOCK = new BlockPos(1, 1, 2);
    private static final BlockPos BURNER = new BlockPos(2, 1, 2);
    private static final BlockPos SOLAR = new BlockPos(3, 1, 2);
    private static final BlockPos BANK = new BlockPos(2, 1, 3);

    private static CompoundTag nbt(BlockEntity entity, ServerLevel world) {
        return entity.saveWithFullMetadata(world.registryAccess());
    }

    private static void load(BlockEntity entity, CompoundTag tag, ServerLevel world) {
        entity.loadWithComponents(tag, world.registryAccess());
    }

    private static int fuelCount(BlockEntity entity, ServerLevel world) {
        return nbt(entity, world).getCompound("Fuel").getInt("count");
    }

    private static BlockPos abs(GameTestHelper test, BlockPos pos) { return test.absolutePos(pos); }

    /**
     * Steps a block through its public ticker, the same path the level takes, so the test never needs
     * package-private access to another block's entity.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void tickBlock(ServerLevel world, BlockPos pos) {
        var state = world.getBlockState(pos);
        var entity = world.getBlockEntity(pos);
        if (entity == null) return;
        BlockEntityTicker ticker = state.getTicker(world, entity.getType());
        if (ticker != null) ticker.tick(world, pos, state, entity);
    }

    // ---- burner generator ----

    @GameTest(template = "curios_test_room", timeoutTicks = 280)
    public static void burnerFillsAdjacentDockToItsBuffer(GameTestHelper test) {
        // Four generators around the pad push 160 FE/t in total, so the 32,000 FE buffer fills in
        // about 200 real ticks. Real ticks matter here: the pad's 2,000 FE/t cable ceiling is keyed
        // to world time, so a synthetic tick loop would throttle the pad instead of the generator.
        BlockPos[] generators = {new BlockPos(1, 1, 2), new BlockPos(3, 1, 2), new BlockPos(2, 1, 1), new BlockPos(2, 1, 3)};
        test.setBlock(new BlockPos(2, 1, 2), ChargingDockRegistry.DOCK.get());
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        ServerLevel world = test.getLevel();
        for (BlockPos pos : generators) {
            test.setBlock(pos, PowerBlocks.BURNER.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 4));
            test.useBlock(pos, player);
            test.assertTrue(player.getMainHandItem().isEmpty(), "burner at " + pos + " refused coal");
        }
        test.runAfterDelay(230, () -> {
            var dock = (ChargingDockEntity) world.getBlockEntity(abs(test, new BlockPos(2, 1, 2)));
            test.assertTrue(dock.getEnergyStored() == 32_000, "dock never reached its buffer: " + dock.getEnergyStored());
            for (BlockPos pos : generators) {
                var burner = (BurnerGeneratorEntity) world.getBlockEntity(abs(test, pos));
                test.assertTrue(fuelCount(burner, world) > 0, "generator at " + pos + " ran dry before the pad filled");
            }
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void burnerNeitherBurnsWithoutReceiverNorWhenFull(GameTestHelper test) {
        test.setBlock(BURNER, PowerBlocks.BURNER.get());
        ServerLevel world = test.getLevel();
        var burner = (BurnerGeneratorEntity) world.getBlockEntity(abs(test, BURNER));
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 3));
        test.useBlock(BURNER, player);
        for (int i = 0; i < 100; i++) burner.tick(world);
        test.assertTrue(burner.getEnergyStored() == 0, "burner produced power with no receiver");
        test.assertTrue(fuelCount(burner, world) == 3 && nbt(burner, world).getInt("BurnRemaining") == 0,
                "burner consumed fuel with no receiver");
        // A full buffer is the other half of the idle rule: even a present-but-full receiver cannot
        // create room, so the fire stops.
        test.setBlock(BANK, PowerBlocks.BANK.get());
        var bank = (BatteryBankEntity) world.getBlockEntity(abs(test, BANK));
        CompoundTag full = nbt(burner, world);
        full.putInt("Energy", BurnerGeneratorEntity.BUFFER);
        load(burner, full, world);
        CompoundTag bankFull = nbt(bank, world);
        bankFull.putInt("Energy", bank.getMaxEnergyStored());
        load(bank, bankFull, world);
        for (int i = 0; i < 50; i++) burner.tick(world);
        test.assertTrue(burner.getEnergyStored() == BurnerGeneratorEntity.BUFFER, "full buffer drifted");
        test.assertTrue(fuelCount(burner, world) == 3, "full buffer kept eating fuel");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void burnerDropsItselfAndLeftoverFuel(GameTestHelper test) {
        test.setBlock(BURNER, PowerBlocks.BURNER.get());
        test.setBlock(BANK, PowerBlocks.BANK.get());
        ServerLevel world = test.getLevel();
        var burner = (BurnerGeneratorEntity) world.getBlockEntity(abs(test, BURNER));
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 3));
        test.useBlock(BURNER, player);
        for (int i = 0; i < 10; i++) burner.tick(world); // 400 FE: one lump starts burning, 2 remain queued
        BlockPos pos = abs(test, BURNER);
        world.destroyBlock(pos, true); // true = roll the loot table, so the block itself drops
        var drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(1));
        long blocks = drops.stream().map(e -> e.getItem()).filter(s -> s.is(PowerBlocks.BURNER.get().asItem())).count();
        int coal = drops.stream().map(e -> e.getItem()).filter(s -> s.is(Items.COAL)).mapToInt(ItemStack::getCount).sum();
        test.assertTrue(blocks == 1, "burner did not drop itself exactly once: " + blocks);
        test.assertTrue(coal == 2, "burner lost or duplicated leftover fuel: " + coal);
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void burnerAndSolarSurviveSaveRoundTrip(GameTestHelper test) {
        test.setBlock(BURNER, PowerBlocks.BURNER.get());
        test.setBlock(BANK, PowerBlocks.BANK.get());
        test.setBlock(SOLAR, PowerBlocks.SOLAR.get());
        ServerLevel world = test.getLevel();
        var burner = (BurnerGeneratorEntity) world.getBlockEntity(abs(test, BURNER));
        var solar = (SolarGeneratorEntity) world.getBlockEntity(abs(test, SOLAR));
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 4));
        test.useBlock(BURNER, player);
        for (int i = 0; i < 30; i++) burner.tick(world);
        int burnerEnergy = burner.getEnergyStored();
        int burnRemaining = nbt(burner, world).getInt("BurnRemaining");
        int fuel = fuelCount(burner, world);
        var restored = new BurnerGeneratorEntity(abs(test, BURNER), world.getBlockState(abs(test, BURNER)));
        load(restored, nbt(burner, world), world);
        test.assertTrue(restored.getEnergyStored() == burnerEnergy && fuelCount(restored, world) == fuel,
                "burner lost energy or fuel across a reload");
        test.assertTrue(nbt(restored, world).getInt("BurnRemaining") == burnRemaining,
                "burner lost the part-burnt fuel timer: " + nbt(restored, world).getInt("BurnRemaining") + " vs " + burnRemaining);
        // Solar keeps its buffer too.
        world.setDayTime(6_000L);
        world.setWeatherParameters(6_000, 0, false, false);
        for (int i = 0; i < 20; i++) solar.tick(world);
        int solarEnergy = solar.getEnergyStored();
        test.assertTrue(solarEnergy > 0, "solar produced nothing at noon");
        var restoredSolar = new SolarGeneratorEntity(abs(test, SOLAR), world.getBlockState(abs(test, SOLAR)));
        load(restoredSolar, nbt(solar, world), world);
        test.assertTrue(restoredSolar.getEnergyStored() == solarEnergy, "solar lost its buffer across a reload");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void generatorsFaceThePlayerAndRotate(GameTestHelper test) {
        test.setBlock(BURNER, PowerBlocks.BURNER.get());
        test.setBlock(SOLAR, PowerBlocks.SOLAR.get());
        ServerLevel world = test.getLevel();
        BlockPos burnerPos = abs(test, BURNER);
        BlockPos solarPos = abs(test, SOLAR);
        var burnerState = world.getBlockState(burnerPos);
        var solarState = world.getBlockState(solarPos);
        // Default state (also what an old save without the property loads) faces north.
        test.assertTrue(burnerState.getValue(BurnerGeneratorBlock.FACING) == Direction.NORTH,
                "burner default facing is " + burnerState.getValue(BurnerGeneratorBlock.FACING));
        test.assertTrue(solarState.getValue(SolarGeneratorBlock.FACING) == Direction.NORTH,
                "solar default facing is " + solarState.getValue(SolarGeneratorBlock.FACING));
        test.assertTrue(burnerState.rotate(Rotation.CLOCKWISE_90).getValue(BurnerGeneratorBlock.FACING) == Direction.EAST,
                "rotating the housing does not turn the facing");
        // LEFT_RIGHT flips the north/south axis, so a north-facing block mirrors to south.
        test.assertTrue(solarState.mirror(Mirror.LEFT_RIGHT).getValue(SolarGeneratorBlock.FACING) == Direction.SOUTH,
                "mirroring the housing does not turn the facing");
        // Placement: the face looks at the player, so a player facing west places it facing east.
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(90.0F);
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(PowerBlocks.BURNER.get()),
                new BlockHitResult(Vec3.atCenterOf(burnerPos), Direction.UP, burnerPos, false));
        test.assertTrue(PowerBlocks.BURNER.get().getStateForPlacement(context).getValue(BurnerGeneratorBlock.FACING) == Direction.EAST,
                "placement did not face the player: " + PowerBlocks.BURNER.get().getStateForPlacement(context));
        test.succeed();
    }

    // ---- solar generator ----

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void solarSixtyAtNoonZeroAtNightAndUnderCover(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        test.setBlock(SOLAR, PowerBlocks.SOLAR.get());
        var solar = (SolarGeneratorEntity) world.getBlockEntity(abs(test, SOLAR));
        world.setDayTime(6_000L);
        world.setWeatherParameters(6_000, 0, false, false);
        int start = solar.getEnergyStored();
        for (int i = 0; i < 10; i++) solar.tick(world);
        test.assertTrue(world.getBlockState(abs(test, SOLAR)).getValue(SolarGeneratorBlock.SUN) == 2,
                "clear noon panel does not show the full-sun state");
        int gained = solar.getEnergyStored() - start;
        test.assertTrue(solar.peak() == 60, "solarOutput default changed: " + solar.peak());
        test.assertTrue(gained == 600, "clear noon output is not 60 FE/t: " + gained / 10.0D + " FE/t");
        world.setDayTime(18_000L);
        solar.tick(world);
        test.assertTrue(world.getBlockState(abs(test, SOLAR)).getValue(SolarGeneratorBlock.SUN) == 0,
                "night panel does not show the idle state");
        int night = solar.getEnergyStored();
        test.assertTrue(SolarGeneratorEntity.rate(world, abs(test, SOLAR)) == 0, "night still produces");
        for (int i = 0; i < 10; i++) solar.tick(world);
        test.assertTrue(solar.getEnergyStored() == night, "solar charged at night");
        world.setDayTime(6_000L);
        test.setBlock(new BlockPos(3, 2, 2), Blocks.STONE);
        // Sky light is recalculated by the light engine, not by setBlock itself: give the level a tick
        // before expecting canSeeSky to flip, otherwise the panel is still lit in the same tick.
        test.runAfterDelay(3, () -> {
            test.assertTrue(SolarGeneratorEntity.rate(world, abs(test, SOLAR)) == 0, "a covered panel still produces");
            int covered = solar.getEnergyStored();
            for (int i = 0; i < 10; i++) solar.tick(world);
            test.assertTrue(solar.getEnergyStored() == covered, "covered panel kept charging");
            test.succeed();
        });
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void solarFeedsAdjacentDock(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        BlockPos dockPos = new BlockPos(1, 1, 2);
        BlockPos solarPos = new BlockPos(1, 1, 1);
        test.setBlock(dockPos, ChargingDockRegistry.DOCK.get());
        test.setBlock(solarPos, PowerBlocks.SOLAR.get());
        world.setDayTime(6_000L);
        world.setWeatherParameters(6_000, 0, false, false);
        var solar = (SolarGeneratorEntity) world.getBlockEntity(abs(test, solarPos));
        var dock = (ChargingDockEntity) world.getBlockEntity(abs(test, dockPos));
        // 30 synthetic ticks stay below the pad's 2,000 FE/t cable ceiling, which is keyed to world
        // time: 30 x 60 FE should arrive in full, proving the generator-to-dock push.
        for (int i = 0; i < 30; i++) {
            solar.tick(world);
            tickBlock(world, abs(test, dockPos));
        }
        test.assertTrue(dock.getEnergyStored() == 1_800, "dock received " + dock.getEnergyStored() + " FE instead of 1,800");
        test.assertTrue(solar.getEnergyStored() == 0, "solar kept power instead of pushing it");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void batteryBankGaugeFollowsItsChargeWindow(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        test.setBlock(BANK, PowerBlocks.BANK.get());
        BlockPos pos = abs(test, BANK);
        var bank = (BatteryBankEntity) world.getBlockEntity(pos);
        int capacity = bank.getMaxEnergyStored();
        test.assertTrue(world.getBlockState(pos).getValue(BatteryBankBlock.CHARGE) == 0, "fresh bank does not start empty-window");
        int[][] steps = {{0, 0}, {capacity * 40 / 100, 1}, {capacity * 70 / 100, 2}, {capacity, 3}};
        for (int[] step : steps) {
            CompoundTag preset = nbt(bank, world);
            preset.putInt("Energy", step[0]);
            load(bank, preset, world);
            bank.tick(world);
            int window = world.getBlockState(pos).getValue(BatteryBankBlock.CHARGE);
            test.assertTrue(window == step[1], "gauge window for " + bank.percent() + "% was " + window + ", expected " + step[1]);
        }
        test.assertTrue(bank.percent() == 100 && bank.chargeWindow() == 3, "full bank does not report 100% / window 4");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 120)
    public static void solarPanelStateFollowsWeather(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        test.setBlock(SOLAR, PowerBlocks.SOLAR.get());
        BlockPos pos = abs(test, SOLAR);
        var solar = (SolarGeneratorEntity) world.getBlockEntity(pos);
        world.setDayTime(6_000L);
        world.setWeatherParameters(6_000, 0, false, false);
        solar.tick(world);
        test.assertTrue(world.getBlockState(pos).getValue(SolarGeneratorBlock.SUN) == 2,
                "clear day should show the full-sun state");
        test.assertTrue(SolarGeneratorEntity.rate(world, pos) == 60, "clear day rate is not 60 FE/t");
        // Rain ramps up over several ticks (rainLevel > 0.2 turns isRaining true), so wait for it.
        world.setWeatherParameters(0, 6_000, true, false);
        test.runAfterDelay(35, () -> {
            solar.tick(world);
            test.assertTrue(world.getBlockState(pos).getValue(SolarGeneratorBlock.SUN) == 1,
                    "rain should dim the panel, state=" + world.getBlockState(pos).getValue(SolarGeneratorBlock.SUN));
            test.assertTrue(SolarGeneratorEntity.rate(world, pos) == 30, "rain rate is not half of 60 FE/t");
            world.setWeatherParameters(6_000, 0, false, false);
            test.runAfterDelay(40, () -> {
                solar.tick(world);
                test.assertTrue(world.getBlockState(pos).getValue(SolarGeneratorBlock.SUN) == 2,
                        "panel stayed dim after the rain stopped");
                test.succeed();
            });
        });
    }

    // ---- battery bank ----

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void bankStopsAtCapacityAndKeepsInput(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        test.setBlock(BURNER, PowerBlocks.BURNER.get());
        test.setBlock(BANK, PowerBlocks.BANK.get());
        var bank = (BatteryBankEntity) world.getBlockEntity(abs(test, BANK));
        var burner = (BurnerGeneratorEntity) world.getBlockEntity(abs(test, BURNER));
        int capacity = bank.getMaxEnergyStored();
        test.assertTrue(capacity == 500_000, "battery bank default capacity is not 500,000: " + capacity);
        CompoundTag preset = nbt(bank, world);
        preset.putInt("Energy", capacity - 100);
        load(bank, preset, world);
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL, 8));
        test.useBlock(BURNER, player);
        for (int i = 0; i < 10; i++) burner.tick(world);
        test.assertTrue(bank.getEnergyStored() == capacity, "bank overflowed or under-filled: " + bank.getEnergyStored());
        test.assertTrue(burner.getEnergyStored() == 300, "burner did not retain the surplus: " + burner.getEnergyStored());
        for (int i = 0; i < 100; i++) burner.tick(world);
        test.assertTrue(bank.getEnergyStored() == capacity, "full bank kept accepting charge");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void bankDischargesIntoAdjacentDockUntilEmpty(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        BlockPos dockPos = new BlockPos(1, 1, 2);
        BlockPos bankPos = new BlockPos(2, 1, 2);
        test.setBlock(dockPos, ChargingDockRegistry.DOCK.get());
        test.setBlock(bankPos, PowerBlocks.BANK.get());
        var bank = (BatteryBankEntity) world.getBlockEntity(abs(test, bankPos));
        var dock = (ChargingDockEntity) world.getBlockEntity(abs(test, dockPos));
        CompoundTag preset = nbt(bank, world);
        preset.putInt("Energy", 1_000);
        load(bank, preset, world);
        bank.tick(world);
        test.assertTrue(bank.getEnergyStored() == 0, "bank did not empty into the dock: " + bank.getEnergyStored());
        test.assertTrue(dock.getEnergyStored() == 1_000, "dock did not receive the discharge: " + dock.getEnergyStored());
        for (int i = 0; i < 20; i++) bank.tick(world);
        test.assertTrue(bank.getEnergyStored() == 0, "empty bank invented power");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void bankKeepsChargeWithoutReceiverAndLosesItOnBreak(GameTestHelper test) {
        ServerLevel world = test.getLevel();
        test.setBlock(BANK, PowerBlocks.BANK.get());
        var bank = (BatteryBankEntity) world.getBlockEntity(abs(test, BANK));
        CompoundTag preset = nbt(bank, world);
        preset.putInt("Energy", 5_000);
        load(bank, preset, world);
        for (int i = 0; i < 50; i++) bank.tick(world);
        test.assertTrue(bank.getEnergyStored() == 5_000, "bank leaked charge with no receiver: " + bank.getEnergyStored());
        var restored = new BatteryBankEntity(abs(test, BANK), world.getBlockState(abs(test, BANK)));
        load(restored, nbt(bank, world), world);
        test.assertTrue(restored.getEnergyStored() == 5_000, "bank lost its charge across a reload");
        BlockPos pos = abs(test, BANK);
        world.destroyBlock(pos, true); // true = roll the loot table, so the block itself drops
        var drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(1));
        long blocks = drops.stream().map(e -> e.getItem()).filter(s -> s.is(PowerBlocks.BANK.get().asItem())).count();
        boolean carriesCharge = drops.stream().map(e -> e.getItem()).anyMatch(s -> s.get(DataComponents.CUSTOM_DATA) != null);
        test.assertTrue(blocks == 1, "bank did not drop itself exactly once: " + blocks);
        test.assertTrue(!carriesCharge, "bank carried its charge out in the dropped item");
        test.succeed();
    }
}
