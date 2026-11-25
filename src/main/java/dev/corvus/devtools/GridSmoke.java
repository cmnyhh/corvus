package dev.corvus.devtools;

import dev.corvus.Corvus;
import dev.corvus.ServerOptions;
import dev.corvus.core.Grid;
import dev.corvus.power.BatteryBankEntity;
import dev.corvus.power.PowerRegistry;
import dev.corvus.power.grid.GridLink;
import dev.corvus.power.grid.GridLoad;
import dev.corvus.power.grid.PowerTowerEntity;
import dev.corvus.power.grid.TowerRegistry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance run for the wireless power grid (1.20.1).
 *
 * <p>Same seven assertions as the 1.21.1 {@code PowerGridGameTest}: one network at 20 blocks, 0.95
 * per hop (0.86 at three hops), six hops unreachable, the 500 FE/t shared budget by priority, active
 * pull from a battery bank, one drop when any segment is broken, and charge across a save round trip.
 * Real blocks in the real world, ticked by the server; the "devices" are {@link GridLoad}s, the same
 * contract the ore mill, charging dock and deployed drone use.</p>
 *
 * <p>Inactive unless {@code -Dcorvus.grid=1} or {@code CORVUS_GRIDTEST=1}.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class GridSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.grid"))
            || "1".equals(System.getenv("CORVUS_GRIDTEST"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-grid-smoke");
    private static final List<String> FAILURES = new ArrayList<>();

    private static int passed;
    private static int ticks;
    private static boolean ran;
    private static int step;

    private static ServerLevel level;
    private static BlockPos origin;

    private static BlockPos nearPos, farPos;
    private static BlockPos oneA, oneB, chainBase;
    private static BlockPos sixBase;
    private static BlockPos priorityBase, bankPos, pullTowerPos, breakBase, savePos;
    private static Probe near, oneHop, threeHop, sixHop, drone, mill, dock;

    private GridSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer current = event.getServer();
        if (current.isStopped() || ran) return;
        if (++ticks < 20) return;
        try {
            advance(current);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[grid-smoke] harness crashed", error);
            finish(current);
        }
    }

    private static void advance(MinecraftServer current) {
        int doing = step++;
        switch (doing) {
            case 0 -> setup(current);
            case 1 -> networkAndHops();
            case 2 -> sharedBudget();
            case 3 -> bankPull();
            case 4 -> breakCascade();
            case 5 -> saveRoundTrip();
            default -> finish(current);
        }
    }

    private static void setup(MinecraftServer current) {
        level = current.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 24;
        int z = spawn.getZ() + 24;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        origin = new BlockPos(x, surface + 12, z);
        for (int cx = (x >> 4) - 2; cx <= ((x + 160) >> 4) + 2; cx++) {
            for (int cz = (z >> 4) - 2; cz <= ((z + 160) >> 4) + 2; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        LOG.info("[grid-smoke] site {} surface={} radius={} throughput={} efficiency={}",
                origin, surface, ServerOptions.towerRadius(), ServerOptions.towerThroughput(),
                ServerOptions.towerEfficiency());

        check("grid.config_radius", ServerOptions.towerRadius() == 32, "radius=" + ServerOptions.towerRadius());
        check("grid.config_throughput", ServerOptions.towerThroughput() == 500, "throughput=" + ServerOptions.towerThroughput());
        check("grid.config_efficiency", Math.abs(ServerOptions.towerEfficiency() - 0.95D) < 1e-9,
                "efficiency=" + ServerOptions.towerEfficiency());
        check("grid.config_ranges", Grid.clampRadius(0) == 8 && Grid.clampRadius(999) == 64
                        && Grid.clampThroughput(0) == 100 && Grid.clampThroughput(99999) == 2000
                        && Math.abs(Grid.clampEfficiency(2.0D) - 1.0D) < 1e-9,
                "clamps");

        // 1) two towers 20 blocks apart, energy only in the far one.
        nearPos = at(0, 0);
        farPos = at(20, 0);
        tower(nearPos);
        tower(farPos);
        towerEntity(farPos).setEnergy(600_000);
        near = register(new Probe(offset(0, 0, 2), GridLink.PRIORITY_MILL, 200));

        // 2) one hop (20 blocks) and three hops (17-block vertical edges, no shortcut).
        oneA = at(0, 40);
        oneB = at(20, 40);
        tower(oneA);
        tower(oneB);
        towerEntity(oneB).setEnergy(600_000);
        oneHop = register(new Probe(offset(0, 0, 42), GridLink.PRIORITY_DOCK, 400));
        chainBase = at(40, 80);
        for (int i = 0; i < 4; i++) tower(chainBase.offset(0, i * 17, 0));
        towerEntity(chainBase.offset(0, 51, 0)).setEnergy(600_000);
        threeHop = register(new Probe(chainBase.offset(0, 0, 2), GridLink.PRIORITY_DOCK, 400));

        // 3) six hops, seven towers in one column.
        sixBase = at(80, 0);
        for (int i = 0; i < 7; i++) tower(sixBase.offset(0, i * 17, 0));
        towerEntity(sixBase.offset(0, 102, 0)).setEnergy(1_000_000);
        sixHop = register(new Probe(sixBase.offset(0, 0, 2), GridLink.PRIORITY_MILL, 400));

        // 4) one tower, three devices, 500 FE/t shared by priority.
        priorityBase = at(0, 80);
        tower(priorityBase);
        towerEntity(priorityBase).setEnergy(1_000_000);
        drone = register(new Probe(priorityBase.offset(0, 0, -2), GridLink.PRIORITY_DRONE, 200));
        mill = register(new Probe(priorityBase.offset(2, 0, 0), GridLink.PRIORITY_MILL, 200));
        dock = register(new Probe(priorityBase.offset(0, 0, 2), GridLink.PRIORITY_DOCK, 200));

        // 5-7) a battery bank pair, a tower to break and a tower to save.
        bankPos = at(40, 120);
        pullTowerPos = bankPos.offset(1, 0, 0);
        breakBase = at(80, 120);
        savePos = at(120, 120);
    }

    // ---- 1) network across towers + hop efficiency + six-hop reach ----

    private static void networkAndHops() {
        int network = GridLink.info(level, nearPos).towers();
        int across = GridLink.withdraw(level, near, 200);
        int one = GridLink.withdraw(level, oneHop, 400);
        int three = GridLink.withdraw(level, threeHop, 400);
        int six = GridLink.withdraw(level, sixHop, 400);
        LOG.info("[grid-smoke] network={} across={} oneHop={} threeHops={} sixHops={}", network, across, one, three, six);
        check("grid.two_towers_one_network", network == 2 && across == 200,
                "towers=" + network + " across=" + across);
        check("grid.hop_one_095", Math.abs(one / 400.0D - 0.95D) <= 0.01D, "ratio=" + (one / 400.0D));
        check("grid.hop_three_086", Math.abs(three / 400.0D - 0.86D) <= 0.01D, "ratio=" + (three / 400.0D));
        check("grid.six_hops_unreachable", six == 0 && !Grid.reachable(6), "sixHops=" + six);
        check("grid.core_hop_math", Math.abs(Grid.hopsCost(1) - 0.95D) < 1e-9
                        && Math.abs(Grid.hopsCost(3) - 0.857375D) < 1e-9,
                "core=" + Grid.hopsCost(3));
    }

    // ---- 4) the shared budget ----

    private static void sharedBudget() {
        int a = GridLink.withdraw(level, drone, 200);
        int b = GridLink.withdraw(level, mill, 200);
        int c = GridLink.withdraw(level, dock, 200);
        LOG.info("[grid-smoke] budget drone={} mill={} dock={} total={}", a, b, c, a + b + c);
        check("grid.shared_budget_priority", a == 200 && b == 200 && c == 100,
                "drone=" + a + " mill=" + b + " dock=" + c);
        check("grid.shared_budget_total", a + b + c == 500, "total=" + (a + b + c));
    }

    // ---- 5) active pull from a battery bank ----

    private static void bankPull() {
        place(bankPos, PowerRegistry.BANK.get());
        BatteryBankEntity bank = (BatteryBankEntity) level.getBlockEntity(bankPos);
        CompoundTag full = bank.saveWithFullMetadata();
        full.putInt("Energy", 500_000);
        bank.load(full);
        place(pullTowerPos, TowerRegistry.TOWER.get());
        PowerTowerEntity tower = (PowerTowerEntity) level.getBlockEntity(pullTowerPos);
        tower.setEnergy(0);
        tower.tick(level);
        LOG.info("[grid-smoke] pull tower={} bank={}", tower.energy(), bank.getEnergyStored());
        check("grid.tower_pulls_bank", tower.energy() == 2_000 && bank.getEnergyStored() == 498_000,
                "tower=" + tower.energy() + " bank=" + bank.getEnergyStored());
    }

    // ---- 6) breaking any segment: all gone, one drop ----

    private static void breakCascade() {
        place(breakBase, TowerRegistry.TOWER.get());
        BlockPos mid = breakBase.above();
        level.destroyBlock(mid, true);
        boolean air = level.getBlockState(breakBase).isAir()
                && level.getBlockState(breakBase.above()).isAir()
                && level.getBlockState(breakBase.above(2)).isAir();
        int towers = 0, other = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(breakBase).inflate(4.0D))) {
            ItemStack stack = entity.getItem();
            if (stack.is(TowerRegistry.ITEM.get())) towers += stack.getCount();
            else other += stack.getCount();
            entity.discard();
        }
        LOG.info("[grid-smoke] break air={} drops={} other={}", air, towers, other);
        check("grid.break_cascade_single_drop", air && towers == 1 && other == 0,
                "air=" + air + " drops=" + towers + " other=" + other);
    }

    // ---- 7) save round trip ----

    private static void saveRoundTrip() {
        place(savePos, TowerRegistry.TOWER.get());
        PowerTowerEntity tower = (PowerTowerEntity) level.getBlockEntity(savePos);
        tower.setEnergy(654_321);
        CompoundTag saved = tower.saveWithFullMetadata();
        tower.setEnergy(0);
        tower.load(saved);
        check("grid.charge_save_roundtrip", tower.energy() == 654_321, "energy=" + tower.energy());
    }

    // ---- plumbing ----

    private static final class Probe implements GridLoad {
        private final BlockPos pos;
        private final int priority;
        private final int demand;
        Probe(BlockPos pos, int priority, int demand) { this.pos = pos; this.priority = priority; this.demand = demand; }
        @Override public BlockPos gridPos() { return pos; }
        @Override public int gridPriority() { return priority; }
        @Override public int gridDemandFe() { return demand; }
    }

    private static Probe register(Probe probe) {
        GridLink.registerLoad(level, probe);
        return probe;
    }

    private static void tower(BlockPos pos) {
        place(pos, TowerRegistry.TOWER.get());
        BlockStateCheck.check(level, pos);
    }

    /** Small assertion helper: the three cells must exist, otherwise the harness fails loudly. */
    private static final class BlockStateCheck {
        static void check(ServerLevel world, BlockPos pos) {
            if (!world.getBlockState(pos).is(TowerRegistry.TOWER.get())
                    || !world.getBlockState(pos.above()).is(TowerRegistry.MID.get())
                    || !world.getBlockState(pos.above(2)).is(TowerRegistry.TOP.get())) {
                fail("grid.tower_placed", "segments missing at " + pos);
            }
        }
    }

    private static PowerTowerEntity towerEntity(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof PowerTowerEntity tower)) throw new IllegalStateException("no tower at " + pos);
        return tower;
    }

    private static void place(BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static BlockPos at(int dx, int dz) {
        return origin.offset(dx, 0, dz);
    }

    private static BlockPos offset(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    private static void finish(MinecraftServer current) {
        LOG.info("[grid-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[grid-smoke] FAILED {}", failure);
        ran = true;
        current.halt(false);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[grid-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        String line = name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
        FAILURES.add(line);
        LOG.error("[grid-smoke] FAIL {}", line);
    }
}
