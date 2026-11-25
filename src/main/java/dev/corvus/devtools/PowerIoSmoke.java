package dev.corvus.devtools;

import dev.corvus.Corvus;
import dev.corvus.ServerOptions;
import dev.corvus.SurvivalWorks;
import dev.corvus.core.PowerIo;
import dev.corvus.power.BatteryBankEntity;
import dev.corvus.power.PowerRegistry;
import dev.corvus.power.io.PowerCableEntity;
import dev.corvus.power.io.PowerIoRegistry;
import dev.corvus.power.io.PowerPortBlock;
import dev.corvus.power.io.PowerPortEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance run for the power <em>interface</em>: {@code power_port} and
 * {@code power_cable}.
 *
 * <p>Inactive unless {@code -Dcorvus.powerio=1} or {@code CORVUS_POWERIO=1}. Like the power run it is
 * multi-tick, because every number under test is a per-tick world fact: the port draws at most
 * {@code portOutput} FE/t, a second consumer in the same tick sees only the remainder, and a cable run
 * reaches exactly 32 blocks. So the harness places real blocks, lets the server tick them and reads the
 * block entities back — the only synthetic part is the consumer, a plain
 * {@link net.minecraftforge.energy.IEnergyStorage} that pulls through the standard capability exactly
 * like another mod's cable would.</p>
 *
 * <p>What it asserts (the same five scenarios as the 1.21.1 GameTest):</p>
 * <ol>
 *   <li><b>outlet</b> — a generic IEnergyStorage consumer attached to the port really receives FE, and
 *       never more than {@code portOutput} in one tick;</li>
 *   <li><b>intake</b> — the port drains an adjacent battery bank, its 8,000 FE buffer fills, and once
 *       full the bank stops losing energy;</li>
 *   <li><b>cable</b> — a distant bank (10 blocks) reaches the port through the run and the port still
 *       outputs; a 40-block run carries nothing;</li>
 *   <li><b>order</b> — two consumers in the same tick are filled in order: 800 + 200 of a 1,000 FE/t
 *       port, a third gets nothing;</li>
 *   <li><b>persistence</b> — blocks drop themselves, the port's charge and the cable's reserve survive
 *       a save/load round trip, loot tables and the pickaxe tag exist.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class PowerIoSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.powerio"))
            || "1".equals(System.getenv("CORVUS_POWERIO"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-powerio-smoke");
    private static final List<String> FAILURES = new ArrayList<>();

    private static int passed;
    private static int ticks;
    private static boolean ran;
    private static int step;
    private static int wait;

    private static MinecraftServer server;
    private static ServerLevel level;
    private static BlockPos origin;

    private static BlockPos portPos, bankPos, fillPortPos, orderPortPos;
    private static BlockPos cableBankPos, cablePortPos, longBankPos, longPortPos;
    private static BlockPos dropPortPos, dropCablePos, savePortPos, saveCablePos;

    private static final TestConsumer first = new TestConsumer("first", 100_000);
    private static final TestConsumer second = new TestConsumer("second", 100_000);
    private static final TestConsumer third = new TestConsumer("third", 100_000);
    private static final TestConsumer cableConsumer = new TestConsumer("cable", 100_000);
    private static final TestConsumer longConsumer = new TestConsumer("long", 100_000);
    /** Per-pull log of the outlet test: FE each pull returned, in order. */
    private static final List<Integer> pulls = new ArrayList<>();
    private static int orderTotal;
    private static int orderFirst, orderSecond, orderThird;

    private PowerIoSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer current = event.getServer();
        if (current.isStopped() || ran) return;
        if (++ticks < 20) return;
        if (wait > 0) {
            if (--wait > 0) return;
        }
        try {
            advance(current);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[powerio-smoke] harness crashed", error);
            finish(current);
        }
    }

    private static void advance(MinecraftServer current) {
        final int doing = step;
        step = doing + 1;
        switch (doing) {
            case 0 -> setup(current);
            case 1 -> intakeCheck();
            case 2 -> intakeStopCheck();
            case 3 -> outletFaceSetup();
            case 4 -> outletFaceCheck();
            case 5 -> pullOnce();
            case 6 -> pullOnce();
            case 7 -> pullOnce();
            case 8 -> outletPullCheck();
            case 9 -> orderCheck();
            case 10 -> cableCheck();
            case 11 -> longCheck();
            case 12 -> persistence();
            case 13 -> drops();
            case 14 -> registryFacts();
            default -> finish(current);
        }
    }

    // ---- 0) setup ----

    private static void setup(MinecraftServer current) {
        server = current;
        level = current.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 40;
        int z = spawn.getZ() + 40;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        origin = new BlockPos(x, surface + 8, z);
        LOG.info("[powerio-smoke] site {} (surface {}), time={}", origin, surface, level.getDayTime() % 24000L);

        // ② intake + ① outlet: bank south of the port, port facing north (default), so the bank is on a
        // supply face and the outlet face stays free.
        portPos = at(0, 0);
        bankPos = at(0, 1);
        // ④ order: a second port with no supply, pre-charged through NBT.
        orderPortPos = at(4, 0);
        // ③ cable: bank - 10 cables - port, and the over-long control run.
        cableBankPos = at(0, 20);
        cablePortPos = at(11, 20);
        longBankPos = at(0, 30);
        longPortPos = at(41, 30);
        // ⑤ drops / NBT
        dropPortPos = at(0, 4);
        dropCablePos = at(1, 4);
        savePortPos = at(3, 4);
        saveCablePos = at(4, 4);

        place(portPos, PowerIoRegistry.PORT.get());
        place(bankPos, PowerRegistry.BANK.get());
        chargeBank(bankPos, 200_000);
        place(orderPortPos, PowerIoRegistry.PORT.get());
        chargePort(orderPortPos, 1_000);

        place(cableBankPos, PowerRegistry.BANK.get());
        chargeBank(cableBankPos, 200_000);
        for (int i = 1; i <= 10; i++) place(at(i, 20), PowerIoRegistry.CABLE.get());
        place(cablePortPos, PowerIoRegistry.PORT.get());
        for (int i = 1; i <= 10; i++) refreshMask(at(i, 20));

        place(longBankPos, PowerRegistry.BANK.get());
        chargeBank(longBankPos, 200_000);
        for (int i = 1; i <= 40; i++) place(at(i, 30), PowerIoRegistry.CABLE.get());
        place(longPortPos, PowerIoRegistry.PORT.get());
        for (int i = 1; i <= 40; i++) refreshMask(at(i, 30));

        place(dropPortPos, PowerIoRegistry.PORT.get());
        place(dropCablePos, PowerIoRegistry.CABLE.get());
        place(savePortPos, PowerIoRegistry.PORT.get());
        place(saveCablePos, PowerIoRegistry.CABLE.get());

        for (int cx = (x >> 4) - 2; cx <= ((x + 40) >> 4) + 2; cx++) {
            for (int cz = (z >> 4) - 2; cz <= ((z + 40) >> 4) + 2; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }

        check("config.port_output_default", ServerOptions.PORT_OUTPUT.getDefault() == 1_000,
                String.valueOf(ServerOptions.PORT_OUTPUT.getDefault()));
        int configured = ServerOptions.portOutput();
        ServerOptions.PORT_OUTPUT.set(5_000);
        int high = ServerOptions.portOutput();
        ServerOptions.PORT_OUTPUT.set(50);
        int low = ServerOptions.portOutput();
        ServerOptions.PORT_OUTPUT.set(1_000);
        check("config.port_output_clamp", configured == 1_000 && high == 4_000 && low == 100,
                "default=" + configured + " high=" + high + " low=" + low);
        check("core.port_buffer", PowerPortEntity.BUFFER == 8_000, String.valueOf(PowerPortEntity.BUFFER));
        check("core.cable_rate", PowerIo.CABLE_RATE == 2_000, String.valueOf(PowerIo.CABLE_RATE));
        check("core.cable_length", PowerIo.CABLE_LENGTH == 32, String.valueOf(PowerIo.CABLE_LENGTH));
        wait = 10; // 10 ticks of 1,000 FE/t fill the 8,000 FE buffer exactly.
    }

    // ---- ② intake: fills from the bank, then stops ----

    private static void intakeCheck() {
        PowerPortEntity port = port(portPos);
        BatteryBankEntity bank = bank(bankPos);
        LOG.info("[powerio-smoke] port after 10t: buffer={} bank={} suppliers={}",
                port.storedEnergy(), bank.storedEnergy(), port.suppliers());
        check("port.fills_from_bank", port.storedEnergy() == 8_000, "buffer=" + port.storedEnergy());
        check("port.bank_lost_8000", bank.storedEnergy() == 200_000 - 8_000, "bank=" + bank.storedEnergy());
        check("port.counts_suppliers", port.suppliers() == 1, "suppliers=" + port.suppliers());
        check("port.can_extract_only", port.getCapability(ForgeCapabilities.ENERGY, Direction.EAST)
                .map(storage -> storage.canExtract() && !storage.canReceive()).orElse(false), "capability flags");
        wait = 5;
    }

    private static void intakeStopCheck() {
        // Five more ticks of a full buffer: nothing may move, in either direction.
        check("port.full_stops_intake", port(portPos).storedEnergy() == 8_000
                        && bank(bankPos).storedEnergy() == 200_000 - 8_000,
                "buffer=" + port(portPos).storedEnergy() + " bank=" + bank(bankPos).storedEnergy());
    }

    // ---- ① outlet: a generic IEnergyStorage consumer pulls ----

    private static void outletFaceSetup() {
        // The outlet face is the front: a bank behind it is not a supply face and must not be drained.
        // Empty the port first, otherwise "it did not draw" could just mean "it was already full".
        chargePort(portPos, 0);
        place(at(1, 0), PowerRegistry.BANK.get()); // directly north of the port = behind the outlet
        chargeBank(at(1, 0), 5_000);
        wait = 3;
    }

    private static void outletFaceCheck() {
        check("port.outlet_face_does_not_draw", bank(at(1, 0)).storedEnergy() == 5_000,
                "north bank=" + bank(at(1, 0)).storedEnergy());
        check("port.other_faces_still_draw", port(portPos).storedEnergy() > 0,
                "buffer=" + port(portPos).storedEnergy());
        place(at(1, 0), Blocks.AIR);
        wait = 1;
    }

    private static void pullOnce() {
        IEnergyStorage storage = portCapability(portPos, Direction.EAST);
        if (storage == null) {
            fail("port.capability", "no energy capability on the port");
            return;
        }
        int got = first.pull(storage, 300);
        pulls.add(got);
        LOG.info("[powerio-smoke] pull #{} -> {} FE (consumer={})", pulls.size(), got, first.stored());
        wait = 1;
    }

    private static void outletPullCheck() {
        int total = 0;
        for (int amount : pulls) total += amount;
        check("port.outlet_moves_energy", total > 0, "total=" + total);
        check("port.outlet_respects_rate", pulls.stream().allMatch(amount -> amount <= ServerOptions.portOutput()),
                "pulls=" + pulls + " cap=" + ServerOptions.portOutput());
        check("port.outlet_three_pulls", first.stored() == total && total == 900, "stored=" + first.stored());
        check("port.outlet_paid_by_bank", bank(bankPos).storedEnergy() < 200_000 - 8_000,
                "bank=" + bank(bankPos).storedEnergy());
    }

    // ---- ④ two consumers in one tick: fill in order, never over the ceiling ----

    private static void orderCheck() {
        // Pre-charge the port so the outcome does not depend on how the tick interleaves with the bank.
        chargePort(orderPortPos, 1_000);
        IEnergyStorage storage = portCapability(orderPortPos, Direction.WEST);
        if (storage == null) {
            fail("order.capability", "no energy capability on the port");
            return;
        }
        orderFirst = first.pull(storage, 800);
        orderSecond = second.pull(storage, 800);
        orderThird = third.pull(storage, 800);
        orderTotal = orderFirst + orderSecond + orderThird;
        LOG.info("[powerio-smoke] same-tick pulls: {}/{}/{} (total {}) against cap {}",
                orderFirst, orderSecond, orderThird, orderTotal, ServerOptions.portOutput());
        check("order.first_filled", orderFirst == 800, "first=" + orderFirst);
        check("order.second_gets_remainder", orderSecond == 200, "second=" + orderSecond);
        check("order.third_gets_nothing", orderThird == 0, "third=" + orderThird);
        check("order.total_is_the_ceiling", orderTotal == ServerOptions.portOutput(), "total=" + orderTotal);
    }

    // ---- ③ cable: a distant bank still feeds the port ----

    private static void cableSetup() {
        wait = 5;
    }

    private static void cableCheck() {
        // The run has had five ticks since setup; a port fills 1,000 FE/t, so anything above 0 proves
        // the energy came through the cable rather than from a neighbour.
        PowerPortEntity port = port(cablePortPos);
        BatteryBankEntity bank = bank(cableBankPos);
        LOG.info("[powerio-smoke] cable run: port buffer={} bank={} length={} downstream={}",
                port.storedEnergy(), bank.storedEnergy(), cableLength(cablePortPos), cablePorts(cablePortPos));
        check("cable.run_reaches_port", port.storedEnergy() > 0, "buffer=" + port.storedEnergy());
        check("cable.bank_drained", bank.storedEnergy() < 200_000, "bank=" + bank.storedEnergy());
        check("cable.reports_length", cableLength(cablePortPos) == 10, "length=" + cableLength(cablePortPos));
        check("cable.reports_downstream", cablePorts(cablePortPos) == 1, "ports=" + cablePorts(cablePortPos));
        IEnergyStorage storage = portCapability(cablePortPos, Direction.EAST);
        int got = storage == null ? 0 : cableConsumer.pull(storage, 400);
        LOG.info("[powerio-smoke] cable outlet pull -> {} FE", got);
        check("cable.port_still_outputs", got > 0, "pull=" + got);
        wait = 8;
    }

    private static void longCheck() {
        PowerPortEntity port = port(longPortPos);
        BatteryBankEntity bank = bank(longBankPos);
        LOG.info("[powerio-smoke] over-long run: port buffer={} bank={} length={}",
                port.storedEnergy(), bank.storedEnergy(), cableLength(longPortPos));
        check("cable.overlong_carries_nothing", port.storedEnergy() == 0, "buffer=" + port.storedEnergy());
        check("cable.overlong_bank_untouched", bank.storedEnergy() == 200_000, "bank=" + bank.storedEnergy());
        IEnergyStorage storage = portCapability(longPortPos, Direction.EAST);
        int got = storage == null ? 0 : longConsumer.pull(storage, 400);
        check("cable.overlong_pull_zero", got == 0, "pull=" + got);
    }

    // ---- ⑤ persistence and drops ----

    private static void persistence() {
        chargePort(savePortPos, 1_234);
        PowerPortEntity port = port(savePortPos);
        CompoundTag portTag = port.saveWithFullMetadata();
        PowerPortEntity portCopy = PowerIoRegistry.PORT_TYPE.get().create(port.getBlockPos(), port.getBlockState());
        portCopy.load(portTag);
        check("persist.port_energy", portCopy.storedEnergy() == 1_234, "energy=" + portCopy.storedEnergy());

        // A cable is a wire, not a battery: it must refuse storage and stay empty after a round trip.
        PowerCableEntity cable = cable(saveCablePos);
        int accepted = cable.receiveEnergy(120, false);
        CompoundTag cableTag = cable.saveWithFullMetadata();
        PowerCableEntity cableCopy = PowerIoRegistry.CABLE_TYPE.get().create(cable.getBlockPos(), cable.getBlockState());
        cableCopy.load(cableTag);
        check("persist.cable_stores_nothing", accepted == 0 && cable.getEnergyStored() == 0
                        && cableCopy.getEnergyStored() == 0,
                "accepted=" + accepted + " live=" + cable.getEnergyStored()
                        + " loaded=" + cableCopy.getEnergyStored());
        check("persist.cable_no_nbt_energy", !cableTag.contains("Energy"), "tag=" + cableTag);
        check("persist.cable_is_a_conduit", cableCopy.canExtract() && !cableCopy.canReceive(),
                "flags");

        BlockState portState = level.getBlockState(portPos);
        check("state.port_facing", portState.getValue(PowerPortBlock.FACING) == Direction.NORTH,
                String.valueOf(portState.getValue(PowerPortBlock.FACING)));
        check("state.port_four_facings",
                PowerIoRegistry.PORT.get().getStateDefinition().getPossibleStates().size() == 4,
                String.valueOf(PowerIoRegistry.PORT.get().getStateDefinition().getPossibleStates().size()));
        var middle = level.getBlockState(at(5, 20));
        check("state.cable_connectivity", middle.getValue(dev.corvus.power.io.PowerCableBlock.EAST)
                        && middle.getValue(dev.corvus.power.io.PowerCableBlock.WEST)
                        && !middle.getValue(dev.corvus.power.io.PowerCableBlock.NORTH)
                        && !middle.getValue(dev.corvus.power.io.PowerCableBlock.UP),
                "middle cable mask east=" + middle.getValue(dev.corvus.power.io.PowerCableBlock.EAST)
                        + " west=" + middle.getValue(dev.corvus.power.io.PowerCableBlock.WEST)
                        + " north=" + middle.getValue(dev.corvus.power.io.PowerCableBlock.NORTH));
        check("state.cable_sixty_four",
                PowerIoRegistry.CABLE.get().getStateDefinition().getPossibleStates().size() == 64,
                String.valueOf(PowerIoRegistry.CABLE.get().getStateDefinition().getPossibleStates().size()));
    }

    private static void drops() {
        List<ItemStack> portDrops = breakAndCollect(dropPortPos);
        List<ItemStack> cableDrops = breakAndCollect(dropCablePos);
        LOG.info("[powerio-smoke] drops: port={} cable={}", describe(portDrops), describe(cableDrops));
        check("drop.port_self", hasItem(portDrops, "power_port", 1), describe(portDrops));
        check("drop.cable_self", hasItem(cableDrops, "power_cable", 1), describe(cableDrops));
        check("drop.port_carries_no_charge", portDrops.size() == 1, describe(portDrops));
    }

    private static void registryFacts() {
        for (String path : List.of("power_port", "power_cable")) {
            LootTable table = server.getLootData().getLootTable(new ResourceLocation(Corvus.ID, "blocks/" + path));
            check("loot." + path, table != LootTable.EMPTY, "loot table");
        }
        check("tag.pickaxe",
                PowerIoRegistry.PORT.get().builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE)
                        && PowerIoRegistry.CABLE.get().builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE),
                "mineable/pickaxe");
        check("tag.iron_tool",
                PowerIoRegistry.PORT.get().builtInRegistryHolder().is(BlockTags.NEEDS_IRON_TOOL)
                        && PowerIoRegistry.CABLE.get().builtInRegistryHolder().is(BlockTags.NEEDS_IRON_TOOL),
                "needs_iron_tool");
        check("block_items.registered",
                ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "power_port"))
                        && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "power_cable")),
                "items");

        Map<String, Integer> costs = new LinkedHashMap<>();
        int produced = 0;
        for (SurvivalWorks.Catalog.Entry entry : SurvivalWorks.catalog().entries()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(entry.icon().getItem());
            if (id == null || !Corvus.ID.equals(id.getNamespace()) || !id.getPath().equals("power_cable")) continue;
            produced = entry.icon().getCount();
            for (SurvivalWorks.Catalog.Ingredient ingredient : entry.ingredients()) {
                costs.put(ingredient.item(), ingredient.count());
            }
        }
        check("blueprint.power_cable", costs.equals(Map.of("minecraft:copper_ingot", 6, "minecraft:iron_ingot", 2)),
                String.valueOf(costs));
        check("blueprint.power_cable_yield", produced == 8, "count=" + produced);
    }

    // ---- plumbing ----

    private static BlockPos at(int dx, int dz) {
        return origin.offset(dx, 0, dz);
    }

    private static void place(BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** A cable placed with setBlock has no connection mask; the player placement path supplies it. */
    private static void refreshMask(BlockPos pos) {
        level.setBlock(pos, PowerIoRegistry.CABLE.get().connectionState(level, pos), Block.UPDATE_ALL);
    }

    private static void chargeBank(BlockPos pos, int energy) {
        BatteryBankEntity bank = bank(pos);
        CompoundTag tag = bank.saveWithFullMetadata();
        tag.putInt("Energy", energy);
        bank.load(tag);
    }

    private static void chargePort(BlockPos pos, int energy) {
        PowerPortEntity port = port(pos);
        CompoundTag tag = port.saveWithFullMetadata();
        tag.putInt("Energy", energy);
        port.load(tag);
    }

    private static PowerPortEntity port(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof PowerPortEntity port)) throw new IllegalStateException("no port at " + pos);
        return port;
    }

    private static PowerCableEntity cable(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof PowerCableEntity cable)) throw new IllegalStateException("no cable at " + pos);
        return cable;
    }

    private static BatteryBankEntity bank(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BatteryBankEntity bank)) throw new IllegalStateException("no bank at " + pos);
        return bank;
    }

    /** The port's energy capability as an outside block sees it, or null. */
    private static IEnergyStorage portCapability(BlockPos pos, Direction side) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return null;
        return entity.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
    }

    private static int cableLength(BlockPos pos) {
        return cable(pos).runLength(level);
    }

    private static int cablePorts(BlockPos pos) {
        return cable(pos).downstreamPorts(level);
    }

    /** Breaks a block and returns what actually hit the ground. */
    private static List<ItemStack> breakAndCollect(BlockPos pos) {
        level.destroyBlock(pos, true);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0D))) {
            drops.add(entity.getItem().copy());
            entity.discard();
        }
        return drops;
    }

    private static boolean hasItem(List<ItemStack> stacks, String path, int count) {
        for (ItemStack stack : stacks) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && Corvus.ID.equals(id.getNamespace()) && id.getPath().equals(path)
                    && stack.getCount() == count) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            parts.add(ForgeRegistries.ITEMS.getKey(stack.getItem()) + " x" + stack.getCount());
        }
        return String.join(", ", parts);
    }

    private static void finish(MinecraftServer current) {
        LOG.info("[powerio-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[powerio-smoke] FAILED {}", failure);
        ran = true;
        current.halt(false);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[powerio-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        String line = name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
        FAILURES.add(line);
        LOG.error("[powerio-smoke] FAIL {}", line);
    }

    /**
     * A generic energy consumer: the standard Forge interface, nothing from this mod. It pulls through
     * whatever storage it is handed, which is exactly what another mod's cable does to our port.
     */
    private static final class TestConsumer implements IEnergyStorage {
        private final String name;
        private final int capacity;
        private int stored;

        TestConsumer(String name, int capacity) {
            this.name = name;
            this.capacity = capacity;
        }

        int stored() { return stored; }

        /** Pulls {@code want} FE out of {@code source} and keeps what it got. */
        int pull(IEnergyStorage source, int want) {
            int got = source.extractEnergy(want, false);
            int accepted = receiveEnergy(got, false);
            if (accepted != got) {
                LOG.error("[powerio-smoke] {} lost {} FE between port and buffer", name, got - accepted);
            }
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
