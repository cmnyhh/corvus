package dev.corvus;

import dev.corvus.charging.ChargingDockEntity;
import dev.corvus.charging.ChargingDockRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only server smoke harness for the block layer (character B).
 *
 * <p>Active when {@code -Dcorvus.smokeB=1} or {@code -Dcorvus.genworld=1} (the property the shipped
 * server run config already forwards). It exercises the three acceptance paths on a real dedicated
 * server — assembly output and bench refund, cargo conservation, charging energy flow and NBT
 * persistence — and logs one PASS/FAIL line per check plus a final verdict. Not gameplay code.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class SmokeB {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.smokeB"))
            || "1".equals(System.getProperty("corvus.genworld"));
    private static final Logger LOGGER = LoggerFactory.getLogger("corvus-smokeB");
    private static final BlockPos BENCH_POS = new BlockPos(8, 100, 8);
    private static final BlockPos DOCK_POS = new BlockPos(12, 100, 8);
    /** Blueprint job length in ticks, so the wait is honest instead of guessed. */
    private static final int JOB_WAIT = 100;

    private static int tick;
    private static int step;
    private static int wait;
    private static final List<String> RESULTS = new ArrayList<>();
    private static SurvivalWorks.Plan plan;
    private static int expectedOutput;
    private static int assemblyElapsedBefore;
    private static ChargingDockEntity dock;
    private static int dockEnergyBefore;
    private static int cableAccepted;
    private static int cellEnergyBefore;

    private SmokeB() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ENABLED) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.isStopped()) return;
        tick++;
        if (step == 0 && tick >= 20) {
            step = 1;
            prepareAssembly(server);
        } else if (step == 1) {
            if (++wait < JOB_WAIT) return;
            verifyAssemblyOutput(server);
            step = 2;
            prepareRefund(server);
            wait = 0;
        } else if (step == 2) {
            if (++wait < 2) return;
            verifyRefund(server);
            step = 3;
            runCargo();
            runCatalogCodec();
            prepareCharging(server);
            wait = 0;
        } else if (step == 3) {
            if (++wait < 80) return;
            verifyCharging(server);
            step = 4;
            report();
        }
    }

    /** Removes whatever occupies the position first, so a persisted save cannot skew the numbers. */
    private static void resetBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (!level.getBlockState(pos).isAir()) level.destroyBlock(pos, false);
        level.setBlockAndUpdate(pos, state);
    }

    private static void check(boolean ok, String label, String detail) {
        RESULTS.add((ok ? "PASS " : "FAIL ") + label + " — " + detail);
        LOGGER.info("[corvus-smokeB] {} {} — {}", ok ? "PASS" : "FAIL", label, detail);
    }

    // ---- assembly ----

    private static void prepareAssembly(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (SurvivalWorks.plans.isEmpty()) {
            check(false, "assembly/catalog", "0 blueprints loaded (data/huhu_dronepal/assembly/*.json)");
            return;
        }
        check(SurvivalWorks.plans.size() == 38, "assembly/catalog",
                SurvivalWorks.plans.size() + " blueprints loaded");
        level.getChunk(BENCH_POS);
        resetBlock(level, BENCH_POS, SurvivalWorks.BENCH.get().defaultBlockState());
        if (!(level.getBlockEntity(BENCH_POS) instanceof AssemblyStation station)) {
            check(false, "assembly/place", "no AssemblyStation at " + BENCH_POS);
            return;
        }
        plan = SurvivalWorks.plans.get(0);
        expectedOutput = plan.output().stack().getCount();
        boolean stocked = stock(station, plan);
        boolean selected = station.select(0);
        boolean started = stocked && selected && station.start();
        assemblyElapsedBefore = station.elapsed;
        check(started, "assembly/start", "plan=" + plan.id() + " selected=" + selected
                + " stocked=" + stocked + " busy=" + station.busy() + " duration=" + (started ? plan.ticks() : -1));
    }

    /** Fills the nine input wells with exactly the cost list of the plan. */
    private static boolean stock(AssemblyStation station, SurvivalWorks.Plan target) {
        for (int i = 0; i < AssemblyStation.INPUTS; i++) station.inventory.setItem(i, ItemStack.EMPTY);
        station.inventory.setItem(AssemblyStation.OUTPUT, ItemStack.EMPTY);
        int slot = 0;
        for (SurvivalWorks.Cost cost : target.costs()) {
            ItemStack product = cost.product().stack();
            int remaining = cost.count();
            while (remaining > 0) {
                if (slot >= AssemblyStation.INPUTS) return false;
                ItemStack input = product.copy();
                int size = Math.min(remaining, input.getMaxStackSize());
                input.setCount(size);
                station.inventory.setItem(slot++, input);
                remaining -= size;
            }
        }
        return true;
    }

    private static void verifyAssemblyOutput(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (!(level.getBlockEntity(BENCH_POS) instanceof AssemblyStation station)) {
            check(false, "assembly/output", "station vanished");
            return;
        }
        ItemStack out = station.inventory.getItem(AssemblyStation.OUTPUT);
        boolean match = !out.isEmpty() && ItemStack.isSameItemSameTags(out, plan.output().stack())
                && out.getCount() == expectedOutput;
        check(match, "assembly/output", "expected=" + plan.output().stack().getHoverName().getString() + " x" + expectedOutput
                + " got=" + (out.isEmpty() ? "empty" : out.getHoverName().getString() + " x" + out.getCount())
                + " pending=" + station.busy() + " duration=" + plan.ticks()
                + " (progress made by the world ticker, no manual tick() call)");
    }

    private static void prepareRefund(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (!(level.getBlockEntity(BENCH_POS) instanceof AssemblyStation station)) return;
        stock(station, plan);
        station.select(0);
        boolean started = station.start();
        int reserved = station.reservedCount();
        check(started && reserved > 0, "assembly/refund-setup",
                "job running=" + started + " reserved stacks=" + reserved);
        // Break the bench with the job in flight: the reserved inputs must come back as drops.
        level.destroyBlock(BENCH_POS, false);
    }

    private static void verifyRefund(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Map<String, Integer> dropped = countDrops(level, BENCH_POS);
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (SurvivalWorks.Cost cost : plan.costs()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(cost.product().stack().getItem());
            expected.merge(id.toString(), cost.count(), Integer::sum);
        }
        check(dropped.equals(expected), "assembly/refund", "dropped=" + dropped + " expected=" + expected);
        check(!(level.getBlockEntity(BENCH_POS) instanceof AssemblyStation), "assembly/refund-block",
                "bench removed=" + level.getBlockState(BENCH_POS).isAir());
    }

    private static Map<String, Integer> countDrops(ServerLevel level, BlockPos pos) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0D))) {
            if (drop.getItem().isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(drop.getItem().getItem());
            counts.merge(id.toString(), drop.getItem().getCount(), Integer::sum);
            drop.discard();
        }
        return counts;
    }

    // ---- cargo ----

    private static void runCargo() {
        ItemStack controller = Frames.create("peregrine");
        ItemStack module = Hardware.moduleStack("carry_mk2");
        List<ItemStack> parts = new ArrayList<>();
        parts.add(module);
        Hardware.save(controller, parts);
        int capacity = CarryLight.slots(controller);
        check(capacity == 18, "cargo/fit", "carry_mk2 exposes " + capacity + " slots");

        CargoHold hold = new CargoHold(controller, capacity, 27);
        hold.setItem(0, new ItemStack(Items.DIRT, 64));
        hold.setItem(1, new ItemStack(Items.STONE, 10));
        List<ItemStack> stored = CarryLight.read(controller);
        int before = count(stored);
        check(stored.size() == 2 && before == 74, "cargo/write", "tag holds " + describe(stored) + " total=" + before);

        ItemStack removed = hold.removeItem(0, 64);
        int after = count(CarryLight.read(controller));
        check(removed.getCount() == 64 && after == 10, "cargo/conservation",
                "took 64 dirt, tag now " + describe(CarryLight.read(controller)) + " (before " + before + ", after " + after + ")");

        // Same object, reloaded from disk: the hold must survive an NBT round-trip unchanged.
        ItemStack reloaded = ItemStack.of(controller.save(new CompoundTag()));
        check(count(CarryLight.read(reloaded)) == after, "cargo/persist",
                "after NBT round-trip " + describe(CarryLight.read(reloaded)));

        // A shrunken hold parks the surplus instead of deleting it: a 1-slot view of a 74-item hold.
        hold.setItem(0, new ItemStack(Items.DIRT, 64));
        CargoHold shrunk = new CargoHold(controller, 1, 27);
        int parked = shrunk.parked();
        check(parked == 10 && count(CarryLight.read(controller)) == 74 && shrunk.capacity() == 1, "cargo/shrink",
                "1-slot view shows " + describe(CarryLight.read(controller)) + ", parked=" + parked
                        + " (nothing lost)");

        // Everything back out: the tag is empty and nothing was duplicated.
        for (int i = 0; i < 27; i++) hold.removeItem(i, 64);
        check(CarryLight.read(controller).isEmpty(), "cargo/empty",
                "hold drained, tag=" + describe(CarryLight.read(controller)));
    }

    private static int count(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) total += stack.getCount();
        return total;
    }

    private static String describe(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) parts.add(stack.getItem() + "x" + stack.getCount());
        return parts.isEmpty() ? "[]" : parts.toString();
    }

    /**
     * The blueprint list the client draws arrives over SimpleChannel; encode and decode it here so a
     * codec regression shows up as a failed smoke check instead of an empty screen.
     */
    private static void runCatalogCodec() {
        SurvivalWorks.Catalog catalog = SurvivalWorks.catalog();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SurvivalWorks.Catalog.write(catalog, buffer);
        int bytes = buffer.readableBytes();
        SurvivalWorks.Catalog decoded;
        try {
            decoded = SurvivalWorks.Catalog.read(buffer);
        } catch (RuntimeException malformed) {
            check(false, "assembly/codec", "decode failed: " + malformed);
            return;
        }
        boolean sameSize = decoded.entries().size() == catalog.entries().size();
        boolean sameFirst = sameSize && !catalog.entries().isEmpty()
                && ItemStack.isSameItemSameTags(decoded.entries().get(0).icon(), catalog.entries().get(0).icon())
                && decoded.entries().get(0).ingredients().size() == catalog.entries().get(0).ingredients().size()
                && decoded.entries().get(0).ingredients().get(0).item().equals(catalog.entries().get(0).ingredients().get(0).item())
                && decoded.entries().get(0).ingredients().get(0).count() == catalog.entries().get(0).ingredients().get(0).count();
        check(sameSize && sameFirst && bytes > 0, "assembly/codec",
                "catalog packet " + bytes + " bytes, entries " + catalog.entries().size() + " -> " + decoded.entries().size()
                        + ", first entry icon+materials match=" + sameFirst);
    }

    // ---- charging dock ----

    private static void prepareCharging(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.getChunk(DOCK_POS);
        // A previous run may have left a docked cell and a charged buffer in the save; a fresh block
        // entity is the only way to start every check from zero.
        resetBlock(level, DOCK_POS, ChargingDockRegistry.DOCK.get().defaultBlockState());
        if (!(level.getBlockEntity(DOCK_POS) instanceof ChargingDockEntity entity)) {
            check(false, "dock/place", "no ChargingDockEntity at " + DOCK_POS);
            return;
        }
        dock = entity;
        IEnergyStorage port = entity.getCapability(ForgeCapabilities.ENERGY, null).resolve().orElse(null);
        check(port != null, "dock/capability", "ForgeCapabilities.ENERGY present=" + (port != null));
        if (port == null) return;
        int first = port.receiveEnergy(5_000, false);
        int second = port.receiveEnergy(5_000, false);
        cableAccepted = first;
        dockEnergyBefore = entity.storedEnergy();
        check(first == 2_000 && second == 0, "dock/cable-rate",
                "first=" + first + " FE, same-tick second=" + second + " FE (buffer cap 2000/t)");
        check(entity.getMaxEnergyStored() == 32_000, "dock/buffer", "buffer=" + entity.getMaxEnergyStored() + " FE");
        entity.addCoal(4);
        ItemStack battery = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(Corvus.ID, "ahi_bat_mk1")));
        // A freshly crafted cell counts as full (same rule as the 1.21 build), so drain it first or the
        // transfer check would pass without moving a single FE.
        Hardware.setEnergy(battery, 0);
        cellEnergyBefore = Hardware.energy(battery);
        boolean docked = entity.insert(battery);
        check(docked && !entity.targetStack().isEmpty(), "dock/insert",
                "docked=" + docked + " stack=" + (entity.targetStack().isEmpty() ? "empty" : entity.targetStack().getItem()));
        check(entity.coalCount() == 4 && cellEnergyBefore == 0, "dock/coal",
                "coal=" + entity.coalCount() + " (4000 FE each, 200 FE/t), docked cell=" + cellEnergyBefore + " FE");
    }

    private static void verifyCharging(MinecraftServer server) {
        if (dock == null) return;
        int stored = dock.storedEnergy();
        ItemStack target = dock.targetStack();
        int charged = target.isEmpty() ? 0 : Hardware.energy(target);
        check(charged > 0 && charged <= 250_000, "dock/charge-flow",
                "cell " + cellEnergyBefore + " -> " + charged + "/" + Hardware.capacity(target) + " FE, buffer "
                        + dockEnergyBefore + " -> " + stored);
        int generated = cableAccepted + 4 * 4_000; // cable input + four pieces of coal
        check(stored + (charged - cellEnergyBefore) == generated, "dock/transfer",
                "energy conserved: buffer " + stored + " + cell " + (charged - cellEnergyBefore) + " = "
                        + (stored + charged - cellEnergyBefore) + ", fed " + generated + " FE");

        // Leave a non-zero buffer and coal behind: persisting zeros would prove nothing.
        dock.receiveEnergy(1_500, false);
        dock.addCoal(2);
        int keptEnergy = dock.storedEnergy();
        int keptCoal = dock.coalCount();
        // Persistence: same tag a chunk save would write, loaded into a fresh block entity.
        CompoundTag saved = dock.saveWithoutMetadata();
        BlockEntity fresh = ChargingDockRegistry.type().create(DOCK_POS, dock.getBlockState());
        fresh.load(saved);
        ChargingDockEntity reloaded = (ChargingDockEntity) fresh;
        boolean sameTarget = !reloaded.targetStack().isEmpty()
                && reloaded.targetStack().getItem() == dock.targetStack().getItem()
                && Hardware.energy(reloaded.targetStack()) == Hardware.energy(dock.targetStack());
        check(reloaded.storedEnergy() == keptEnergy && reloaded.coalCount() == keptCoal && sameTarget,
                "dock/persist", "buffer " + keptEnergy + " -> " + reloaded.storedEnergy() + " FE, coal "
                        + keptCoal + " -> " + reloaded.coalCount() + ", target cell same=" + sameTarget);
        check(reloaded.getCapability(ForgeCapabilities.ENERGY, null).resolve().isPresent(), "dock/persist-cap",
                "reloaded entity still exposes the energy capability");
    }

    // ---- verdict ----

    private static void report() {
        int failed = 0;
        for (String line : RESULTS) if (line.startsWith("FAIL")) failed++;
        LOGGER.info("[corvus-smokeB] ---- {} checks, {} failed ----", RESULTS.size(), failed);
        for (String line : RESULTS) LOGGER.info("[corvus-smokeB] {}", line);
        LOGGER.info("[corvus-smokeB] SMOKE-B {}", failed == 0 ? "OK" : "FAILED");
    }
}
