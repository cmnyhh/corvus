package dev.corvus.devtools;

import dev.corvus.Corvus;
import dev.corvus.DroneCombat;
import dev.corvus.DroneCurios;
import dev.corvus.DroneDeployment;
import dev.corvus.DroneEntity;
import dev.corvus.DroneItem;
import dev.corvus.DroneRemote;
import dev.corvus.Engagement;
import dev.corvus.Frames;
import dev.corvus.Hardware;
import dev.corvus.ScanSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance harness for the combat / scan / dialogue layer.
 *
 * <p>Runs only with {@code -Dcorvus.smoke=1} (the same switch the phase-1 harness uses) and only
 * after that harness has given and worn a controller, so the two scripts share one world instead of
 * racing each other. It exercises the three acceptance items end to end:</p>
 * <ol>
 *   <li><b>scan snapshot → subtitle</b>: install a scanner + chip, force a scan, and let the dialogue
 *       layer speak the completed snapshot;</li>
 *   <li><b>silence</b>: the same completed snapshot with no chip, and with the barks switch off, must
 *       produce no line at all;</li>
 *   <li><b>policy switching</b>: cycle all five {@link Engagement} packs and report each mode.</li>
 * </ol>
 *
 * <p>It also places a small demo ore cluster and one zombie in front of the pilot so the client
 * screenshot has something to mark: that is the only reason this file writes to the world.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class SmokeCombat {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.smoke"));
    private static final Logger LOGGER = LoggerFactory.getLogger("corvus-smoke-combat");

    private static final int INSTALL_AT = 90;
    private static final int SCAN_AT = 95;
    private static final int INSPECT_AT = 150;
    private static final int SILENCE_AT = 200;
    private static final int POLICY_AT = 230;
    private static final int REPORT_AT = 255;

    private static int ticks;
    private static boolean installed;
    private static boolean scanRequested;
    private static boolean inspected;
    private static ItemStack controller = ItemStack.EMPTY;
    private static String snapshotSummary = "none";
    private static final List<String> checks = new ArrayList<>();

    private SmokeCombat() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.isStopped() || server.getPlayerList().getPlayers().isEmpty()) return;
        ticks++;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (ticks == INSTALL_AT) install(player);
        if (ticks == SCAN_AT) requestScan(player);
        if (ticks == INSPECT_AT) inspect(player);
        if (ticks == SILENCE_AT) silenceRun(player);
        if (ticks == POLICY_AT) policyRun(player);
        if (ticks == REPORT_AT) report(player);
    }

    // ------------------------------------------------------------------ steps

    private static void install(ServerPlayer player) {
        ItemStack worn = DroneCurios.equipped(player);
        if (worn.isEmpty()) {
            LOGGER.error("[smoke-c] no controller in the Curios slot; skipping the combat/scan script");
            return;
        }
        controller = worn;
        List<ItemStack> parts = new ArrayList<>(Hardware.contents(worn));
        addIfPresent(parts, "omd_bat_200");
        // Modules are one registry item carrying a product id, so they are built through Hardware.
        addModule(parts, "scan_mk2");
        addModule(parts, "combat_mk1");
        addIfPresent(parts, "chip_tactical");
        Hardware.save(worn, parts);
        installed = true;
        LOGGER.info("[smoke-c] installed parts={} chip={} hasChip={} scanTier={} scanOnline={} energy={}/{}",
                parts.size(), Hardware.chip(worn), Hardware.hasChip(worn), Hardware.tier(worn, "scan"),
                Hardware.online(worn, "scan"), Hardware.energy(worn), Hardware.capacity(worn));
        seedTargets(player);
    }

    /** Demo ore + one hostile in front of the pilot, so the screenshot has marks to show. */
    private static void seedTargets(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        // A dev save can be Peaceful, in which case any spawned monster is removed on its first tick
        // and the orange hostile boxes could never be demonstrated.
        if (level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
            LOGGER.info("[smoke-c] dev world difficulty peaceful -> normal (screenshot run only)");
        }
        // Horizontal facing, so the demo targets land in front of the third-person camera even when
        // the pilot is looking straight down at a cave floor.
        var facing = player.getDirection();
        BlockPos base = player.blockPosition();
        int placed = 0;
        for (int step = 3; step <= 7; step++) {
            BlockPos pos = base.offset(facing.getStepX() * step, 1, facing.getStepZ() * step);
            level.setBlockAndUpdate(pos, Blocks.COAL_ORE.defaultBlockState());
            placed++;
        }
        // A spider is an Enemy that does not burn in daylight, so it survives long enough to be
        // marked and photographed; a zombie would self-immolate over the screenshot window.
        net.minecraft.world.entity.Mob hostile = EntityType.SPIDER.create(level);
        // Offset to the pilot's left so the pilot's own body does not cover the marker in the shot.
        BlockPos mobPos = base.offset(facing.getStepX() * 4 + facing.getStepZ() * 3, 0,
                facing.getStepZ() * 4 - facing.getStepX() * 3);
        boolean spawned = false;
        if (hostile != null) {
            hostile.moveTo(mobPos.getX() + 0.5, mobPos.getY(), mobPos.getZ() + 0.5, 0F, 0F);
            hostile.setNoAi(true);
            hostile.setPersistenceRequired();
            spawned = level.addFreshEntity(hostile) && level.getEntity(hostile.getId()) != null;
        }
        LOGGER.info("[smoke-c] seeded {} coal ore blocks and hostile={} (difficulty={}) around {} (facing {})",
                placed, spawned, level.getDifficulty(), base, facing);
    }

    private static void addModule(List<ItemStack> parts, String product) {
        try {
            parts.add(Hardware.moduleStack(product));
        } catch (RuntimeException unknown) {
            LOGGER.warn("[smoke-c] module {} is not defined; skipped ({})", product, unknown.toString());
        }
    }

    private static void addIfPresent(List<ItemStack> parts, String path) {
        var item = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(Corvus.ID, path));
        if (item.isEmpty()) {
            LOGGER.warn("[smoke-c] item huhu_dronepal:{} not registered; part skipped", path);
            return;
        }
        parts.add(new ItemStack(item.get()));
    }

    private static void requestScan(ServerPlayer player) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone == null) {
            LOGGER.error("[smoke-c] no deployed projection at tick {}; cannot scan", ticks);
            checks.add("FAIL deployed=false");
            return;
        }
        boolean started = drone.execute(player, "scan");
        scanRequested = started;
        LOGGER.info("[smoke-c] scan requested -> {} (scanning={} mountPitch={})", started, drone.isScanActive(),
                drone.mountPitch());
        checks.add((started ? "PASS" : "FAIL") + " scan started");
    }

    private static void inspect(ServerPlayer player) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone == null) {
            LOGGER.error("[smoke-c] projection vanished before inspection");
            return;
        }
        ScanSnapshot snapshot = drone.scanSnapshot();
        snapshotSummary = snapshot == null ? "none"
                : snapshot.ores().size() + " ores, " + snapshot.hostiles().size() + " hostiles, expires="
                + snapshot.expiresAt();
        LOGGER.info("[smoke-c] snapshot={} scanning={}", snapshotSummary, drone.isScanActive());
        // Diagnostics for the hostile half of the marker display.
        var nearby = player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                drone.getBoundingBox().inflate(24.0D));
        LOGGER.info("[smoke-c] mobs within 24 blocks: {}", nearby.size());
        for (var mob : nearby) {
            LOGGER.info("[smoke-c]   {} enemy={} alive={} pos={} dist={}", mob.getType(),
                    mob instanceof net.minecraft.world.entity.monster.Enemy, mob.isAlive(), mob.position(),
                    Math.sqrt(mob.distanceToSqr(drone)));
        }
        checks.add((snapshot != null && !snapshot.ores().isEmpty() ? "PASS" : "FAIL") + " snapshot has ore marks");
        if (snapshot == null || snapshot.hostiles().isEmpty()) {
            LOGGER.warn("[smoke-c] no hostile in the snapshot: the orange box path is unproven this run");
        } else {
            checks.add("PASS snapshot has hostile marks (" + snapshot.hostiles().size() + ")");
        }
        if (snapshot == null) {
            inspected = true;
            return;
        }
        // 1) chip fitted, barks on: this must speak.
        boolean speakGate = DroneRemote.barksEnabled(controller);
        LOGGER.info("[smoke-c] barksEnabled(chip fitted)={}", speakGate);
        if (speakGate) dev.corvus.DialogueServer.scanComplete(player, snapshot, false);
        checks.add((speakGate ? "PASS" : "FAIL") + " chip+barks gate open");
        inspected = true;
    }

    private static void silenceRun(ServerPlayer player) {
        DroneEntity drone = DroneDeployment.active(player);
        ScanSnapshot snapshot = drone == null ? null : drone.scanSnapshot();
        if (snapshot == null) {
            LOGGER.error("[smoke-c] no snapshot to test the silence gate with");
            return;
        }
        // 2) chip physically removed: the drone must be mute even with barks "on".
        List<ItemStack> withoutChip = new ArrayList<>();
        for (ItemStack part : Hardware.contents(controller)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(part.getItem());
            if (id.getPath().startsWith("chip_")) continue;
            withoutChip.add(part);
        }
        Hardware.save(controller, withoutChip);
        boolean chipGate = DroneRemote.barksEnabled(controller);
        LOGGER.info("[smoke-c] no-chip barksEnabled={} hasChip={}", chipGate, Hardware.hasChip(controller));
        dev.corvus.DialogueServer.scanComplete(player, snapshot, false);
        checks.add((!chipGate ? "PASS" : "FAIL") + " no chip is silent");

        // 3) chip back, barks switch off: still silent.
        addIfPresent(withoutChip, "chip_tactical");
        Hardware.save(controller, withoutChip);
        DroneRemote.toggleBarks(controller);
        boolean switchGate = DroneRemote.barksEnabled(controller);
        LOGGER.info("[smoke-c] barks-switch-off barksEnabled={} barksOn={}", switchGate, DroneRemote.barksOn(controller));
        dev.corvus.DialogueServer.scanComplete(player, snapshot, false);
        checks.add((!switchGate ? "PASS" : "FAIL") + " barks switch off is silent");

        // 4) switch back on: the same snapshot speaks again (or is de-duplicated server-side).
        DroneRemote.toggleBarks(controller);
        boolean reopened = DroneRemote.barksEnabled(controller);
        LOGGER.info("[smoke-c] barks back on -> {}", reopened);
        dev.corvus.DialogueServer.scanComplete(player, snapshot, false);
        checks.add((reopened ? "PASS" : "FAIL") + " barks switch reopens the gate");
    }

    private static void policyRun(ServerPlayer player) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone == null) {
            LOGGER.error("[smoke-c] projection missing for the policy check");
            return;
        }
        StringBuilder seen = new StringBuilder();
        boolean cycleOk = true;
        for (int step = 0; step < Engagement.IDS.size(); step++) {
            boolean ok = Engagement.cycle(drone);
            cycleOk &= ok;
            seen.append(Engagement.mode(controller)).append(' ');
        }
        LOGGER.info("[smoke-c] engagement cycle (ok={}) -> {}", cycleOk, seen.toString().trim());
        boolean back = Engagement.IDS.contains(Engagement.mode(controller));
        checks.add((cycleOk && back ? "PASS" : "FAIL") + " engagement cycles 5 packs");
        Engagement.setMode(controller, "retaliate");
        // Weapon bus state, for the record: with no TaCZ the drone may be armed but never fires.
        boolean armed = DroneCombat.setDeployed(drone, true);
        LOGGER.info("[smoke-c] weapon armed={} automatic={} fireBackend={}", armed,
                DroneCombat.automatic(controller), dev.corvus.CombatSlots.available() ? "tacz" : "none (degraded)");
        checks.add((armed ? "PASS" : "FAIL") + " weapon bus arms without a gun backend");
    }

    private static void report(ServerPlayer player) {
        DroneEntity drone = DroneDeployment.active(player);
        if (drone != null) {
            Frames.Frame frame = Frames.byId(drone.getFrameId());
            boolean hitbox = frame != null
                    && Math.abs(drone.getBbWidth() - frame.width()) < 1.0E-4
                    && Math.abs(drone.getBbHeight() - frame.height()) < 1.0E-4;
            LOGGER.info("[smoke-c] hitbox={}x{} frame={} definition={}x{}",
                    drone.getBbWidth(), drone.getBbHeight(), drone.getFrameId(),
                    frame == null ? "?" : frame.width(), frame == null ? "?" : frame.height());
            checks.add((hitbox ? "PASS" : "FAIL") + " hitbox follows the frame definition");
        }
        LOGGER.info("[smoke-c] serial={} frame={} snapshot={} mode={} barks={}",
                DroneItem.serial(controller), drone == null ? "n/a" : drone.getFrameId(), snapshotSummary,
                Engagement.mode(controller), DroneRemote.barksEnabled(controller));
        boolean pass = checks.stream().allMatch(check -> check.startsWith("PASS"));
        LOGGER.info("[smoke-c] RESULT {} :: {}", pass ? "PASS" : "FAIL", String.join(" | ", checks));
    }
}
