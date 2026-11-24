package dev.corvus.devtools;

import com.mojang.authlib.GameProfile;
import dev.corvus.Batteries;
import dev.corvus.Corvus;
import dev.corvus.Frames;
import dev.corvus.Hardware;
import dev.corvus.LootSerializers;
import dev.corvus.PaintCans;
import dev.corvus.ServerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance run for the data &amp; hardware layer.
 *
 * <p>Inactive unless {@code -Dcorvus.hwtest=1}. It drives a dedicated server (no client needed), then
 * asserts the things that are easy to get wrong and impossible to see in a screenshot:</p>
 *
 * <ul>
 *   <li>frames/modules/batteries really came from the datapack, not from the jar fallback</li>
 *   <li>fitting a battery, a module and a chip survives an NBT save/load round trip unchanged</li>
 *   <li>over-slot, duplicate-family, second-battery and battery-tier installations are refused</li>
 *   <li>the brown-out rule takes exactly one module offline below one fifth charge and all of them at 0</li>
 *   <li>the per-tick receive budget is enforced on the cell</li>
 *   <li>the supply loot modifiers resolve, and a real chest roll produces mod loot at the documented rate</li>
 *   <li>the creative page holds the 53 entries this layer contributes</li>
 * </ul>
 *
 * <p>Not gameplay code: it never ships behaviour that runs without the system property.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class HardwareSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.hwtest"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-hw-smoke");

    private static final List<String> FAILURES = new ArrayList<>();
    private static int ticks;
    private static boolean ran;
    private static int passed;

    private HardwareSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.isStopped() || ran) return;
        // Datapacks are reloaded during server start, so by the first tick every table is live.
        if (++ticks < 20) return;
        ran = true;
        LOG.info("[hw-smoke] ==== hardware acceptance run ====");
        try {
            run(server);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[hw-smoke] harness crashed", error);
        }
        LOG.info("[hw-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[hw-smoke] FAILED {}", failure);
        server.halt(false);
    }

    private static void run(MinecraftServer server) {
        checkFrames();
        checkBatteries();
        checkModuleDefinitions();
        checkModuleSync();
        checkInstallAndRoundTrip(server);
        checkRejections();
        checkBrownOut();
        checkCreativePage(server);
        checkPaint(server);
        checkTooltips();
        checkLegacyIds();
        checkLegacyRemap();
        checkRecipes(server);
        checkLoot(server);
    }

    // ---------------------------------------------------------------------------------------------

    private static void checkFrames() {
        check("frames.datapack", Frames.reloadCount() > 0, "reloads=" + Frames.reloadCount());
        Frames.describeAll().forEach(line -> LOG.info("[hw-smoke] frame {}", line));

        Frames.Frame bedrock = Frames.byId("bedrock");
        check("frame.bedrock.slots", bedrock != null && bedrock.moduleSlots() == 4, String.valueOf(bedrock));
        check("frame.bedrock.ammo", bedrock != null && bedrock.ammoSlots() == 8, "");
        check("frame.bedrock.health", bedrock != null && bedrock.maxHealth() == 144F && bedrock.hitCap() == 12F, "");
        check("frame.bedrock.paint", bedrock != null && "crimson".equals(bedrock.defaultPaint()), "");
        check("frame.bedrock.rpg", bedrock != null && bedrock.allowsGun("rpg"), "");
        check("frame.bedrock.collision", bedrock != null && Math.abs(bedrock.width() - 0.9F) < 1.0E-4F
                && Math.abs(bedrock.height() - 0.9F) < 1.0E-4F, "");

        Frames.Frame owl = Frames.byId("owl");
        check("frame.owl.limits", owl != null && owl.moduleSlots() == 2 && owl.ammoSlots() == 4
                && owl.maxBatteryTier() == 2, String.valueOf(owl));
        check("frame.owl.guns", owl != null && owl.allowsGun("smg") && !owl.allowsGun("rpg"), "");

        Frames.Frame scrap = Frames.byId("scrap");
        check("frame.scrap.quirks", scrap != null && scrap.rollsMalfunction()
                && Math.abs(scrap.teleportFailChance() - 0.08F) < 1.0E-4F, String.valueOf(scrap));
        check("frame.peregrine.teleport", Frames.byId("peregrine").teleportThreshold() == 16F, "");

        check("alias.scout", "owl".equals(Frames.canonical("scout")), Frames.canonical("scout"));
        check("alias.heavy", "bedrock".equals(Frames.canonical("heavy")), Frames.canonical("heavy"));
        check("alias.standard", "peregrine".equals(Frames.canonical("standard")), Frames.canonical("standard"));
        check("alias.namespaced", "bedrock".equals(Frames.canonical("huhu_dronepal:bedrock")), "");
        check("alias.unknown", "peregrine".equals(Frames.canonical("nonsense")), "");

        // Paint stored as a full resource location (older /give lines) still resolves.
        ItemStack controller = Frames.create("scrap");
        check("frame.create.paint", "rust".equals(Frames.paint(controller)), Frames.paint(controller));
    }

    private static void checkBatteries() {
        check("batteries.datapack", Batteries.reloadCount() > 0, "reloads=" + Batteries.reloadCount());
        List<String> missing = new ArrayList<>();
        for (String id : Batteries.IDS) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, id));
            if (item == null || Batteries.model(new ItemStack(item)) == null) missing.add(id);
        }
        check("batteries.ten", missing.isEmpty(), "missing=" + missing);

        Batteries.Model cell = Batteries.model(stack("ahi_bat_mk3"));
        check("battery.capacity", cell != null && cell.capacity() == 1_200_000 && cell.maxReceive() == 1000
                && cell.tier() == 3, String.valueOf(cell));

        Batteries.Model scrap = Batteries.model(stack("rn_bat_g1"));
        check("battery.leak", scrap != null && Math.abs(scrap.leakChance() - 0.02D) < 1.0E-9
                && Math.abs(scrap.leakFraction() - 0.05D) < 1.0E-9, String.valueOf(scrap));

        // A fresh cell reads as full: the charge key is absent until something writes it.
        check("battery.fresh_is_full", cell != null && Batteries.energy(stack("ahi_bat_mk3"), cell) == 1_200_000, "");
        check("battery.receive_rate", Batteries.receiveRate(cell, 1.0D) == 1000
                && Batteries.receiveRate(cell, 2.0D) == 2000, "rate=" + Batteries.receiveRate(cell));
        check("battery.not_a_battery", Batteries.model(stack("module")) == null, "");
    }

    private static void checkModuleDefinitions() {
        check("modules.datapack", Hardware.moduleReloads() > 0, "reloads=" + Hardware.moduleReloads());
        check("modules.count", Hardware.definitions().size() == 27, "count=" + Hardware.definitions().size());

        Hardware.Module combat = Hardware.definition(Hardware.moduleStack("combat_mk2"));
        check("module.combat", combat != null && combat.family().equals("combat") && combat.tier() == 2
                && combat.drain() == 18, String.valueOf(combat));
        check("module.combat.draw", combat != null && combat.draw(20) == Math.round(18 * 20 * 1.1F),
                combat == null ? "null" : String.valueOf(combat.draw(20)));
        check("module.combat.intents", combat != null && combat.intents().contains("order.cover"), "");

        Hardware.Module rust = Hardware.definition(Hardware.moduleStack("carry_rust_g1"));
        check("module.rust.trait", rust != null && rust.trait("drain_multiplier", 1D) < 1D,
                rust == null ? "null" : String.valueOf(rust.trait("drain_multiplier", 1D)));

        Hardware.Module medic = Hardware.definition(Hardware.moduleStack("medic_mk1"));
        check("module.medic.supply", medic != null && medic.requiresSupply(), "alb consumes a medkit");

        ItemStack bad = new ItemStack(Hardware.MODULE.get());
        check("module.unknown_product", Hardware.definition(bad) == null, "");
    }

    /**
     * The client's copy of the server module table (ModuleSync). A malformed or empty payload must not be
     * able to blank the table; a real one must replace the jar fallback.
     */
    private static void checkModuleSync() {
        var live = Hardware.definitions();
        Hardware.acceptSynced(null);
        Hardware.acceptSynced(java.util.Map.of());
        check("sync.ignores_empty_payload", Hardware.definitions().size() == live.size()
                && Hardware.definition(Hardware.moduleStack("combat_mk2")) != null,
                "size=" + Hardware.definitions().size());
        Hardware.acceptSynced(live);
        Hardware.Module combat = Hardware.definition(Hardware.moduleStack("combat_mk2"));
        check("sync.accepts_table", Hardware.synced() && Hardware.definitions().size() == 27
                && combat != null && combat.tier() == 2, "size=" + Hardware.definitions().size());
    }

    private static void checkInstallAndRoundTrip(MinecraftServer server) {
        ServerPlayer actor = fakePlayer(server);
        ItemStack controller = Frames.create("peregrine");
        actor.getInventory().setItem(0, controller);
        actor.getInventory().setItem(1, stack("omd_bat_200"));
        actor.getInventory().setItem(2, Hardware.moduleStack("combat_mk1"));
        actor.getInventory().setItem(3, new ItemStack(Hardware.CHIP_ITEMS.get("tactical").get()));

        check("install.battery", "".equals(Hardware.installFromInventory(actor, controller, 1)), "");
        check("install.module", "".equals(Hardware.installFromInventory(actor, controller, 2)), "");
        check("install.chip", "".equals(Hardware.installFromInventory(actor, controller, 3)), "");
        check("install.consumed", actor.getInventory().getItem(1).isEmpty()
                && actor.getInventory().getItem(2).isEmpty()
                && actor.getInventory().getItem(3).isEmpty(), "");
        check("install.count", Hardware.contents(controller).size() == 3, Hardware.describe(controller));

        String before = Hardware.describe(controller);
        LOG.info("[hw-smoke] fitted: {}", before);
        CompoundTag saved = controller.save(new CompoundTag());
        ItemStack reloaded = ItemStack.of(saved);
        check("nbt.describe", before.equals(Hardware.describe(reloaded)), Hardware.describe(reloaded));
        check("nbt.raw_equal", controller.getTag() != null && controller.getTag().equals(reloaded.getTag()),
                "bytes=" + saved.size());
        check("nbt.energy_full", Hardware.energy(reloaded) == 350_000, "energy=" + Hardware.energy(reloaded));
        check("nbt.chip", "tactical".equals(Hardware.chip(reloaded)) && Hardware.hasChip(reloaded), "");
        check("nbt.installed_family", Hardware.installed(reloaded).size() == 1
                && Hardware.tier(reloaded, "combat") == 1, "");

        // Charge accounting: one tick accepts at most the model's receive rate (omd 200 -> 400 FE/t),
        // the rest of the request is dropped rather than queued, and the budget is spent once.
        Hardware.setEnergy(controller, 100_000);
        int accepted = Hardware.charge(controller, 1_000_000, false);
        check("charge.rate_cap", accepted == 400, "accepted=" + accepted + " of 1000000 requested");
        int again = Hardware.charge(controller, 1_000_000, false);
        check("charge.same_tick_refused", again == 0, "accepted=" + again);
        check("charge.persisted", Hardware.energy(ItemStack.of(controller.save(new CompoundTag()))) == 100_400,
                "energy=" + Hardware.energy(controller));
        // The budget is per cell, so a second, untouched cell still has its full allowance this tick.
        ItemStack fresh = Frames.create("peregrine");
        Hardware.save(fresh, new ArrayList<>(List.of(stack("omd_bat_200"))));
        Hardware.setEnergy(fresh, 100_000);
        int simulate = Hardware.charge(fresh, 1000, true);
        check("charge.simulate_spends_nothing", simulate == 400 && Hardware.energy(fresh) == 100_000,
                "simulated=" + simulate + " energy=" + Hardware.energy(fresh));
        Hardware.setEnergy(controller, 350_000);
        check("charge.full", Hardware.charge(controller, 1000, false) == 0, "energy=" + Hardware.energy(controller));

        // Removing the module returns it and leaves the battery in place.
        String removed = Hardware.removeToInventory(actor, controller, 1);
        check("remove.module", "".equals(removed), removed);
        check("remove.kept_battery", Hardware.batteryTier(controller) == 2 && Hardware.contents(controller).size() == 2,
                Hardware.describe(controller));
    }

    private static void checkRejections() {
        ItemStack owl = Frames.create("owl");
        check("limit.battery_ok", "".equals(Hardware.installError(owl, stack("ahi_bat_mk2"), List.of())),
                Hardware.installError(owl, stack("ahi_bat_mk2"), List.of()));
        List<ItemStack> parts = new ArrayList<>();
        parts.add(stack("ahi_bat_mk2"));
        check("limit.second_battery", "full".equals(Hardware.installError(owl, stack("omd_bat_100"), parts)),
                Hardware.installError(owl, stack("omd_bat_100"), parts));
        check("limit.battery_tier", "battery_tier".equals(Hardware.installError(owl, stack("ahi_bat_mk3"), List.of())),
                Hardware.installError(owl, stack("ahi_bat_mk3"), List.of()));

        parts.add(Hardware.moduleStack("combat_mk1"));
        parts.add(Hardware.moduleStack("scan_mk1"));
        check("limit.duplicate_family", "duplicate".equals(
                Hardware.installError(owl, Hardware.moduleStack("combat_mk2"), parts)),
                Hardware.installError(owl, Hardware.moduleStack("combat_mk2"), parts));
        // Owl has two module slots, and both are taken.
        check("limit.slots_full", "full".equals(Hardware.installError(owl, Hardware.moduleStack("carry_mk1"), parts)),
                Hardware.installError(owl, Hardware.moduleStack("carry_mk1"), parts));

        ItemStack bedrock = Frames.create("bedrock");
        List<ItemStack> wide = new ArrayList<>();
        wide.add(Hardware.moduleStack("combat_mk1"));
        wide.add(Hardware.moduleStack("scan_mk1"));
        wide.add(Hardware.moduleStack("carry_mk1"));
        check("limit.bedrock_fourth", "".equals(Hardware.installError(bedrock, Hardware.moduleStack("medic_mk1"), wide)), "");
        wide.add(Hardware.moduleStack("medic_mk1"));
        check("limit.bedrock_fifth", "full".equals(Hardware.installError(bedrock, Hardware.moduleStack("light_mk1"), wide)), "");

        parts.add(new ItemStack(Hardware.CHIP_ITEMS.get("silent").get()));
        check("limit.second_chip", "full".equals(Hardware.installError(owl, new ItemStack(Hardware.CHIP_ITEMS.get("chatty").get()), parts)),
                Hardware.installError(owl, new ItemStack(Hardware.CHIP_ITEMS.get("chatty").get()), parts));
        String nonPart = Hardware.installError(owl, new ItemStack(net.minecraft.world.item.Items.STICK), parts);
        check("limit.not_a_part", "unrelated".equals(nonPart) || "invalid".equals(nonPart), nonPart);

        // The combat module cannot be pulled while a gun is hanging off it. Without a gun handler one
        // cannot be fitted, so this only checks the "no gun -> allowed" side of the rule.
        check("limit.remove_module_ok", "".equals(Hardware.removalError(Hardware.moduleStack("combat_mk1"), parts)), "");
    }

    private static void checkBrownOut() {
        ItemStack controller = Frames.create("peregrine");
        List<ItemStack> parts = new ArrayList<>();
        parts.add(stack("omd_bat_200"));
        parts.add(Hardware.moduleStack("combat_mk1"));
        parts.add(Hardware.moduleStack("scan_mk1"));
        parts.add(Hardware.moduleStack("carry_mk1"));
        Hardware.save(controller, parts);

        check("power.full.combat", Hardware.online(controller, "combat"), "full charge");
        check("power.full.scan", Hardware.online(controller, "scan"), "");

        Hardware.setEnergy(controller, 350_000 / 10);
        check("power.low.combat_off", !Hardware.online(controller, "combat"),
                "energy=" + Hardware.energy(controller) + " draw=" + Hardware.installed(controller).get(0).draw(20));
        check("power.low.scan_on", Hardware.online(controller, "scan"), "");
        check("power.low.carry_on", Hardware.online(controller, "carry"), "");

        Hardware.setEnergy(controller, 0);
        check("power.empty.combat", !Hardware.online(controller, "combat"), "");
        check("power.empty.scan", !Hardware.online(controller, "scan"), "");
        check("power.empty.carry", !Hardware.online(controller, "carry"), "");
        check("power.empty.capacity", Hardware.capacity(controller) == 350_000, "");
        check("power.no_battery", !Hardware.online(Frames.create("peregrine"), "combat"), "");
    }

    private static void checkCreativePage(MinecraftServer server) {
        CreativeModeTab tab = Corvus.TAB.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, false,
                server.registryAccess()));
        int size = tab.getDisplayItems().size();
        // 4 frames + 27 modules + 3 chips + 10 batteries + 9 spray cans (this layer) + bench + 23 parts
        // + dock (the UI/block layer) = the 78 entries the 1.21 build has.
        check("creative.entries_78", size == 78, "entries=" + size);
        LOG.info("[hw-smoke] creative page: {} entries (4 frames + 27 modules + 3 chips + 10 batteries"
                + " + 9 cans + bench + 23 parts + dock)", size);
    }

    /**
     * Spraying a drone: the colour has to land in the terminal NBT (survives recall/save) and on the
     * entity's synced data (visible immediately). The interaction event itself needs a real player and a
     * Curios slot, so this drives the shared core that the event calls.
     */
    private static void checkPaint(MinecraftServer server) {
        dev.corvus.DroneEntity drone = new dev.corvus.DroneEntity(Corvus.ENTITY.get(), server.overworld());
        ItemStack controller = Frames.create("peregrine");
        check("paint.before", "standard".equals(Frames.paint(controller))
                && "standard".equals(drone.getPaintName()), Frames.paint(controller));
        check("paint.applied", PaintCans.paint(controller, drone, "neon"), "apply neon");
        check("paint.terminal_nbt",
                "neon".equals(Frames.paint(ItemStack.of(controller.save(new CompoundTag())))),
                Frames.paint(controller));
        check("paint.entity_sync", "neon".equals(drone.getPaintName()), drone.getPaintName());
        check("paint.idempotent", !PaintCans.paint(controller, drone, "neon"),
                "a second click with the same colour must not consume the can");
        check("paint.unknown_colour", !PaintCans.paint(controller, drone, "chartreuse"), "");
        check("paint.terminal_only", PaintCans.paint(controller, null, "void")
                && "void".equals(Frames.paint(controller)), "no entity: NBT only");
    }

    /** Hover text must exist for every product the datapack defines, not just the ones with names. */
    private static void checkTooltips() {
        List<Component> lines = dev.corvus.ModuleTooltips.describe(Hardware.definition(Hardware.moduleStack("combat_mk2")));
        check("tooltip.combat_lines", lines.size() >= 3, "lines=" + lines.size());
        String first = lines.isEmpty() ? "" : lines.get(0).getString();
        check("tooltip.combat_summary", !first.isEmpty(), first);

        List<String> empty = new ArrayList<>();
        for (String family : Hardware.FAMILIES) {
            Hardware.Module module = Hardware.definition(Hardware.moduleStack(family + "_mk3"));
            if (module == null || dev.corvus.ModuleTooltips.describe(module).isEmpty()) empty.add(family);
        }
        check("tooltip.every_family", empty.isEmpty(), "empty=" + empty);

        ItemStack tooltip = Hardware.moduleStack("scan_rust_g1");
        List<Component> moduleLines = new ArrayList<>();
        dev.corvus.ModuleTooltips.append(tooltip, moduleLines);
        check("tooltip.append", moduleLines.size() >= 2, "lines=" + moduleLines.size());
    }

    /** Legacy ids must still resolve, or a carried-over world loses its batteries. */
    private static void checkLegacyIds() {
        ResourceLocation renamed = Hardware.legacyTarget(new ResourceLocation(Corvus.ID, "battery_mk2"));
        check("legacy.battery_renamed", new ResourceLocation(Corvus.ID, "ahi_bat_mk2").equals(renamed),
                String.valueOf(renamed));
        check("legacy.battery_target_exists",
                renamed != null && ForgeRegistries.ITEMS.getValue(renamed) != null, String.valueOf(renamed));
        ResourceLocation namespaced = Hardware.legacyTarget(new ResourceLocation("dronepal", "paint_can_neon"));
        check("legacy.namespace", new ResourceLocation(Corvus.ID, "paint_can_neon").equals(namespaced),
                String.valueOf(namespaced));
        check("legacy.unknown", Hardware.legacyTarget(new ResourceLocation("minecraft", "stone")) == null, "");
        // If Forge remapped the old id in this world, the alias must point at the new battery, never at
        // some other item: a carried-over stack has to keep its charge and its model.
        ResourceLocation oldId = new ResourceLocation(Corvus.ID, "battery_mk1");
        Item oldItem = ForgeRegistries.ITEMS.getValue(oldId);
        Item newItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, "ahi_bat_mk1"));
        Item unrelated = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, "definitely_not_an_item"));
        LOG.info("[hw-smoke] legacy id {} -> {} (new={}, unregistered probe={})", oldId,
                oldItem == null ? "null" : ForgeRegistries.ITEMS.getKey(oldItem), newItem == null ? "null"
                        : ForgeRegistries.ITEMS.getKey(newItem),
                unrelated == null ? "null" : ForgeRegistries.ITEMS.getKey(unrelated));
        check("legacy.old_id_never_foreign", oldItem == null || oldItem == newItem
                || oldItem == net.minecraft.world.item.Items.AIR, String.valueOf(oldItem));
    }

    /**
     * Drives the real {@code MissingMappingsEvent} handler with a synthetic mapping for the pre-vendor
     * battery id, then reads back what the handler decided.
     *
     * <p>Forge itself turns a REMAP into a registry alias while it processes missing mappings at world
     * load; that part is Forge's code. What is worth proving here is that our handler answers REMAP with
     * the vendor-prefixed battery instead of leaving the id to be dropped.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void checkLegacyRemap() {
        Item expected = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, "ahi_bat_mk1"));
        try {
            var registry = ForgeRegistries.ITEMS;
            var mapping = new net.minecraftforge.registries.MissingMappingsEvent.Mapping<Item>(
                    registry, registry, new ResourceLocation(Corvus.ID, "battery_mk1"), 0);
            var event = new net.minecraftforge.registries.MissingMappingsEvent(
                    (net.minecraft.resources.ResourceKey) ForgeRegistries.Keys.ITEMS, registry,
                    (java.util.Collection) List.of(mapping));
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);

            var actionField = mapping.getClass().getDeclaredField("action");
            actionField.setAccessible(true);
            var targetField = mapping.getClass().getDeclaredField("target");
            targetField.setAccessible(true);
            String action = String.valueOf(actionField.get(mapping));
            Object target = targetField.get(mapping);
            check("legacy.handler_remaps", "REMAP".equals(action) && target == expected,
                    "action=" + action + " target=" + ForgeRegistries.ITEMS.getKey((Item) target));
        } catch (ReflectiveOperationException | RuntimeException error) {
            check("legacy.handler_remaps", false, "synthetic mapping threw " + error);
        }
    }

    /** The datapack has to survive a real load: recipes are the easiest thing to get subtly wrong. */
    private static void checkRecipes(MinecraftServer server) {
        List<String> ours = new ArrayList<>();
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            if (Corvus.ID.equals(recipe.getId().getNamespace())) ours.add(recipe.getId().getPath());
        }
        // 20 files: 19 load here. corvus_manual produces patchouli:guide_book, and Patchouli is not a
        // dependency of this build, so the recipe is (correctly) skipped rather than producing a ghost item.
        check("recipes.count", ours.size() == 19, "loaded=" + ours.size() + " " + ours);
        check("recipes.manual_needs_patchouli", !ours.contains("corvus_manual"), "");
        check("recipes.paint_can", ours.contains("paint_can_rust"), "");
        check("recipes.bench", ours.stream().anyMatch(id -> id.endsWith("assembly_bench")), "");
        check("recipes.dock", ours.stream().anyMatch(id -> id.endsWith("charging_dock")), "");
    }

    private static void checkLoot(MinecraftServer server) {
        check("loot.serializer", ForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS.get()
                .containsKey(new ResourceLocation(Corvus.ID, "supply")), "");
        check("loot.serializer_registered", LootSerializers.SUPPLY.get() != null, "");

        for (String name : List.of("military", "salvage", "medical")) {
            LootTable table = server.getLootData().getLootTable(new ResourceLocation(Corvus.ID, "supplies/" + name));
            check("loot.table." + name, table != LootTable.EMPTY && table != null,
                    String.valueOf(new ResourceLocation(Corvus.ID, "supplies/" + name)));
        }

        // Roll the pillager outpost chest many times: the military modifier should fire at 42%.
        LootTable outpost = server.getLootData().getLootTable(new ResourceLocation("minecraft", "chests/pillager_outpost"));
        LootParams params = new LootParams.Builder(server.overworld())
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        net.minecraft.world.phys.Vec3.atCenterOf(server.overworld().getSharedSpawnPos()))
                .withLuck(0F)
                .create(LootContextParamSets.CHEST);
        int rolls = 500;
        int hits = 0;
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            boolean found = false;
            for (ItemStack stack : outpost.getRandomItems(params)) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null && Corvus.ID.equals(id.getNamespace())) {
                    found = true;
                    if (samples.size() < 6) samples.add(describeStack(stack));
                }
            }
            if (found) hits++;
        }
        double rate = (double) hits / rolls;
        LOG.info("[hw-smoke] pillager_outpost rolls={} hits={} rate={} samples={}", rolls, hits,
                String.format("%.3f", rate), samples);
        check("loot.rate", rate > 0.30D && rate < 0.54D, "rate=" + rate);

        // A supply table roll must produce a real stack, not a broken entry.
        LootTable military = server.getLootData().getLootTable(new ResourceLocation(Corvus.ID, "supplies/military"));
        boolean produced = false;
        for (int i = 0; i < 40 && !produced; i++) {
            for (ItemStack stack : military.getRandomItems(params)) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id == null || !Corvus.ID.equals(id.getNamespace())) continue;
                produced = true;
                if (stack.is(Corvus.DRONE.get())) {
                    check("loot.drone_serial", dev.corvus.DroneItem.serial(stack) != null, describeStack(stack));
                }
            }
        }
        check("loot.supply_items", produced, "military table produced no mod item in 40 rolls");

        check("loot.paint_cans", PaintCans.colorOf(can("neon")) != null && PaintCans.PALETTE.size() == 9, PaintCans.describe());
        check("config.charge_rate", ServerOptions.chargeMultiplier() >= 0.25D, String.valueOf(ServerOptions.chargeMultiplier()));
    }

    // ---------------------------------------------------------------------------------------------

    private static String describeStack(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        CompoundTag tag = stack.getTag();
        return (id == null ? "?" : id.toString()) + (tag == null ? "" : " " + tag);
    }

    private static ItemStack stack(String path) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, path));
        if (item == null) throw new IllegalStateException("Unregistered item " + path);
        return new ItemStack(item);
    }

    private static ItemStack can(String color) {
        return stack("paint_can_" + color);
    }

    private static ServerPlayer fakePlayer(MinecraftServer server) {
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("corvus-hw-smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "HwSmoke");
        return net.minecraftforge.common.util.FakePlayerFactory.get(server.overworld(), profile);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[hw-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        FAILURES.add(name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }
}
