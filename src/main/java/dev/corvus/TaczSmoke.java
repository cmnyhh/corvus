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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
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
 *   <li>empties the magazine through the gun's own NBT and asserts the 装填 transaction is now a
 *       <em>locked</em> one: {@code RELOADING} for the gun's reload time, no round and no magazine
 *       change while it runs, a ~6° gimbal dip on the synced {@code aimPitch}, one reload sound, and
 *       firing again once the lock expires;</li>
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
    /** Firing window for each "player stands on the firing line" scenario. */
    private static final int BLOCK_TICKS = 80;
    /**
     * 装填锁定 window: long enough to spend a burst, hit {@code NO_AMMO}, feed, sit out the gun's own
     * reload time and fire again afterwards.
     */
    private static final int RELOAD_PHASE_TICKS = 180;
    /** {@code EngagementPreferences}'s controller-independent opt-in flag on the owner. */
    private static final String PVP_OPT_IN = "dronepal_pvp";
    /** Hitpoints the fake players get so a failing invocation cannot kill them mid-run. */
    private static final double PLAYER_HEALTH = 300.0D;

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

    // ---- difference ①: a drone round must pass through any player that is not a legal target ----
    /** Second fake player: unrelated to the owner (no team, no grudge) — never a legal target. */
    private static ServerPlayer stranger;
    /** Third fake player: the one legal PvP target (owner opted in, drone retaliating). */
    private static ServerPlayer rival;
    private static Monster blockedTarget;
    private static Monster strangerTarget;
    private static Monster controlTarget;

    private static float ownerHealthBefore;
    private static float strangerHealthBefore;
    private static float rivalHealthBefore;
    private static float blockedTargetHealthBefore;
    private static float strangerTargetHealthBefore;
    private static float controlTargetHealthBefore;
    private static int minBlockedMagazine = Integer.MAX_VALUE;
    private static int minStrangerMagazine = Integer.MAX_VALUE;
    private static int minControlMagazine = Integer.MAX_VALUE;
    private static int blockedMagazineBefore;
    private static int strangerMagazineBefore;
    private static int controlMagazineBefore;
    private static int shotsBeforeBlocked;
    private static int shotsBeforeStranger;
    private static int shotsBeforeControl;
    private static int shotsBeforeRival;
    private static int rivalAllowedBefore;
    private static int rivalRefusedBefore;
    /** True when the fake players really lose health, i.e. the "no damage" checks mean something. */
    private static boolean playersDamageable = true;

    // ---- 装填锁定 / 云台反馈 / 装填音效 ------------------------------------------------------------
    /** Shots / locks / sounds at the start of the reload phase, so the phase can be judged on deltas. */
    private static int reloadShotsBefore;
    private static int reloadsBefore;
    private static int reloadSoundsBefore;
    private static int reloadMagBefore;
    /** Drone tick the lock was first seen on, and the tick the lock was first seen to be gone. */
    private static int reloadLockStart = -1;
    private static int reloadLockEnd = -1;
    private static int reloadResumedAt = -1;
    /** Ticks the lock was observed for; the gun's own reload time is the expectation. */
    private static int reloadLockTicks;
    /** Lock length the bus declared for this reload (0 when never read). */
    private static int reloadDeclared;
    /** Shots / magazine at the lock start: neither may move while the lock runs. */
    private static int reloadStartShots;
    private static int reloadStartMag;
    /** Ticks on which the lock leaked a round or a magazine change. */
    private static int reloadViolations;
    /** Peak "aimPitch minus the pitch the geometry asks for" during the dip window. */
    private static float reloadDipPeak;
    /** How many sampled ticks showed the dip — the kick is supposed to last {@code RELOAD_DIP_TICKS}. */
    private static int reloadDipWindow;
    /** Same measurement once the dip window is over: the gimbal must be back on the target. */
    private static float reloadDipAfterKick;
    /** The same measurement with no lock running: the baseline the dip is compared against. */
    private static float aimBaselineDeviation;

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
        if (drone != null && step >= 1 && step <= 6) {
            int held = TestKit.magazine(drone.getMainHandItem());
            if (held >= 0) {
                minMagazine = Math.min(minMagazine, held);
                switch (step) {
                    case 3 -> minBlockedMagazine = Math.min(minBlockedMagazine, held);
                    case 4 -> minStrangerMagazine = Math.min(minStrangerMagazine, held);
                    case 5 -> minControlMagazine = Math.min(minControlMagazine, held);
                    default -> {
                    }
                }
            }
        }
        if (step == 6 && rival != null && drone != null) {
            // Keep the retaliation window open: the drone's target selection re-reads this each tick.
            drone.setLastHurtByMob(rival);
        }
        // The unlocked baseline for the gimbal check: with a live target and no lock running, aimPitch
        // must sit on the geometry (that is what makes "+6° during the lock" a measurement, not a claim).
        if ((step == 1 || step == 11) && drone != null && drone.getTarget() != null
                && !DroneCombat.reloading(drone)) {
            aimBaselineDeviation = Math.max(aimBaselineDeviation,
                    Math.abs(DroneCombat.aimPitch(drone) - pitchToTarget()));
        }
        wait++;
        if (wait % 20 == 0) debug();
        switch (step) {
            case 1 -> {
                if (wait < FIRE_TICKS) return;
                verifyFiring();
                // 装填: the firing phase already fed the magazine at least once; this phase proves the
                // lock itself, on a magazine this harness empties on purpose.
                step = 11;
                wait = 0;
                prepareReload();
            }
            case 11 -> {
                observeReload();
                if (wait < RELOAD_PHASE_TICKS) return;
                verifyReload();
                step = 2;
                wait = 0;
                prepareKill();
            }
            case 2 -> {
                if (wait < KILL_TICKS) return;
                verifyKill();
                step = 3;
                wait = 0;
                prepareBlocked();
            }
            case 3 -> {
                if (wait < BLOCK_TICKS) return;
                verifyBlocked();
                step = 4;
                wait = 0;
                prepareStranger();
            }
            case 4 -> {
                if (wait < BLOCK_TICKS) return;
                verifyStranger();
                step = 5;
                wait = 0;
                prepareControl();
            }
            case 5 -> {
                if (wait < BLOCK_TICKS) return;
                verifyControl();
                step = 6;
                wait = 0;
                prepareRival(server);
            }
            case 6 -> {
                if (wait < BLOCK_TICKS) return;
                verifyRival();
                step = 7;
                wait = 0;
                cutPower();
            }
            case 7 -> {
                if (wait < IDLE_TICKS) return;
                verifyLowPower();
                step = 8;
                wait = 0;
                removeCombatModule();
            }
            case 8 -> {
                if (wait < IDLE_TICKS) return;
                verifyNoModule();
                step = 9;
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
        placeActors(server);
        TestKit.listen();
        report("setup");
        return true;
    }

    private static ServerPlayer fakePilot(MinecraftServer server) {
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("corvus-tacz-smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "TaczSmoke");
        ServerPlayer fake = new SmokePlayer(level, profile);
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

    // ------------------------------------------------- 装填锁定 / 云台反馈 / 装填音效
    //
    // The feed used to happen on the same tick the gun ran dry and the round it loaded was fired
    // immediately — the drone looked like it had infinite ammo. The phase below empties the magazine
    // (through the gun's own NBT, the very slot TaCZ decrements) and then watches the bus:
    //   ① RELOADING is published and nothing leaves the barrel while the lock runs;
    //   ② the lock lasts the gun pack's own empty-reload time;
    //   ③ the gimbal takes the kick: aimPitch sits RELOAD_DIP_DEG below the geometry for the first
    //      RELOAD_DIP_TICKS tick and returns afterwards;
    //   ④ a reload sound was dispatched;
    //   ⑤ firing resumes once the lock expires.

    private static void prepareReload() {
        reloadShotsBefore = TaczLink.shots;
        reloadsBefore = TaczLink.reloads;
        reloadSoundsBefore = TaczLink.reloadSounds;
        reloadLockStart = -1;
        reloadLockEnd = -1;
        reloadResumedAt = -1;
        reloadLockTicks = 0;
        reloadDeclared = 0;
        reloadViolations = 0;
        reloadDipPeak = 0F;
        reloadDipWindow = 0;
        reloadDipAfterKick = 0F;
        // Headroom for the phase: the kill phase later needs this mob alive to force a 4 HP finishing hit.
        if (zombie != null && zombie.isAlive()) zombie.setHealth(zombie.getMaxHealth());
        ItemStack hand = drone.getMainHandItem();
        reloadMagBefore = TestKit.magazine(hand);
        // 打空: the magazine lives in the gun's own NBT, so emptying it here is exactly the state a dry
        // trigger pull produces. No test-only entry point into the reload path is involved.
        TestKit.setMagazine(hand, 0);
        LOG.info("[tacz-smoke] 装填 phase: magazine {} -> {} (barrel cleared), reserve={}, gun reload time={} tick, "
                        + "dip={}° for {} tick, target={}, waiting {} ticks",
                reloadMagBefore, TestKit.magazine(hand),
                TestKit.reserve(Hardware.contents(controller), mountedGun(controller)),
                TaczLink.reloadTicks(hand), DroneCombat.RELOAD_DIP_DEG, DroneCombat.RELOAD_DIP_TICKS,
                targetLine(), RELOAD_PHASE_TICKS);
    }

    private static void observeReload() {
        if (drone == null) return;
        int tick = drone.tickCount;
        int shots = TaczLink.shots;
        if (DroneCombat.reloading(drone)) {
            if (reloadLockStart < 0) {
                reloadLockStart = tick;
                reloadStartShots = shots;
                reloadStartMag = TestKit.magazine(drone.getMainHandItem());
                reloadDeclared = DroneCombat.reloadLength(drone);
                LOG.info("[tacz-smoke] 装填锁定 armed at droneTick={}: declared lock={} tick, magazine={}, "
                                + "reserve={}, lastResult={}",
                        tick, reloadDeclared, reloadStartMag,
                        TestKit.reserve(Hardware.contents(controller), mountedGun(controller)),
                        drone.combat.lastResult);
            }
            reloadLockTicks++;
            // ① the trigger really is dead, and the magazine really is not being touched.
            if (shots != reloadStartShots) reloadViolations++;
            if (TestKit.magazine(drone.getMainHandItem()) != reloadStartMag) reloadViolations++;
            // ③ the dip, measured on the SYNCED field against geometry the bus does not know about.
            // The feed tick itself is the lock's tick 0 (armed at the end of that tick), so the kick
            // occupies ticks 1..RELOAD_DIP_TICKS of the lock — hence the strict ">" below.
            if (drone.getTarget() != null) {
                float dip = DroneCombat.aimPitch(drone) - pitchToTarget();
                reloadDipPeak = Math.max(reloadDipPeak, dip);
                int offset = tick - reloadLockStart;
                if (offset > DroneCombat.RELOAD_DIP_TICKS) {
                    reloadDipAfterKick = Math.max(reloadDipAfterKick, Math.abs(dip));
                } else if (dip >= DroneCombat.RELOAD_DIP_DEG - 1.5F) {
                    reloadDipWindow++;
                }
            }
            return;
        }
        if (reloadLockStart >= 0 && reloadLockEnd < 0) {
            reloadLockEnd = tick;
            LOG.info("[tacz-smoke] 装填锁定 released at droneTick={} ({} tick observed, declared {}), "
                            + "lastResult={}, magazine={}",
                    tick, reloadLockTicks, reloadDeclared, drone.combat.lastResult,
                    TestKit.magazine(drone.getMainHandItem()));
        }
        if (reloadLockEnd >= 0 && reloadResumedAt < 0 && shots > reloadStartShots) {
            reloadResumedAt = tick;
            LOG.info("[tacz-smoke] firing resumed at droneTick={}, {} tick after the lock ended (shots {} -> {})",
                    tick, tick - reloadLockEnd, reloadStartShots, shots);
        }
    }

    private static void verifyReload() {
        int declared = reloadDeclared <= 0 ? TaczLink.DEFAULT_RELOAD_TICKS : reloadDeclared;
        int sounds = TaczLink.reloadSounds - reloadSoundsBefore;
        LOG.info("[tacz-smoke] 装填 after {} ticks: lockSeen={} start={} end={} observed={} tick declared={} "
                        + "dipPeak={}° dipWindow={} tick dipAfterKick={}° baseline={}° violations={} shots {} -> {} resumed@{} "
                        + "locks={} sounds={} (tacz={} vanilla={}) reserve={} lastResult={}",
                RELOAD_PHASE_TICKS, reloadLockStart >= 0, reloadLockStart, reloadLockEnd, reloadLockTicks,
                reloadDeclared, reloadDipPeak, reloadDipWindow, reloadDipAfterKick, aimBaselineDeviation,
                reloadViolations, reloadShotsBefore, TaczLink.shots, reloadResumedAt,
                TaczLink.reloads - reloadsBefore, sounds, TaczLink.taczReloadSounds,
                TaczLink.vanillaReloadSounds,
                TestKit.reserve(Hardware.contents(controller), mountedGun(controller)), drone.combat.lastResult);
        check("reload.lock_armed", reloadLockStart >= 0,
                "no RELOADING tick in " + RELOAD_PHASE_TICKS + " ticks (lastResult=" + drone.combat.lastResult + ")");
        check("reload.lock_len", reloadLockTicks >= declared - 2 && reloadLockTicks <= declared + 2,
                "observed " + reloadLockTicks + " tick, the gun pack declares " + declared);
        check("reload.no_fire_while_locked", reloadViolations == 0,
                "violations=" + reloadViolations + " (a round left the barrel or the magazine moved)");
        check("reload.dip", reloadDipPeak >= DroneCombat.RELOAD_DIP_DEG - 1.5F,
                "peak aimPitch - target pitch = " + reloadDipPeak + "°, expected ≈" + DroneCombat.RELOAD_DIP_DEG);
        check("reload.dip_window", reloadDipWindow >= DroneCombat.RELOAD_DIP_TICKS - 1
                        && reloadDipWindow <= DroneCombat.RELOAD_DIP_TICKS + 1,
                "the dip was present on " + reloadDipWindow + " sampled tick, RELOAD_DIP_TICKS="
                        + DroneCombat.RELOAD_DIP_TICKS);
        check("reload.dip_returns", reloadDipAfterKick < 1.5F,
                "|dip| after the kick = " + reloadDipAfterKick + "° (the gimbal must come back)");
        check("reload.dip_baseline", aimBaselineDeviation < 1.5F,
                "unlocked |aimPitch - target pitch| = " + aimBaselineDeviation + "°");
        check("reload.resumed", reloadResumedAt > 0 && reloadResumedAt - reloadLockEnd <= 60,
                "first shot after the lock at tick " + reloadResumedAt + " (lock ended at " + reloadLockEnd + ")");
        check("reload.shots_resume", TaczLink.shots > reloadShotsBefore,
                reloadShotsBefore + " -> " + TaczLink.shots);
        check("reload.sound", sounds > 0,
                "reload sounds " + reloadSoundsBefore + " -> " + TaczLink.reloadSounds + " (via TaCZ="
                        + TaczLink.taczReloadSounds + ", vanilla fallback=" + TaczLink.vanillaReloadSounds + ")");
        check("reload.reserve_drawn", TaczLink.reloads > reloadsBefore,
                "locks armed " + reloadsBefore + " -> " + TaczLink.reloads);
    }

    /** The pitch the gimbal is asked to hold for the current target — the bus's own formula, re-run here. */
    private static float pitchToTarget() {
        if (drone == null || drone.getTarget() == null) return 0F;
        Vec3 direction = drone.getTarget().getBoundingBox().getCenter().subtract(drone.getEyePosition());
        return (float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
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

    // ------------------------------------------------- difference ① : the firing line matrix
    //
    // TaCZ 1.1.8 resolves a round's candidate hits through com.tacz.guns.util.EntityUtil
    // #getHitResult; TaczPlayerCollisionMixin drops every player candidate that
    // DroneCombat.mayHitPlayer refuses, so the round keeps flying and can hit whoever stands behind.
    // Four phases below prove that on the real gun path:
    //   ① the owner stands on the line  -> owner untouched, target behind it still bleeds;
    //   ② an unrelated player stands on the line -> same;
    //   ③ nobody on the line -> target bleeds (so ① and ② are not "the bullets got eaten");
    //   ④ a legal PvP target is still damageable (the filter only skips, it does not disarm).

    /**
     * Puts the three fake players into {@link #level} and in front of the muzzle.
     *
     * <p>TaCZ finds candidates with {@code level.getEntities(projectile, box, predicate)}, so a player
     * that was never registered with the level's entity storage is invisible to it. The previous
     * harness only put the pilot in {@code PlayerList}, which would have made every "nothing
     * happened" assertion pass for the wrong reason. Forge's {@code FakePlayer} is also
     * damage-immune and refuses every PvP check by design; {@link SmokePlayer} restores exactly the
     * two vanilla answers the matrix needs.</p>
     */
    private static void placeActors(MinecraftServer server) {
        Vec3 station = DroneEntity.station(pilot);
        double eyeHeight = drone.getEyePosition().y - drone.getY();
        Vec3 eye = new Vec3(station.x, station.y + eyeHeight, station.z);
        // Straight away from the owner: the second player must never share the owner's firing line.
        // ... pointing the other way: away from the owner's shoulder, straight out of the muzzle.
        Vec3 away = horizontalUnit(eye, pilot.getBoundingBox().getCenter()).scale(-1.0D);
        stranger = smokePlayer("CorvusStranger",
                new Vec3(station.x + away.x * 2.0D, eye.y - 0.9D, station.z + away.z * 2.0D));
        rival = smokePlayer("CorvusRival", new Vec3(station.x, eye.y - 0.9D, station.z - 3.2D));
        addToLevel(pilot, "pilot");
        addToLevel(stranger, "stranger");
        addToLevel(rival, "rival");
        LOG.info("[tacz-smoke] actors: pilot={} stranger={} rival={} droneEye={} eyeHeight={}",
                pilot.position(), stranger.position(), rival.position(), eye, eyeHeight);
    }

    private static SmokePlayer smokePlayer(String name, Vec3 position) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(
                ("corvus-tacz-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        SmokePlayer player = new SmokePlayer(level, profile);
        player.moveTo(position.x, position.y, position.z, 0F, 0F);
        var health = player.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(PLAYER_HEALTH);
        player.setHealth((float) PLAYER_HEALTH);
        // Forge's FakePlayer is built to be harmless; make the double answer like a real player.
        player.getAbilities().invulnerable = false;
        float before = player.getHealth();
        boolean damaged = player.hurt(player.damageSources().generic(), 1.0F);
        boolean damageable = player.getHealth() < before;
        player.setHealth(player.getMaxHealth());
        playersDamageable &= damageable;
        LOG.info("[tacz-smoke] fake player {} uuid={} pos={} hp={} damageProbe(hurt 1.0)={} damageable={}",
                name, player.getUUID(), player.position(), player.getHealth(), damaged, damageable);
        return player;
    }

    private static void addToLevel(ServerPlayer player, String label) {
        boolean added = false;
        try {
            added = level.addFreshEntity(player);
        } catch (Throwable error) {
            fail("actor." + label + ".add", "addFreshEntity threw " + error);
        }
        // The only thing that matters: TaCZ's own entity query has to see this player.
        List<Player> seen = level.getEntitiesOfClass(Player.class, player.getBoundingBox().inflate(1.0D));
        check("actor." + label + ".in_level", added && seen.contains(player),
                "added=" + added + " visible=" + seen.contains(player) + " pos=" + player.position());
    }

    private static void prepareBlocked() {
        Vec3 eye = drone.getEyePosition();
        Vec3 anchor = pilot.getBoundingBox().getCenter();
        Vec3 centre = beyond(eye, anchor, 2.6D);
        carve(eye, centre);
        blockedTarget = spawnTarget(centre, "blocked");
        ownerHealthBefore = pilot.getHealth();
        blockedTargetHealthBefore = blockedTarget == null ? 0F : blockedTarget.getHealth();
        blockedMagazineBefore = TestKit.magazine(drone.getMainHandItem());
        minBlockedMagazine = Integer.MAX_VALUE;
        shotsBeforeBlocked = TaczLink.shots;
        boolean onRay = onRay(pilot, eye, centre);
        LOG.info("[tacz-smoke] ① owner on the line: owner={} centreOfOwner={} ownerBoxOnRay={} eye={} target={} "
                        + "mayHitPlayer(drone, owner)={} guard filteredForOwner={}",
                pilot.position(), anchor, onRay, eye, centre, DroneCombat.mayHitPlayer(drone, pilot),
                TaczPlayerGuard.filteredFor(pilot.getUUID()));
        check("blocked.owner_is_on_the_ray", onRay, "owner box clip(eye,target)=" + onRay);
    }

    private static void verifyBlocked() {
        float hp = blockedTarget == null ? 0F : blockedTarget.getHealth();
        int ownerOnPath = TaczPlayerGuard.onPathFor(pilot.getUUID());
        LOG.info("[tacz-smoke] ① after {} ticks: owner hp {} -> {} (Pre for owner: {} posted / {} allowed), "
                        + "target hp {} -> {}, magazine {} -> min {}, shots {} -> {}, guard refused owner={} "
                        + "(box on ray={}), trace={}",
                BLOCK_TICKS, ownerHealthBefore, pilot.getHealth(), TestKit.preHits(pilot), TestKit.preAllowed(pilot),
                blockedTargetHealthBefore, hp, blockedMagazineBefore, minBlockedMagazine, shotsBeforeBlocked,
                TaczLink.shots, TaczPlayerGuard.filteredFor(pilot.getUUID()), ownerOnPath, TaczPlayerGuard.trace());
        LOG.info("[tacz-smoke] ① drone target now: {}", targetLine());
        check("blocked.owner_unhurt", pilot.getHealth() == ownerHealthBefore,
                ownerHealthBefore + " -> " + pilot.getHealth());
        check("blocked.target_damaged", hp < blockedTargetHealthBefore, blockedTargetHealthBefore + " -> " + hp);
        check("blocked.magazine_down", minBlockedMagazine < blockedMagazineBefore,
                blockedMagazineBefore + " -> min " + minBlockedMagazine);
        check("blocked.guard_skipped_owner", ownerOnPath > 0,
                "owner candidates refused=" + TaczPlayerGuard.filteredFor(pilot.getUUID()) + ", on the ray="
                        + ownerOnPath);
        check("blocked.owner_never_hit", TestKit.preHits(pilot) == 0,
                "EntityHurtByGunEvent.Pre for the owner=" + TestKit.preHits(pilot));
        check("blocked.drone_target_is_the_blocked_one", drone.getTarget() == blockedTarget, targetLine());
    }

    private static void prepareStranger() {
        Vec3 eye = drone.getEyePosition();
        discard(blockedTarget);
        Vec3 centre = beyond(eye, stranger.getBoundingBox().getCenter(), 2.5D);
        carve(eye, centre);
        strangerTarget = spawnTarget(centre, "stranger");
        strangerHealthBefore = stranger.getHealth();
        strangerTargetHealthBefore = strangerTarget == null ? 0F : strangerTarget.getHealth();
        strangerMagazineBefore = TestKit.magazine(drone.getMainHandItem());
        minStrangerMagazine = Integer.MAX_VALUE;
        shotsBeforeStranger = TaczLink.shots;
        boolean onRay = onRay(stranger, eye, centre);
        LOG.info("[tacz-smoke] ② unrelated player on the line: stranger={} boxOnRay={} eye={} target={} "
                        + "mayHitPlayer(drone, stranger)={} ownerOnThisRay={}",
                stranger.position(), onRay, eye, centre, DroneCombat.mayHitPlayer(drone, stranger),
                onRay(pilot, eye, centre));
        check("stranger.is_on_the_ray", onRay, "stranger box clip(eye,target)=" + onRay);
        check("stranger.not_a_legal_target", !DroneCombat.mayHitPlayer(drone, stranger),
                "mayHitPlayer(drone, stranger)=" + DroneCombat.mayHitPlayer(drone, stranger));
        check("stranger.owner_not_on_ray", !onRay(pilot, eye, centre), "the owner must not shadow this case");
    }

    private static void verifyStranger() {
        float hp = strangerTarget == null ? 0F : strangerTarget.getHealth();
        int onPath = TaczPlayerGuard.onPathFor(stranger.getUUID());
        LOG.info("[tacz-smoke] ② after {} ticks: stranger hp {} -> {} (Pre: {} posted / {} allowed), target hp {} -> {}, "
                        + "magazine {} -> min {}, shots {} -> {}, guard refused stranger={} (box on ray={}), "
                        + "owner Pre={}",
                BLOCK_TICKS, strangerHealthBefore, stranger.getHealth(), TestKit.preHits(stranger),
                TestKit.preAllowed(stranger), strangerTargetHealthBefore, hp, strangerMagazineBefore,
                minStrangerMagazine, shotsBeforeStranger, TaczLink.shots,
                TaczPlayerGuard.filteredFor(stranger.getUUID()), onPath, TestKit.preHits(pilot));
        LOG.info("[tacz-smoke] ② drone target now: {}", targetLine());
        check("stranger.unhurt", stranger.getHealth() == strangerHealthBefore,
                strangerHealthBefore + " -> " + stranger.getHealth());
        check("stranger.target_damaged", hp < strangerTargetHealthBefore, strangerTargetHealthBefore + " -> " + hp);
        check("stranger.magazine_down", minStrangerMagazine < strangerMagazineBefore,
                strangerMagazineBefore + " -> min " + minStrangerMagazine);
        check("stranger.guard_skipped_stranger", onPath > 0,
                "stranger candidates refused=" + TaczPlayerGuard.filteredFor(stranger.getUUID()) + ", on the ray="
                        + onPath);
        check("stranger.never_hit", TestKit.preHits(stranger) == 0,
                "EntityHurtByGunEvent.Pre for the stranger=" + TestKit.preHits(stranger));
        check("stranger.drone_target_is_the_stranger_target", drone.getTarget() == strangerTarget, targetLine());
    }

    private static void prepareControl() {
        Vec3 eye = drone.getEyePosition();
        Vec3 station = DroneEntity.station(pilot);
        discard(strangerTarget);
        Vec3 centre = new Vec3(station.x + 6.0D, station.y + 0.45D, station.z + 0.5D);
        carve(eye, centre);
        controlTarget = spawnTarget(centre, "control");
        controlTargetHealthBefore = controlTarget == null ? 0F : controlTarget.getHealth();
        controlMagazineBefore = TestKit.magazine(drone.getMainHandItem());
        minControlMagazine = Integer.MAX_VALUE;
        shotsBeforeControl = TaczLink.shots;
        boolean clear = !onRay(pilot, eye, centre) && !onRay(stranger, eye, centre) && !onRay(rival, eye, centre);
        LOG.info("[tacz-smoke] ③ control (nobody on the line): eye={} target={} noPlayerOnRay={}", eye, centre, clear);
        check("control.no_player_on_ray", clear, "owner/stranger/rival box clip="
                + onRay(pilot, eye, centre) + "/" + onRay(stranger, eye, centre) + "/" + onRay(rival, eye, centre));
    }

    private static void verifyControl() {
        float hp = controlTarget == null ? 0F : controlTarget.getHealth();
        LOG.info("[tacz-smoke] ③ after {} ticks: target hp {} -> {}, magazine {} -> min {}, shots {} -> {}, "
                        + "Pre for players: owner={} stranger={} rival={}",
                BLOCK_TICKS, controlTargetHealthBefore, hp, controlMagazineBefore, minControlMagazine,
                shotsBeforeControl, TaczLink.shots, TestKit.preHits(pilot), TestKit.preHits(stranger),
                TestKit.preHits(rival));
        check("control.target_damaged", hp < controlTargetHealthBefore, controlTargetHealthBefore + " -> " + hp);
        check("control.magazine_down", minControlMagazine < controlMagazineBefore,
                controlMagazineBefore + " -> min " + minControlMagazine);
        check("control.drone_target_is_the_control_target", drone.getTarget() == controlTarget, targetLine());
    }

    private static void prepareRival(MinecraftServer server) {
        // The owner opts in to drone PvP and the drone is "retaliating" against the rival: this is the
        // state Engagement.permitsHit calls a legal target, i.e. the one case the filter must let
        // through. Server PVP has to be on for the same reason.
        discard(controlTarget);
        server.setPvpAllowed(true);
        pilot.getPersistentData().putBoolean(PVP_OPT_IN, true);
        Engagement.setMode(controller, "retaliate");
        drone.setLastHurtByMob(rival);
        rivalHealthBefore = rival.getHealth();
        shotsBeforeRival = TaczLink.shots;
        rivalAllowedBefore = TestKit.preAllowed(rival);
        rivalRefusedBefore = TaczPlayerGuard.filteredFor(rival.getUUID());
        LOG.info("[tacz-smoke] ④ legal PvP target: mode={} pvpAllowed={} optIn={} rival={} eye={} "
                        + "distance={}", Engagement.mode(controller), server.isPvpAllowed(),
                pilot.getPersistentData().getBoolean(PVP_OPT_IN), rival.position(), drone.getEyePosition(),
                Math.sqrt(drone.distanceToSqr(rival)));
    }

    private static void verifyRival() {
        int allowed = TestKit.preAllowed(rival) - rivalAllowedBefore;
        LOG.info("[tacz-smoke] ④ after {} ticks: target={} permitsHit={} rival hp {} -> {} (invulnerable={}), "
                        + "shots {} -> {}, Pre for rival {} posted / {} allowed, guard refused rival={}, "
                        + "guard refused owner={}",
                BLOCK_TICKS, drone.getTarget() == rival ? "rival" : String.valueOf(drone.getTarget()),
                DroneCombat.mayHitPlayer(drone, rival), rivalHealthBefore, rival.getHealth(),
                rival.isInvulnerable(), shotsBeforeRival, TaczLink.shots, TestKit.preHits(rival), allowed,
                TaczPlayerGuard.filteredFor(rival.getUUID()), TaczPlayerGuard.filteredFor(pilot.getUUID()));
        check("pvp.rival_targeted", drone.getTarget() == rival,
                "drone target=" + (drone.getTarget() == null ? "none" : drone.getTarget().getName().getString()));
        check("pvp.legal_target_allowed", DroneCombat.mayHitPlayer(drone, rival), "permitsHit(drone, rival)");
        check("pvp.round_reached_rival", allowed > 0,
                "uncancelled EntityHurtByGunEvent.Pre for the rival=" + allowed + " (posted="
                        + TestKit.preHits(rival) + ")");
        // A candidate is judged at the moment the round resolves, so a few evaluations can land while
        // the drone's target is momentarily something else; what matters is that allowed rounds got
        // through (asserted above). Keep the raw counts in the log rather than failing on them.
        LOG.info("[tacz-smoke] ④ filter detail: refusals for the rival {} -> {} while it was the drone's "
                        + "target, rounds that reached it={} (a refusal means DroneCombat.mayHitPlayer "
                        + "answered false at that instant)",
                rivalRefusedBefore, TaczPlayerGuard.filteredFor(rival.getUUID()), TestKit.preHits(rival));
        if (playersDamageable) {
            check("pvp.rival_lost_health", rival.getHealth() < rivalHealthBefore,
                    rivalHealthBefore + " -> " + rival.getHealth());
        } else {
            LOG.warn("[tacz-smoke] pvp.rival_lost_health skipped: this runtime's fake player takes no damage "
                    + "(the 1.0-damage probe at setup failed), so the round is evidenced by the uncancelled "
                    + "EntityHurtByGunEvent.Pre instead");
        }
    }

    /** One target per phase: the drone always shoots the nearest valid mob, so retire the old one. */
    private static void discard(Monster mob) {
        if (mob != null && !mob.isRemoved()) mob.discard();
    }

    private static String targetLine() {
        return drone.getTarget() == null ? "none"
                : drone.getTarget().getUUID() + " at " + drone.getTarget().position();
    }

    /** A point {@code extra} blocks further along the ray that starts at {@code eye} through {@code anchor}. */
    private static Vec3 beyond(Vec3 eye, Vec3 anchor, double extra) {
        Vec3 delta = anchor.subtract(eye);
        double length = delta.length();
        if (length < 1.0E-4D) return anchor;
        return eye.add(delta.scale((length + extra) / length));
    }

    private static Vec3 horizontalUnit(Vec3 from, Vec3 to) {
        Vec3 flat = new Vec3(to.x - from.x, 0.0D, to.z - from.z);
        return flat.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : flat.normalize();
    }

    private static boolean onRay(ServerPlayer player, Vec3 from, Vec3 to) {
        return player.getBoundingBox().inflate(0.05D).clip(from, to).isPresent();
    }

    /**
     * Clears a 3×3×3 tunnel along a segment. The blocked scenarios fire from a hovering drone down
     * through the owner's chest, so the round leaves the roofed arena floor: the tunnel (and the pit
     * it digs) is what makes that geometry physically possible instead of "hit the ground first".
     */
    private static void carve(Vec3 from, Vec3 to) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 0.4D));
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.lerp(to, (double) i / steps);
            BlockPos centre = BlockPos.containing(point.x, point.y, point.z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlockAndUpdate(centre.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /** A high-health, no-AI, floating spider whose box CENTRE sits exactly on {@code centre}. */
    private static Monster spawnTarget(Vec3 centre, String label) {
        Monster mob = EntityType.SPIDER.create(level);
        if (mob == null) {
            fail("target." + label, "EntityType.SPIDER.create returned null");
            return null;
        }
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPersistenceRequired();
        var health = mob.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(300.0D);
        mob.setHealth(300.0F);
        mob.moveTo(centre.x, centre.y - mob.getBbHeight() / 2.0D, centre.z, 0F, 0F);
        boolean added = level.addFreshEntity(mob);
        check("target." + label + ".spawn", added && mob.isAlive(), "pos=" + mob.position() + " centre="
                + mob.getBoundingBox().getCenter() + " wanted=" + centre);
        return mob;
    }

    private static void cutPower() {        lowPowerHeld = TestKit.magazine(drone.getMainHandItem());
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
                        + "reserve={} zombieHp={} zombieDead={} shots={} hurt={} kill={} reports={} energy={} combatOnline={} tier={} "
                        + "reload={} lock={} dip={}° sounds={}",
                wait, step, drone == null ? -1 : drone.tickCount, drone == null || !drone.isRemoved(),
                drone == null || drone.getTarget() == null ? "none" : drone.getTarget().getName().getString(),
                TestKit.magazine(drone == null ? ItemStack.EMPTY : drone.getMainHandItem()),
                TestKit.magazine(mountedGun(controller)),
                TestKit.reserve(Hardware.contents(controller), mountedGun(controller)),
                zombie == null ? -1F : zombie.getHealth(), zombie != null && zombie.isDeadOrDying(),
                TaczLink.shots, TestKit.hurtEvents(), TestKit.killEvents(), TaczLink.reports,
                Hardware.energy(controller), Hardware.online(controller, "combat"),
                Hardware.tier(controller, "combat"),
                drone != null && DroneCombat.reloading(drone), DroneCombat.reloadRemaining(drone),
                DroneCombat.reloadDip(drone), TaczLink.reloadSounds);
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
     * The matrix needs a player that answers like a real one at the two points that decide
     * everything here. Forge's {@link net.minecraftforge.common.util.FakePlayer} deliberately
     * answers {@code isInvulnerableTo} with true and {@code canHarmPlayer} with false, which would
     * (a) make "the owner did not lose health" true no matter what the collision code does, and
     * (b) make a legal PvP target impossible to build on a headless server. This subclass restores
     * the two vanilla answers and leaves everything else to {@code FakePlayer}.
     */
    private static final class SmokePlayer extends net.minecraftforge.common.util.FakePlayer {
        SmokePlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isInvulnerableTo(DamageSource source) {
            return false;
        }

        @Override
        public boolean canHarmPlayer(Player other) {
            return true;
        }
    }

    /**
     * The TaCZ-typed part of the harness, isolated in its own class so loading this file never
     * resolves a TaCZ class when the backend is absent.
     */
    private static final class TestKit {
        private static int hurtEvents;
        private static int killEvents;
        /**
         * {@code EntityHurtByGunEvent.Pre} per entity: {@code [0]} posted, {@code [1]} posted and not
         * cancelled. This is the damage layer's own account of a round: a player whose box absorbed a
         * round shows up here even though the fake player is not damageable, and a player whose
         * candidate the mixin dropped never shows up at all.
         */
        private static final Map<net.minecraft.world.entity.Entity, int[]> pre =
                new java.util.IdentityHashMap<>();

        static int hurtEvents() {
            return hurtEvents;
        }

        static int killEvents() {
            return killEvents;
        }

        static int preHits(net.minecraft.world.entity.Entity entity) {
            synchronized (pre) {
                int[] counts = pre.get(entity);
                return counts == null ? 0 : counts[0];
            }
        }

        static int preAllowed(net.minecraft.world.entity.Entity entity) {
            synchronized (pre) {
                int[] counts = pre.get(entity);
                return counts == null ? 0 : counts[1];
            }
        }

        /** Counts TaCZ's gun events as the drone's attack would see them. */
        static void listen() {
            // Lowest priority: sees the cancellation decision TaczLink made at HIGHEST.
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                    (com.tacz.guns.api.event.common.EntityHurtByGunEvent.Pre event) -> {
                        if (event.getLogicalSide().isClient() || !(event.getAttacker() instanceof DroneEntity)) {
                            return;
                        }
                        synchronized (pre) {
                            int[] counts = pre.computeIfAbsent(event.getHurtEntity(), key -> new int[2]);
                            counts[0]++;
                            if (!event.isCanceled()) counts[1]++;
                        }
                    });
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

        /**
         * 打空: writes the gun's own magazine NBT — the same slot TaCZ decrements on a shot — and clears
         * the chamber, so the very next trigger pull answers {@code NO_AMMO} exactly like a real dry gun.
         */
        static void setMagazine(ItemStack stack, int count) {
            com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            if (gun == null) return;
            gun.setCurrentAmmoCount(stack, Math.max(0, count));
            gun.setBulletInBarrel(stack, count > 0);
        }

        /** Rounds left in the drone's own reserve stacks for the mounted gun. */
        static int reserve(List<ItemStack> parts, ItemStack gun) {            int total = 0;
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
