package dev.corvus;

import dev.corvus.data.CorvusData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * The once-per-second module runtime: the power transaction, the lamp profile and the cargo pickup pass.
 *
 * <p>{@link DroneEntity} calls {@link #second} from its existing {@code tickCount % 20 == 0} block, right
 * after {@link DroneSystems#second}, so a projection settles exactly once per second while it exists.</p>
 *
 * <p><b>Power.</b> The transaction is {@code energy ± (solar − drain) × 20}: the drain is
 * {@link Hardware#drainPerTick} (the {@code drain_watts} of every fitted module that is
 * {@linkplain Hardware#online online}, after its vendor {@code drain_multiplier}) and the supply is
 * {@link Hardware#solarOutput} in the projection's own weather/daylight — the wire-visible figure the
 * wheel prints. Only the settlement applies the {@code output_jitter} of the scrap panel, which is why
 * the wheel keeps showing the nominal number. The result is written back to the battery stack through
 * {@link Hardware#setEnergy} and clamped to {@code [0, capacity]}, so an empty cell stays empty instead
 * of going negative and a full one does not overflow. At zero charge every module is offline
 * ({@link Hardware#powered}) and the drain figure is therefore 0 on the next second: the cell cannot be
 * debited twice.</p>
 *
 * <p><b>Lamp.</b> {@link #lightProfile} packs the fitted lamp's radius, brightness and flicker into the
 * same integer the 1.21 build synched on the entity ({@code radius << 4 | level | 512·flicker}) and
 * {@link LightSync} ships it to the clients tracking the projection. A lamp that is switched off, below
 * the brown-out line, faulted, out of charge, or under water without the {@code underwater} parameter is
 * profile 0 — "低电/离线时自动熄灭" is the same predicate, not a separate timer.</p>
 *
 * <p><b>Cargo.</b> 1.20.1 Forge has no {@code ItemEntityPickupEvent} (the 1.21 event this port's
 * reference implementation posts to let protection mods veto a pickup); {@code EntityItemPickupEvent}
 * there only models a <em>player</em> walking over an item. The equivalent gate is therefore applied
 * inline: distance against {@code pickup_radius}, capacity against {@code slots}, no pickup delay, the
 * item must not be a terminal,
 * and it must not carry the {@value #DELIVERED} mark a delivery leaves behind — that mark is what stops
 * the drone swallowing the stack it just handed over. The hold is never written while its screen is open
 * ({@link CarryLight#viewing}). 1.20.1's {@code ItemEntity} exposes {@code setTarget(UUID)} but no getter,
 * so the 1.21 "reserved for somebody else" half of the test cannot be read back; the delivery mark covers
 * the case that actually happens here.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModuleRuntime {
    /** Power and pickup settle on whole seconds. */
    static final int SECOND = 20;
    /** Lamp-state bit for the scrap headlamp's flicker. */
    static final int LIGHT_FLICKER = 512;
    /** Radius ceiling of the packed profile (5 bits). */
    static final int MAX_RADIUS = 16;
    /** Set by {@link CarryLight#deliver}: a delivered stack must never be picked straight back up. */
    static final String DELIVERED = "dronepal_delivered";
    /** The NBT switch {@link DroneRemote} toggles, spelled here so the order path owns no copy of it. */
    private static final String OFF_LIGHT = DroneRemote.OFF_PREFIX + "light";
    /** Fallback tint of a lamp whose data file carries no colour. */
    private static final int DEFAULT_TINT = 0xFFE7B0;

    /** Last lamp state published per projection, so a quiet lamp costs no packets. */
    private static final Map<UUID, Integer> PUBLISHED = new HashMap<>();
    private static final Map<UUID, Integer> PUBLISHED_TINT = new HashMap<>();

    private ModuleRuntime() {}

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            LightSync.register();
            // Chain, not replace: the scan/medic/cargo orders keep going through DroneSystems.
            Hardware.setIntentHandler(ModuleRuntime::dispatch);
        });
    }

    /**
     * Intent entry point. {@code order.light} is the one order the scan runtime cannot answer (it owns
     * scan and heal), everything else is handed back to the handler {@code CorvusNetwork} installs.
     */
    private static boolean dispatch(ServerPlayer player, String intent) {
        if ("order.light".equals(intent)) return orderLight(player);
        return DroneSystems.executeIntent(player, intent);
    }

    // ---------------------------------------------------------------------------------------------
    // Once per second
    // ---------------------------------------------------------------------------------------------

    /** The whole second: pay for the modules, then publish the lamp, then run the pickup pass. */
    static void second(DroneEntity drone, ServerPlayer owner, ItemStack controller) {
        if (controller.isEmpty() || !(drone.level() instanceof ServerLevel level)) return;
        int delta = settle(level, drone, controller);
        syncLight(drone, lightProfile(drone, controller), tint(controller));
        pickupPhase(level, drone, owner, controller);
        if (delta != 0) {
            // Debug-level only: the terminal and the wheel show the live figure, the log is a trace.
            org.slf4j.LoggerFactory.getLogger("corvus/power").debug(
                    "settled {} FE on {} ({} left)", delta, drone.getUUID(), Hardware.energy(controller));
        }
    }

    /**
     * One second of the power transaction. Returns the signed change written to the cell, so a harness
     * (and a future HUD) can assert the arithmetic without reading NBT twice.
     */
    static int settle(ServerLevel level, DroneEntity drone, ItemStack controller) {
        int capacity = Hardware.capacity(controller);
        if (capacity <= 0) return 0;
        int before = Hardware.energy(controller);
        int drain = Math.max(0, Hardware.drainPerTick(controller)) * SECOND;
        // TODO(grid): the grid pays the drain first and writes the rest into the cell.
        var grid = dev.corvus.power.grid.DroneCharger.service(level, drone, controller, drain);
        drain = Math.max(0, drain - grid.drainPaid());
        int solar = solarPerSecond(level, drone, controller);
        int cellAfterGrid = Hardware.energy(controller);
        int after = Mth.clamp(cellAfterGrid + solar - drain, 0, capacity);
        if (after != cellAfterGrid) Hardware.setEnergy(controller, after);
        return after - before;
    }

    /** FE the fitted photovoltaic modules make in one second, jitter included. */
    static int solarPerSecond(ServerLevel level, DroneEntity drone, ItemStack controller) {
        boolean sky = level.canSeeSky(drone.blockPosition());
        boolean day = level.isDay();
        boolean raining = level.isRaining();
        int perTick = 0;
        for (Hardware.Module module : Hardware.installed(controller)) {
            int nominal = Hardware.solarOutput(module, sky, day, raining);
            if (nominal <= 0) continue;
            perTick += jittered(module, nominal, drone.getRandom());
        }
        return perTick * SECOND;
    }

    /**
     * Applies {@code output_jitter} (the scrap panel's 0.3) to one module's nominal output.
     *
     * <p>Only the settlement jitters: {@link DroneRemote.Status#solar} keeps printing the nominal figure,
     * so the wheel still answers "what does this panel make in this weather" instead of a random sample.</p>
     */
    static int jittered(Hardware.Module module, int nominal, RandomSource random) {
        if (nominal <= 0 || module.params() == null || !module.params().has("output_jitter")) return nominal;
        double jitter = Mth.clamp(module.params().get("output_jitter").getAsDouble(), 0D, 1D);
        if (jitter <= 0D) return nominal;
        return (int) Math.round(nominal * (1D + jitter * (random.nextDouble() * 2D - 1D)));
    }

    // ---------------------------------------------------------------------------------------------
    // Lamp
    // ---------------------------------------------------------------------------------------------

    /**
     * The packed lamp state: {@code radius << 4 | brightness | 512·flicker}, or 0 for "dark".
     *
     * <p>Dark means: no lamp fitted, the wheel switch is off, the module is not online (empty cell,
     * fault, or the brown-out cut), the data file claims no brightness, or the drone is submerged
     * without an {@code underwater} rating.</p>
     */
    static int lightProfile(DroneEntity drone, ItemStack controller) {
        Hardware.Module lamp = module(controller, "light");
        if (lamp == null || drone == null) return 0;
        if (DroneRemote.disabled(controller, "light")) return 0;
        if (!Hardware.online(controller, "light")) return 0;
        if (drone.isInWater() && !lamp.flag("underwater")) return 0;
        int radius = Mth.clamp(lamp.integer("radius"), 1, MAX_RADIUS);
        int brightness = Mth.clamp(lamp.integer("light_level"), 0, 15);
        if (brightness <= 0) return 0;
        return (radius << 4) | brightness | (lamp.flag("flicker") ? LIGHT_FLICKER : 0);
    }

    /** The lamp colour from {@code params.tint}, as {@code 0xRRGGBB}. */
    static int tint(ItemStack controller) {
        Hardware.Module lamp = module(controller, "light");
        if (lamp == null || lamp.params() == null || !lamp.params().has("tint")) return DEFAULT_TINT;
        String text = lamp.params().get("tint").getAsString().trim();
        if (text.startsWith("#")) text = text.substring(1);
        try {
            return (int) (Long.parseLong(text, 16) & 0xFFFFFFL);
        } catch (NumberFormatException unparsable) {
            return DEFAULT_TINT;
        }
    }

    /**
     * Publishes the lamp state to the clients tracking the projection.
     *
     * <p>A <em>lit</em> lamp is re-announced every second even when nothing changed: a client that logged
     * in, changed dimension or started tracking the projection late has an empty lamp cache, and waiting
     * for the next state change would leave it in the dark. One small packet per second per lit drone is
     * cheap; a dark lamp is announced once (a repeat of "dark" carries no information).</p>
     */
    static void syncLight(DroneEntity drone, int profile, int rgb) {
        UUID id = drone.getUUID();
        Integer lastProfile = PUBLISHED.get(id);
        Integer lastTint = PUBLISHED_TINT.get(id);
        boolean unchanged = lastProfile != null && lastProfile == profile && lastTint != null && lastTint == rgb;
        if (unchanged && profile == 0) return;
        PUBLISHED.put(id, profile);
        PUBLISHED_TINT.put(id, rgb);
        LightSync.publish(drone, profile, rgb);
    }

    /** Recomputes and publishes the lamp immediately, so a switch shows up before the next second. */
    static void refreshLight(ServerPlayer player, ItemStack controller) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone == null) return;
        syncLight(drone, lightProfile(drone, controller), tint(controller));
    }

    /**
     * {@code order.light}: the lamp switch, toggled.
     *
     * <p>The 1.21 build's {@code order.light} only ever <em>enables</em> the lamp, because its wheel owns
     * the toggle. This port routes both the wheel sector and the dialogue order through the same switch, so
     * the order toggles: off → on (accepted only when the lamp really comes on), on → off. Both paths write
     * the same {@code dronepal_off_light} key the wheel writes, so they cannot disagree.</p>
     */
    static boolean orderLight(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return false;
        ItemStack controller = Hardware.controller(player);
        if (controller.isEmpty() || module(controller, "light") == null) return false;
        if (Hardware.energy(controller) <= 0) {
            player.displayClientMessage(Component.translatable("dronepal.light.no_power"), true);
            return false;
        }
        CorvusData data = CorvusData.of(controller);
        if (data.getBoolean(OFF_LIGHT)) {
            // Switched off: turn it on, but only report success when it will actually light up.
            data.setBoolean(OFF_LIGHT, false);
            boolean online = Hardware.online(controller, "light");
            player.displayClientMessage(Component.translatable(
                    online ? "dronepal.light.on" : "dronepal.light.no_power"), true);
            refreshLight(player, controller);
            return online;
        }
        data.setBoolean(OFF_LIGHT, true);
        player.displayClientMessage(Component.translatable("dronepal.light.off"), true);
        refreshLight(player, controller);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Cargo pickup
    // ---------------------------------------------------------------------------------------------

    /** Runs the automatic pickup pass; returns true when anything moved into the hold. */
    static boolean pickupPhase(ServerLevel level, DroneEntity drone, ServerPlayer owner, ItemStack controller) {
        Hardware.Module hold = module(controller, "carry");
        if (hold == null || owner == null) return false;
        int limit = CarryLight.slots(controller);
        if (limit <= 0 || !Hardware.online(controller, "carry")) return false;
        // The screen owns the tag while it is open; writing behind it would desync or duplicate stacks.
        if (CarryLight.viewing(owner, controller)) return false;
        int radius = Mth.clamp(hold.integer("pickup_radius"), 1, 16);
        double squared = (double) radius * radius;
        List<ItemStack> cargo = new ArrayList<>(CarryLight.read(controller));
        boolean changed = false;
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                drone.getBoundingBox().inflate(radius),
                drop -> drop.isAlive() && !drop.hasPickUpDelay() && !drop.getItem().isEmpty());
        for (ItemEntity drop : drops) {
            if (drop.distanceToSqr(drone) > squared) continue;
            // A delivery's overflow drop is aimed at its owner; the mark below is what stops the drone
            // picking it straight back up (1.20.1's ItemEntity has setTarget but no getter for it, so the
            // mark — which CarryLight.deliver also writes — is the checkable half of that pair).
            ItemStack item = drop.getItem();
            if (item.is(Corvus.DRONE.get())) continue;
            if (drop.getPersistentData().getBoolean(DELIVERED)) continue;
            ItemStack moving = item.copy();
            int before = moving.getCount();
            store(cargo, moving, limit);
            int moved = before - moving.getCount();
            if (moved <= 0) continue;
            changed = true;
            if (moving.isEmpty()) drop.discard();
            else drop.setItem(moving);
        }
        if (!changed) return false;
        if (hold.flag("sort")) {
            cargo.sort(Comparator.comparing(stack -> String.valueOf(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        }
        CarryLight.save(controller, cargo);
        if (hold.flag("auto_deliver")) {
            CarryLight.deliver(owner, controller, CarryLight.read(controller), false);
        }
        return true;
    }

    /**
     * Moves as much of {@code source} into {@code slots} as fits: matching partial stacks first, then new
     * slots up to {@code limit}. A hold shrunk by a data pack never loses what it already carries, because
     * nothing above {@code limit} is touched.
     */
    static void store(List<ItemStack> slots, ItemStack source, int limit) {
        for (int index = 0; index < Math.min(slots.size(), limit) && !source.isEmpty(); index++) {
            ItemStack target = slots.get(index);
            if (target.isEmpty() || !ItemStack.isSameItemSameTags(target, source)) continue;
            int room = Math.max(0, target.getMaxStackSize() - target.getCount());
            int moved = Math.min(source.getCount(), room);
            if (moved <= 0) continue;
            target.grow(moved);
            source.shrink(moved);
        }
        while (!source.isEmpty() && slots.size() < limit) {
            slots.add(source.split(source.getMaxStackSize()));
        }
    }

    /** The fitted module of one family, or null. */
    static Hardware.Module module(ItemStack controller, String family) {
        for (Hardware.Module module : Hardware.installed(controller)) {
            if (module.family().equals(family)) return module;
        }
        return null;
    }
}
