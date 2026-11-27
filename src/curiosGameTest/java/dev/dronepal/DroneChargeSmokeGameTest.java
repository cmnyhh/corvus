package dev.dronepal;

import dev.dronepal.power.grid.DroneCharger;
import dev.dronepal.power.grid.GridLink;
import dev.dronepal.power.grid.GridLoad;
import dev.dronepal.power.grid.PowerTowerBlock;
import dev.dronepal.power.grid.PowerTowerEntity;
import dev.dronepal.power.grid.PowerTowerPartBlock;
import dev.dronepal.power.grid.TowerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Acceptance smoke for the intermittent-charging complaints (drone battery, 1.21.1).
 *
 * <p>These drive the real settlement contract — a deployed drone's controller stack, the tower block
 * entities and the same per-tick allocator the server runs — instead of a mock, because every bug
 * below lives in the seam between the one-second drone tick and the one-tick grid budget:</p>
 *
 * <ol>
 *   <li><b>Sub-FE delivery.</b> A big cell fed by a 1 FE/t trickle through one hop must still charge
 *       every second: {@code floor(1 * 0.95)} is 0, so without a carried remainder the tower pays
 *       1 FE/t for ever and the cell never moves. 200 ticks must put {@code 0.95 * 200} FE in.</li>
 *   <li><b>Top-off.</b> Three FE short of full over one hop used to stall one FE short for ever,
 *       because the bank shrank to a single FE per tick and {@code floor(1 * 0.95) = 0}.</li>
 *   <li><b>Reload.</b> A projection that is unloaded (discarded) is not settled and keeps no share of
 *       the tower; the same serial coming back resumes charging without paying for the same FE twice.</li>
 *   <li><b>Queued.</b> A tower whose whole budget went to the ore mill must report "on grid · queued"
 *       (the HUD reads {@code online}, {@code queued} and {@code ratePerSecond}).</li>
 *   <li><b>Controller swap.</b> A controller with a different serial must not charge the airframe, the
 *       old serial's cell must not be charged by the new airframe, and the swapped-out airframe must
 *       stop drawing a share of the tower.</li>
 *   <li><b>State changes.</b> Idle ⇄ working ⇄ docked between settlements must not blank the charging
 *       target for a second.</li>
 * </ol>
 *
 * <p>Inside one game test the world does not advance, so the allocator is driven explicitly: one
 * {@link GridLink#forceAllocate} per simulated tick and one settlement every twenty ticks, which is
 * exactly the order the server runs (allocate at the start of the tick, the drone settles on its
 * second boundary).</p>
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DroneChargeSmokeGameTest {
    /** ahi_bat_mk1 after the rebalance (was 250,000). */
    private static final int CAPACITY_MK1 = 500_000;
    private static final int TOWER_ENERGY = 600_000;
    /** One settlement's worth of the default 500 FE/t budget, 0 hops. */
    private static final int PER_SECOND = 10_000;

    private DroneChargeSmokeGameTest() {}

    private static final class Probe implements GridLoad {
        private final BlockPos pos;
        private final int priority, demand;
        Probe(BlockPos pos, int priority, int demand) { this.pos = pos; this.priority = priority; this.demand = demand; }
        @Override public BlockPos gridPos() { return pos; }
        @Override public int gridPriority() { return priority; }
        @Override public int gridDemandFe() { return demand; }
    }

    // ---------------------------------------------------------------- fixtures

    /** The tower columns this room has always used; the only place a test of this class builds one. */
    private static final int[] COLUMN_X = {14, 24, 34};
    private static final int[] COLUMN_Z = {24, 26};

    /**
     * Removes every tower segment this room could still hold.
     *
     * <p>The gametest level is shared and its structures are only five or six blocks apart
     * ({@code StructureGridSpawner}), so the scan has to stay inside this room: clearing a wider box
     * would delete the towers of the tests running next to it and break their numbers (and can leave
     * a neighbour's chunk in a state where its test never starts).</p>
     */
    private static void prepare(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        BlockPos base = test.absolutePos(BlockPos.ZERO);
        for (int x : COLUMN_X) for (int z : COLUMN_Z) for (int y = 0; y <= 110; y++) {
            BlockPos pos = base.offset(x, y, z);
            var block = level.getBlockState(pos).getBlock();
            if (block instanceof PowerTowerBlock || block instanceof PowerTowerPartBlock) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void tower(GameTestHelper test, BlockPos pos) {
        test.setBlock(pos, TowerRegistry.TOWER.get());
        if (!test.getBlockState(pos.above()).is(TowerRegistry.MID.get())) test.setBlock(pos.above(), TowerRegistry.MID.get());
        if (!test.getBlockState(pos.above(2)).is(TowerRegistry.TOP.get())) test.setBlock(pos.above(2), TowerRegistry.TOP.get());
    }

    private static PowerTowerEntity towerAt(GameTestHelper test, BlockPos pos) {
        return (PowerTowerEntity) test.getBlockEntity(pos);
    }

    /** A real controller: the drone item, one Mk I cell installed through the assembly path. */
    private static ItemStack controller(GameTestHelper test, ServerPlayer owner, int energy) {
        ItemStack controller = new ItemStack(Dronepal.DRONE.get());
        owner.getInventory().setItem(0, new ItemStack(Hardware.BATTERIES.get(0).get()));
        String error = Hardware.installFromInventory(owner, controller, 0);
        test.assertTrue(error.isEmpty(), "battery install failed: " + error);
        Hardware.setEnergy(controller, energy);
        return controller;
    }

    private static DroneEntity drone(GameTestHelper test, UUID serial, BlockPos offset) {
        BlockPos at = test.absolutePos(offset);
        DroneEntity drone = new DroneEntity(Dronepal.ENTITY.get(), test.getLevel());
        drone.setUUID(serial);
        drone.setPos(at.getX() + 0.5D, at.getY() + 1.5D, at.getZ() + 2.5D);
        return drone;
    }

    /** One server tick of the grid: the tower allocates, the drone settles on the second boundary. */
    private static List<Integer> run(GameTestHelper test, int ticks, DroneEntity drone, ItemStack controller,
            List<Integer> perTick) {
        List<Integer> charges = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            GridLink.forceAllocate(test.getLevel());
            if (tick % 20 == 19) charges.add(DroneCharger.service(test.getLevel(), drone, controller, 0).charged());
            if (perTick != null) perTick.add(Hardware.energy(controller));
        }
        return charges;
    }

    // ---------------------------------------------------------------- 1: sub-FE delivery

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void trickleFedCellChargesEverySecond(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        ServerPlayer owner = GameTestPlayers.join(test);
        BlockPos attached = new BlockPos(24, 1, 24);
        BlockPos supplied = new BlockPos(14, 1, 24);          // one hop away
        tower(test, attached);
        tower(test, supplied);
        towerAt(test, attached).setEnergy(0);                 // the attached tower holds nothing
        PowerTowerEntity source = towerAt(test, supplied);

        ItemStack controller = controller(test, owner, 0);    // empty 250,000 FE cell
        UUID serial = DroneItem.ensureSerial(controller);
        DroneEntity drone = drone(test, serial, attached);
        test.assertTrue(Hardware.capacity(controller) == CAPACITY_MK1, "unexpected cell: " + Hardware.capacity(controller));
        test.assertTrue(GridLink.covered(level, drone.blockPosition()), "drone is not covered");
        test.assertTrue(DroneCharger.service(level, drone, controller, 0).charged() == 0,
                "the seeding settlement charged before the grid had allocated anything");

        // The only power in the grid is a 1 FE/t trickle (a solar panel or a nearly empty bank).
        // One hop costs 5 %, and floor(1 * 0.95) = 0: this is the "it never charges" case.
        double ratio = ServerOptions.towerEfficiency();
        int spent = 0;
        List<Integer> perTick = new ArrayList<>();
        List<Integer> charges = new ArrayList<>();
        for (int tick = 0; tick < 200; tick++) {
            int before = source.energy();
            source.setEnergy(before + 1);
            GridLink.forceAllocate(level);
            spent += before + 1 - source.energy();
            if (tick % 20 == 19) charges.add(DroneCharger.service(level, drone, controller, 0).charged());
            perTick.add(Hardware.energy(controller));
        }

        int cell = Hardware.energy(controller);
        test.assertTrue(charges.size() == 10, "expected 10 settlements, got " + charges);
        test.assertTrue(charges.stream().allMatch(charged -> charged > 0),
                "a whole second delivered nothing (the remainder was floored away): " + charges);
        int rises = 0;
        for (int i = 1; i < perTick.size(); i++) {
            test.assertTrue(perTick.get(i) >= perTick.get(i - 1), "charge fell at tick " + i + ": " + perTick);
            if (perTick.get(i) > perTick.get(i - 1)) rises++;
        }
        test.assertTrue(rises == 10, "expected a rise in all ten seconds, got " + rises + ": " + perTick);
        test.assertTrue(spent == 200, "the tower spent " + spent + " FE, expected 1 FE/t for 200 ticks");
        int expected = (int) Math.round(spent * ratio);       // 190 FE at 0.95 per hop
        test.assertTrue(Math.abs(cell - expected) <= 2,
                "cell holds " + cell + " FE, expected " + expected + " (0.95 x " + spent + " FE spent)");
        drone.discard();
        prepare(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 2: the last few FE arrive

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void topOffAcrossOneHopReachesCapacity(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        ServerPlayer owner = GameTestPlayers.join(test);
        BlockPos localTower = new BlockPos(24, 1, 24);
        BlockPos supplied = new BlockPos(14, 1, 24);
        tower(test, localTower);
        towerAt(test, localTower).setEnergy(0);                           // the attached tower is empty
        tower(test, supplied);
        towerAt(test, supplied).setEnergy(TOWER_ENERGY);                   // so the FE comes from one hop away

        ItemStack controller = controller(test, owner, CAPACITY_MK1 - 3);  // three FE short of full
        UUID serial = DroneItem.ensureSerial(controller);
        DroneEntity drone = drone(test, serial, localTower);
        test.assertTrue(GridLink.covered(level, drone.blockPosition()), "drone is not covered");

        DroneCharger.service(level, drone, controller, 0);
        List<Integer> charges = run(test, 200, drone, controller, null);

        test.assertTrue(Hardware.energy(controller) == CAPACITY_MK1,
                "top-off stalled at " + Hardware.energy(controller) + "/" + CAPACITY_MK1 + " charges=" + charges);
        DroneCharger.Result last = DroneCharger.service(level, drone, controller, 0);
        test.assertTrue(last.full(), "a full cell is not reported full");
        test.assertTrue(!last.queued(), "a full cell is reported queued");
        test.assertTrue(towerAt(test, supplied).energy() < TOWER_ENERGY, "the supplying tower paid nothing");
        test.assertTrue(towerAt(test, supplied).energy() > TOWER_ENERGY - 200,
                "a top-off of 3 FE cost the tower " + (TOWER_ENERGY - towerAt(test, supplied).energy()) + " FE");
        drone.discard();
        prepare(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 3: unload/reload

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void unloadedDroneSettlesNothingAndReloadResumes(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        ServerPlayer owner = GameTestPlayers.join(test);
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        towerAt(test, towerPos).setEnergy(TOWER_ENERGY);

        ItemStack controller = controller(test, owner, 0);
        UUID serial = DroneItem.ensureSerial(controller);
        DroneEntity drone = drone(test, serial, towerPos);
        DroneCharger.service(level, drone, controller, 0);
        run(test, 100, drone, controller, null);
        int beforeUnload = Hardware.energy(controller);
        test.assertTrue(beforeUnload == 5 * PER_SECOND, "first 100 ticks charged " + beforeUnload);

        // Unload (chunk unload / recall / another dimension): the entity is discarded while the
        // controller and its serial stay in the slot. The projection cannot tick, so nothing settles;
        // the grid must also stop reserving a share for it.
        drone.discard();
        int towerBefore = towerAt(test, towerPos).energy();
        for (int tick = 0; tick < 100; tick++) GridLink.forceAllocate(level);
        test.assertTrue(Hardware.energy(controller) == beforeUnload,
                "an unloaded drone kept charging: " + Hardware.energy(controller));
        test.assertTrue(towerAt(test, towerPos).energy() == towerBefore,
                "an unloaded drone kept a share of the tower: " + (towerBefore - towerAt(test, towerPos).energy()) + " FE");

        // Reload: same serial, brand new entity instance — the grid must serve it again, and the tower
        // must pay exactly the FE the cell gained over the whole run (no double billing).
        DroneEntity reloaded = drone(test, serial, towerPos);
        test.assertTrue(reloaded != drone && reloaded.getUUID().equals(serial), "reload did not rebuild the projection");
        DroneCharger.service(level, reloaded, controller, 0);
        List<Integer> charges = run(test, 200, reloaded, controller, null);

        test.assertTrue(Hardware.energy(controller) == beforeUnload + 10 * PER_SECOND,
                "reloaded drone charged to " + Hardware.energy(controller) + ", expected "
                        + (beforeUnload + 10 * PER_SECOND) + " charges=" + charges);
        int spent = TOWER_ENERGY - towerAt(test, towerPos).energy();
        test.assertTrue(spent == Hardware.energy(controller),
                "tower spent " + spent + " FE for " + Hardware.energy(controller) + " FE in the cell (double billing)");
        reloaded.discard();
        prepare(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 4: budget taken -> queued

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void towerBudgetTakenByTheMillReportsQueued(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        ServerPlayer owner = GameTestPlayers.join(test);
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        towerAt(test, towerPos).setEnergy(50_000);

        // The ore mill on this tower eats the whole 50,000 FE buffer before the drone settles.
        Probe mill = new Probe(test.absolutePos(towerPos), GridLink.PRIORITY_MILL, 500);
        GridLink.registerLoad(level, mill);
        for (int tick = 0; tick < 200; tick++) {
            GridLink.forceAllocate(level);
            GridLink.withdraw(level, mill, 500);
        }
        GridLink.unregisterLoad(level, mill);
        test.assertTrue(towerAt(test, towerPos).energy() == 0,
                "the mill did not drain the tower: " + towerAt(test, towerPos).energy());

        ItemStack controller = controller(test, owner, 0);
        UUID serial = DroneItem.ensureSerial(controller);
        DroneEntity drone = drone(test, serial, towerPos);
        test.assertTrue(GridLink.covered(level, drone.blockPosition()), "drone is not covered");
        DroneCharger.Result result = DroneCharger.service(level, drone, controller, 0);

        test.assertTrue(result.online(), "an in-coverage drone is reported off grid");
        test.assertTrue(result.queued(), "a starved drone is not reported queued: " + result);
        test.assertTrue(!result.full(), "an empty cell is reported full");
        test.assertTrue(result.charged() == 0 && result.ratePerSecond() == 0, "starved drone charged: " + result);
        test.assertTrue(Hardware.energy(controller) == 0, "starved drone charged " + Hardware.energy(controller));
        drone.discard();
        prepare(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 5: controller swap

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void swappedControllerDoesNotChargeTheAirframe(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        ServerPlayer owner = GameTestPlayers.join(test);
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        towerAt(test, towerPos).setEnergy(TOWER_ENERGY);

        ItemStack first = controller(test, owner, 0);
        UUID firstSerial = DroneItem.ensureSerial(first);
        DroneEntity airframe = drone(test, firstSerial, towerPos);
        DroneCharger.service(level, airframe, first, 0);
        run(test, 100, airframe, first, null);
        test.assertTrue(Hardware.energy(first) == 5 * PER_SECOND, "the fitted controller did not charge");

        // Swap the controller out of the slot: a different serial is now handed to the old airframe.
        ItemStack second = controller(test, owner, 0);
        UUID secondSerial = DroneItem.ensureSerial(second);
        test.assertTrue(!secondSerial.equals(firstSerial), "the swap reused a serial");
        DroneCharger.Result refused = DroneCharger.service(level, airframe, second, 0);
        test.assertTrue(refused.charged() == 0 && refused.ratePerSecond() == 0,
                "a foreign controller charged the airframe: " + refused);
        int towerBeforeGhost = towerAt(test, towerPos).energy();
        run(test, 100, airframe, second, null);
        test.assertTrue(Hardware.energy(second) == 0, "the new controller was charged by the old airframe");
        test.assertTrue(Hardware.energy(first) == 5 * PER_SECOND,
                "the old serial kept charging: " + Hardware.energy(first));
        test.assertTrue(towerAt(test, towerPos).energy() == towerBeforeGhost,
                "the swapped-out airframe kept drawing from the tower: "
                        + (towerBeforeGhost - towerAt(test, towerPos).energy()) + " FE");

        // And the swap is not a blanket refusal: the second serial charges its own airframe.
        DroneEntity rebuilt = drone(test, secondSerial, towerPos);
        DroneCharger.service(level, rebuilt, second, 0);
        run(test, 100, rebuilt, second, null);
        test.assertTrue(Hardware.energy(second) == 5 * PER_SECOND,
                "the swapped-in controller cannot charge its own airframe: " + Hardware.energy(second));
        airframe.discard();
        rebuilt.discard();
        prepare(test);
        test.succeed();
    }

    // ---------------------------------------------------------------- 6: state transitions

    @GameTest(template = "grid_test_room", timeoutTicks = 80)
    public static void stateChangesNeverSkipASecond(GameTestHelper test) {
        prepare(test);
        ServerLevel level = test.getLevel();
        ServerPlayer owner = GameTestPlayers.join(test);
        BlockPos towerPos = new BlockPos(24, 1, 24);
        tower(test, towerPos);
        towerAt(test, towerPos).setEnergy(TOWER_ENERGY);

        ItemStack controller = controller(test, owner, 0);
        UUID serial = DroneItem.ensureSerial(controller);
        DroneEntity drone = drone(test, serial, towerPos);
        DroneCharger.service(level, drone, controller, 0);

        // The pilot re-tasks the drone between settlements: idle -> working over a mill -> docked.
        // None of these states is read by the settlement, so every second must still pay in full.
        List<Integer> charges = new ArrayList<>();
        for (int tick = 0; tick < 60; tick++) {
            if (tick % 20 == 0) {
                switch ((tick / 20) % 3) {
                    case 0 -> { drone.clearWork(); drone.holdingStation = false; }
                    case 1 -> { drone.assignWork(test.absolutePos(towerPos)); drone.working(true); }
                    default -> { drone.clearWork(); drone.holdingStation = true; }
                }
            }
            GridLink.forceAllocate(level);
            if (tick % 20 == 19) charges.add(DroneCharger.service(level, drone, controller, 0).charged());
        }
        test.assertTrue(charges.size() == 3, "expected 3 settlements, got " + charges);
        test.assertTrue(charges.stream().allMatch(charged -> charged == PER_SECOND),
                "a state change cost a second: " + charges);
        drone.clearWork();
        drone.holdingStation = false;
        drone.discard();
        prepare(test);
        test.succeed();
    }
}
