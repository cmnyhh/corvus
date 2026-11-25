package dev.dronepal.power.io;

import dev.corvus.core.PowerIo;
import dev.dronepal.ServerOptions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side acceptance tests for the power <em>interface</em> blocks.
 *
 * <p>The 1.20.1 twin is {@code dev.corvus.devtools.PowerIoSmoke}; both assert the same five scenarios
 * on the same numbers ({@link PowerIo} is compiled into both builds):</p>
 *
 * <ol>
 *   <li>a generic {@link IEnergyStorage} consumer attached to a {@code power_port} really receives FE,
 *       never more than {@code portOutput} in one tick;</li>
 *   <li>the port drains an adjacent battery bank until its 8,000 FE buffer is full, then stops, and the
 *       outlet face is not a supply face;</li>
 *   <li>a 10-block {@code power_cable} run feeds the port from a distant bank, a 40-block run carries
 *       nothing;</li>
 *   <li>two consumers pulling in the same tick are filled in order (800 + 200 of a 1,000 FE/t port) and
 *       a third gets nothing;</li>
 *   <li>both blocks drop themselves and the port's charge / the cable's reserve survive NBT.</li>
 * </ol>
 *
 * <p>The template is a bare 48 x 5 x 12 stone platform ({@code powerio_test_room}) so a 40-cell run
 * fits; real ticks are used everywhere a per-tick window is under test ({@code runAfterDelay}), because
 * a synthetic tick loop would be throttled by the very windows being measured.</p>
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class PowerIoGameTest {
    private PowerIoGameTest() {}

    // ---- helpers ----

    private static CompoundTag nbt(BlockEntity entity, ServerLevel world) {
        return entity.saveWithFullMetadata(world.registryAccess());
    }

    private static void load(BlockEntity entity, CompoundTag tag, ServerLevel world) {
        entity.loadWithComponents(tag, world.registryAccess());
    }

    private static void charge(GameTestHelper test, BlockPos pos, int energy) {
        ServerLevel world = test.getLevel();
        BlockEntity entity = world.getBlockEntity(test.absolutePos(pos));
        CompoundTag tag = nbt(entity, world);
        tag.putInt("Energy", energy);
        load(entity, tag, world);
    }

    private static PowerPortEntity port(GameTestHelper test, BlockPos pos) {
        BlockEntity entity = test.getLevel().getBlockEntity(test.absolutePos(pos));
        if (!(entity instanceof PowerPortEntity port)) throw new IllegalStateException("no port at " + pos);
        return port;
    }

    private static PowerCableEntity cable(GameTestHelper test, BlockPos pos) {
        BlockEntity entity = test.getLevel().getBlockEntity(test.absolutePos(pos));
        if (!(entity instanceof PowerCableEntity cable)) throw new IllegalStateException("no cable at " + pos);
        return cable;
    }

    private static int bankEnergy(GameTestHelper test, BlockPos pos) {
        BlockEntity entity = test.getLevel().getBlockEntity(test.absolutePos(pos));
        if (!(entity instanceof dev.dronepal.power.BatteryBankEntity bank)) {
            throw new IllegalStateException("no bank at " + pos);
        }
        return bank.getEnergyStored();
    }

    private static IEnergyStorage capability(GameTestHelper test, BlockPos pos, Direction side) {
        return test.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, test.absolutePos(pos), side);
    }

    private static void cableLine(GameTestHelper test, BlockPos from, int cells) {
        for (int i = 0; i < cells; i++) {
            test.setBlock(from.east(i), PowerIoRegistry.CABLE.get());
        }
    }

    // ---- ① + ② the outlet and its intake ----

    @GameTest(template = "powerio_test_room", timeoutTicks = 160)
    public static void portFillsFromABankAndPowersAGenericConsumer(GameTestHelper test) {
        BlockPos portPos = new BlockPos(1, 1, 3);
        BlockPos bankPos = portPos.south(); // a north-facing port's outlet is north, so south is a supply face
        test.setBlock(bankPos, dev.dronepal.power.PowerBlocks.BANK.get());
        test.setBlock(portPos, PowerIoRegistry.PORT.get());
        charge(test, bankPos, 200_000);

        List<Integer> pulls = new ArrayList<>();
        TestConsumer consumer = new TestConsumer(100_000);

        // Ten ticks of intake: 1,000 FE/t fills the 8,000 FE buffer exactly.
        test.runAfterDelay(10, () -> {
            test.assertTrue(port(test, portPos).getEnergyStored() == PowerIo.PORT_BUFFER,
                    "buffer after 10 ticks: " + port(test, portPos).getEnergyStored());
            test.assertTrue(bankEnergy(test, bankPos) == 200_000 - PowerIo.PORT_BUFFER,
                    "bank after 10 ticks: " + bankEnergy(test, bankPos));
            test.assertTrue(port(test, portPos).suppliers() == 1,
                    "supply blocks counted: " + port(test, portPos).suppliers());
            int before = bankEnergy(test, bankPos);
            // Full buffer: five more ticks may not move a single FE.
            test.runAfterDelay(5, () -> {
                test.assertTrue(port(test, portPos).getEnergyStored() == PowerIo.PORT_BUFFER
                                && bankEnergy(test, bankPos) == before,
                        "a full port kept drawing: buffer=" + port(test, portPos).getEnergyStored()
                                + " bank=" + bankEnergy(test, bankPos));
                // The consumer pulls 300 FE/t for six ticks through the standard capability.
                for (int i = 0; i < 6; i++) {
                    test.runAfterDelay(6 + i, () -> {
                        IEnergyStorage storage = capability(test, portPos, Direction.EAST);
                        test.assertTrue(storage != null, "the port exposes no energy capability");
                        test.assertTrue(storage.canExtract() && !storage.canReceive(),
                                "a port must be extract-only");
                        pulls.add(consumer.pull(storage, 300));
                    });
                }
                test.runAfterDelay(20, () -> {
                    int total = pulls.stream().mapToInt(Integer::intValue).sum();
                    int rate = PowerIo.portOutput(ServerOptions.portOutput());
                    test.assertTrue(total > 0, "the consumer never received energy: " + pulls);
                    test.assertTrue(pulls.stream().allMatch(amount -> amount <= rate),
                            "a pull exceeded portOutput " + rate + ": " + pulls);
                    test.assertTrue(consumer.stored() == total, "consumer accounting drifted");
                    // The port refills at 1,000 FE/t while the consumer draws 300 FE/t, so the buffer
                    // legitimately stays full; the proof that energy left is that the bank paid for it.
                    test.assertTrue(bankEnergy(test, bankPos) < 200_000 - PowerIo.PORT_BUFFER,
                            "the bank did not pay for the consumer's draw: " + bankEnergy(test, bankPos));
                    test.succeed();
                });
            });
        });
    }

    @GameTest(template = "powerio_test_room", timeoutTicks = 80)
    public static void outletFaceDoesNotDrawFromABankBehindIt(GameTestHelper test) {
        BlockPos portPos = new BlockPos(3, 1, 2);
        BlockPos behind = portPos.north();  // the outlet face of a north-facing port
        BlockPos side = portPos.south();    // a normal supply face
        test.setBlock(portPos, PowerIoRegistry.PORT.get());
        test.setBlock(behind, dev.dronepal.power.PowerBlocks.BANK.get());
        test.setBlock(side, dev.dronepal.power.PowerBlocks.BANK.get());
        charge(test, behind, 5_000);
        charge(test, side, 50_000);
        test.runAfterDelay(4, () -> {
            test.assertTrue(bankEnergy(test, behind) == 5_000,
                    "the outlet face drained a bank behind it: " + bankEnergy(test, behind));
            test.assertTrue(bankEnergy(test, side) < 50_000,
                    "the other faces did not draw at all: " + bankEnergy(test, side));
            test.assertTrue(port(test, portPos).getEnergyStored() > 0,
                    "buffer stayed empty: " + port(test, portPos).getEnergyStored());
            test.succeed();
        });
    }

    // ---- ④ ordered fill ----

    @GameTest(template = "powerio_test_room", timeoutTicks = 60)
    public static void severalConsumersInOneTickAreFilledInOrder(GameTestHelper test) {
        BlockPos portPos = new BlockPos(5, 1, 2);
        test.setBlock(portPos, PowerIoRegistry.PORT.get());
        charge(test, portPos, 1_000);
        TestConsumer first = new TestConsumer(100_000);
        TestConsumer second = new TestConsumer(100_000);
        TestConsumer third = new TestConsumer(100_000);
        test.runAfterDelay(2, () -> {
            IEnergyStorage storage = capability(test, portPos, Direction.WEST);
            test.assertTrue(storage != null, "the port exposes no energy capability");
            int a = first.pull(storage, 800);
            int b = second.pull(storage, 800);
            int c = third.pull(storage, 800);
            int rate = PowerIo.portOutput(ServerOptions.portOutput());
            test.assertTrue(a == 800, "first consumer got " + a);
            test.assertTrue(b == rate - 800, "second consumer got " + b + " of a " + rate + " FE/t port");
            test.assertTrue(c == 0, "third consumer got " + c + " in an exhausted tick");
            test.assertTrue(a + b + c == rate, "total " + (a + b + c) + " vs ceiling " + rate);
            test.succeed();
        });
    }

    // ---- ③ the cable ----

    @GameTest(template = "powerio_test_room", timeoutTicks = 120)
    public static void cableFeedsThePortFromADistantBank(GameTestHelper test) {
        BlockPos bankPos = new BlockPos(1, 1, 6);
        BlockPos cableStart = new BlockPos(2, 1, 6);
        BlockPos portPos = new BlockPos(12, 1, 6); // west of the port, i.e. not the outlet face
        test.setBlock(bankPos, dev.dronepal.power.PowerBlocks.BANK.get());
        cableLine(test, cableStart, 10);
        test.setBlock(portPos, PowerIoRegistry.PORT.get());
        charge(test, bankPos, 200_000);
        test.runAfterDelay(8, () -> {
            test.assertTrue(port(test, portPos).getEnergyStored() > 0,
                    "the run did not reach the port: " + port(test, portPos).getEnergyStored());
            test.assertTrue(bankEnergy(test, bankPos) < 200_000,
                    "the bank never lost energy: " + bankEnergy(test, bankPos));
            test.assertTrue(cable(test, portPos.west()).runLength(test.getLevel()) == 10,
                    "run length reported as " + cable(test, portPos.west()).runLength(test.getLevel()));
            test.assertTrue(cable(test, portPos.west()).downstreamPorts(test.getLevel()) == 1,
                    "downstream ports reported as " + cable(test, portPos.west()).downstreamPorts(test.getLevel()));
            IEnergyStorage storage = capability(test, portPos, Direction.EAST);
            test.assertTrue(storage != null, "the port exposes no energy capability");
            TestConsumer consumer = new TestConsumer(100_000);
            int got = consumer.pull(storage, 400);
            test.assertTrue(got > 0, "the port behind the cable delivered nothing: " + got);
            test.succeed();
        });
    }

    @GameTest(template = "powerio_test_room", timeoutTicks = 120)
    public static void cableRunLongerThan32BlocksCarriesNothing(GameTestHelper test) {
        BlockPos bankPos = new BlockPos(1, 1, 9);
        BlockPos cableStart = new BlockPos(2, 1, 9);
        BlockPos portPos = new BlockPos(42, 1, 9);
        test.setBlock(bankPos, dev.dronepal.power.PowerBlocks.BANK.get());
        cableLine(test, cableStart, 40);
        test.setBlock(portPos, PowerIoRegistry.PORT.get());
        charge(test, bankPos, 200_000);
        test.runAfterDelay(12, () -> {
            test.assertTrue(port(test, portPos).getEnergyStored() == 0,
                    "an over-long run carried energy: " + port(test, portPos).getEnergyStored());
            test.assertTrue(bankEnergy(test, bankPos) == 200_000,
                    "an over-long run drained the bank: " + bankEnergy(test, bankPos));
            test.assertTrue(cable(test, portPos.west()).tooLong(test.getLevel()),
                    "the run does not report itself as too long");
            IEnergyStorage storage = capability(test, portPos, Direction.EAST);
            test.assertTrue(storage != null, "the port exposes no energy capability");
            test.assertTrue(new TestConsumer(1_000).pull(storage, 400) == 0,
                    "the port handed out energy it never received");
            test.succeed();
        });
    }

    // ---- ⑤ drops and NBT ----

    @GameTest(template = "powerio_test_room", timeoutTicks = 60)
    public static void bothBlocksDropThemselvesAndRoundTripTheirNbt(GameTestHelper test) {
        BlockPos portPos = new BlockPos(20, 1, 2);
        BlockPos cablePos = new BlockPos(21, 1, 2);
        test.setBlock(portPos, PowerIoRegistry.PORT.get());
        test.setBlock(cablePos, PowerIoRegistry.CABLE.get());
        charge(test, portPos, 1_234);
        ServerLevel world = test.getLevel();

        PowerPortEntity live = port(test, portPos);
        CompoundTag portTag = nbt(live, world);
        BlockEntity copy = PowerIoRegistry.PORT_TYPE.get().create(test.absolutePos(portPos), live.getBlockState());
        copy.loadWithComponents(portTag, world.registryAccess());
        test.assertTrue(((PowerPortEntity) copy).getEnergyStored() == 1_234,
                "port charge did not survive NBT: " + ((PowerPortEntity) copy).getEnergyStored());

        // A cable is a wire, not a battery: it must refuse storage and report 0 after a round trip.
        PowerCableEntity liveCable = cable(test, cablePos);
        test.assertTrue(liveCable.receiveEnergy(500, false) == 0 && liveCable.getEnergyStored() == 0,
                "a cable accepted energy: stored=" + liveCable.getEnergyStored());
        CompoundTag cableTag = nbt(liveCable, world);
        test.assertTrue(!cableTag.contains("Energy"), "a cable wrote a charge to NBT: " + cableTag);
        BlockEntity cableCopy = PowerIoRegistry.CABLE_TYPE.get().create(test.absolutePos(cablePos),
                liveCable.getBlockState());
        cableCopy.loadWithComponents(cableTag, world.registryAccess());
        test.assertTrue(((PowerCableEntity) cableCopy).getEnergyStored() == 0
                        && ((PowerCableEntity) cableCopy).canExtract()
                        && !((PowerCableEntity) cableCopy).canReceive(),
                "a cable loaded from NBT is not a plain conduit");

        test.assertTrue(PowerIoRegistry.PORT.get().getStateDefinition().getPossibleStates().size() == 4,
                "the port must have exactly four facings");
        test.assertTrue(PowerIoRegistry.CABLE.get().getStateDefinition().getPossibleStates().size() == 64,
                "the cable must have 64 connection masks");

        world.destroyBlock(test.absolutePos(portPos), true);
        world.destroyBlock(test.absolutePos(cablePos), true);
        test.assertItemEntityPresent(PowerIoRegistry.PORT_ITEM.get(), portPos, 2.0);
        test.assertItemEntityPresent(PowerIoRegistry.CABLE_ITEM.get(), cablePos, 2.0);
        test.succeed();
    }

    @GameTest(template = "powerio_test_room", timeoutTicks = 40)
    public static void registeredUnderTheAdvertisedIds(GameTestHelper test) {
        test.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(PowerIoRegistry.PORT.get())
                        .getPath().equals("power_port"),
                "block id drifted: " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(PowerIoRegistry.PORT.get()));
        test.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(PowerIoRegistry.CABLE.get())
                        .getPath().equals("power_cable"),
                "block id drifted: " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(PowerIoRegistry.CABLE.get()));
        test.assertTrue(PowerPortEntity.BUFFER == 8_000 && PowerIo.CABLE_RATE == 2_000
                        && PowerIo.CABLE_LENGTH == 32,
                "shared core numbers drifted");
        test.succeed();
    }

    /**
     * A generic energy consumer: the standard NeoForge interface, nothing from this mod. It pulls
     * through whatever storage it is handed — exactly what another mod's cable does to our port.
     */
    private static final class TestConsumer implements IEnergyStorage {
        private final int capacity;
        private int stored;

        TestConsumer(int capacity) { this.capacity = capacity; }

        int stored() { return stored; }

        int pull(IEnergyStorage source, int want) {
            int got = source.extractEnergy(want, false);
            int accepted = receiveEnergy(got, false);
            if (accepted != got) throw new IllegalStateException("consumer lost " + (got - accepted) + " FE");
            return accepted;
        }

        @Override public int receiveEnergy(int amount, boolean simulate) {
            int accepted = Math.min(amount, capacity - stored);
            if (!simulate) stored += accepted;
            return accepted;
        }

        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return stored; }
        @Override public int getMaxEnergyStored() { return capacity; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    }
}
