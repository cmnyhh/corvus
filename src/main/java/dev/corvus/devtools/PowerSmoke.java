package dev.corvus.devtools;

import dev.corvus.Corvus;
import dev.corvus.ServerOptions;
import dev.corvus.SurvivalWorks;
import dev.corvus.charging.ChargingDockEntity;
import dev.corvus.charging.ChargingDockRegistry;
import dev.corvus.power.BatteryBankEntity;
import dev.corvus.power.BurnerGeneratorBlock;
import dev.corvus.power.SolarGeneratorBlock;
import dev.corvus.power.BurnerGeneratorEntity;
import dev.corvus.power.FuelPower;
import dev.corvus.power.PowerRegistry;
import dev.corvus.power.SolarGeneratorEntity;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance run for the power grid: charging dock fuel, burner generator, solar
 * generator and battery bank.
 *
 * <p>Inactive unless {@code -Dcorvus.power=1} or {@code CORVUS_POWERTEST=1}. It is a multi-tick run
 * because almost everything under test is a per-tick world fact: the pad releases 800 FE/t, a generator
 * only pushes when somebody can take the power, the solar panel reads the real sky, weather and time of
 * day, and the bank fills and empties at a fixed rate. So the harness places real blocks in the real
 * world, lets the server tick them and reads the block entities back — no hand-called tick methods, no
 * mocked world.</p>
 *
 * <p>What it asserts:</p>
 * <ol>
 *   <li><b>fuel values</b> — charcoal and coal 4,000 FE, coal block 40,000, blaze rod 6,000, lava bucket
 *       50,000 with its empty bucket dropped back, and the 32,000 FE buffer still caps the pad;</li>
 *   <li><b>burner</b> — 8 coal fill an adjacent pad, then its fuel count freezes because the pad is
 *       full; at the default 40 FE/t it delivers exactly 400 FE per 10 ticks;</li>
 *   <li><b>no fuel / no receiver</b> — nothing is generated, and a fuelled burner with no neighbour does
 *       not burn at all;</li>
 *   <li><b>solar</b> — 60 FE/t at clear noon (600 FE per 10 ticks into a pad), 0 at night, 0 with a
 *       block above the panel, 30 in rain;</li>
 *   <li><b>battery bank</b> — fills to capacity, refuses more, discharges into a pad until empty, keeps
 *       its charge when nothing is listening, survives a save round trip, and drops a flat block item;</li>
 *   <li><b>persistence and registration</b> — burner fuel/burn/buffer and pad buffer/burn survive
 *       save+load, loot tables, the pickaxe tag and the three assembly blueprints exist.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class PowerSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.power"))
            || "1".equals(System.getenv("CORVUS_POWERTEST"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-power-smoke");
    private static final List<String> FAILURES = new ArrayList<>();

    private static int passed;
    private static int ticks;
    private static boolean ran;
    private static int step;
    private static int wait;
    /** One measurement in flight at a time: the value captured before the wait. */
    private static int recorded;

    private static MinecraftServer server;
    private static ServerLevel level;
    private static BlockPos origin;

    private static BlockPos dockFuelPos, burnerFastPos, dockFastPos, burnerIdlePos, dockIdlePos;
    private static BlockPos burnerLonelyPos, burnerRatePos, dockRatePos, solarPos, dockSolarPos;
    private static BlockPos bankPos, burnerBankPos, dockBankPos, bankIdlePos, bankSavePos;
    private static BlockPos burnerLavaPos, dockLavaPos;
    private static ItemStack batteryCell;
    private static net.minecraftforge.energy.IEnergyStorage batteryPort;

    private PowerSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer current = event.getServer();
        if (current.isStopped() || ran) return;
        if (++ticks < 20) return;
        if (wait > 0) {
            // Counting down to 0 inside the tick means "wait=10" is exactly ten world ticks between the
            // record and the check, which is what the FE/t measurements rely on.
            if (--wait > 0) return;
        }
        try {
            advance(current);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[power-smoke] harness crashed", error);
            finish(current);
        }
    }

    private static void advance(MinecraftServer current) {
        final int doing = step;
        step = doing + 1;
        switch (doing) {
            case 0 -> setup(current);
            case 1 -> dockLoadCharcoal();
            case 2 -> dockCharcoalCheck();
            case 3 -> dockCoalBlockCheck();
            case 4 -> dockFullCheck();
            case 5 -> burnerLoad();
            case 6 -> burnerFilledCheck();
            case 7 -> burnerIdleWhileFull();
            case 8 -> bankStart();
            case 9 -> bankFullCheck();
            case 10 -> bankStopsAndBreaks();
            case 11 -> bankDrainedCheck();
            case 12 -> bankIdleAndBreak();
            case 13 -> solarNoonSetup();
            case 14 -> solarNoonRecord();
            case 15 -> solarNoonCheck();
            case 16 -> solarNightSetup();
            case 17 -> solarNightRecord();
            case 18 -> solarNightCheck();
            case 19 -> solarBlockedSetup();
            case 20 -> solarBlockedRecord();
            case 21 -> solarBlockedCheck();
            case 22 -> solarRainSetup();
            case 23 -> solarRainRecord();
            case 24 -> solarRainCheck();
            case 25 -> solarBufferClear();
            case 26 -> solarBufferCut();
            case 27 -> solarBufferCheck();
            case 28 -> idleSetup();
            case 29 -> idleCheck();
            case 30 -> burnerDefaultRate();
            case 31 -> burnerDefaultRateCheck();
            case 32 -> lavaBucketSetup();
            case 33 -> lavaBucketCheck();
            case 34 -> persistenceCheck();
            case 35 -> registryFacts();
            case 36 -> batteryCapability();
            case 37 -> batteryCapabilityNextTick();
            case 38 -> facingAndGauge();
            default -> finish(current);
        }
    }

    // ---- 0) setup ----

    private static void setup(MinecraftServer current) {
        server = current;
        level = current.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 24;
        int z = spawn.getZ() + 24;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        origin = new BlockPos(x, surface + 8, z);
        LOG.info("[power-smoke] site {} (surface {}), time={} raining={}", origin, surface,
                level.getDayTime() % 24000L, level.isRaining());

        // All on one Y, spaced so that only the pairs meant to touch actually share a face.
        dockFuelPos     = at(0, 0);
        burnerFastPos   = at(4, 0);
        dockFastPos     = at(5, 0);
        burnerIdlePos   = at(0, 4);
        dockIdlePos     = at(1, 4);
        burnerLonelyPos = at(4, 4);
        burnerRatePos   = at(7, 0);
        dockRatePos     = at(8, 0);
        solarPos        = at(0, 8);
        dockSolarPos    = at(1, 8);
        bankPos         = at(4, 8);
        burnerBankPos   = at(5, 8);
        dockBankPos     = at(3, 8);
        bankIdlePos     = at(8, 4);
        bankSavePos     = at(8, 8);
        burnerLavaPos   = at(0, 12);
        dockLavaPos     = at(1, 12);

        place(dockFuelPos, ChargingDockRegistry.DOCK.get());
        place(burnerFastPos, PowerRegistry.BURNER.get());
        place(dockFastPos, ChargingDockRegistry.DOCK.get());
        place(burnerIdlePos, PowerRegistry.BURNER.get());
        place(dockIdlePos, ChargingDockRegistry.DOCK.get());
        place(burnerLonelyPos, PowerRegistry.BURNER.get());
        place(burnerRatePos, PowerRegistry.BURNER.get());
        place(dockRatePos, ChargingDockRegistry.DOCK.get());
        place(solarPos, PowerRegistry.SOLAR.get());
        place(dockSolarPos, ChargingDockRegistry.DOCK.get());
        place(bankPos, PowerRegistry.BANK.get());
        place(burnerBankPos, PowerRegistry.BURNER.get());
        place(bankIdlePos, PowerRegistry.BANK.get());
        place(bankSavePos, PowerRegistry.BANK.get());
        place(burnerLavaPos, PowerRegistry.BURNER.get());
        place(dockLavaPos, ChargingDockRegistry.DOCK.get());

        // A headless run has no players, so chunks are only ticking while the spawn tickets last. Force
        // the site so a block entity never freezes halfway through a measurement.
        for (int cx = (x >> 4) - 1; cx <= ((x + 10) >> 4) + 1; cx++) {
            for (int cz = (z >> 4) - 1; cz <= ((z + 10) >> 4) + 1; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }

        level.setWeatherParameters(6000, 0, false, false);
        level.setDayTime(6_000L);

        check("config.burner_default", ServerOptions.BURNER_OUTPUT.getDefault() == 40,
                String.valueOf(ServerOptions.BURNER_OUTPUT.getDefault()));
        check("config.solar_default", ServerOptions.SOLAR_OUTPUT.getDefault() == 60,
                String.valueOf(ServerOptions.SOLAR_OUTPUT.getDefault()));
        check("config.bank_default", ServerOptions.BATTERY_BANK_CAPACITY.getDefault() == 500_000,
                String.valueOf(ServerOptions.BATTERY_BANK_CAPACITY.getDefault()));

        check("fuel.charcoal_4000", FuelPower.fe(new ItemStack(Items.CHARCOAL)) == 4_000,
                String.valueOf(FuelPower.fe(new ItemStack(Items.CHARCOAL))));
        check("fuel.coal_4000", FuelPower.fe(new ItemStack(Items.COAL)) == 4_000,
                String.valueOf(FuelPower.fe(new ItemStack(Items.COAL))));
        check("fuel.coal_block_40000", FuelPower.fe(new ItemStack(Items.COAL_BLOCK)) == 40_000,
                String.valueOf(FuelPower.fe(new ItemStack(Items.COAL_BLOCK))));
        check("fuel.blaze_rod_6000", FuelPower.fe(new ItemStack(Items.BLAZE_ROD)) == 6_000,
                String.valueOf(FuelPower.fe(new ItemStack(Items.BLAZE_ROD))));
        check("fuel.lava_bucket_50000", FuelPower.fe(new ItemStack(Items.LAVA_BUCKET)) == 50_000,
                String.valueOf(FuelPower.fe(new ItemStack(Items.LAVA_BUCKET))));
        check("fuel.bread_is_not_fuel", !FuelPower.fuel(new ItemStack(Items.BREAD)), "bread");

        // The pad already burns at 800 FE/t, but the burner and bank phases would need 800 and 1,250
        // ticks at the default 40 FE/t. Run those at the configured ceiling and restore 40 before the
        // rate check, which is what proves the default.
        ServerOptions.BURNER_OUTPUT.set(400);
    }

    // ---- 1) charging dock: any furnace fuel ----

    private static void dockLoadCharcoal() {
        ChargingDockEntity dock = dock(dockFuelPos);
        int accepted = dock.addFuel(new ItemStack(Items.CHARCOAL));
        check("dock.charcoal_accepted", accepted == 1, "accepted=" + accepted);
        check("dock.fuel_slot_holds_charcoal", dock.fuelStack().is(Items.CHARCOAL) && dock.fuelStack().getCount() == 1,
                dock.fuelStack().toString());
        check("dock.buffer_32000", dock.buffer() == 32_000, String.valueOf(dock.buffer()));
        wait = 30;
    }

    private static void dockCharcoalCheck() {
        ChargingDockEntity dock = dock(dockFuelPos);
        LOG.info("[power-smoke] dock after charcoal: energy={} burn={} fuel={}", dock.storedEnergy(),
                dock.burnRemainingFe(), dock.fuelStack().getCount());
        // Charcoal is 1,600 burn ticks -> 4,000 FE, released at 800 FE/t: 5 ticks of burning.
        check("dock.charcoal_4000", dock.storedEnergy() == 4_000, "energy=" + dock.storedEnergy());
        check("dock.charcoal_consumed", dock.fuelStack().isEmpty(), "fuel=" + dock.fuelStack());
        int accepted = dock.addFuel(new ItemStack(Items.COAL_BLOCK));
        check("dock.coal_block_accepted", accepted == 1, "accepted=" + accepted);
        wait = 60;
    }

    private static void dockCoalBlockCheck() {
        ChargingDockEntity dock = dock(dockFuelPos);
        LOG.info("[power-smoke] dock after coal block +60t: energy={} burn={} total={}", dock.storedEnergy(),
                dock.burnRemainingFe(), dock.burnTotalFe());
        check("dock.coal_block_40000", dock.burnTotalFe() == 40_000, "burnTotal=" + dock.burnTotalFe());
        // Nothing is created or lost: buffer + queued burn == 4,000 (charcoal) + 40,000 (block).
        check("dock.energy_conserved", dock.storedEnergy() + dock.burnRemainingFe() == 44_000,
                "energy=" + dock.storedEnergy() + " burn=" + dock.burnRemainingFe());
        wait = 140;
    }

    private static void dockFullCheck() {
        ChargingDockEntity dock = dock(dockFuelPos);
        LOG.info("[power-smoke] dock settled: energy={} burn={}", dock.storedEnergy(), dock.burnRemainingFe());
        check("dock.capped_at_buffer", dock.storedEnergy() == 32_000, "energy=" + dock.storedEnergy());
        check("dock.burn_paused_when_full", dock.burnRemainingFe() == 12_000, "burn=" + dock.burnRemainingFe());
        check("dock.fuel_slot_empty", dock.fuelStack().isEmpty(), "fuel=" + dock.fuelStack());
    }

    // ---- 2) burner generator fills an adjacent pad ----

    private static void burnerLoad() {
        BurnerGeneratorEntity burner = burner(burnerFastPos);
        int accepted = burner.addFuel(new ItemStack(Items.COAL, 10));
        check("burner.fuel_accepted", accepted == 10, "accepted=" + accepted);
        check("burner.buffer_100000", burner.buffer() == 100_000, String.valueOf(burner.buffer()));
        wait = 120;
    }

    private static void burnerFilledCheck() {
        BurnerGeneratorEntity burner = burner(burnerFastPos);
        ChargingDockEntity dock = dock(dockFastPos);
        LOG.info("[power-smoke] burner->pad: pad={} burnerBuffer={} fuelLeft={}", dock.storedEnergy(),
                burner.storedEnergy(), burner.fuelStack().getCount());
        check("burner.push_fills_pad", dock.storedEnergy() == 32_000, "pad=" + dock.storedEnergy());
        check("burner.burned_eight_coal", burner.fuelStack().getCount() == 2,
                "left=" + burner.fuelStack().getCount());
        wait = 40;
    }

    private static void burnerIdleWhileFull() {
        BurnerGeneratorEntity burner = burner(burnerFastPos);
        ChargingDockEntity dock = dock(dockFastPos);
        LOG.info("[power-smoke] burner after pad filled: fuel={} buffer={} pad={}",
                burner.fuelStack().getCount(), burner.storedEnergy(), dock.storedEnergy());
        check("burner.stops_when_full", burner.fuelStack().getCount() == 2 && dock.storedEnergy() == 32_000,
                "fuel=" + burner.fuelStack().getCount() + " pad=" + dock.storedEnergy());
        check("burner.buffer_stays_empty", burner.storedEnergy() <= 400, "buffer=" + burner.storedEnergy());
    }

    // ---- 3) battery bank ----

    private static void bankStart() {
        // A 10,000 FE bank is the same code path as the 500,000 default, just fast enough to watch.
        ServerOptions.BATTERY_BANK_CAPACITY.set(10_000);
        BatteryBankEntity bank = bank(bankPos);
        check("bank.capacity_config", bank.capacity() == 10_000 && bank.getMaxEnergyStored() == 10_000,
                "capacity=" + bank.capacity());
        burner(burnerBankPos).addFuel(new ItemStack(Items.COAL, 10));
        wait = 60;
    }

    private static void bankFullCheck() {
        BatteryBankEntity bank = bank(bankPos);
        BurnerGeneratorEntity burner = burner(burnerBankPos);
        LOG.info("[power-smoke] bank after burner: bank={} fuelLeft={} burnerBuffer={}", bank.storedEnergy(),
                burner.fuelStack().getCount(), burner.storedEnergy());
        check("bank.fills_to_capacity", bank.storedEnergy() == 10_000, "bank=" + bank.storedEnergy());
        check("bank.burned_three_coal", burner.fuelStack().getCount() == 7,
                "left=" + burner.fuelStack().getCount());
        check("bank.refuses_when_full", bank.receiveEnergy(1_000, true) == 0, "simulated");
        check("bank.gauge_full", bank.getBlockState().getValue(BatteryBankEntity.CHARGE) == 3,
                "charge=" + bank.getBlockState().getValue(BatteryBankEntity.CHARGE));
        wait = 30;
    }

    private static void bankStopsAndBreaks() {
        BurnerGeneratorEntity burner = burner(burnerBankPos);
        BatteryBankEntity bank = bank(bankPos);
        check("bank.stops_generator", burner.fuelStack().getCount() == 7 && bank.storedEnergy() == 10_000,
                "fuel=" + burner.fuelStack().getCount() + " bank=" + bank.storedEnergy());

        // Breaking the burner drops the block and the fuel left in its slot (never the buffer).
        List<ItemStack> drops = breakAndCollect(burnerBankPos);
        int coal = 0;
        int blocks = 0;
        for (ItemStack stack : drops) {
            if (stack.is(Items.COAL)) coal += stack.getCount();
            if (stack.is(PowerRegistry.BURNER_ITEM.get())) blocks += stack.getCount();
        }
        LOG.info("[power-smoke] burner break drops: {}", describe(drops));
        check("break.burner_block_item", blocks == 1, "count=" + blocks);
        check("break.burner_fuel_dropped", coal == 7, "coal=" + coal);

        // A pad next to the bank: 10,000 FE to give, 2,000 FE/t.
        place(dockBankPos, ChargingDockRegistry.DOCK.get());
        check("bank.place_pad", level.getBlockEntity(dockBankPos) instanceof ChargingDockEntity, "pad");
        wait = 40;
    }

    private static void bankDrainedCheck() {
        BatteryBankEntity bank = bank(bankPos);
        ChargingDockEntity dock = dock(dockBankPos);
        LOG.info("[power-smoke] bank->pad: bank={} pad={}", bank.storedEnergy(), dock.storedEnergy());
        check("bank.drains_to_empty", bank.storedEnergy() == 0, "bank=" + bank.storedEnergy());
        check("bank.discharge_received", dock.storedEnergy() == 10_000, "pad=" + dock.storedEnergy());
        check("bank.gauge_empty", bank.getBlockState().getValue(BatteryBankEntity.CHARGE) == 0,
                "charge=" + bank.getBlockState().getValue(BatteryBankEntity.CHARGE));
    }

    private static void bankIdleAndBreak() {
        BatteryBankEntity idle = bank(bankIdlePos);
        // One call per tick: the bank's input ceiling is 2,000 FE/t, so a second call in the same tick
        // is refused. That cap is checked here as well.
        int first = idle.receiveEnergy(2_000, false);
        int second = idle.receiveEnergy(2_000, false);
        check("bank.idle_charged", first == 2_000 && second == 0 && idle.storedEnergy() == 2_000,
                "first=" + first + " second=" + second + " bank=" + idle.storedEnergy());
        // A charged bank drops a flat block item: the charge stays with the block.
        List<ItemStack> drops = breakAndCollect(bankIdlePos);
        boolean flat = false;
        for (ItemStack stack : drops) {
            if (stack.is(PowerRegistry.BANK_ITEM.get())) flat = !stack.hasTag();
        }
        check("break.bank_drops_uncharged", flat, describe(drops));

        BatteryBankEntity save = bank(bankSavePos);
        save.receiveEnergy(2_000, false);
        check("bank.save_seed", save.storedEnergy() == 2_000, "bank=" + save.storedEnergy());
    }

    // ---- 4) solar generator ----

    private static void solarNoonSetup() {
        level.setDayTime(6_000L);
        level.setWeatherParameters(6_000, 0, false, false);
        // Everything before this phase may have been pushing into this pad, so start from an empty one;
        // a full buffer would show "no gain" and prove nothing about the 60 FE/t.
        level.destroyBlock(dockSolarPos, true);
        place(dockSolarPos, ChargingDockRegistry.DOCK.get());
        check("solar.fresh_pad", dock(dockSolarPos).storedEnergy() == 0, "pad");
        wait = 8;
    }

    private static void solarNoonRecord() {
        recorded = dock(dockSolarPos).storedEnergy();
        wait = 10;
    }

    private static void solarNoonCheck() {
        SolarGeneratorEntity solar = solar(solarPos);
        ChargingDockEntity dock = dock(dockSolarPos);
        int output = SolarGeneratorEntity.outputNow(level, solarPos);
        int gained = dock.storedEnergy() - recorded;
        LOG.info("[power-smoke] solar noon: output={} padGain={} over 10t (solarBuffer {})", output, gained,
                solar.storedEnergy());
        check("solar.noon_60", output == 60, "output=" + output);
        check("solar.noon_rate", gained == 600, "gained=" + gained);
        check("solar.sun_full", solar.getBlockState().getValue(SolarGeneratorEntity.SUN) == 2,
                "sun=" + solar.getBlockState().getValue(SolarGeneratorEntity.SUN));
    }

    private static void solarNightSetup() {
        level.setDayTime(18_000L);
        wait = 8;
    }

    private static void solarNightRecord() {
        recorded = dock(dockSolarPos).storedEnergy();
        wait = 10;
    }

    private static void solarNightCheck() {
        int output = SolarGeneratorEntity.outputNow(level, solarPos);
        int gained = dock(dockSolarPos).storedEnergy() - recorded;
        LOG.info("[power-smoke] solar night (dayTime={}): output={} padGain={} over 10t", level.getDayTime() % 24000L,
                output, gained);
        check("solar.night_zero", output == 0 && gained == 0, "output=" + output + " gained=" + gained);
        check("solar.sun_night", solar(solarPos).getBlockState().getValue(SolarGeneratorEntity.SUN) == 0,
                "sun=" + solar(solarPos).getBlockState().getValue(SolarGeneratorEntity.SUN));
    }

    private static void solarBlockedSetup() {
        level.setDayTime(6_000L);
        place(solarPos.above(), Blocks.STONE);
        wait = 8;
    }

    private static void solarBlockedRecord() {
        recorded = dock(dockSolarPos).storedEnergy();
        wait = 10;
    }

    private static void solarBlockedCheck() {
        int output = SolarGeneratorEntity.outputNow(level, solarPos);
        int gained = dock(dockSolarPos).storedEnergy() - recorded;
        LOG.info("[power-smoke] solar blocked (canSeeSky={}): output={} padGain={} over 10t",
                level.canSeeSky(solarPos.above()), output, gained);
        check("solar.blocked_zero", output == 0 && gained == 0, "output=" + output + " gained=" + gained);
        check("solar.sun_blocked", solar(solarPos).getBlockState().getValue(SolarGeneratorEntity.SUN) == 0,
                "sun=" + solar(solarPos).getBlockState().getValue(SolarGeneratorEntity.SUN));
    }

    private static void solarRainSetup() {
        level.removeBlock(solarPos.above(), false);
        level.setDayTime(6_000L);
        level.setWeatherParameters(0, 2_400, true, false);
        // isRaining() reads the rendered rain level, which ramps at 0.01/tick and only counts above 0.2,
        // so give the weather a full 60 ticks to arrive before measuring anything.
        wait = 60;
    }

    private static void solarRainRecord() {
        recorded = dock(dockSolarPos).storedEnergy();
        wait = 10;
    }

    private static void solarRainCheck() {
        int output = SolarGeneratorEntity.outputNow(level, solarPos);
        int gained = dock(dockSolarPos).storedEnergy() - recorded;
        LOG.info("[power-smoke] solar rain (raining={} thundering={}): output={} padGain={} over 10t",
                level.isRaining(), level.isThundering(), output, gained);
        check("solar.rain_half", output == 30 && gained == 300, "output=" + output + " gained=" + gained);
        check("solar.sun_dim", solar(solarPos).getBlockState().getValue(SolarGeneratorEntity.SUN) == 1,
                "sun=" + solar(solarPos).getBlockState().getValue(SolarGeneratorEntity.SUN));
        check("solar.reason_key", "message.dronepal.solar_rain".equals(solar(solarPos).reasonKey()),
                solar(solarPos).reasonKey());
    }

    private static void solarBufferClear() {
        level.setWeatherParameters(6_000, 0, false, false);
        level.setDayTime(6_000L);
        // The rain level ramps back down at 0.01/tick, so isRaining() stays true for ~20 ticks after the
        // weather is cleared; wait it out or the panel would still be producing 30 FE/t.
        wait = 60;
    }

    private static void solarBufferCut() {
        // No receiver any more: the panel has to keep what it makes.
        level.destroyBlock(dockSolarPos, true);
        recorded = solar(solarPos).storedEnergy();
        wait = 30;
    }

    private static void solarBufferCheck() {
        SolarGeneratorEntity solar = solar(solarPos);
        int output = SolarGeneratorEntity.outputNow(level, solarPos);
        int buffer = solar.storedEnergy();
        int gained = buffer - recorded;
        LOG.info("[power-smoke] solar alone: output={} buffer={} (+{} over 30t)", output, buffer, gained);
        check("solar.buffer_holds", output == 60 && gained == 1_800, "gained=" + gained);
    }

    // ---- 5) idle rules ----

    private static void idleSetup() {
        // Fuelled but with no neighbour: must not burn.
        burner(burnerLonelyPos).addFuel(new ItemStack(Items.COAL, 4));
        wait = 40;
    }

    private static void idleCheck() {
        BurnerGeneratorEntity lonely = burner(burnerLonelyPos);
        BurnerGeneratorEntity noFuel = burner(burnerIdlePos);
        ChargingDockEntity idlePad = dock(dockIdlePos);
        LOG.info("[power-smoke] idle: no-fuel burner={} pad={} · lonely fuel={} burn={} buffer={}",
                noFuel.storedEnergy(), idlePad.storedEnergy(), lonely.fuelStack().getCount(),
                lonely.burnRemainingFe(), lonely.storedEnergy());
        check("idle.no_fuel_no_power", noFuel.storedEnergy() == 0 && idlePad.storedEnergy() == 0,
                "burner=" + noFuel.storedEnergy() + " pad=" + idlePad.storedEnergy());
        check("idle.no_receiver_no_burn", lonely.storedEnergy() == 0 && lonely.fuelStack().getCount() == 4
                        && lonely.burnRemainingFe() == 0,
                "buffer=" + lonely.storedEnergy() + " fuel=" + lonely.fuelStack().getCount());
    }

    // ---- 6) the configured default rate ----

    private static void burnerDefaultRate() {
        ServerOptions.BURNER_OUTPUT.set(40);
        burner(burnerRatePos).addFuel(new ItemStack(Items.COAL, 2));
        recorded = dock(dockRatePos).storedEnergy();
        wait = 10;
    }

    private static void burnerDefaultRateCheck() {
        int gained = dock(dockRatePos).storedEnergy() - recorded;
        LOG.info("[power-smoke] burner at 40 FE/t: padGain={} over 10t", gained);
        check("burner.default_rate_40", gained == 400, "gained=" + gained);
    }

    // ---- 7) persistence, loot, tags, blueprints ----

    private static void persistenceCheck() {
        ChargingDockEntity dock = dock(dockFuelPos);
        CompoundTag dockTag = dock.saveWithFullMetadata();
        ChargingDockEntity dockCopy = ChargingDockRegistry.type().create(dock.getBlockPos(), dock.getBlockState());
        dockCopy.load(dockTag);
        check("persist.dock", dockCopy.storedEnergy() == dock.storedEnergy()
                        && dockCopy.burnRemainingFe() == dock.burnRemainingFe()
                        && ItemStack.matches(dockCopy.fuelStack(), dock.fuelStack()),
                "energy=" + dockCopy.storedEnergy() + " burn=" + dockCopy.burnRemainingFe());

        BurnerGeneratorEntity burner = burner(burnerRatePos);
        CompoundTag burnerTag = burner.saveWithFullMetadata();
        BurnerGeneratorEntity burnerCopy = PowerRegistry.BURNER_TYPE.get().create(burner.getBlockPos(),
                burner.getBlockState());
        burnerCopy.load(burnerTag);
        LOG.info("[power-smoke] burner live: fuel={} burn={} buffer={}", burner.fuelStack().getCount(),
                burner.burnRemainingFe(), burner.storedEnergy());
        check("persist.burner", burnerCopy.fuelStack().getCount() == burner.fuelStack().getCount()
                        && burnerCopy.burnRemainingFe() == burner.burnRemainingFe()
                        && burnerCopy.storedEnergy() == burner.storedEnergy(),
                "fuel=" + burnerCopy.fuelStack().getCount() + " burn=" + burnerCopy.burnRemainingFe()
                        + " buffer=" + burnerCopy.storedEnergy());

        BatteryBankEntity bank = bank(bankSavePos);
        CompoundTag bankTag = bank.saveWithFullMetadata();
        BatteryBankEntity bankCopy = PowerRegistry.BANK_TYPE.get().create(bank.getBlockPos(), bank.getBlockState());
        bankCopy.load(bankTag);
        check("persist.bank", bankCopy.storedEnergy() == 2_000 && bankCopy.storedEnergy() == bank.storedEnergy(),
                "bank=" + bankCopy.storedEnergy());

        SolarGeneratorEntity solar = solar(solarPos);
        CompoundTag solarTag = solar.saveWithFullMetadata();
        SolarGeneratorEntity solarCopy = PowerRegistry.SOLAR_TYPE.get().create(solar.getBlockPos(),
                solar.getBlockState());
        solarCopy.load(solarTag);
        check("persist.solar", solarCopy.storedEnergy() == solar.storedEnergy() && solar.storedEnergy() > 0,
                "solar=" + solarCopy.storedEnergy());
    }

    private static void registryFacts() {
        for (String path : List.of("burner_generator", "solar_generator", "battery_bank")) {
            LootTable table = server.getLootData().getLootTable(new ResourceLocation(Corvus.ID, "blocks/" + path));
            check("loot." + path, table != LootTable.EMPTY, "loot table");
        }
        check("tag.pickaxe",
                PowerRegistry.BURNER.get().builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE)
                        && PowerRegistry.SOLAR.get().builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE)
                        && PowerRegistry.BANK.get().builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE),
                "mineable/pickaxe");
        check("no_occlusion", machinesDoNotOccludeTheirNeighbours(),
                "a machine that reports itself as a full occluder culls the top face of the block it stands on");

        check("block_items.registered",
                ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "burner_generator"))
                        && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "solar_generator"))
                        && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "battery_bank")),
                "items");

        Map<String, Map<String, Integer>> blueprints = new LinkedHashMap<>();
        for (SurvivalWorks.Catalog.Entry entry : SurvivalWorks.catalog().entries()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(entry.icon().getItem());
            if (id == null || !Corvus.ID.equals(id.getNamespace())) continue;
            if (!id.getPath().endsWith("_generator") && !id.getPath().equals("battery_bank")) continue;
            Map<String, Integer> costs = new LinkedHashMap<>();
            for (SurvivalWorks.Catalog.Ingredient ingredient : entry.ingredients()) {
                costs.put(ingredient.item(), ingredient.count());
            }
            blueprints.put(id.getPath(), costs);
        }
        check("blueprint.burner", blueprints.getOrDefault("burner_generator", Map.of()).equals(Map.of(
                        "minecraft:iron_ingot", 6, "minecraft:copper_ingot", 4,
                        "minecraft:redstone", 3, "minecraft:furnace", 1)),
                String.valueOf(blueprints.get("burner_generator")));
        check("blueprint.solar", blueprints.getOrDefault("solar_generator", Map.of()).equals(Map.of(
                        "minecraft:copper_ingot", 4, "minecraft:glass", 4,
                        "minecraft:iron_ingot", 2, "huhu_dronepal:solar_core", 1)),
                String.valueOf(blueprints.get("solar_generator")));
        check("blueprint.bank", blueprints.getOrDefault("battery_bank", Map.of()).equals(Map.of(
                        "minecraft:copper_ingot", 4, "huhu_dronepal:battery_cell", 3,
                        "minecraft:redstone", 6, "minecraft:iron_ingot", 2)),
                String.valueOf(blueprints.get("battery_bank")));
    }

    // ---- 7b) a fuel's container comes back ----

    private static void lavaBucketSetup() {
        burner(burnerLavaPos).addFuel(new ItemStack(Items.LAVA_BUCKET));
        wait = 2;
    }

    private static void lavaBucketCheck() {
        BurnerGeneratorEntity burner = burner(burnerLavaPos);
        ChargingDockEntity pad = dock(dockLavaPos);
        int buckets = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(burnerLavaPos).inflate(3.0D))) {
            if (entity.getItem().is(Items.BUCKET)) {
                buckets += entity.getItem().getCount();
                entity.discard();
            }
        }
        LOG.info("[power-smoke] lava bucket: burnTotal={} pad={} bucketsDropped={}", burner.burnTotalFe(),
                pad.storedEnergy(), buckets);
        check("fuel.lava_bucket_yield", burner.burnTotalFe() == 50_000, "burnTotal=" + burner.burnTotalFe());
        check("fuel.lava_bucket_remainder", buckets == 1, "buckets=" + buckets);
        check("fuel.lava_bucket_burning", burner.burning() && pad.storedEnergy() > 0, "pad=" + pad.storedEnergy());
    }

    // ---- 8) the battery stack's own capability ----

    private static void batteryCapability() {
        // This is the provider Forge finds first (Item#initCapabilities beats AttachCapabilitiesEvent), so
        // it has to accept charge itself instead of shadowing the dock's charge-only port with a 0.
        batteryCell = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, "ahi_bat_mk1")));
        dev.corvus.Hardware.setEnergy(batteryCell, 0);
        batteryPort = batteryCell.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .resolve().orElse(null);
        check("battery.port_present", batteryPort != null, "capability");
        if (batteryPort == null) return;
        check("battery.port_can_receive", batteryPort.canReceive(), "canReceive");
        // ahi_bat_mk1 recharges at 1,200 FE/t after the rebalance (was 600), so the tick budget is 1,200.
        int first = batteryPort.receiveEnergy(1_200, false);
        int second = batteryPort.receiveEnergy(1_200, false);
        check("battery.charge_1200", first == 1_200, "first=" + first);
        check("battery.tick_budget", second == 0, "same-tick second=" + second);
        wait = 1;
    }

    private static void batteryCapabilityNextTick() {
        if (batteryPort == null) return;
        int next = batteryPort.receiveEnergy(1_200, false);
        check("battery.next_tick_1200", next == 1_200, "next=" + next);
        check("battery.two_ticks_total", dev.corvus.Hardware.energy(batteryCell) == 2_400,
                "energy=" + dev.corvus.Hardware.energy(batteryCell));
        int out = batteryPort.extractEnergy(200, false);
        check("battery.extract_still_works", out == 200, "out=" + out);
        batteryPort = null;
    }

    // ---- 9) blockstate wiring: facing, gauge buckets, four-state definitions ----

    private static void facingAndGauge() {
        BlockState burner = PowerRegistry.BURNER.get().defaultBlockState();
        BlockState solar = PowerRegistry.SOLAR.get().defaultBlockState();
        check("facing.default_north", burner.getValue(BurnerGeneratorBlock.FACING) == Direction.NORTH
                        && solar.getValue(SolarGeneratorBlock.FACING) == Direction.NORTH,
                String.valueOf(burner.getValue(BurnerGeneratorBlock.FACING)));
        check("facing.rotate", PowerRegistry.BURNER.get().rotate(burner,
                        net.minecraft.world.level.block.Rotation.CLOCKWISE_90).getValue(BurnerGeneratorBlock.FACING)
                        == Direction.EAST,
                "rotate 90");
        // Left-right mirroring only flips along Z, so a west-facing machine stays west and a
        // north-facing one turns south (vanilla Mirror#getRotation semantics).
        check("facing.mirror", PowerRegistry.BURNER.get().mirror(
                        burner.setValue(BurnerGeneratorBlock.FACING, Direction.NORTH),
                        net.minecraft.world.level.block.Mirror.LEFT_RIGHT).getValue(BurnerGeneratorBlock.FACING)
                        == Direction.SOUTH,
                "mirror north -> south");
        check("facing.states", PowerRegistry.BURNER.get().getStateDefinition().getPossibleStates().size() == 4
                        && PowerRegistry.SOLAR.get().getStateDefinition().getPossibleStates().size() == 12,
                "burner 4 facings, solar 4 facings x 3 sun segments");
        check("gauge.states",
                PowerRegistry.BANK.get().getStateDefinition().getPossibleStates().size() == 4, "four segments");
        check("gauge.buckets", BatteryBankEntity.bucket(0, 10_000) == 0
                        && BatteryBankEntity.bucket(3_300, 10_000) == 0
                        && BatteryBankEntity.bucket(3_400, 10_000) == 1
                        && BatteryBankEntity.bucket(6_600, 10_000) == 1
                        && BatteryBankEntity.bucket(6_700, 10_000) == 2
                        && BatteryBankEntity.bucket(6_650, 10_000) == 2 // 66.5 % rounds to 67
                        && BatteryBankEntity.bucket(9_900, 10_000) == 2
                        && BatteryBankEntity.bucket(10_000, 10_000) == 3,
                "0/33/34/66/67/99/100");
    }

    // ---- plumbing ----

    private static BlockPos at(int dx, int dz) {
        return origin.offset(dx, 0, dz);
    }

    /**
     * The regression the users hit: a housing that does not fill its cell must not be a full occluder,
     * or the block underneath culls the very face the machine stands on and the stone "disappears".
     * Checks the state flags and, for real, whether the renderer would still draw the stone's top face.
     */
    private static boolean machinesDoNotOccludeTheirNeighbours() {
        BlockPos base = origin.offset(40, 0, 0);
        boolean ok = true;
        for (Block machine : List.of(PowerRegistry.BURNER.get(), PowerRegistry.SOLAR.get(), PowerRegistry.BANK.get())) {
            BlockPos stone = base.below();
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(base, machine.defaultBlockState(), Block.UPDATE_ALL);
            BlockState state = level.getBlockState(base);
            boolean clean = !state.canOcclude() && !state.isSolidRender(level, base);
            boolean faceStillDrawn = Block.shouldRenderFace(level.getBlockState(stone), level, stone,
                    Direction.UP, base);
            if (!clean || !faceStillDrawn) {
                LOG.error("[power-smoke] {} canOcclude={} solidRender={} stoneTopDrawn={}",
                        machine, state.canOcclude(), state.isSolidRender(level, base), faceStillDrawn);
                ok = false;
            }
            level.setBlock(base, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            base = base.offset(2, 0, 0);
        }
        return ok;
    }

    private static void place(BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static ChargingDockEntity dock(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof ChargingDockEntity dock)) throw new IllegalStateException("no dock at " + pos);
        return dock;
    }

    private static BurnerGeneratorEntity burner(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BurnerGeneratorEntity burner)) throw new IllegalStateException("no burner at " + pos);
        return burner;
    }

    private static SolarGeneratorEntity solar(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof SolarGeneratorEntity solar)) throw new IllegalStateException("no solar at " + pos);
        return solar;
    }

    private static BatteryBankEntity bank(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof BatteryBankEntity bank)) throw new IllegalStateException("no bank at " + pos);
        return bank;
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

    private static String describe(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            parts.add(ForgeRegistries.ITEMS.getKey(stack.getItem()) + " x" + stack.getCount());
        }
        return String.join(", ", parts);
    }

    private static void finish(MinecraftServer current) {
        LOG.info("[power-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[power-smoke] FAILED {}", failure);
        ran = true;
        current.halt(false);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[power-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        String line = name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
        FAILURES.add(line);
        LOG.error("[power-smoke] FAIL {}", line);
    }
}
