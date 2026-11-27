package dev.corvus;

import com.mojang.authlib.GameProfile;
import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Development-only acceptance run for the module runtime (`ModuleRuntime`).
 *
 * <p>Inactive unless {@code CORVUS_MODTEST=1} in the environment or {@code -Dcorvus.modtest=1}. It is a
 * multi-tick run because two of the four things under test are <em>world</em> facts: the photovoltaic
 * figure depends on the real daylight and weather of the level, and the level only recomputes its sky
 * darkness on a tick. Everything else runs synchronously inside the tick handler.</p>
 *
 * <p>What it asserts, and why each one is worth a run:</p>
 * <ol>
 *   <li><b>the power transaction</b> — one settlement is exactly
 *       {@code −drain×20 + solar×20} against a hand calculation from the data files, at night, by day,
 *       in rain and without a view of the sky (0), plus the scrap panel's {@code output_jitter} band;</li>
 *   <li><b>exhaustion</b> — the cell lands on 0 and never below it, the modules go offline, and the next
 *       settlement charges nothing (no negative, no double debit);</li>
 *   <li><b>the pickup pass</b> — inside the radius, outside it, a full hold, a delivery marked
 *       {@code dronepal_delivered}, a terminal, and a partial-stack merge;</li>
 *   <li><b>the lamp</b> — the dialogue text resolves to {@code order.light}, the order toggles the switch
 *       both ways, the packed profile carries radius/brightness/flicker, an empty cell extinguishes it,
 *       and the wire form round-trips.</li>
 * </ol>
 *
 * <p>It lives in {@code dev.corvus} like {@link GapSmoke}: the runtime it drives is package-scoped.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class ModuleSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.modtest"))
            || "1".equals(System.getenv("CORVUS_MODTEST"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-module-smoke");
    /** {@code CarryLight}'s delivery mark; a delivered stack must never be swallowed again. */
    private static final String DELIVERED = "dronepal_delivered";

    private static final List<String> FAILURES = new ArrayList<>();
    private static int ticks;
    private static int step;
    private static int wait;
    /** Ticks to wait before the next phase; a step that changes the weather needs ~30 (rain ramps). */
    private static int need = 3;
    private static boolean ran;
    private static int passed;

    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static ItemStack controller;
    /** Detached projection: drives the arithmetic without the world ticking it away. */
    private static DroneEntity drone;
    private static ItemEntity near;
    private static ItemEntity far;

    private ModuleSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer current = event.getServer();
        if (current.isStopped() || ran) return;
        if (++ticks < 20) return;
        try {
            phase(current);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[module-smoke] harness crashed", error);
            finish(current);
        }
    }

    private static void finish(MinecraftServer current) {
        LOG.info("[module-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[module-smoke] FAILED {}", failure);
        ran = true;
        roof(false);
        current.halt(false);
    }

    private static void phase(MinecraftServer current) {
        if (step == 0) {
            setup(current);
            step = 1;
            return;
        }
        // Give the level a few ticks to recompute sky darkness / weather before reading it back.
        if (++wait < need) return;
        wait = 0;
        need = 3;
        switch (step++) {
            case 1 -> drainPhase();
            case 2 -> solarNightPhase();
            case 3 -> solarDayPhase();
            case 4 -> solarRainPhase();
            case 5 -> solarRoofedPhase();
            case 6 -> combinedPhase();
            case 7 -> jitterPhase();
            case 8 -> exhaustionPhase();
            case 9 -> pickupPhase();
            case 10 -> lightPhase();
            default -> finish(current);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static void setup(MinecraftServer current) {
        server = current;
        level = current.overworld();
        player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes("corvus-module-smoke".getBytes(StandardCharsets.UTF_8)), "ModuleSmoke"));
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
        player.moveTo(8.5D, surface, 8.5D, 0F, 0F);
        controller = Frames.create("bedrock");
        UUID serial = DroneItem.ensureSerial(controller);
        boolean worn = wear(player, controller);
        player.getPersistentData().putUUID(CorvusKeys.ACTIVE, serial);
        drone = new DroneEntity(Corvus.ENTITY.get(), level);
        drone.moveTo(8.5D, surface + 2D, 8.5D, 0F, 0F);
        level.setWeatherParameters(6000, 0, false, false);
        level.setDayTime(6000L);
        check("setup.curios_worn", worn, "curios " + DroneCurios.SLOT + " slot");
        check("setup.controller", !Hardware.controller(player).isEmpty(), "worn controller resolves");
        check("setup.sky", level.canSeeSky(drone.blockPosition()), "pos=" + drone.blockPosition());
        LOG.info("[module-smoke] controller frame={} serial={} pos={} weather: day={} raining={} sky={}",
                Frames.id(controller), serial, drone.blockPosition(), level.isDay(), level.isRaining(),
                level.canSeeSky(drone.blockPosition()));
    }

    // ---- 1. one second of the power transaction ------------------------------------------------

    private static void drainPhase() {
        fit("omd_bat_300", "combat_mk1", "scan_mk1");
        Hardware.setEnergy(controller, 500_000);
        // Standby, not the old always-on figure: combat stands by at 3 FE/t (x1.10 vendor trait), the
        // scanner costs nothing between pulses (14 and 6 are its in-use rates now).
        int drain = Hardware.drainPerTick(controller);
        check("power.drain_hand_calc", drain == 3, "drain=" + drain + " (combat 3×1.10 standby + scan 0 standby)");
        int before = Hardware.energy(controller);
        int delta = ModuleRuntime.settle(level, drone, controller);
        int after = Hardware.energy(controller);
        // standby 3.3 + deployed baseline 0.5 = 3.8 FE/t, x the default 0.6 multiplier = 2.28 -> 46 FE.
        check("power.settled_once", delta == -46 && after == before - 46,
                "delta=" + delta + " " + before + " -> " + after + " (3.8 FE/t x 0.6 x 20)");
        LOG.info("[module-smoke] settle: {} -> {} FE (delta {}) for {} FE/t", before, after, delta, drain);
        // Night, for the next step: the solar module must then make nothing at all.
        level.setDayTime(18000L);
    }

    // ---- 2. night = 0 ----------------------------------------------------------------------------

    private static void solarNightPhase() {
        check("solar.night_world", !level.isDay(), "dayTime=" + level.getDayTime() + " isDay=" + level.isDay());
        fit("omd_bat_300", "solar_mk1");
        Hardware.setEnergy(controller, 0);
        int delta = ModuleRuntime.settle(level, drone, controller);
        check("solar.night_zero", delta == 0 && Hardware.energy(controller) == 0,
                "delta=" + delta + " energy=" + Hardware.energy(controller));
        level.setDayTime(6000L);
        level.setWeatherParameters(6000, 0, false, false);
    }

    // ---- 3. clear noon = clear FE/t --------------------------------------------------------------

    private static void solarDayPhase() {
        check("solar.day_world", level.isDay() && !level.isRaining(),
                "dayTime=" + level.getDayTime() + " isDay=" + level.isDay() + " raining=" + level.isRaining());
        fit("omd_bat_300", "solar_mk1");
        Hardware.Module solar = ModuleRuntime.module(controller, "solar");
        check("solar.data_clear", solar != null && solar.integer("clear") == 8, "clear=8 FE/t");
        Hardware.setEnergy(controller, 0);
        int delta = ModuleRuntime.settle(level, drone, controller);
        check("solar.day_charges", delta == 160, "delta=" + delta + " (8 FE/t × 20)");
        LOG.info("[module-smoke] solar clear: delta={} FE", delta);
        level.setWeatherParameters(0, 1200, true, true);
        // Level.isRaining() reads the interpolated rain level, which ramps by 0.01/tick: give it 30.
        need = 30;
    }

    // ---- 4. rain = the rain figure ----------------------------------------------------------------

    private static void solarRainPhase() {
        check("solar.rain_world", level.isRaining(), "raining=" + level.isRaining());
        Hardware.setEnergy(controller, 0);
        int delta = ModuleRuntime.settle(level, drone, controller);
        check("solar.rain_output", delta == 40, "delta=" + delta + " (2 FE/t × 20)");
        level.setWeatherParameters(6000, 0, false, false);
        roof(true);
        need = 30;
    }

    // ---- 5. no view of the sky = 0 ----------------------------------------------------------------

    private static void solarRoofedPhase() {
        check("solar.roof_built", !level.canSeeSky(drone.blockPosition()), "pos=" + drone.blockPosition());
        Hardware.setEnergy(controller, 0);
        int delta = ModuleRuntime.settle(level, drone, controller);
        check("solar.no_sky_zero", delta == 0, "delta=" + delta);
        roof(false);
    }

    // ---- 6. drain and supply in the same second --------------------------------------------------

    private static void combinedPhase() {
        fit("omd_bat_300", "combat_mk1", "scan_mk1", "solar_mk1");
        Hardware.setEnergy(controller, 500_000);
        int before = Hardware.energy(controller);
        int delta = ModuleRuntime.settle(level, drone, controller);
        int after = Hardware.energy(controller);
        check("power.combined", delta == -260 && after == before - 260,
                "delta=" + delta + " (-21×20 + 8×20 = -260)");
        int expected = -420 + 160;
        check("power.combined_formula", delta == expected, "delta=" + delta + " expected=" + expected);
        LOG.info("[module-smoke] combined: {} FE/t drain, 8 FE/t solar -> {} FE", 21, delta);
    }

    // ---- 7. the scrap panel's output_jitter ------------------------------------------------------

    private static void jitterPhase() {
        fit("omd_bat_300", "solar_rust_g1");
        Hardware.Module solar = ModuleRuntime.module(controller, "solar");
        check("jitter.fitted", solar != null && solar.params().has("output_jitter")
                && Math.abs(solar.params().get("output_jitter").getAsDouble() - 0.3D) < 1.0E-6,
                String.valueOf(solar == null ? null : solar.params()));
        Hardware.setEnergy(controller, 0);
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int total = 0;
        Set<Integer> distinct = new HashSet<>();
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int delta = ModuleRuntime.settle(level, drone, controller);
            low = Math.min(low, delta);
            high = Math.max(high, delta);
            total += delta;
            distinct.add(delta);
        }
        LOG.info("[module-smoke] jitter: {} samples, min={} max={} mean={} distinct={}",
                samples, low, high, total / samples, distinct.size());
        // nominal 5 FE/t -> 100 FE/s, ±30%
        check("jitter.band", low >= 70 && high <= 130, "min=" + low + " max=" + high);
        check("jitter.varies", distinct.size() > 1, "distinct=" + distinct.size());
        boolean nominal = distinct.contains(100) || Math.abs(total / samples - 100) <= 6;
        check("jitter.centred", nominal, "mean=" + (total / samples));
    }

    // ---- 8. exhaustion: 0, offline, no further debit ---------------------------------------------

    private static void exhaustionPhase() {
        fit("omd_bat_300", "combat_mk1", "scan_mk1");
        // 100 FE against the brown-out draw (the hungriest module is cut below one fifth of capacity, so
        // the second really spends 6 FE/t = 120): the clamp has to bite inside this one settlement.
        Hardware.setEnergy(controller, 100);
        int before = Hardware.energy(controller);
        int delta = ModuleRuntime.settle(level, drone, controller);
        check("exhaust.clamped", Hardware.energy(controller) == 0 && delta == -before,
                "energy=" + Hardware.energy(controller) + " delta=" + delta);
        check("exhaust.offline", !Hardware.online(controller, "scan") && !Hardware.online(controller, "combat"),
                "energy=" + Hardware.energy(controller));
        check("exhaust.drain_zero", Hardware.drainPerTick(controller) == 0,
                "drain=" + Hardware.drainPerTick(controller));
        int second = ModuleRuntime.settle(level, drone, controller);
        check("exhaust.no_double_debit", second == 0 && Hardware.energy(controller) == 0,
                "delta=" + second + " energy=" + Hardware.energy(controller));
        // And it can never be negative, even if a data pack asks for an absurd draw.
        Hardware.setEnergy(controller, -5000);
        check("exhaust.never_negative", Hardware.energy(controller) >= 0,
                "energy=" + Hardware.energy(controller));
        // A photovoltaic module still charges a flat cell: solar is not gated by the brown-out rule.
        fit("omd_bat_300", "solar_mk1");
        Hardware.setEnergy(controller, 0);
        int revive = ModuleRuntime.settle(level, drone, controller);
        check("exhaust.solar_revives", revive == 160 && Hardware.online(controller, "solar"),
                "delta=" + revive + " energy=" + Hardware.energy(controller));
    }

    // ---- 9. the pickup pass ----------------------------------------------------------------------

    private static void pickupPhase() {
        fit("omd_bat_300", "carry_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        Hardware.Module hold = ModuleRuntime.module(controller, "carry");
        check("pickup.data", hold != null && hold.integer("slots") == 9 && hold.integer("pickup_radius") == 6,
                String.valueOf(hold == null ? null : hold.params()));
        CarryLight.save(controller, new ArrayList<>());
        player.getInventory().clearContent();

        near = drop(2.0D, new ItemStack(Items.BREAD, 5));
        far = drop(9.0D, new ItemStack(Items.BREAD, 3));
        boolean moved = ModuleRuntime.pickupPhase(level, drone, player, controller);
        check("pickup.inside_radius", moved && holdCount() == 5 && count(Items.BREAD) == 0,
                "hold=" + holdCount() + " bread=" + count(Items.BREAD) + " (the hold takes it, not the inventory)");
        check("pickup.entity_gone", near.isRemoved() || !near.isAlive(), "picked entity removed");
        check("pickup.outside_radius", far.isAlive() && far.getItem().getCount() == 3,
                "far drop still on the ground: " + far.getItem());
        far.discard();

        // A shrunken hold still merges into the partial stack and only then takes a new slot.
        CarryLight.save(controller, new ArrayList<>(List.of(new ItemStack(Items.BREAD, 60))));
        near = drop(1.5D, new ItemStack(Items.BREAD, 5));
        ModuleRuntime.pickupPhase(level, drone, player, controller);
        List<ItemStack> cargo = CarryLight.read(controller);
        check("pickup.partial_merge", holdCount() == 65 && cargo.size() == 2,
                "hold=" + holdCount() + " stacks=" + cargo.size() + " " + cargo);

        // A full hold takes nothing, and the drop stays where it is.
        List<ItemStack> full = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) full.add(new ItemStack(Items.STONE, 64));
        CarryLight.save(controller, full);
        near = drop(1.5D, new ItemStack(Items.DIAMOND, 2));
        boolean collected = ModuleRuntime.pickupPhase(level, drone, player, controller);
        check("pickup.full_hold_refused", !collected && near.isAlive() && near.getItem().getCount() == 2,
                "hold=" + holdCount() + " drop=" + near.getItem());
        near.discard();

        // A delivery this drone just made must never be swallowed back.
        CarryLight.save(controller, new ArrayList<>());
        near = drop(1.5D, new ItemStack(Items.EMERALD, 4));
        near.getPersistentData().putBoolean(DELIVERED, true);
        boolean reacquired = ModuleRuntime.pickupPhase(level, drone, player, controller);
        check("pickup.delivery_not_reacquired", !reacquired && holdCount() == 0 && near.isAlive(),
                "hold=" + holdCount() + " drop=" + near.getItem());
        near.discard();

        // Nor may it eat a terminal.
        near = drop(1.5D, Frames.create("owl"));
        boolean terminal = ModuleRuntime.pickupPhase(level, drone, player, controller);
        check("pickup.no_terminal", !terminal && holdCount() == 0 && near.isAlive(),
                "hold=" + holdCount());
        near.discard();

        // Offline hold: nothing moves.
        Hardware.setEnergy(controller, 0);
        near = drop(1.5D, new ItemStack(Items.BREAD, 1));
        boolean offline = ModuleRuntime.pickupPhase(level, drone, player, controller);
        check("pickup.offline_refused", !offline && holdCount() == 0 && near.isAlive(), "energy=0");
        near.discard();
        Hardware.setEnergy(controller, Hardware.capacity(controller));
    }

    // ---- 10. the lamp ---------------------------------------------------------------------------

    private static void lightPhase() {
        fit("omd_bat_300", "light_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        CorvusData.of(controller).setBoolean(DroneRemote.OFF_PREFIX + "light", false);
        check("light.dialogue_text", "order.light".equals(DialogueServer.intentOf("开灯")),
                "resolved=" + DialogueServer.intentOf("开灯"));
        check("light.profile_packed", ModuleRuntime.lightProfile(drone, controller) == ((8 << 4) | 12),
                "profile=" + ModuleRuntime.lightProfile(drone, controller) + " (radius 8, level 12)");
        check("light.tint", ModuleRuntime.tint(controller) == 0xA8E8FF,
                "tint=" + Integer.toHexString(ModuleRuntime.tint(controller)));

        // Tagged off, so the first order switches it on through the real intent handler.
        CorvusData.of(controller).setBoolean(DroneRemote.OFF_PREFIX + "light", true);
        boolean on = Hardware.execute(player, "order.light");
        check("light.order_on", on && ModuleRuntime.lightProfile(drone, controller) != 0,
                "profile=" + ModuleRuntime.lightProfile(drone, controller));
        boolean off = Hardware.execute(player, "order.light");
        check("light.order_off", off && ModuleRuntime.lightProfile(drone, controller) == 0,
                "profile=" + ModuleRuntime.lightProfile(drone, controller));

        // ... and again through the dialogue command tree, the way a player does it.
        CorvusData.of(controller).setBoolean(DroneRemote.OFF_PREFIX + "light", false);
        say("开灯");
        check("light.dialogue_off", DroneRemote.disabled(controller, "light"),
                "switch=" + !DroneRemote.disabled(controller, "light"));
        say("开灯");
        check("light.dialogue_on", !DroneRemote.disabled(controller, "light"), "");
        // An empty cell extinguishes the lamp without any timer: same predicate, profile 0.
        Hardware.setEnergy(controller, 0);
        check("light.low_battery_dark", ModuleRuntime.lightProfile(drone, controller) == 0,
                "energy=0 profile=" + ModuleRuntime.lightProfile(drone, controller));
        CorvusData.of(controller).setBoolean(DroneRemote.OFF_PREFIX + "light", true);
        check("light.order_refused_no_power", !Hardware.execute(player, "order.light"), "energy=0");

        // The scrap headlamp: brighter, and the profile carries the flicker bit.
        fit("omd_bat_300", "light_rust_g1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        CorvusData.of(controller).setBoolean(DroneRemote.OFF_PREFIX + "light", false);
        int flickering = ModuleRuntime.lightProfile(drone, controller);
        check("light.flicker_bit", flickering == ((9 << 4) | 14 | ModuleRuntime.LIGHT_FLICKER),
                "profile=" + flickering);
        check("light.flicker_tint", ModuleRuntime.tint(controller) == 0xFFE7B0,
                "tint=" + Integer.toHexString(ModuleRuntime.tint(controller)));

        // Wire form: radius, brightness and the flicker bit must survive the codec.
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            LightSync.State state = new LightSync.State(17, flickering, 0xFFE7B0);
            LightSync.State.encode(state, buffer);
            LightSync.State decoded = LightSync.State.decode(buffer);
            check("light.wire_roundtrip", decoded.entityId() == 17 && decoded.profile() == flickering
                    && decoded.rgb() == 0xFFE7B0, decoded.toString());
        } finally {
            buffer.release();
        }
        LightSync.remember(23, flickering, 0xFFE7B0);
        LightSync.Lamp cached = LightSync.lamp(23);
        check("light.client_cache", cached != null && cached.radius() == 9 && cached.brightness() == 14
                && cached.flicker() && cached.lit(), String.valueOf(cached));
        LightSync.remember(23, 0, 0);
        check("light.client_dark_dropped", LightSync.lamp(23) == null && LightSync.cached() == 0,
                "cache=" + LightSync.cached());
        LightSync.forgetAll();
        // Publishing to the clients tracking a projection must not throw with no trackers at all.
        boolean published;
        try {
            ModuleRuntime.syncLight(drone, flickering, 0xFFE7B0);
            published = true;
        } catch (RuntimeException failure) {
            published = false;
            LOG.error("[module-smoke] lamp publish threw", failure);
        }
        check("light.publish", published, "");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Fits a battery, the tactical chip (the dialogue gate) and the given module ids. */
    private static void fit(String battery, String... modules) {
        List<ItemStack> parts = new ArrayList<>();
        parts.add(stack(battery));
        parts.add(new ItemStack(Hardware.CHIP_ITEMS.get("tactical").get()));
        for (String module : modules) parts.add(Hardware.moduleStack(module));
        Hardware.save(controller, parts);
    }

    /** Drops a stack {@code offset} blocks east of the projection. */
    private static ItemEntity drop(double offset, ItemStack stack) {
        ItemEntity drop = new ItemEntity(level, drone.getX() + offset, drone.getY(), drone.getZ(), stack);
        drop.setNoPickUpDelay();
        level.addFreshEntity(drop);
        return drop;
    }

    /** A 3×3 stone ceiling above the projection, so it loses its view of the sky (and back). */
    private static void roof(boolean build) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                var pos = drone.blockPosition().offset(x, 2, z);
                if (build) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                else level.removeBlock(pos, false);
            }
        }
    }

    /** Runs the registered {@code /dronepal} tree the way a player does, through the real dispatcher. */
    private static void say(String text) {
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4), "dronepal " + text);
    }

    private static boolean wear(ServerPlayer player, ItemStack controller) {
        boolean[] placed = {false};
        CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inventory -> inventory.getStacksHandler(DroneCurios.SLOT))
                .ifPresent(handler -> {
                    handler.getStacks().setStackInSlot(0, controller);
                    placed[0] = !handler.getStacks().getStackInSlot(0).isEmpty();
                });
        return placed[0];
    }

    private static int count(Item item) {
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int holdCount() {
        int total = 0;
        for (ItemStack stack : CarryLight.read(controller)) total += stack.getCount();
        return total;
    }

    private static ItemStack stack(String path) {
        Item item = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(Corvus.ID, path));
        if (item == null) throw new IllegalStateException("Unregistered item " + path);
        return new ItemStack(item);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[module-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        FAILURES.add(name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        LOG.error("[module-smoke] FAIL {} {}", name, detail == null ? "" : detail);
    }
}
