package dev.corvus;

import com.mojang.authlib.GameProfile;
import dev.corvus.data.CorvusKeys;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Development-only acceptance run for the two order/runtime gaps (agent G).
 *
 * <p>Inactive unless {@code -Dcorvus.gaps=1}, in which case it drives a dedicated server once and stops
 * it. It exists because both gaps are invisible to a screenshot and easy to fake: the point is to run the
 * <em>real</em> chain — the registered {@code /dronepal} command tree, the dialogue library's intent
 * matching, the injected {@code Hardware} intent handler, and the module runtime — and then assert that
 * something happened to the world (a medical supply gone, ammo moved out of the hold, a cooldown refusing
 * a second order, a FE/t figure that matches a hand calculation).</p>
 *
 * <p>It lives in {@code dev.corvus} rather than {@code dev.corvus.devtools} on purpose: the runtime under
 * test ({@link DroneSystems}, {@link CarryLight}, {@link DroneRemote}) is package-scoped, and a test that
 * has to be handed a friendlier API than the game gets would not be testing the game's path.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class GapSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.gaps"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-gap-smoke");

    private static final List<String> FAILURES = new ArrayList<>();
    private static int ticks;
    private static boolean ran;
    private static int passed;

    private GapSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.isStopped() || ran) return;
        // Datapacks (modules, vendors, chips, intents) load during server start.
        if (++ticks < 20) return;
        ran = true;
        LOG.info("[gap-smoke] ==== order runtime + power readout acceptance run ====");
        try {
            run(server);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[gap-smoke] harness crashed", error);
        }
        LOG.info("[gap-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[gap-smoke] FAILED {}", failure);
        server.halt(false);
    }

    private static void run(MinecraftServer server) {
        ServerPlayer player = FakePlayerFactory.get(server.overworld(), new GameProfile(
                UUID.nameUUIDFromBytes("corvus-gap-smoke".getBytes(StandardCharsets.UTF_8)), "GapSmoke"));
        ItemStack controller = Frames.create("bedrock");
        UUID serial = DroneItem.ensureSerial(controller);
        boolean worn = wear(player, controller);
        boolean deployed = DroneDeployment.deploy(player, controller, serial);
        DroneEntity drone = DroneDeployment.active(player);
        check("setup.curios_worn", worn, "curios " + DroneCurios.SLOT + " slot");
        check("setup.deployed", deployed && drone != null, "serial=" + serial);
        if (drone == null) {
            fail("setup.terminal", "no deployed projection; the rest of the run needs one");
            return;
        }
        check("setup.terminal", !Hardware.controller(player).isEmpty() && serial.equals(DroneItem.serial(Hardware.controller(player))),
                "worn controller resolves");
        LOG.info("[gap-smoke] controller frame={} serial={} drone={}", Frames.id(controller), serial, drone.getUUID());

        dialoguePhase();
        whitelistPhase(player, controller);
        unimplementedPhase();
        switchedOffPhase(player, controller, drone);
        drainPhase(player, controller);
        solarPhase(player, controller);
        noModulePhase(player, controller, drone);
        healPhase(player, controller, drone);
        supplyPhase(player, controller);
        fetchPhase(player, controller);
        offlinePhase(player, controller);
    }

    // ---------------------------------------------------------------------------------------------
    // 0. The three orders the player types must resolve to the intents the runtime implements
    // ---------------------------------------------------------------------------------------------

    private static void dialoguePhase() {
        check("dialogue.text.治疗", "order.heal".equals(DialogueServer.intentOf("治疗")),
                "resolved=" + DialogueServer.intentOf("治疗"));
        check("dialogue.text.捡一下", "order.fetch".equals(DialogueServer.intentOf("捡一下")),
                "resolved=" + DialogueServer.intentOf("捡一下"));
        check("dialogue.text.递弹", "order.supply".equals(DialogueServer.intentOf("递弹")),
                "resolved=" + DialogueServer.intentOf("递弹"));
        check("dialogue.text.supply_ammo", "order.supply".equals(DialogueServer.intentOf("补给弹药")),
                "resolved=" + DialogueServer.intentOf("补给弹药"));
        check("dialogue.text.heal_sentence", "order.heal".equals(DialogueServer.intentOf("快治疗一下")),
                "resolved=" + DialogueServer.intentOf("快治疗一下"));
        check("dialogue.text.unknown", DialogueServer.intentOf("今天天气怎么样") == null
                || !"order.heal".equals(DialogueServer.intentOf("今天天气怎么样")), "smalltalk is not an order");
    }

    // ---------------------------------------------------------------------------------------------
    // 0b. R3: one whitelist, every implemented order, identical words for success and failure
    // ---------------------------------------------------------------------------------------------

    /**
     * The eleven orders both builds now dispatch. The same sentences are asserted in the 1.21.1
     * {@code DialogueCheck}, so a whitelist that drifts again fails on one side at once.
     */
    private static void whitelistPhase(ServerPlayer player, ItemStack controller) {
        // sentence, expected id, the one module that order needs ("" = none)
        List<String[]> cases = List.of(
                new String[] {"扫描", "scan", "scan_mk1"},
                new String[] {"附近有怪吗", "scan.hostile", "scan_mk1"},
                new String[] {"找矿", "scan.ore", "scan_mk1"},
                new String[] {"治疗", "order.heal", "medic_mk1"},
                new String[] {"捡一下", "order.fetch", "carry_mk1"},
                new String[] {"递弹", "order.supply", "carry_mk1"},
                new String[] {"开灯", "order.light", "light_mk1"},
                new String[] {"掩护我", "order.cover", "combat_mk1"},
                new String[] {"停火", "order.hold", "combat_mk1"},
                new String[] {"上工", "order.work", ""},
                new String[] {"回来", "order.idle", ""});
        for (String[] entry : cases) {
            String sentence = entry[0];
            String expected = entry[1];
            String module = entry[2];
            fit(controller, "omd_bat_300", module.isEmpty() ? new String[0] : new String[] {module});
            Hardware.setEnergy(controller, Hardware.capacity(controller));
            String resolved = DialogueServer.intentOf(sentence);
            String reply = DialogueServer.replyTo(player, sentence);
            check("whitelist." + expected + ".resolved", expected.equals(resolved), "resolved=" + resolved);
            check("whitelist." + expected + ".dispatched", !reply.contains("尚未实现"), "reply=" + reply);
        }
        // The happy and the refusing path have to answer with the same words as the 1.21.1 build.
        fit(controller, "omd_bat_300", "light_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        check("whitelist.reply.success", "指令已执行。".equals(DialogueServer.replyTo(player, "开灯")),
                "reply=" + DialogueServer.replyTo(player, "开灯"));
        fit(controller, "omd_bat_300", "medic_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        check("whitelist.reply.refused", "当前无法执行，请检查能量、目标或冷却。"
                        .equals(DialogueServer.replyTo(player, "治疗")),
                "a full-health owner cannot be healed: " + DialogueServer.replyTo(player, "治疗"));
        fit(controller, "omd_bat_300");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        check("whitelist.reply.refused_work", "当前无法执行，请检查能量、目标或冷却。"
                        .equals(DialogueServer.replyTo(player, "上工")),
                "no mill nearby: " + DialogueServer.replyTo(player, "上工"));
    }

    /**
     * An order the firmware does not own must still answer "尚未实现" — the other half of R3. Built from
     * a two-document library so the check cannot be satisfied by the real data by accident.
     */
    private static void unimplementedPhase() {
        var docs = new java.util.HashMap<String, com.google.gson.JsonObject>();
        docs.put("chips/tactical.json", com.google.gson.JsonParser.parseString(
                "{\"prompt_profile\":\"tactical\",\"unlocks_chat\":true,\"allows_smalltalk\":true,"
                        + "\"bark_rate\":0.5,\"bark_cooldown_ticks\":100,\"voice_speed\":1.0}").getAsJsonObject());
        docs.put("intents/order.hack.json", com.google.gson.JsonParser.parseString(
                "{\"id\":\"order.hack\",\"aliases\":[\"hack\"],\"response\":\"action\"}").getAsJsonObject());
        LocalDialogue library = new LocalDialogue(docs);
        boolean[] executed = {false};
        LocalDialogue.Device device = new LocalDialogue.Device() {
            public boolean active() { return true; }
            public String chip() { return "tactical"; }
            public boolean installed(String family) { return true; }
            public java.util.Map<String, String> slots() { return java.util.Map.of(); }
            public boolean execute(String id) { executed[0] = true; return true; }
            public String lookup(String intent, String question) { return ""; }
        };
        String reply = library.respond(device, "hack", "", new java.util.Random(1)).text();
        check("whitelist.unknown_is_unimplemented", "当前固件尚未实现这项指令。".equals(reply), "reply=" + reply);
        check("whitelist.unknown_not_executed", !executed[0], "an unknown order must not reach a handler");
    }

    // ---------------------------------------------------------------------------------------------
    // 0c. R2: a module switched off in the wheel stays off, draws nothing and is not re-armed
    // ---------------------------------------------------------------------------------------------

    /**
     * The wheel switch used to be invisible to {@code Hardware.online} on this build: a module switched
     * off still drew power, and the automatic scan re-armed itself 15 s later.
     */
    private static void switchedOffPhase(ServerPlayer player, ItemStack controller, DroneEntity drone) {
        fit(controller, "omd_bat_300", "carry_mk1", "scan_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        Hardware.Module scan = module(controller, "scan");
        int both = Hardware.drainPerTick(controller);
        int scanOnly = scan == null ? -1
                : (int) Math.round(scan.drain() * Math.min(10D, Math.max(0D, scan.trait("drain_multiplier", 1D))));
        check("offline.baseline_online", Hardware.online(controller, "carry") && Hardware.online(controller, "scan")
                && both > scanOnly, "drain=" + both + " scanOnly=" + scanOnly);

        // Flip the switch the wheel flips (same key, same helper).
        DroneRemote.toggle(controller, "carry");
        check("offline.switch_off", DroneRemote.disabled(controller, "carry"), "");
        check("offline.online_false", !Hardware.online(controller, "carry"), "");
        check("offline.drain_excludes_module", Hardware.drainPerTick(controller) == scanOnly,
                "drain=" + Hardware.drainPerTick(controller) + " expected=" + scanOnly + " (carry 5 W/t gone)");
        DroneRemote.toggle(controller, "carry");
        check("offline.switch_back_on", !DroneRemote.disabled(controller, "carry")
                && Hardware.online(controller, "carry") && Hardware.drainPerTick(controller) == both,
                "drain=" + Hardware.drainPerTick(controller) + " expected=" + both);

        // While a scanner is online the automatic patrol arms; switched off, 15 seconds of runtime must
        // neither re-arm it nor flip the switch back.
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        drone.systems().second(drone, player, controller);
        boolean armedWhileOn = drone.systems().nextAutomaticTick() >= 0;
        check("offline.autoscan_arms_when_on", armedWhileOn, "nextAutomatic=" + drone.systems().nextAutomaticTick());
        DroneRemote.toggle(controller, "scan");
        boolean stayedOff = true;
        for (int second = 0; second < 15; second++) {
            drone.systems().second(drone, player, controller);
            stayedOff &= DroneRemote.disabled(controller, "scan")
                    && !Hardware.online(controller, "scan")
                    && drone.systems().nextAutomaticTick() < 0;
        }
        check("offline.autoscan_stays_off_15s", stayedOff,
                "disabled=" + DroneRemote.disabled(controller, "scan")
                        + " online=" + Hardware.online(controller, "scan")
                        + " nextAutomatic=" + drone.systems().nextAutomaticTick());
        check("offline.drain_after_15s", Hardware.drainPerTick(controller) == carryOnly(controller),
                "drain=" + Hardware.drainPerTick(controller) + " expected=" + carryOnly(controller));
        // Leave the controller as the later phases expect it: every switch back on.
        DroneRemote.toggle(controller, "scan");
    }

    private static int carryOnly(ItemStack controller) {
        Hardware.Module carry = module(controller, "carry");
        return carry == null ? -1
                : (int) Math.round(carry.drain() * Math.min(10D, Math.max(0D, carry.trait("drain_multiplier", 1D))));
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Status.drain against a hand calculation
    // ---------------------------------------------------------------------------------------------

    private static void drainPhase(ServerPlayer player, ItemStack controller) {
        fit(controller, "omd_bat_300", "combat_mk1", "scan_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        // Hand calculation straight out of the data files, vendor multipliers included:
        //   combat_mk1 (ahi, ×1.10) 14 W/t -> 15.4
        //   scan_mk1   (cvs, ×1.00)  6 W/t ->  6.0
        //   total 21.4 -> 21 FE/t
        // (The 1.21 panel prints one decimal and would say 21.4; this field is an int, hence 21.)
        Hardware.Module combat = module(controller, "combat");
        Hardware.Module scan = module(controller, "scan");
        check("drain.data_combat", combat != null && combat.drain() == 14
                && Math.abs(combat.trait("drain_multiplier", 1D) - 1.1) < 1.0E-4,
                combat == null ? "not fitted" : combat.drain() + " W/t ×" + combat.trait("drain_multiplier", 1D));
        check("drain.data_scan", scan != null && scan.drain() == 6
                && Math.abs(scan.trait("drain_multiplier", 1D) - 1.0) < 1.0E-4,
                scan == null ? "not fitted" : scan.drain() + " W/t ×" + scan.trait("drain_multiplier", 1D));
        int expected = 21;
        int measured = Hardware.drainPerTick(controller);
        check("drain.measured_hand_calc", measured == expected,
                "measured=" + measured + " expected=" + expected + " (14×1.10 + 6×1.00 = 21.4)");
        LOG.info("[gap-smoke] drain: combat_mk1 14×1.10 + scan_mk1 6×1.00 = 21.4 -> {} FE/t", measured);

        DroneRemote.Status status = DroneRemote.status(player, 1, 1);
        check("status.drain_hand_calc", status != null && status.drain() == expected,
                status == null ? "no status" : "drain=" + status.drain());
        check("status.solar_none", status != null && status.solar() == -1, "no solar module fitted");

        if (status != null) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                DroneRemote.Status.encode(status, buffer);
                DroneRemote.Status decoded = DroneRemote.Status.decode(buffer);
                check("status.wire.drain", decoded.drain() == status.drain(),
                        "decoded=" + decoded.drain() + " sent=" + status.drain());
                check("status.wire.labels", decoded.labels().equals(status.labels()), "");
            } finally {
                buffer.release();
            }
        }

        // Zero charge takes every module offline, so the honest figure drops to 0 rather than staying.
        Hardware.setEnergy(controller, 0);
        check("drain.empty_0", Hardware.drainPerTick(controller) == 0, "energy=0");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Solar output is the real weather/daylight figure
    // ---------------------------------------------------------------------------------------------

    private static void solarPhase(ServerPlayer player, ItemStack controller) {
        fit(controller, "omd_bat_300", "solar_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        Hardware.Module solar = Hardware.installed(controller).stream()
                .filter(module -> module.family().equals("solar")).findFirst().orElse(null);
        if (solar == null) {
            fail("solar.fitted", "solar_mk1 not installed");
            return;
        }
        check("solar.nominal_clear", Hardware.solarOutput(solar, true, true, false) == solar.integer("clear"),
                "clear=" + solar.integer("clear"));
        check("solar.night_zero", Hardware.solarOutput(solar, true, false, false) == solar.integer("night"),
                "night=" + solar.integer("night"));
        check("solar.no_sky_zero", Hardware.solarOutput(solar, false, true, false) == 0, "no view of the sky");

        DroneRemote.Status status = DroneRemote.status(player, 2, 2);
        var level = player.serverLevel();
        DroneEntity drone = DroneDeployment.active(player);
        boolean sky = drone != null && level.canSeeSky(drone.blockPosition());
        String bucket = !level.isDay() ? "night" : level.isRaining() ? "rain" : "clear";
        int expected = sky ? solar.integer(bucket) : 0;
        check("solar.status_matches_world", status != null && status.solar() == expected,
                "status=" + (status == null ? "none" : status.solar()) + " expected=" + expected
                        + " sky=" + sky + " bucket=" + bucket);
        LOG.info("[gap-smoke] solar: sky={} day={} raining={} bucket={} output={}", sky, level.isDay(),
                level.isRaining(), bucket, expected);
    }

    // ---------------------------------------------------------------------------------------------
    // 3. No module fitted: the dialogue text still reaches the runtime and is refused there
    // ---------------------------------------------------------------------------------------------

    private static void noModulePhase(ServerPlayer player, ItemStack controller, DroneEntity drone) {
        fit(controller, "omd_bat_300");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        CarryLight.save(controller, new ArrayList<>(List.of(new ItemStack(Items.ARROW, 4))));
        player.setHealth(6F);
        int supplies = count(player, "medical_supply");
        double health = player.getHealth();

        say(player, "治疗");
        say(player, "捡一下");
        say(player, "递弹");

        check("nomodule.heal_refused", player.getHealth() == health && count(player, "medical_supply") == supplies,
                "health=" + player.getHealth() + " supplies=" + count(player, "medical_supply"));
        check("nomodule.fetch_refused", holdCount(controller) == 4, "hold=" + holdCount(controller));
        check("nomodule.supply_refused", holdCount(controller) == 4 && count(player, Items.ARROW) == 0,
                "hold=" + holdCount(controller) + " arrows=" + count(player, Items.ARROW));
        // The injected handler must answer false for all three, not just the dialogue layer.
        for (String intent : List.of("order.heal", "order.fetch", "order.supply")) {
            check("nomodule.handler." + intent, !Hardware.execute(player, intent), "");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. order.heal: supply, cooldown, power
    // ---------------------------------------------------------------------------------------------

    private static void healPhase(ServerPlayer player, ItemStack controller, DroneEntity drone) {
        fit(controller, "omd_bat_300", "medic_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        player.getInventory().clearContent();
        ItemStack supplies = stack("medical_supply");
        supplies.setCount(2);
        player.getInventory().setItem(0, supplies);
        player.setHealth(6F);
        // No medical supply: the Alba vendor consumes one per use, so the order must be refused here and
        // must not burn the cooldown either.
        player.getInventory().clearContent();
        say(player, "治疗");
        check("heal.needs_supply", player.getHealth() == 6F && count(player, "medical_supply") == 0,
                "health=" + player.getHealth() + " supplies=" + count(player, "medical_supply"));
        // Withdraw the supply back in: the same order now starts a use, so the gate was the supply.
        supplies.setCount(2);
        player.getInventory().setItem(0, supplies);
        int energyBefore = Hardware.energy(controller);

        say(player, "治疗");
        check("heal.supply_consumed", count(player, "medical_supply") == 1,
                "supplies=" + count(player, "medical_supply") + " (expected 2 -> 1)");

        // Second order inside the same use: refused, and it must not burn another supply.
        say(player, "治疗");
        check("heal.second_order_refused", count(player, "medical_supply") == 1,
                "supplies=" + count(player, "medical_supply"));

        // Drive the once-per-second medic step: health up, energy down by the active draw above idle.
        float healthBefore = player.getHealth();
        int healedSeconds = 0;
        for (int second = 0; second < 20; second++) {
            float before = player.getHealth();
            drone.systems().second(drone, player, controller);
            if (player.getHealth() > before) healedSeconds++;
        }
        int spent = energyBefore - Hardware.energy(controller);
        check("heal.health_rose", player.getHealth() > healthBefore,
                "health " + healthBefore + " -> " + player.getHealth());
        // medic_mk1: active_drain_watts 12, drain_watts 4 -> 20 * 8 = 160 FE per healing second.
        check("heal.energy_spent", spent == 160 * healedSeconds,
                "spent=" + spent + " seconds=" + healedSeconds + " (expected 160 FE each)");
        check("heal.once_per_use", healedSeconds <= 12 && player.getHealth() <= 20F,
                "seconds=" + healedSeconds + " health=" + player.getHealth());
        // A finished use rests on its cooldown (medic_mk1: 800 ticks), so the supply is not burned again.
        check("heal.auto_restart_on_cooldown", count(player, "medical_supply") == 1,
                "supplies=" + count(player, "medical_supply"));

        player.setHealth(6F);
        say(player, "治疗");
        check("heal.cooldown_refused", count(player, "medical_supply") == 1,
                "supplies=" + count(player, "medical_supply") + " (cooldown must refuse without spending)");

        // A full-health pilot has nothing to heal: the order is refused for "no target".
        player.setHealth(player.getMaxHealth());
        check("heal.full_health_refused", !Hardware.execute(player, "order.heal"), "health is full");
        LOG.info("[gap-smoke] heal: health={} energy={}/{} supplies={}", player.getHealth(),
                Hardware.energy(controller), Hardware.capacity(controller), count(player, "medical_supply"));
    }

    // ---------------------------------------------------------------------------------------------
    // 5. order.supply: one stack out of the hold, conserved
    // ---------------------------------------------------------------------------------------------

    private static void supplyPhase(ServerPlayer player, ItemStack controller) {
        fit(controller, "omd_bat_300", "carry_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        player.getInventory().clearContent();
        clearOffhand(player);
        player.getInventory().selected = 0;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ARROW, 1));
        CarryLight.save(controller, new ArrayList<>(List.of(new ItemStack(Items.ARROW, 10))));
        int before = count(player, Items.ARROW) + holdCount(controller);

        say(player, "递弹");
        int after = count(player, Items.ARROW) + holdCount(controller);
        check("supply.delivered", holdCount(controller) == 0 && count(player, Items.ARROW) == 11,
                "hold=" + holdCount(controller) + " arrows=" + count(player, Items.ARROW));
        check("supply.conserved", before == after, before + " -> " + after);
        // The 1.21 toast is the link module's line; it must not be printed when nothing moved.
        say(player, "递弹");
        check("supply.empty_hold_refused", count(player, Items.ARROW) == 11 && holdCount(controller) == 0,
                "arrows=" + count(player, Items.ARROW));
        check("supply.handler_false_when_empty", !Hardware.execute(player, "order.supply"), "");
    }

    // ---------------------------------------------------------------------------------------------
    // 6. order.fetch: the whole hold, conserved
    // ---------------------------------------------------------------------------------------------

    private static void fetchPhase(ServerPlayer player, ItemStack controller) {
        fit(controller, "omd_bat_300", "carry_mk1");
        Hardware.setEnergy(controller, Hardware.capacity(controller));
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        CarryLight.save(controller, new ArrayList<>(List.of(
                new ItemStack(Items.BREAD, 5), new ItemStack(Items.TORCH, 3))));
        int before = count(player, Items.BREAD) + count(player, Items.TORCH) + holdCount(controller);

        say(player, "捡一下");
        int after = count(player, Items.BREAD) + count(player, Items.TORCH) + holdCount(controller);
        check("fetch.hold_emptied", holdCount(controller) == 0, "hold=" + holdCount(controller));
        check("fetch.bread", count(player, Items.BREAD) == 5, "bread=" + count(player, Items.BREAD));
        check("fetch.torch", count(player, Items.TORCH) == 3, "torch=" + count(player, Items.TORCH));
        check("fetch.conserved", before == after, before + " -> " + after);
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Offline module / brown-out: every order is refused, and the drain figure follows
    // ---------------------------------------------------------------------------------------------

    private static void offlinePhase(ServerPlayer player, ItemStack controller) {
        fit(controller, "omd_bat_300", "medic_mk1", "carry_mk1");
        player.getInventory().clearContent();
        ItemStack supplies = stack("medical_supply");
        supplies.setCount(1);
        player.getInventory().setItem(0, supplies);
        CarryLight.save(controller, new ArrayList<>(List.of(new ItemStack(Items.ARROW, 3))));
        player.setHealth(6F);

        Hardware.setEnergy(controller, 0);
        say(player, "治疗");
        say(player, "捡一下");
        say(player, "递弹");
        check("offline.heal_refused", count(player, "medical_supply") == 1 && player.getHealth() == 6F,
                "supplies=" + count(player, "medical_supply") + " health=" + player.getHealth());
        check("offline.fetch_refused", holdCount(controller) == 3, "hold=" + holdCount(controller));
        check("offline.supply_refused", holdCount(controller) == 3, "hold=" + holdCount(controller));
        check("offline.drain_0", Hardware.drainPerTick(controller) == 0, "energy=0");

        // One tenth of capacity is below the fifth that triggers the brown-out rule: exactly one module
        // is cut, the hungriest (carry 5 W/t beats medic 4 W/t), so the hold orders stay refused while
        // the drain figure only counts what is still running.
        Hardware.setEnergy(controller, Hardware.capacity(controller) / 10);
        check("brownout.carry_off", !Hardware.online(controller, "carry"), "carry is the hungriest module");
        check("brownout.medic_on", Hardware.online(controller, "medic"), "");
        check("brownout.drain_counts_online_only", Hardware.drainPerTick(controller) == 4,
                "drain=" + Hardware.drainPerTick(controller) + " (medic_mk1 4 W/t, carry dropped)");
        say(player, "递弹");
        check("brownout.supply_refused", holdCount(controller) == 3, "hold=" + holdCount(controller));
        say(player, "捡一下");
        check("brownout.fetch_refused", holdCount(controller) == 3, "hold=" + holdCount(controller));

        // The medic on its own is the module the rule cuts, so order.heal is refused for lack of power and
        // not for lack of anything else: the cooldown from the earlier phase is cleared first.
        fit(controller, "omd_bat_300", "medic_mk1");
        dev.corvus.data.CorvusData.of(controller).remove("dronepal_medic_ready");
        Hardware.setEnergy(controller, Hardware.capacity(controller) / 10);
        check("brownout.medic_only_off", !Hardware.online(controller, "medic"),
                "energy=" + Hardware.energy(controller) + "/" + Hardware.capacity(controller));
        int held = count(player, "medical_supply");
        player.setHealth(6F);
        say(player, "治疗");
        check("brownout.heal_refused", count(player, "medical_supply") == held && player.getHealth() == 6F,
                "supplies=" + count(player, "medical_supply") + " health=" + player.getHealth());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Fits a battery, the tactical chip (dialogue needs a chatting chip) and the given module ids. */
    private static void fit(ItemStack controller, String battery, String... modules) {
        List<ItemStack> parts = new ArrayList<>();
        parts.add(stack(battery));
        parts.add(new ItemStack(Hardware.CHIP_ITEMS.get("tactical").get()));
        for (String module : modules) parts.add(Hardware.moduleStack(module));
        Hardware.save(controller, parts);
    }

    /** Runs the registered {@code /dronepal} tree the way a player would, through the real dispatcher. */
    private static void say(ServerPlayer player, String text) {
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4), "dronepal " + text);
    }

    private static boolean wear(ServerPlayer player, ItemStack controller) {
        ItemStack worn = controller;
        boolean[] placed = {false};
        CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inventory -> inventory.getStacksHandler(DroneCurios.SLOT))
                .ifPresent(handler -> {
                    handler.getStacks().setStackInSlot(0, worn);
                    placed[0] = !handler.getStacks().getStackInSlot(0).isEmpty();
                });
        return placed[0];
    }

    private static void clearOffhand(ServerPlayer player) {
        player.getInventory().offhand.set(0, ItemStack.EMPTY);
    }

    private static int count(ServerPlayer player, String path) {
        return count(player, stack(path).getItem());
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static Hardware.Module module(ItemStack controller, String family) {
        return Hardware.installed(controller).stream()
                .filter(module -> module.family().equals(family)).findFirst().orElse(null);
    }

    private static int holdCount(ItemStack controller) {
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
            LOG.info("[gap-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        FAILURES.add(name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        LOG.error("[gap-smoke] FAIL {} {}", name, detail == null ? "" : detail);
    }
}
