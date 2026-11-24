package dev.corvus;

import com.mojang.authlib.GameProfile;
import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance run for the TaCZ gun backend.
 *
 * <p>Inactive unless {@code -Dcorvus.tacztest=1} ({@code -PcorvusTacz=1} on the server run). It
 * drives a dedicated server with no human input at all:</p>
 *
 * <ol>
 *   <li>builds a controller with a combat module, a link module, a real TaCZ gun and real TaCZ
 *       ammunition, and puts it in a synthetic pilot's Curios {@code drone} slot (no player ever
 *       joins);</li>
 *   <li>deploys a {@link DroneEntity} for that pilot, clears a firing lane and floats a high-health
 *       zombie in it, then lets the drone's own weapon bus run;</li>
 *   <li>asserts the magazine in the gun's NBT went <em>down</em>, the reserve was <em>drawn</em> by a
 *       reload through the entity's item-handler capability, and the zombie lost health;</li>
 *   <li>asserts TaCZ's {@code EntityHurtByGunEvent} / {@code EntityKillByGunEvent} reached the
 *       attacker-attributed path and the link module delivered its hit/kill lines;</li>
 *   <li>cuts the battery to zero, then removes the combat module, asserting nothing is consumed.</li>
 * </ol>
 *
 * <p>The pilot is a Forge {@code FakePlayer} registered in {@code PlayerList}'s UUID index: the
 * drone, the engagement policy and the Curios controller lookup all resolve their owner through that
 * index, and a headless dedicated server has no real player to put there. Nothing here runs without
 * the system property.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class TaczSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.tacztest"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-tacz-smoke");

    // TaCZ 1.1.8 registers ONE gun item (tacz:modern_kinetic_gun) and ONE ammo item (tacz:ammo); the
    // concrete gun/pack id lives in their NBT, so stacks are built through the api builders.
    private static final String GUN_ID = "tacz:m4a1";
    private static final String AMMO_ID = "tacz:556x45";
    private static final int MAGAZINE = 6;
    private static final int RESERVE_ROUNDS = 90;
    private static final int FIRE_TICKS = 200;
    private static final int KILL_TICKS = 80;
    private static final int IDLE_TICKS = 60;

    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<net.minecraft.world.level.ChunkPos> forced = new ArrayList<>();

    private static int tick;
    private static int step;
    private static int wait;
    private static int passed;
    private static ServerLevel level;
    private static ServerPlayer pilot;
    private static DroneEntity drone;
    private static Monster zombie;
    private static ItemStack controller = ItemStack.EMPTY;
    private static int magazineBefore;
    /** Lowest magazine count seen while it was firing: the round count really did drop. */
    private static int minMagazine = Integer.MAX_VALUE;
    private static int reserveBefore;
    private static int reportsBeforeKill;
    private static float zombieHealthBefore;
    private static int lowPowerStored;
    private static int lowPowerHeld;
    private static int noModuleStored;

    private TaczSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.isStopped()) return;
        if (++tick < 20) return;
        if (step == 0) {
            step = run(server) ? 1 : 9;
            wait = 0;
            return;
        }
        if (step == 9) return;
        if (step <= 2 && drone != null) {
            int held = TestKit.magazine(drone.getMainHandItem());
            if (held >= 0) minMagazine = Math.min(minMagazine, held);
        }
        wait++;
        if (wait % 20 == 0) debug();
        switch (step) {
            case 1 -> {
                if (wait < FIRE_TICKS) return;
                verifyFiring();
                step = 2;
                wait = 0;
                prepareKill();
            }
            case 2 -> {
                if (wait < KILL_TICKS) return;
                verifyKill();
                step = 3;
                wait = 0;
                cutPower();
            }
            case 3 -> {
                if (wait < IDLE_TICKS) return;
                verifyLowPower();
                step = 4;
                wait = 0;
                removeCombatModule();
            }
            case 4 -> {
                if (wait < IDLE_TICKS) return;
                verifyNoModule();
                step = 5;
                finish(server);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ setup

    private static boolean run(MinecraftServer server) {
        LOG.info("[tacz-smoke] ==== TaCZ gun-backend acceptance run ====");
        if (!CombatSlots.available()) {
            fail("tacz.loaded", "TaCZ is not on the runtime classpath (use -PcorvusTacz=1)");
            finish(server);
            return false;
        }
        check("tacz.loaded", true, "TaCZ " + net.minecraftforge.fml.ModList.get()
                .getModContainerById("tacz").map(c -> c.getModInfo().getVersion().toString()).orElse("?"));
        level = server.overworld();
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            server.setDifficulty(Difficulty.NORMAL, true);
            LOG.info("[tacz-smoke] difficulty peaceful -> normal so the target mob survives");
        }
        pilot = fakePilot(server);
        if (pilot == null) {
            finish(server);
            return false;
        }
        controller = buildController();
        if (controller.isEmpty()) {
            finish(server);
            return false;
        }
        if (!wear(controller)) {
            finish(server);
            return false;
        }
        buildArena();
        spawnZombie();
        spawnDrone();
        TestKit.listen();
        report("setup");
        return true;
    }

    private static ServerPlayer fakePilot(MinecraftServer server) {
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("corvus-tacz-smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "TaczSmoke");
        ServerPlayer fake = net.minecraftforge.common.util.FakePlayerFactory.get(level, profile);
        BlockPos base = level.getSharedSpawnPos();
        fake.moveTo(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5, 0F, 0F);
        try {
            Field field = null;
            for (String name : new String[] {"playersByUUID", "f_11196_"}) {
                try {
                    field = net.minecraft.server.players.PlayerList.class.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException ignored) {
                    // dev runtime is mojmap; the SRG name is only a safety net for production runs
                }
            }
            if (field == null) {
                fail("pilot.index", "no playersByUUID field on PlayerList");
                return null;
            }
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, ServerPlayer> index = (Map<UUID, ServerPlayer>) field.get(server.getPlayerList());
            index.put(fake.getUUID(), fake);
        } catch (Throwable error) {
            fail("pilot.index", "reflection failed: " + error);
            return null;
        }
        boolean visible = server.getPlayerList().getPlayer(fake.getUUID()) == fake;
        check("pilot.index", visible, "getPlayer(uuid) resolved=" + visible);
        LOG.info("[tacz-smoke] synthetic pilot uuid={} pos={} level={}", fake.getUUID(), fake.position(),
                fake.serverLevel().dimension().location());
        return fake;
    }

    private static ItemStack buildController() {
        ItemStack stack = Frames.create("peregrine");
        UUID serial = DroneItem.ensureSerial(stack);
        List<ItemStack> parts = new ArrayList<>();
        parts.add(item("omd_bat_200"));
        parts.add(Hardware.moduleStack("combat_mk2"));
        // The link module is what carries the hit/kill lines and the reserve telemetry.
        parts.add(Hardware.moduleStack("link_mk2"));
        ItemStack gun = TestKit.gun(GUN_ID, MAGAZINE);
        if (gun.isEmpty()) {
            fail("gun.item", GUN_ID + " has no loaded gun index (gun pack missing?)");
            return ItemStack.EMPTY;
        }
        check("gun.index", TestKit.hasGunIndex(GUN_ID), "index for " + GUN_ID);
        parts.add(gun);
        ItemStack ammo = TestKit.ammo(AMMO_ID, RESERVE_ROUNDS);
        if (ammo.isEmpty()) {
            fail("ammo.item", AMMO_ID + " is not a registered ammo stack");
            return ItemStack.EMPTY;
        }
        parts.add(ammo);
        Hardware.save(stack, parts);
        Hardware.setEnergy(stack, Hardware.capacity(stack));
        // The default engage pack is retaliate (radius 0), which never acquires anything on its own.
        Engagement.setMode(stack, "aggressive");
        LOG.info("[tacz-smoke] controller parts after NBT round trip: {}", describeParts(stack));
        check("controller.parts", !Hardware.contents(stack).isEmpty() && Hardware.tier(stack, "combat") == 2,
                "parts=" + parts.size() + " combatTier=" + Hardware.tier(stack, "combat")
                        + " linkTier=" + Hardware.tier(stack, "link"));
        ItemStack probe = CombatSlots.mounted(stack);
        LOG.info("[tacz-smoke] predicates: mountedGun={} isGun={} isAmmo(gun)={} ammoParts={}",
                TestKit.describe(probe), CombatSlots.isGun(probe), CombatSlots.isAmmo(probe),
                CombatSlots.ammo(stack).size());
        int mag = TestKit.magazine(mountedGun(stack));
        check("gun.magazine", mag == MAGAZINE, "mag=" + mag);
        LOG.info("[tacz-smoke] controller serial={} energy={}/{} gun={} magazine={} reserve={}",
                serial, Hardware.energy(stack), Hardware.capacity(stack),
                TestKit.describe(gun), mag, TestKit.reserve(Hardware.contents(stack), gun));
        return stack;
    }

    private static boolean wear(ItemStack stack) {
        boolean[] placed = {false};
        top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(pilot).resolve()
                .flatMap(inventory -> inventory.getStacksHandler(DroneCurios.SLOT))
                .ifPresent(handler -> {
                    handler.getStacks().setStackInSlot(0, stack);
                    placed[0] = !handler.getStacks().getStackInSlot(0).isEmpty();
                });
        boolean slot = DroneCurios.slotPresent(pilot);
        check("controller.worn", placed[0], "slotPresent=" + slot);
        if (!placed[0]) LOG.error("[tacz-smoke] could not put the controller in the Curios drone slot (slotPresent={})", slot);
        return placed[0];
    }

    /**
     * Clears a roofed firing lane so line of sight and daylight cannot decide the test.
     *
     * <p>The chunks are force-loaded for the run: the pilot is a FakePlayer with no connection, so
     * nothing would keep the arena entity-ticking once vanilla decides the chunk is idle, and a drone
     * that stops ticking looks exactly like a drone that stopped firing.</p>
     */
    private static void buildArena() {
        BlockPos base = level.getSharedSpawnPos();
        for (int dx = -8; dx <= 12; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = 1; dy <= 7; dy++) {
                    level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(base.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                // Roof: no open sky, so a hostile cannot burn and the lane stays lit by the drone only.
                level.setBlockAndUpdate(base.offset(dx, 8, dz), Blocks.STONE.defaultBlockState());
            }
        }
        for (int cx = base.getX() - 16; cx <= base.getX() + 16; cx += 16) {
            for (int cz = base.getZ() - 16; cz <= base.getZ() + 16; cz += 16) {
                level.setChunkForced(cx >> 4, cz >> 4, true);
                forced.add(new net.minecraft.world.level.ChunkPos(cx >> 4, cz >> 4));
            }
        }
        LOG.info("[tacz-smoke] cleared a roofed firing lane around {} and force-loaded {} chunks",
                base, forced.size());
    }

    private static void spawnZombie() {
        Vec3 station = DroneEntity.station(pilot);
        zombie = EntityType.SPIDER.create(level);
        if (zombie == null) {
            fail("target.spawn", "EntityType.SPIDER.create returned null");
            return;
        }
        // Floating, high health and no AI: the only thing that can move the health bar is the drone.
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setPersistenceRequired();
        var health = zombie.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(300.0D);
        zombie.setHealth(300.0F);
        zombie.moveTo(station.x + 6.0D, station.y, station.z + 0.5D, 0F, 0F);
        boolean added = level.addFreshEntity(zombie);
        check("zombie.spawn", added && zombie.isAlive(), "pos=" + zombie.position() + " hp=" + zombie.getHealth());
    }

    private static void spawnDrone() {
        UUID serial = DroneItem.serial(controller);
        drone = new DroneEntity(Corvus.ENTITY.get(), level);
        drone.setUUID(serial);
        drone.setOwner(pilot.getUUID());
        drone.setFrame(Frames.id(controller));
        drone.setPaint(Frames.paint(controller));
        Vec3 station = DroneEntity.station(pilot);
        drone.moveTo(station.x, station.y, station.z, 0F, 0F);
        // The projection's own tick gate: the flying serial must be this drone's.
        pilot.getPersistentData().putUUID(CorvusKeys.ACTIVE, serial);
        boolean added = level.addFreshEntity(drone);
        check("drone.spawn", added && !drone.isRemoved(), "serial=" + serial + " pos=" + drone.position());
        check("drone.armed", DroneCombat.automatic(controller), "safe flag="
                + CorvusData.of(controller).getBoolean(DroneCombat.SAFE_KEY));
        // The weapon bus adopts the gun on its first tick, so seed the baseline from the controller
        // copy (the gun the drone is about to pick up) rather than from the still-empty hand.
        magazineBefore = TestKit.magazine(mountedGun(controller));
        reserveBefore = TestKit.reserve(Hardware.contents(controller), mountedGun(controller));
        zombieHealthBefore = zombie == null ? 0F : zombie.getHealth();
        LOG.info("[tacz-smoke] drone in world pos={} mainhand={} magazine={} reserve={} zombie={} hp={}",
                drone.position(), TestKit.describe(drone.getMainHandItem()), magazineBefore, reserveBefore,
                zombie == null ? "none" : zombie.getUUID(), zombieHealthBefore);
    }

    // ------------------------------------------------------------------ phases

    private static void verifyFiring() {
        int held = TestKit.magazine(drone.getMainHandItem());
        int stored = TestKit.magazine(mountedGun(controller));
        int reserve = TestKit.reserve(Hardware.contents(controller), mountedGun(controller));
        float hp = zombie == null ? 0F : zombie.getHealth();
        report("firing");
        LOG.info("[tacz-smoke] after {} ticks: magazine {} -> {} (controller copy {}), reserve {} -> {}, "
                        + "zombie hp {} -> {}, target={}, lastResult={}, mode={}, mountPitch={}, hurtEvents={}, "
                        + "minMagazine={}",
                FIRE_TICKS, magazineBefore, held, stored, reserveBefore, reserve,
                zombieHealthBefore, hp, drone.getTarget() == null ? "none" : drone.getTarget().getName().getString(),
                drone.combat.lastResult, drone.combat.modeName(), DroneCombat.mountPitch(drone),
                TestKit.hurtEvents(), minMagazine);
        // The magazine really emptied and was refilled from the drone's own reserve, so the assertion
        // is on the lowest count observed, not on the final one.
        check("fire.magazine_down", minMagazine < magazineBefore,
                magazineBefore + " -> min " + minMagazine + " -> final " + held);
        check("fire.zombie_damaged", hp < zombieHealthBefore, zombieHealthBefore + " -> " + hp);
        check("fire.shots", TaczLink.shots > 0, "shots=" + TaczLink.shots + " lastResult=" + drone.combat.lastResult);
        // 6-round magazine + a 90-round reserve: at least one reload has to draw from the drone's own
        // inventory, which only happens through the item-handler capability.
        check("fire.reserve_drawn", reserve < reserveBefore, reserveBefore + " -> " + reserve);
        check("report.hit_event", TestKit.hurtEvents() > 0, "EntityHurtByGunEvent.Post seen=" + TestKit.hurtEvents());
        check("report.hit_line", TaczLink.reports > 0, "delivered lines=" + TaczLink.reports);
    }

    private static void prepareKill() {
        reportsBeforeKill = TaczLink.reports;
        // One round from now on: the next hit is lethal, which is what EntityKillByGunEvent reports.
        if (zombie != null) zombie.setHealth(4.0F);
        LOG.info("[tacz-smoke] kill phase: zombie hp forced to 4.0, waiting {} ticks", KILL_TICKS);
    }

    private static void verifyKill() {
        boolean dead = zombie == null || zombie.isDeadOrDying() || zombie.getHealth() <= 0F;
        LOG.info("[tacz-smoke] kill phase after {} ticks: zombie dead={} hp={} killEvents={} reports={} (was {})",
                KILL_TICKS, dead, zombie == null ? -1F : zombie.getHealth(), TestKit.killEvents(),
                TaczLink.reports, reportsBeforeKill);
        check("report.kill_event", TestKit.killEvents() > 0, "EntityKillByGunEvent seen=" + TestKit.killEvents());
        check("report.kill_line", TaczLink.reports > reportsBeforeKill,
                "delivered lines " + reportsBeforeKill + " -> " + TaczLink.reports);
    }

    private static void cutPower() {
        lowPowerHeld = TestKit.magazine(drone.getMainHandItem());
        lowPowerStored = TestKit.magazine(mountedGun(controller));
        Hardware.setEnergy(controller, 0);
        LOG.info("[tacz-smoke] battery drained to {}/{} (combat online={}), magazine stays at {} for {} ticks",
                Hardware.energy(controller), Hardware.capacity(controller),
                Hardware.online(controller, "combat"), lowPowerStored, IDLE_TICKS);
    }

    private static void verifyLowPower() {
        int held = TestKit.magazine(drone.getMainHandItem());
        int stored = TestKit.magazine(mountedGun(controller));
        LOG.info("[tacz-smoke] low power: held magazine {} -> {} (stored {} -> {}), energy={}/{}, combat online={}",
                lowPowerHeld, held, lowPowerStored, stored,
                Hardware.energy(controller), Hardware.capacity(controller),
                Hardware.online(controller, "combat"));
        check("safety.low_power_no_ammo", stored == lowPowerStored && held <= lowPowerHeld,
                "held " + lowPowerHeld + " -> " + held + ", stored " + lowPowerStored + " -> " + stored);
    }

    private static void removeCombatModule() {
        noModuleStored = TestKit.magazine(mountedGun(controller));
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        List<ItemStack> parts = Hardware.contents(controller);
        parts.removeIf(part -> {
            Hardware.Module module = Hardware.definition(part);
            return module != null && module.family().equals("combat");
        });
        Hardware.save(controller, parts);
        LOG.info("[tacz-smoke] combat module removed (tier now {}), battery recharged to {}/{}",
                Hardware.tier(controller, "combat"), Hardware.energy(controller), Hardware.capacity(controller));
    }

    private static void verifyNoModule() {
        int stored = TestKit.magazine(mountedGun(controller));
        LOG.info("[tacz-smoke] no combat module: stored magazine {} -> {}, combat tier={}, mainhand={}",
                noModuleStored, stored, Hardware.tier(controller, "combat"),
                TestKit.describe(drone.getMainHandItem()));
        check("safety.no_module_no_ammo", stored == noModuleStored, noModuleStored + " -> " + stored);
        check("safety.no_module_no_gun", drone.getMainHandItem().isEmpty(),
                "mainhand=" + TestKit.describe(drone.getMainHandItem()));
    }

    private static String describeParts(ItemStack stack) {
        StringBuilder text = new StringBuilder();
        for (ItemStack part : Hardware.contents(stack)) {
            if (text.length() > 0) text.append(" | ");
            text.append(TestKit.describe(part)).append(" x").append(part.getCount());
        }
        return text.toString();
    }

    /** The gun part currently stored in the controller, or EMPTY. */
    private static ItemStack mountedGun(ItemStack controller) {
        for (ItemStack part : Hardware.contents(controller)) {
            if (CombatSlots.isGun(part)) return part;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ output

    /** One compact state line every second, so a failure shows where the run diverged. */
    private static void debug() {
        LOG.info("[tacz-smoke] t+{} step={} droneTick={} droneAlive={} target={} mag(held)={} mag(stored)={} "
                        + "reserve={} zombieHp={} zombieDead={} shots={} hurt={} kill={} reports={} energy={} combatOnline={} tier={}",
                wait, step, drone == null ? -1 : drone.tickCount, drone == null || !drone.isRemoved(),
                drone == null || drone.getTarget() == null ? "none" : drone.getTarget().getName().getString(),
                TestKit.magazine(drone == null ? ItemStack.EMPTY : drone.getMainHandItem()),
                TestKit.magazine(mountedGun(controller)),
                TestKit.reserve(Hardware.contents(controller), mountedGun(controller)),
                zombie == null ? -1F : zombie.getHealth(), zombie != null && zombie.isDeadOrDying(),
                TaczLink.shots, TestKit.hurtEvents(), TestKit.killEvents(), TaczLink.reports,
                Hardware.energy(controller), Hardware.online(controller, "combat"),
                Hardware.tier(controller, "combat"));
    }

    private static void report(String label) {
        LOG.info("[tacz-smoke] == {} == droneAlive={} tick={} inWorld={} controllerGun={} reserve={}",
                label, drone != null && !drone.isRemoved(), drone == null ? -1 : drone.tickCount,
                drone != null && level.getEntitiesOfClass(DroneEntity.class, drone.getBoundingBox().inflate(64))
                        .contains(drone),
                TestKit.describe(drone == null ? ItemStack.EMPTY : drone.getMainHandItem()),
                drone == null ? 0 : TestKit.reserve(Hardware.contents(controller), mountedGun(controller)));
    }

    private static void finish(MinecraftServer server) {
        step = 9;
        for (net.minecraft.world.level.ChunkPos pos : forced) level.setChunkForced(pos.x, pos.z, false);
        LOG.info("[tacz-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[tacz-smoke] FAILED {}", failure);
        if (FAILURES.isEmpty()) LOG.info("[tacz-smoke] RESULT: PASS");
        else LOG.error("[tacz-smoke] RESULT: FAIL");
        server.halt(false);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[tacz-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        FAILURES.add(name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static ItemStack item(String path) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, path));
        if (item == null) throw new IllegalStateException("Unregistered item " + path);
        return new ItemStack(item);
    }

    /**
     * The TaCZ-typed part of the harness, isolated in its own class so loading this file never
     * resolves a TaCZ class when the backend is absent.
     */
    private static final class TestKit {
        private static int hurtEvents;
        private static int killEvents;

        static int hurtEvents() {
            return hurtEvents;
        }

        static int killEvents() {
            return killEvents;
        }

        /** Counts TaCZ's gun events as the drone's attack would see them. */
        static void listen() {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    (com.tacz.guns.api.event.common.EntityHurtByGunEvent.Post event) -> {
                        if (event.getLogicalSide().isServer()
                                && event.getAttacker() instanceof DroneEntity
                                && event.getHurtEntity() == zombie) {
                            hurtEvents++;
                        }
                    });
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    (com.tacz.guns.api.event.common.EntityKillByGunEvent event) -> {
                        if (event.getLogicalSide().isServer()
                                && event.getAttacker() instanceof DroneEntity
                                && event.getKilledEntity() == zombie) {
                            killEvents++;
                        }
                    });
        }

        static boolean hasGunIndex(String id) {
            ResourceLocation gunId = ResourceLocation.tryParse(id);
            return gunId != null && com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunId).isPresent();
        }

        static ItemStack gun(String id, int magazine) {
            ResourceLocation gunId = ResourceLocation.tryParse(id);
            if (gunId == null) return ItemStack.EMPTY;
            return com.tacz.guns.api.item.builder.GunItemBuilder.create()
                    .setId(gunId)
                    .setCount(1)
                    .setAmmoCount(magazine)
                    .setAmmoInBarrel(true)
                    .build();
        }

        static ItemStack ammo(String id, int count) {
            ResourceLocation ammoId = ResourceLocation.tryParse(id);
            if (ammoId == null) return ItemStack.EMPTY;
            return com.tacz.guns.api.item.builder.AmmoItemBuilder.create()
                    .setId(ammoId)
                    .setCount(count)
                    .build();
        }

        static int magazine(ItemStack stack) {
            com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            return gun == null ? -1 : gun.getCurrentAmmoCount(stack);
        }

        /** Rounds left in the drone's own reserve stacks for the mounted gun. */
        static int reserve(List<ItemStack> parts, ItemStack gun) {
            int total = 0;
            for (ItemStack part : parts) {
                if (part.isEmpty()) continue;
                if (part.getItem() instanceof com.tacz.guns.api.item.IAmmo loose) {
                    if (!gun.isEmpty() && loose.isAmmoOfGun(gun, part)) total += part.getCount();
                } else if (part.getItem() instanceof com.tacz.guns.api.item.IAmmoBox box) {
                    if (!gun.isEmpty() && box.isAmmoBoxOfGun(gun, part)) total += box.getAmmoCount(part);
                }
            }
            return total;
        }

        static String describe(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return "empty";
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            return id + (gun == null ? ""
                    : "[gun=" + gun.getGunId(stack) + ", mag=" + gun.getCurrentAmmoCount(stack) + "]");
        }
    }
}
