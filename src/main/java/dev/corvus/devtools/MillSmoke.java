package dev.corvus.devtools;

import com.mojang.authlib.GameProfile;
import dev.corvus.Corvus;
import dev.corvus.DroneDeployment;
import dev.corvus.DroneEntity;
import dev.corvus.Frames;
import dev.corvus.Hardware;
import dev.corvus.SurvivalWorks;
import dev.corvus.data.CorvusKeys;
import dev.corvus.mill.DroneWork;
import dev.corvus.mill.MillItems;
import dev.corvus.mill.OreKind;
import dev.corvus.mill.OreMillBlock;
import dev.corvus.mill.OreMillEntity;
import dev.corvus.mill.OreMillMenu;
import dev.corvus.mill.OreMillRegistry;
import dev.corvus.mill.OreMillRules;
import dev.corvus.mill.WaterPipeBlock;
import dev.corvus.mill.WaterPipeNetwork;
import dev.corvus.mill.WaterPumpEntity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only acceptance run for the ore mill, the water pump and drone work.
 *
 * <p>Inactive unless {@code -Dcorvus.mill=1} or {@code CORVUS_MILLTEST=1}. Numerical checks drive a
 * detached block entity tick by tick (the same calls the world ticker makes), which lets the harness
 * measure exact 120/30-tick jobs and 200-job statistical rolls without waiting in real time. Pump and GUI
 * checks use real blocks in the world.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class MillSmoke {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.mill"))
            || "1".equals(System.getenv("CORVUS_MILLTEST"));
    private static final Logger LOG = LoggerFactory.getLogger("corvus-mill-smoke");
    private static final List<String> FAILURES = new ArrayList<>();

    private static int passed;
    private static int ticks;
    private static boolean ran;
    private static int step;
    private static int wait;

    private static MinecraftServer server;
    private static ServerLevel level;
    private static BlockPos origin;
    private static BlockPos pumpMillPos, pumpPos, sourcePos, dryPumpPos, dryMillPos, dropPos, pumpDropPos,
            callPos, otherPos, farPos;

    private static OreMillEntity manualMill, droneMill, rareMill, byproductMill, droneByproductMill,
            upgradeMill, scanMill, waterMill, noPowerMill, outputMill, persistMill, sieveMill;
    private static OreMillEntity pumpMill, dryMill, callMill, otherMill, farMill;
    private static WaterPumpEntity pump, dryPump;
    private static ServerPlayer player;
    private static DroneEntity drone;
    private static ItemStack controller;
    private static final UUID DRONE_ID = UUID.nameUUIDFromBytes("corvus-mill-smoke-drone".getBytes());

    private MillSmoke() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        MinecraftServer current = event.getServer();
        if (current.isStopped() || ran) return;
        if (++ticks < 20) return;
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            advance(current);
        } catch (Throwable error) {
            fail("harness", "threw " + error);
            LOG.error("[mill-smoke] harness crashed", error);
            finish(current);
        }
    }

    private static void advance(MinecraftServer current) {
        final int doing = step;
        step = doing + 1;
        switch (doing) {
            case 0 -> setup(current);
            case 1 -> manualTiming();
            case 2 -> manualByproducts();
            case 3 -> byproductsAndWells();
            case 4 -> rareOre();
            case 5 -> upgradesAndWater();
            case 6 -> droneJob();
            case 7 -> airframesAndModules();
            case 8 -> batteryContract();
            case 9 -> stopConditions();
            case 10 -> pumpBehaviour();
            case 11 -> persistenceAndDrops();
            case 12 -> guiAndOrders();
            case 13 -> twoBlockAndPipes();
            case 14 -> registryFacts();
            case 15 -> finish(current);
            default -> finish(current);
        }
    }

    // ---- setup ----

    private static void setup(MinecraftServer current) {
        server = current;
        level = current.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 40;
        int z = spawn.getZ() + 40;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        origin = new BlockPos(x, surface + 8, z);

        int seed = 20261125;
        manualMill = standalone(400, seed++);
        droneMill = standalone(401, seed++);
        rareMill = standalone(402, seed++);
        byproductMill = standalone(403, seed++);
        droneByproductMill = standalone(404, seed++);
        upgradeMill = standalone(405, seed++);
        scanMill = standalone(406, seed++);
        waterMill = standalone(407, seed++);
        noPowerMill = standalone(408, seed++);
        outputMill = standalone(409, seed++);
        persistMill = standalone(410, seed++);
        sieveMill = standalone(411, seed++);

        pumpMillPos = at(0, 0);
        pumpPos = at(1, 0);
        sourcePos = pumpPos.above();
        dryPumpPos = at(4, 0);
        dryMillPos = at(3, 0);
        dropPos = at(6, 0);
        pumpDropPos = at(8, 0);
        callPos = at(0, 2);
        otherPos = at(2, 2);
        farPos = at(36, 2);

        place(pumpMillPos, OreMillRegistry.MILL.get());
        place(dryMillPos, OreMillRegistry.MILL.get());
        place(dropPos, OreMillRegistry.MILL.get());
        place(pumpDropPos, OreMillRegistry.PUMP.get());
        place(callPos, OreMillRegistry.MILL.get());
        place(otherPos, OreMillRegistry.MILL.get());
        place(farPos, OreMillRegistry.MILL.get());
        place(sourcePos, Blocks.WATER);
        pumpMill = mill(pumpMillPos);
        dryMill = mill(dryMillPos);
        callMill = mill(callPos);
        otherMill = mill(otherPos);
        farMill = mill(farPos);
        pump = (WaterPumpEntity) OreMillRegistry.PUMP_TYPE.get().create(pumpPos,
                OreMillRegistry.PUMP.get().defaultBlockState());
        dryPump = (WaterPumpEntity) OreMillRegistry.PUMP_TYPE.get().create(dryPumpPos,
                OreMillRegistry.PUMP.get().defaultBlockState());

        for (int cx = (x >> 4) - 2; cx <= ((x + 40) >> 4) + 1; cx++) {
            for (int cz = (z >> 4) - 2; cz <= ((z + 4) >> 4) + 1; cz++) level.setChunkForced(cx, cz, true);
        }
        level.setWeatherParameters(6000, 0, false, false);

        player = fakePlayer();
        player.moveTo(callPos.getX() + 0.5D, callPos.getY() + 1.0D, callPos.getZ() + 0.5D);
        LOG.info("[mill-smoke] site {} player={}", origin, player.getName().getString());

        check("registry.block", OreMillRegistry.MILL.get() != null && OreMillRegistry.PUMP.get() != null, "");
        check("registry.items", ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "iron_concentrate"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "copper_concentrate"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "diamond_shard"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "coarse_iron"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "coarse_lapis"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "sieve"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "cooling_tower"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "water_pump"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "water_pipe")), "");
        check("registry.menu", OreMillRegistry.MENU.get() != null && OreMillRegistry.TYPE.get() != null
                && OreMillRegistry.PUMP_TYPE.get() != null, "");
        check("kind.reads_raw", OreKind.of(new ItemStack(Items.RAW_IRON)) == OreKind.IRON
                && OreKind.of(new ItemStack(Items.DIAMOND_ORE)) == OreKind.DIAMOND
                && OreKind.of(new ItemStack(Items.ANCIENT_DEBRIS)) == OreKind.DEBRIS
                && OreKind.of(new ItemStack(Items.DIRT)) == null, "");
    }

    // ---- manual ----

    private static void manualTiming() {
        manualMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 8));
        manualMill.setEnergy(OreMillRules.BUFFER);
        manualMill.setWater(OreMillRules.WATER_CAPACITY);
        run(manualMill, OreMillRules.MANUAL_TICKS - 1);
        check("manual.progress_119", manualMill.progress() == OreMillRules.MANUAL_TICKS - 1
                && output(manualMill, 0).isEmpty(), "progress=" + manualMill.progress());
        run(manualMill, 1);
        ItemStack result = output(manualMill, 0);
        check("manual.one_concentrate", result.is(iron()) && result.getCount() == 1,
                "result=" + result);
        check("manual.energy_1200", manualMill.energy() == OreMillRules.BUFFER - OreMillRules.FE_PER_ITEM,
                "energy=" + manualMill.energy());
        check("manual.water_250", manualMill.water() == OreMillRules.WATER_CAPACITY - OreMillRules.WATER_PER_ITEM,
                "water=" + manualMill.water());
        check("manual.consumed_one", manualMill.inventory().getItem(0).getCount() == 7,
                "input=" + manualMill.inventory().getItem(0).getCount());

        run(manualMill, OreMillRules.MANUAL_TICKS * 2);
        check("manual.three_jobs", total(manualMill, iron()) == 3,
                "refined=" + total(manualMill, iron()));
        check("manual.three_water", manualMill.water()
                == OreMillRules.WATER_CAPACITY - OreMillRules.WATER_PER_ITEM * 3, "water=" + manualMill.water());
        check("manual.three_energy", manualMill.energy()
                == OreMillRules.BUFFER - OreMillRules.FE_PER_ITEM * 3, "energy=" + manualMill.energy());
    }

    private static void manualByproducts() {
        int jobs = 200, coarse = 0;
        for (int job = 0; job < jobs; job++) {
            byproductMill.inventory().setItem(0, new ItemStack(Items.RAW_COPPER, 1));
            byproductMill.setEnergy(OreMillRules.FE_PER_ITEM);
            byproductMill.setWater(OreMillRules.WATER_PER_ITEM);
            run(byproductMill, OreMillRules.MANUAL_TICKS);
            coarse += total(byproductMill, MillItems.coarse(OreKind.COPPER, 1).getItem());
            if (total(byproductMill, MillItems.REFINED.get(OreKind.COPPER).get()) != 1) {
                fail("manual.byproduct_yield", "job " + job + " did not produce one concentrate");
                return;
            }
            clearOutputs(byproductMill);
        }
        check("manual.byproduct_20", coarse > jobs * 0.15 && coarse < jobs * 0.25,
                coarse + "/" + jobs);
        check("manual.common_no_shard", total(byproductMill, diamondShard()) == 0, "shard from copper");
        check("chance.values", Math.abs(OreMillRules.byproductChance(false, false) - 0.20D) < 1e-9
                && Math.abs(OreMillRules.byproductChance(true, false) - 0.35D) < 1e-9
                && Math.abs(OreMillRules.byproductChance(true, true) - 0.50D) < 1e-9
                && Math.abs(OreMillRules.rareChance(false) - 0.12D) < 1e-9
                && Math.abs(OreMillRules.rareChance(true) - 0.18D) < 1e-9, "");
    }

    // ---- per-mineral by-products and the three role wells ---------------------------------------

    /** Runs one-ore jobs until the by-product well takes a roll; leaves that roll in the well and returns it. */
    private static ItemStack grindUntilSand(OreMillEntity mill, Item ore) {
        for (int job = 0; job < 80; job++) {
            mill.inventory().setItem(0, new ItemStack(ore, 1));
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY, ItemStack.EMPTY);
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_BYPRODUCT, ItemStack.EMPTY);
            run(mill, OreMillRules.MANUAL_TICKS);
            if (!output(mill, OreMillMenu.OUTPUT_BYPRODUCT).isEmpty()) break;
        }
        return output(mill, OreMillMenu.OUTPUT_BYPRODUCT);
    }

    /**
     * The acceptance pass for the per-mineral by-product and the three role wells: iron ore leaves
     * coarse_iron and lapis ore coarse_lapis, nine sands craft one concentrate of their own family, the
     * wells stay primary / by-product / extra without mixing, a full well stops the mill and keeps the
     * finished job, and the old mixed {@code coarse_concentrate} is gone from the game.
     */
    private static void byproductsAndWells() {
        OreMillEntity ironMill = standalone(450, 950);
        OreMillEntity lapisMill = standalone(451, 951);
        ItemStack ironSand = grindUntilSand(ironMill, Items.IRON_ORE);
        ItemStack lapisSand = grindUntilSand(lapisMill, Items.LAPIS_ORE);
        check("coarse.per_kind", ironSand.is(MillItems.coarse(OreKind.IRON, 1).getItem())
                        && lapisSand.is(MillItems.coarse(OreKind.LAPIS, 1).getItem())
                        && !ItemStack.isSameItem(ironSand, lapisSand)
                        && output(ironMill, OreMillMenu.OUTPUT_PRIMARY).is(iron())
                        && output(lapisMill, OreMillMenu.OUTPUT_PRIMARY).is(MillItems.REFINED.get(OreKind.LAPIS).get()),
                "iron=" + ironSand + " lapis=" + lapisSand);

        boolean craft = true;
        StringBuilder detail = new StringBuilder();
        for (OreKind kind : OreKind.values()) {
            if (!kind.hasConcentrate()) continue;
            TransientCraftingContainer grid = new TransientCraftingContainer(dummyMenu(), 3, 3);
            for (int i = 0; i < 9; i++) grid.setItem(i, MillItems.coarse(kind, 1));
            var recipe = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level);
            if (recipe.isEmpty()
                    || !recipe.get().getResultItem(server.registryAccess()).is(MillItems.REFINED.get(kind).get())
                    || recipe.get().getResultItem(server.registryAccess()).getCount() != 1) {
                craft = false;
                detail.append(kind.id()).append(' ');
                continue;
            }
            TransientCraftingContainer eight = new TransientCraftingContainer(dummyMenu(), 3, 3);
            for (int i = 0; i < 8; i++) eight.setItem(i, MillItems.coarse(kind, 1));
            if (server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, eight, level).isPresent()) {
                craft = false;
                detail.append("eight-").append(kind.id()).append(' ');
            }
        }
        check("coarse.nine_craft_one", craft, detail.toString());

        OreMillEntity goldMill = standalone(452, 952);
        grindUntilSand(goldMill, Items.RAW_GOLD);
        OreMillEntity rareMill = standalone(453, 953);
        for (int job = 0; job < 80 && output(rareMill, OreMillMenu.OUTPUT_EXTRA).isEmpty(); job++) {
            rareMill.inventory().setItem(0, new ItemStack(Items.DIAMOND_ORE, 1));
            rareMill.setEnergy(OreMillRules.FE_PER_ITEM);
            rareMill.setWater(OreMillRules.WATER_PER_ITEM);
            rareMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY, ItemStack.EMPTY);
            run(rareMill, OreMillRules.MANUAL_TICKS);
        }
        check("slots.role_split", output(goldMill, OreMillMenu.OUTPUT_PRIMARY).is(MillItems.REFINED.get(OreKind.GOLD).get())
                        && MillItems.coarseKind(output(goldMill, OreMillMenu.OUTPUT_BYPRODUCT)) == OreKind.GOLD
                        && output(goldMill, OreMillMenu.OUTPUT_EXTRA).isEmpty()
                        && output(rareMill, OreMillMenu.OUTPUT_PRIMARY).is(diamondShard())
                        && output(rareMill, OreMillMenu.OUTPUT_EXTRA).is(diamondShard())
                        && output(rareMill, OreMillMenu.OUTPUT_BYPRODUCT).isEmpty(),
                "gold=" + output(goldMill, 0) + "|" + output(goldMill, 1) + "|" + output(goldMill, 2)
                        + " diamond=" + output(rareMill, 0) + "|" + output(rareMill, 1) + "|" + output(rareMill, 2));

        OreMillEntity blocked = standalone(454, 954);
        blocked.inventory().setItem(0, new ItemStack(Items.IRON_ORE, 4));
        blocked.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY,
                new ItemStack(iron(), 64));
        blocked.setEnergy(OreMillRules.BUFFER);
        blocked.setWater(OreMillRules.WATER_CAPACITY);
        run(blocked, OreMillRules.MANUAL_TICKS + 5);
        boolean refused = blocked.link() == OreMillEntity.Link.OUTPUT_FULL
                && blocked.inventory().getItem(0).getCount() == 4
                && blocked.energy() == OreMillRules.BUFFER
                && blocked.water() == OreMillRules.WATER_CAPACITY
                && output(blocked, OreMillMenu.OUTPUT_PRIMARY).getCount() == 64;

        // A full by-product well on a fresh mill: the job runs, then waits whole instead of half a batch.
        OreMillEntity wedgedMill = standalone(455, 955);
        // The sieve lifts the sand roll to 35%, so a full sand well is reached inside the tick budget.
        wedgedMill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        wedgedMill.inventory().setItem(0, new ItemStack(Items.IRON_ORE, 64));
        wedgedMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_BYPRODUCT,
                new ItemStack(MillItems.coarse(OreKind.IRON, 1).getItem(), 64));
        wedgedMill.setEnergy(OreMillRules.BUFFER);
        wedgedMill.setWater(OreMillRules.WATER_CAPACITY);
        for (int tick = 0; tick < 4_000 && wedgedMill.link() != OreMillEntity.Link.OUTPUT_FULL; tick++) {
            wedgedMill.tick(level);
            // Keep the primary well clear between attempts, but never hide a delivery made while wedged.
            if (wedgedMill.link() != OreMillEntity.Link.OUTPUT_FULL) {
                wedgedMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY, ItemStack.EMPTY);
            }
        }
        int energy = wedgedMill.energy(), water = wedgedMill.water();
        int input = wedgedMill.inventory().getItem(0).getCount();
        boolean wedged = wedgedMill.link() == OreMillEntity.Link.OUTPUT_FULL
                && output(wedgedMill, OreMillMenu.OUTPUT_PRIMARY).isEmpty()
                && output(wedgedMill, OreMillMenu.OUTPUT_BYPRODUCT).getCount() == 64
                && wedgedMill.progress() == wedgedMill.duration() - 1;
        run(wedgedMill, 200);
        boolean quiet = wedgedMill.link() == OreMillEntity.Link.OUTPUT_FULL
                && wedgedMill.energy() == energy && wedgedMill.water() == water
                && wedgedMill.inventory().getItem(0).getCount() == input
                && output(wedgedMill, OreMillMenu.OUTPUT_PRIMARY).isEmpty();
        wedgedMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_BYPRODUCT, ItemStack.EMPTY);
        run(wedgedMill, 1);
        check("stop.output_full_keeps", refused && wedged && quiet
                        && wedgedMill.inventory().getItem(0).getCount() == input - 1
                        && output(wedgedMill, OreMillMenu.OUTPUT_PRIMARY).getCount() == 1
                        && output(wedgedMill, OreMillMenu.OUTPUT_BYPRODUCT).getCount() == 1,
                "refused=" + refused + " wedged=" + wedged + " quiet=" + quiet
                        + " link=" + wedgedMill.link() + " input=" + wedgedMill.inventory().getItem(0).getCount()
                        + " out=" + output(wedgedMill, OreMillMenu.OUTPUT_PRIMARY));

        boolean gone = !ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "coarse_concentrate"))
                && ForgeRegistries.ITEMS.getKeys().stream()
                        .noneMatch(id -> id.getPath().contains("coarse_concentrate"))
                && server.getRecipeManager().byKey(new ResourceLocation(Corvus.ID, "coarse_concentrate_smelting")).isEmpty()
                && server.getRecipeManager().byKey(new ResourceLocation(Corvus.ID, "coarse_concentrate")).isEmpty()
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "coarse_iron"))
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(Corvus.ID, "coarse_quartz"));
        check("coarse.generic_gone", gone, "mixed coarse still present");
    }

    private static void rareOre() {
        int jobs = 200, shards = 0;
        for (int job = 0; job < jobs; job++) {
            rareMill.inventory().setItem(0, new ItemStack(Items.DIAMOND_ORE, 1));
            rareMill.setEnergy(OreMillRules.FE_PER_ITEM);
            rareMill.setWater(OreMillRules.WATER_PER_ITEM);
            run(rareMill, OreMillRules.MANUAL_TICKS);
            if (total(rareMill, diamondShard()) < 1) {
                fail("rare.primary_shard", "rare ore produced no shard");
                return;
            }
            shards += total(rareMill, diamondShard()) - 1;
            clearOutputs(rareMill);
        }
        check("rare.shard_12", shards > jobs * 0.07 && shards < jobs * 0.18, shards + "/" + jobs);
        check("rare.primary_shard", true, "one shard per rare job");
    }

    private static void upgradesAndWater() {
        upgradeMill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        upgradeMill.inventory().setItem(OreMillMenu.UPGRADE_FIRST + 1, new ItemStack(MillItems.COOLING.get()));
        check("upgrade.recognised", upgradeMill.hasSieve() && upgradeMill.hasCooling(), "");
        check("upgrade.cooling_numbers", upgradeMill.currentDuration() == 80
                && upgradeMill.currentCost() == 1_680 && upgradeMill.speedPercent() == 150,
                upgradeMill.currentDuration() + "/" + upgradeMill.currentCost() + "/" + upgradeMill.speedPercent());
        upgradeMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
        upgradeMill.setEnergy(1_680);
        upgradeMill.setWater(250);
        run(upgradeMill, 80);
        check("upgrade.cooling_job", total(upgradeMill, iron()) == 1
                        && upgradeMill.energy() == 0 && upgradeMill.water() == 0,
                "energy=" + upgradeMill.energy() + " water=" + upgradeMill.water());

        waterMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        waterMill.setEnergy(OreMillRules.BUFFER);
        waterMill.setWater(0);
        run(waterMill, OreMillRules.MANUAL_TICKS * 2);
        check("water.dry_stops", output(waterMill, 0).isEmpty() && waterMill.progress() == 0,
                "progress=" + waterMill.progress());
        waterMill.setWater(OreMillRules.WATER_PER_ITEM * 4);
        run(waterMill, OreMillRules.MANUAL_TICKS * 4);
        check("water.four_jobs", total(waterMill, iron()) == 4 && waterMill.water() == 0,
                "refined=" + total(waterMill, iron()) + " water=" + waterMill.water());
        run(waterMill, OreMillRules.MANUAL_TICKS);
        check("water.stays_stopped", total(waterMill, iron()) == 4, "kept running dry");
        check("water.bucket_1000", waterMill.addWater(1_000) == 1_000 && waterMill.water() == 1_000,
                "water=" + waterMill.water());
        check("water.capability_fill", waterMill.fill(new FluidStack(net.minecraft.world.level.material.Fluids.WATER,
                5_000), IFluidHandler.FluidAction.EXECUTE) == 3_000 && waterMill.water() == 4_000,
                "water=" + waterMill.water());
        check("water.fill_only", waterMill.drain(1_000, IFluidHandler.FluidAction.SIMULATE).isEmpty(), "");
    }

    // ---- drone ----

    private static void droneJob() {
        controller = controller("peregrine", true, true);
        droneMill.attachTestController(controller);
        droneMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        droneMill.setEnergy(OreMillRules.BUFFER);
        droneMill.setWater(OreMillRules.WATER_CAPACITY);
        run(droneMill, 1);
        check("drone.link_working", droneMill.link() == OreMillEntity.Link.WORKING, droneMill.link().toString());
        check("drone.duration_30", droneMill.currentDuration() == 30, String.valueOf(droneMill.currentDuration()));
        check("drone.speed_100", droneMill.speedPercent() == 100, String.valueOf(droneMill.speedPercent()));
        run(droneMill, 30);
        check("drone.doubles", total(droneMill, iron()) == 2,
                "refined=" + total(droneMill, iron()));
        check("drone.water_300", droneMill.water() == OreMillRules.WATER_CAPACITY
                - OreMillRules.WATER_PER_ITEM_DRONE, "water=" + droneMill.water());
        check("drone.energy_1200", droneMill.energy() == OreMillRules.BUFFER - OreMillRules.FE_PER_ITEM,
                "energy=" + droneMill.energy());

        int jobs = 100, coarse = 0;
        for (int job = 0; job < jobs; job++) {
            droneByproductMill.attachTestController(controller);
            droneByproductMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
            droneByproductMill.setEnergy(OreMillRules.FE_PER_ITEM);
            droneByproductMill.setWater(OreMillRules.WATER_PER_ITEM_DRONE);
            run(droneByproductMill, 30);
            coarse += total(droneByproductMill, MillItems.coarse(OreKind.IRON, 1).getItem());
            clearOutputs(droneByproductMill);
        }
        check("drone.byproduct_35", coarse > jobs * 0.25 && coarse < jobs * 0.45, coarse + "/" + jobs);
    }

    private static void airframesAndModules() {
        String[] frames = {"peregrine", "owl", "bedrock", "scrap"};
        int[] expected = {30, 24, 20, 40};
        for (int i = 0; i < frames.length; i++) {
            OreMillEntity mill = standalone(420 + i, 900 + i);
            mill.attachTestController(controller(frames[i], true, true));
            run(mill, 1);
            check("frame." + frames[i], mill.currentDuration() == expected[i],
                    frames[i] + "=" + mill.currentDuration());
        }
        OreMillEntity noCarry = standalone(430, 910);
        noCarry.attachTestController(controller("peregrine", true, false));
        run(noCarry, 1);
        check("frame.no_carry_half", noCarry.currentDuration() == 60, String.valueOf(noCarry.currentDuration()));
        OreMillEntity cooling = standalone(431, 911);
        cooling.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.COOLING.get()));
        cooling.attachTestController(controller("peregrine", true, true));
        run(cooling, 1);
        check("frame.cooling_speed", cooling.currentDuration() == 20 && cooling.currentCost() == 1_680,
                cooling.currentDuration() + "/" + cooling.currentCost());

        scanMill.attachTestController(controller("peregrine", false, true));
        scanMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        scanMill.setEnergy(OreMillRules.FE_PER_ITEM * 4);
        scanMill.setWater(OreMillRules.WATER_CAPACITY);
        run(scanMill, OreMillRules.MANUAL_TICKS + 5);
        check("drone.scan_required", scanMill.link() == OreMillEntity.Link.NO_MODULE
                && output(scanMill, 0).isEmpty(), "link=" + scanMill.link());
    }

    private static void batteryContract() {
        ItemStack cell = controller("peregrine", true, true);
        DroneEntity probe = new DroneEntity(Corvus.ENTITY.get(), level);
        probe.setWorkTarget(BlockPos.ZERO);
        probe.setTestController(cell);
        int capacity = Hardware.capacity(cell);
        Hardware.setEnergy(cell, (int) (capacity * 0.14D) + 400);
        DroneWork.second(probe, cell);
        check("drone.stall_15", probe.workStalled(), "energy=" + Hardware.energy(cell));
        Hardware.setEnergy(cell, (int) (capacity * 0.39D) + 400);
        DroneWork.second(probe, cell);
        check("drone.still_stalled_39", probe.workStalled(), "energy=" + Hardware.energy(cell));
        Hardware.setEnergy(cell, (int) (capacity * 0.40D) + 400);
        DroneWork.second(probe, cell);
        check("drone.resume_40", !probe.workStalled(), "energy=" + Hardware.energy(cell));

        noPowerMill.attachTestController(controller("peregrine", true, true));
        noPowerMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        noPowerMill.setEnergy(0);
        noPowerMill.setWater(OreMillRules.WATER_CAPACITY);
        run(noPowerMill, OreMillRules.MANUAL_TICKS + 5);
        check("drone.no_power", noPowerMill.link() == OreMillEntity.Link.NO_POWER
                && output(noPowerMill, 0).isEmpty(), "link=" + noPowerMill.link());

        droneMill.setTestStalled(true);
        run(droneMill, 1);
        check("drone.low_battery_link", droneMill.link() == OreMillEntity.Link.LOW_BATTERY,
                "link=" + droneMill.link());
        droneMill.setTestStalled(false);
        run(droneMill, 1);
        check("drone.resume_link", droneMill.link() == OreMillEntity.Link.WORKING,
                "link=" + droneMill.link());
    }

    private static void stopConditions() {
        outputMill.attachTestController(controller("peregrine", true, true));
        outputMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        for (int i = 0; i < OreMillMenu.OUTPUT_COUNT; i++) {
            outputMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + i, new ItemStack(Items.COBBLESTONE, 64));
        }
        outputMill.setEnergy(OreMillRules.FE_PER_ITEM);
        outputMill.setWater(OreMillRules.WATER_CAPACITY);
        run(outputMill, 2);
        check("stop.output_full", outputMill.link() == OreMillEntity.Link.OUTPUT_FULL
                && outputMill.inventory().getItem(0).getCount() == 4, "link=" + outputMill.link());
        clearOutputs(outputMill);
        run(outputMill, 30);
        check("stop.resumes", total(outputMill, iron()) == 2, "did not resume");

        OreMillEntity dry = standalone(440, 920);
        dry.attachTestController(controller("peregrine", true, true));
        dry.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
        dry.setEnergy(OreMillRules.FE_PER_ITEM);
        dry.setWater(0);
        run(dry, 2);
        check("stop.no_water", dry.link() == OreMillEntity.Link.NO_WATER, "link=" + dry.link());
        check("stop.idle_link", manualMill.link() == OreMillEntity.Link.NONE, manualMill.link().toString());

        sieveMill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        sieveMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
        sieveMill.setEnergy(OreMillRules.FE_PER_ITEM);
        sieveMill.setWater(OreMillRules.WATER_PER_ITEM);
        sieveMill.setRandom(RandomSource.create(7));
        run(sieveMill, OreMillRules.MANUAL_TICKS);
        check("upgrade.sieve_installed", sieveMill.hasSieve() && total(sieveMill, iron()) == 1, "");
    }

    // ---- water pump ----

    private static void pumpBehaviour() {
        pump.tick(level);
        for (int i = 0; i < 20; i++) pump.tick(level);
        check("pump.fills_mill", pumpMill.water() == OreMillRules.WATER_CAPACITY,
                "mill=" + pumpMill.water() + " pump=" + pump.water());
        for (int i = 0; i < 40; i++) pump.tick(level);
        check("pump.buffer_8000", pump.water() == OreMillRules.PUMP_BUFFER, "pump=" + pump.water());
        for (int i = 0; i < 10; i++) pump.tick(level);
        check("pump.full_stops", pump.water() == OreMillRules.PUMP_BUFFER
                && pumpMill.water() == OreMillRules.WATER_CAPACITY, "pump=" + pump.water());
        check("pump.source", pump.hasSource(level) && pump.source(), "source");

        for (int i = 0; i < 20; i++) dryPump.tick(level);
        check("pump.no_source_stops", dryPump.water() == 0 && !dryPump.source(), "dry=" + dryPump.water());

        CompoundTag tag = pump.saveWithFullMetadata();
        WaterPumpEntity copy = OreMillRegistry.PUMP_TYPE.get().create(pumpPos,
                OreMillRegistry.PUMP.get().defaultBlockState());
        copy.load(tag);
        check("pump.persist", copy.water() == pump.water() && copy.water() == 8_000,
                "copy=" + copy.water());
        check("pump.fill_only", pump.drain(1_000, IFluidHandler.FluidAction.SIMULATE).isEmpty(), "");
    }

    // ---- persistence / drops ----

    private static void persistenceAndDrops() {
        persistMill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 2));
        persistMill.setEnergy(9_000);
        persistMill.setWater(3_000);
        run(persistMill, 12);
        CompoundTag tag = persistMill.saveWithFullMetadata();
        OreMillEntity copy = OreMillRegistry.TYPE.get().create(BlockPos.ZERO,
                OreMillRegistry.MILL.get().defaultBlockState());
        copy.load(tag);
        check("persist.energy_water", copy.energy() == persistMill.energy()
                        && copy.water() == persistMill.water() && copy.water() > 0,
                "energy=" + copy.energy() + " water=" + copy.water());
        check("persist.progress", copy.progress() == persistMill.progress()
                        && copy.duration() == persistMill.duration() && copy.progress() > 0,
                "copy=" + copy.progress() + "/" + copy.duration());
        check("persist.slots", ItemStack.matches(copy.inventory().getItem(0), persistMill.inventory().getItem(0)),
                "input=" + copy.inventory().getItem(0));

        OreMillEntity drop = mill(dropPos);
        drop.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 3));
        drop.inventory().setItem(OreMillMenu.OUTPUT_FIRST, new ItemStack(iron(), 2));
        drop.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        List<ItemStack> drops = breakAndCollect(dropPos);
        LOG.info("[mill-smoke] mill drops: {}", describe(drops));
        check("drop.mill_block", count(drops, OreMillRegistry.MILL_ITEM.get()) == 1, describe(drops));
        check("drop.mill_contents", count(drops, Items.RAW_IRON) == 3
                && count(drops, iron()) == 2 && count(drops, MillItems.SIEVE.get()) == 1,
                describe(drops));
        List<ItemStack> pumpDrops = breakAndCollect(pumpDropPos);
        check("drop.pump_block", count(pumpDrops, OreMillRegistry.PUMP_ITEM.get()) == 1, describe(pumpDrops));
    }

    // ---- GUI / orders ----

    private static void guiAndOrders() {
        drone = new DroneEntity(Corvus.ENTITY.get(), level);
        drone.setUUID(DRONE_ID);
        drone.setOwner(player.getUUID());
        drone.setHealth(20.0F);
        drone.setFrame("owl");
        controller = controller("peregrine", true, true);
        drone.setTestController(controller);
        drone.moveTo(otherPos.getX() + 0.5D, otherPos.getY() + 1.0D, otherPos.getZ() + 0.5D);
        registerDrone(drone);
        player.getPersistentData().putUUID(CorvusKeys.ACTIVE, DRONE_ID);
        player.moveTo(callPos.getX() + 0.5D, callPos.getY() + 1.0D, callPos.getZ() + 0.5D);

        check("call.drone_found", DroneDeployment.active(player) == drone, "");
        OreMillMenu menu = new OreMillMenu(1, player.getInventory(), otherMill);
        player.containerMenu = menu;
        boolean handled = menu.clickMenuButton(player, OreMillMenu.BUTTON_CALL_DRONE);
        check("call.button_assigns", handled && DRONE_ID.equals(otherMill.assignedDrone())
                        && otherPos.equals(drone.workTarget()),
                "drone=" + otherMill.assignedDrone() + " target=" + drone.workTarget());
        check("call.picks_this_mill", callMill.assignedDrone() == null, "nearby mill untouched");

        drone.moveTo(callPos.getX() + 0.5D, callPos.getY() + 1.0D, callPos.getZ() + 0.5D);
        OreMillMenu far = new OreMillMenu(2, player.getInventory(), farMill);
        player.containerMenu = far;
        far.clickMenuButton(player, OreMillMenu.BUTTON_CALL_DRONE);
        check("call.range_32", farMill.assignedDrone() == null, "far mill got " + farMill.assignedDrone());

        DroneWork.stop(drone);
        drone.moveTo(otherPos.getX() + 0.5D, otherPos.getY() + 1.0D, otherPos.getZ() + 0.5D);
        player.moveTo(otherPos.getX() + 0.5D, otherPos.getY(), otherPos.getZ() + 0.5D);
        check("order.work_assigns", DroneWork.order(player, "order.work")
                        && DRONE_ID.equals(otherMill.assignedDrone()) && otherPos.equals(drone.workTarget()),
                "drone=" + otherMill.assignedDrone() + " target=" + drone.workTarget());
        check("order.idle_clears", DroneWork.order(player, "order.idle")
                && drone.workTarget() == null && otherMill.assignedDrone() == null, "");
        drone.setTestController(controller("peregrine", false, false));
        check("order.refuses_no_scan", !DroneWork.order(player, "order.work"), "no scan");
        drone.setTestController(controller);
        check("intent.work", "order.work".equals(intent("上工")), String.valueOf(intent("上工")));
        check("intent.idle", "order.idle".equals(intent("下班")), String.valueOf(intent("下班")));
        check("intent.work_alias", "order.work".equals(intent("去干活")), String.valueOf(intent("去干活")));
        check("intent.idle_alias", "order.idle".equals(intent("收工")), String.valueOf(intent("收工")));
    }

    // ---- two-block mill / pipes ----

    private static void twoBlockAndPipes() {
        BlockPos two = at(30, 2);
        place(two, OreMillRegistry.MILL.get());
        BlockPos side = OreMillBlock.sidePos(level.getBlockState(two), two);
        check("mill.side_block", level.getBlockState(side).is(OreMillRegistry.SIDE.get()), "side missing");
        check("mill.no_occlusion", machinesDoNotOccludeTheirNeighbours(), "machine state still claims occlusion");
        check("mill.side_shape", OreMillRegistry.MILL.get().getShape(level.getBlockState(two), level, two,
                net.minecraft.world.phys.shapes.CollisionContext.empty()).bounds().maxY == 1.0D, "shape height");
        List<ItemStack> sideDrops = breakAndCollect(side);
        check("mill.side_breaks_body", level.getBlockState(two).isAir()
                && count(sideDrops, OreMillRegistry.MILL_ITEM.get()) == 1, describe(sideDrops));
        check("mill.side_no_second_drop", count(sideDrops, OreMillRegistry.MILL_ITEM.get()) == 1, describe(sideDrops));

        BlockPos two2 = at(32, 2);
        place(two2, OreMillRegistry.MILL.get());
        BlockPos side2 = OreMillBlock.sidePos(level.getBlockState(two2), two2);
        List<ItemStack> bodyDrops = breakAndCollect(two2);
        check("mill.body_breaks_side", level.getBlockState(side2).isAir()
                && count(bodyDrops, OreMillRegistry.MILL_ITEM.get()) == 1, describe(bodyDrops));

        // Pump -> pipe -> mill.
        BlockPos pumpPos = at(40, 2);
        BlockPos pipeA = at(41, 2);
        BlockPos pipeB = at(42, 2);
        BlockPos pipeMillPos = at(43, 2);
        place(pumpPos, OreMillRegistry.PUMP.get());
        place(pipeA, OreMillRegistry.PIPE.get());
        place(pipeB, OreMillRegistry.PIPE.get());
        place(pipeMillPos, OreMillRegistry.MILL.get());
        place(pumpPos.above(), Blocks.WATER);
        WaterPumpEntity pump = (WaterPumpEntity) level.getBlockEntity(pumpPos);
        OreMillEntity piped = mill(pipeMillPos);
        for (int i = 0; i < 25; i++) pump.tick(level);
        check("pipe.connections", level.getBlockState(pipeA).getValue(WaterPipeBlock.EAST)
                && level.getBlockState(pipeA).getValue(WaterPipeBlock.WEST)
                && level.getBlockState(pipeB).getValue(WaterPipeBlock.EAST), "connection mask");
        check("pipe.moves_200", piped.water() > 0 && pump.lastPushed() <= OreMillRules.PUMP_RATE,
                "mill=" + piped.water() + " flow=" + pump.lastPushed());
        check("pipe.inspect", !WaterPipeNetwork.describe(level, pipeA).getString().isEmpty(),
                WaterPipeNetwork.describe(level, pipeA).getString());

        // Over-long segment: 33 pipes between pump and mill.
        BlockPos overPump = at(0, 8);
        place(overPump, OreMillRegistry.PUMP.get());
        BlockPos overMill = at(34, 8);
        place(overMill, OreMillRegistry.MILL.get());
        for (int i = 1; i <= 33; i++) place(at(i, 8), OreMillRegistry.PIPE.get());
        place(overPump.above(), Blocks.WATER);
        OreMillEntity over = mill(overMill);
        WaterPumpEntity overPumpEntity = (WaterPumpEntity) level.getBlockEntity(overPump);
        for (int i = 0; i < 10; i++) overPumpEntity.tick(level);
        check("pipe.overlong", over.water() == 0 && overPumpEntity.overlong(), "water=" + over.water());
        check("pipe.overlong_scan", WaterPipeNetwork.scan(level, at(1, 8)).overlong(), "scan");

        // Even split: pump with three direct mills gets 67/67/66.
        BlockPos splitPump = at(50, 2);
        place(splitPump, OreMillRegistry.PUMP.get());
        BlockPos s1 = at(49, 2), s2 = at(51, 2), s3 = at(50, 1);
        place(s1, OreMillRegistry.MILL.get());
        place(s2, OreMillRegistry.MILL.get());
        place(s3, OreMillRegistry.MILL.get());
        place(splitPump.above(), Blocks.WATER);
        WaterPumpEntity split = (WaterPumpEntity) level.getBlockEntity(splitPump);
        split.setWater(200);
        split.tick(level);
        int[] waters = {mill(s1).water(), mill(s2).water(), mill(s3).water()};
        int sum = waters[0] + waters[1] + waters[2];
        check("pipe.even_split", sum == 200
                && Math.abs(waters[0] - waters[1]) <= 1 && Math.abs(waters[1] - waters[2]) <= 1,
                waters[0] + "/" + waters[1] + "/" + waters[2]);

        // Infinite pond: two horizontal sources keep the supply forever.
        BlockPos pondPump = at(60, 2);
        place(pondPump, OreMillRegistry.PUMP.get());
        place(at(59, 2), Blocks.WATER);
        place(at(61, 2), Blocks.WATER);
        WaterPumpEntity pond = (WaterPumpEntity) level.getBlockEntity(pondPump);
        check("pump.infinite", pond.infiniteSource(level), "expected infinite");
        int pondWater = 0;
        for (int i = 0; i < 100; i++) {
            pond.setWater(0);
            pond.tick(level);
            pondWater += pond.water();
        }
        check("pump.infinite_20k", pondWater >= 20_000 && pond.hasSource(level),
                "generated=" + pondWater);

        // Finite single source: 1,000 mB drains the block and the pump stops.
        BlockPos finitePump = at(70, 2);
        place(finitePump, OreMillRegistry.PUMP.get());
        place(at(70, 1), Blocks.WATER);
        WaterPumpEntity finite = (WaterPumpEntity) level.getBlockEntity(finitePump);
        check("pump.finite", !finite.infiniteSource(level), "expected finite");
        for (int i = 0; i < 8; i++) finite.tick(level);
        check("pump.finite_drains", !finite.hasSource(level)
                && level.getBlockState(at(70, 1)).isAir(), "source=" + level.getBlockState(at(70, 1)));
        place(at(70, 1), Blocks.WATER);
        finite.tick(level);
        check("pump.refilled_resumes", finite.hasSource(level), "did not resume");
        check("pump.status_string", finite.status().getString().length() > 0, finite.status().getString());
    }

    // ---- registry / data ----

    private static void registryFacts() {
        Block mill = OreMillRegistry.MILL.get();
        Block pumpBlock = OreMillRegistry.PUMP.get();
        check("tag.pickaxe", mill.builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE)
                && pumpBlock.builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE), "");
        check("tag.needs_stone", mill.builtInRegistryHolder().is(BlockTags.NEEDS_STONE_TOOL), "");
        check("tag.needs_iron", pumpBlock.builtInRegistryHolder().is(BlockTags.NEEDS_IRON_TOOL), "");
        LootTable loot = server.getLootData().getLootTable(new ResourceLocation(Corvus.ID, "blocks/ore_mill"));
        LootTable pumpLoot = server.getLootData().getLootTable(new ResourceLocation(Corvus.ID, "blocks/water_pump"));
        check("loot.table", loot != LootTable.EMPTY && pumpLoot != LootTable.EMPTY, "");

        Map<String, Map<String, Integer>> blueprints = new LinkedHashMap<>();
        for (SurvivalWorks.Catalog.Entry entry : SurvivalWorks.catalog().entries()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(entry.icon().getItem());
            if (id == null || !Corvus.ID.equals(id.getNamespace())) continue;
            Map<String, Integer> costs = new LinkedHashMap<>();
            for (SurvivalWorks.Catalog.Ingredient ingredient : entry.ingredients()) {
                costs.put(ingredient.item(), ingredient.count());
            }
            blueprints.put(id.getPath(), costs);
        }
        check("blueprint.count_45", SurvivalWorks.catalog().entries().size() == 45,
                "plans=" + SurvivalWorks.catalog().entries().size());
        check("blueprint.ore_mill", blueprints.getOrDefault("ore_mill", Map.of()).equals(Map.of(
                        "minecraft:iron_ingot", 6, "minecraft:copper_ingot", 4, "minecraft:redstone", 3,
                        "minecraft:piston", 2, "minecraft:iron_pickaxe", 1)),
                String.valueOf(blueprints.get("ore_mill")));
        check("blueprint.sieve", blueprints.getOrDefault("sieve", Map.of()).equals(Map.of(
                        "minecraft:iron_ingot", 2, "minecraft:copper_ingot", 1, "minecraft:sand", 3)),
                String.valueOf(blueprints.get("sieve")));
        check("blueprint.cooling_tower", blueprints.getOrDefault("cooling_tower", Map.of()).equals(Map.of(
                        "minecraft:copper_ingot", 4, "minecraft:iron_ingot", 2, "minecraft:water_bucket", 1)),
                String.valueOf(blueprints.get("cooling_tower")));
        check("blueprint.water_pump", blueprints.getOrDefault("water_pump", Map.of()).equals(Map.of(
                        "minecraft:copper_ingot", 6, "minecraft:iron_ingot", 4,
                        "minecraft:glass", 2, "minecraft:piston", 2)),
                String.valueOf(blueprints.get("water_pump")));

        int ours = 0;
        for (var recipe : server.getRecipeManager().getRecipes()) {
            if (Corvus.ID.equals(recipe.getId().getNamespace())) ours++;
        }
        check("recipes.count_39", ours == 39, "loaded=" + ours);
        check("recipe.iron", smelts(MillItems.refined(OreKind.IRON, 1), Items.IRON_INGOT, 1), "");
        check("recipe.copper", smelts(MillItems.refined(OreKind.COPPER, 1), Items.COPPER_INGOT, 1), "");
        check("recipe.gold", smelts(MillItems.refined(OreKind.GOLD, 1), Items.GOLD_INGOT, 1), "");
        check("recipe.nether_gold", smelts(MillItems.refined(OreKind.NETHER_GOLD, 1), Items.GOLD_NUGGET, 4), "");
        check("recipe.coal", smelts(MillItems.refined(OreKind.COAL, 1), Items.COAL, 2), "");
        check("recipe.redstone", smelts(MillItems.refined(OreKind.REDSTONE, 1), Items.REDSTONE, 4), "");
        check("recipe.lapis", smelts(MillItems.refined(OreKind.LAPIS, 1), Items.LAPIS_LAZULI, 6), "");
        check("recipe.quartz", smelts(MillItems.refined(OreKind.QUARTZ, 1), Items.QUARTZ, 2), "");


        TransientCraftingContainer grid = new TransientCraftingContainer(dummyMenu(), 3, 3);
        for (int i = 0; i < 9; i++) grid.setItem(i, MillItems.shard(OreKind.DIAMOND, 1));
        var recipe = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level);
        check("recipe.nine_shards", recipe.isPresent()
                && recipe.get().getResultItem(server.registryAccess()).is(Items.DIAMOND),
                String.valueOf(recipe));

        CreativeModeTab tab = Corvus.TAB.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, false,
                server.registryAccess()));
        check("creative.entries_105", tab.getDisplayItems().size() == 105,
                "entries=" + tab.getDisplayItems().size());
        check("creative.covers_registry", tab.getDisplayItems().stream().map(ItemStack::getItem)
                .collect(java.util.stream.Collectors.toSet()).containsAll(registeredItems()),
                "registered mod items missing from the page");

        List<Component> lines = new ItemStack(MillItems.SIEVE.get()).getTooltipLines(player, TooltipFlag.Default.NORMAL);
        check("tooltip.sieve", lines.size() >= 3, "lines=" + lines.size());
        List<Component> millLines = new ItemStack(OreMillRegistry.MILL_ITEM.get())
                .getTooltipLines(player, TooltipFlag.Default.NORMAL);
        check("tooltip.ore_mill", millLines.size() >= 4, "lines=" + millLines.size());
        List<Component> pumpLines = new ItemStack(OreMillRegistry.PUMP_ITEM.get())
                .getTooltipLines(player, TooltipFlag.Default.NORMAL);
        check("tooltip.water_pump", pumpLines.size() >= 4, "lines=" + pumpLines.size());

        for (String language : List.of("zh_cn", "en_us")) {
            check("lang." + language, langHas(language, "block.huhu_dronepal.ore_mill",
                    "block.huhu_dronepal.water_pump", "item.huhu_dronepal.diamond_shard",
                    "item.huhu_dronepal.iron_concentrate", "item.huhu_dronepal.coarse_iron",
                    "item.huhu_dronepal.coarse_lapis", "item.huhu_dronepal.coarse.tip",
                    "gui.dronepal.ore_mill.call", "gui.dronepal.ore_mill.water",
                    "gui.dronepal.ore_mill.slot.output.primary", "gui.dronepal.ore_mill.slot.output.byproduct",
                    "gui.dronepal.ore_mill.slot.output.extra", "gui.dronepal.ore_mill.status.no_water",
                    "dronepal.ore.iron", "dronepal.wheel.work"), language);
        }
    }

    private static AbstractContainerMenu dummyMenu() {
        return new AbstractContainerMenu(null, 0) {
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int index) {
                return ItemStack.EMPTY;
            }
        };
    }

    private static boolean smelts(ItemStack stack, Item item, int count) {
        var found = server.getRecipeManager().getRecipeFor(RecipeType.SMELTING,
                new SimpleContainer(stack), level);
        return found.isPresent() && found.get().getResultItem(server.registryAccess()).is(item)
                && found.get().getResultItem(server.registryAccess()).getCount() == count;
    }

    private static List<Item> registeredItems() {
        List<Item> items = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null && Corvus.ID.equals(id.getNamespace())) items.add(item);
        }
        return items;
    }

    // ---- plumbing ----

    private static BlockPos at(int dx, int dz) {
        return origin.offset(dx, 0, dz);
    }

    /**
     * The regression the users hit: a housing that does not fill its cell must not be a full occluder,
     * or the block underneath culls the very face the machine stands on and the stone "disappears".
     * Checks the state flags and, for real, whether the renderer would still draw the stone's top face.
     * The mill's second half is checked too, in the same pass, because it is its own block.
     */
    private static boolean machinesDoNotOccludeTheirNeighbours() {
        BlockPos base = at(46, 2);
        boolean ok = true;
        for (Block machine : List.of(OreMillRegistry.MILL.get(), OreMillRegistry.PUMP.get())) {
            BlockPos stone = base.below();
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(base, machine.defaultBlockState(), Block.UPDATE_ALL);
            BlockState state = level.getBlockState(base);
            boolean clean = !state.canOcclude() && !state.isSolidRender(level, base);
            boolean faceStillDrawn = Block.shouldRenderFace(level.getBlockState(stone), level, stone,
                    Direction.UP, base);
            BlockPos side = machine == OreMillRegistry.MILL.get() ? OreMillBlock.sidePos(state, base) : null;
            boolean sideClean = side == null || !level.getBlockState(side).canOcclude();
            if (!clean || !faceStillDrawn || !sideClean) {
                LOG.error("[mill-smoke] {} canOcclude={} solidRender={} stoneTopDrawn={} sideClean={}",
                        machine, state.canOcclude(), state.isSolidRender(level, base), faceStillDrawn, sideClean);
                ok = false;
            }
            level.setBlock(base, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (side != null) level.setBlock(side, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            base = base.offset(0, 0, 3);
        }
        return ok;
    }

    private static void place(BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void place(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private static OreMillEntity mill(BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof OreMillEntity mill)) throw new IllegalStateException("no mill at " + pos);
        return mill;
    }

    /** A detached mill: the world ticker never sees it, so a harness can step it deterministically. */
    private static OreMillEntity standalone(int index, int seed) {
        BlockPos pos = origin.offset(200 + index, 32, 200 + index);
        OreMillEntity mill = OreMillRegistry.TYPE.get().create(pos,
                OreMillRegistry.MILL.get().defaultBlockState());
        mill.setRandom(RandomSource.create(seed));
        return mill;
    }

    private static void run(OreMillEntity mill, int ticks) {
        for (int i = 0; i < ticks; i++) mill.tick(level);
    }

    private static void clearOutputs(OreMillEntity mill) {
        for (int i = 0; i < OreMillMenu.OUTPUT_COUNT; i++) {
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + i, ItemStack.EMPTY);
        }
    }

    private static Item iron() { return MillItems.REFINED.get(OreKind.IRON).get(); }

    private static Item diamondShard() { return MillItems.SHARD.get(OreKind.DIAMOND).get(); }

    private static int total(OreMillEntity mill, Item item) {
        int count = 0;
        for (int i = 0; i < OreMillMenu.OUTPUT_COUNT; i++) {
            if (mill.output(i).is(item)) count += mill.output(i).getCount();
        }
        return count;
    }

    private static ItemStack output(OreMillEntity mill, int index) {
        return mill.output(index);
    }

    private static ItemStack controller(String frame, boolean scan, boolean carry) {
        ItemStack stack = Frames.create(frame);
        List<ItemStack> parts = new ArrayList<>();
        parts.add(item("omd_bat_300"));
        if (scan) parts.add(Hardware.moduleStack("scan_mk1"));
        if (carry) parts.add(Hardware.moduleStack("carry_mk1"));
        Hardware.save(stack, parts);
        Hardware.setEnergy(stack, Hardware.capacity(stack));
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static void registerDrone(DroneEntity entity) {
        try {
            Field field = DroneDeployment.class.getDeclaredField("LIVE");
            field.setAccessible(true);
            ((Map<UUID, DroneEntity>) field.get(null)).put(DRONE_ID, entity);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String intent(String text) {
        try {
            var method = dev.corvus.DialogueServer.class.getDeclaredMethod("intentOf", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, text);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean langHas(String language, String... keys) {
        try (var stream = MillSmoke.class.getResourceAsStream(
                "/assets/huhu_dronepal/lang/" + language + ".json")) {
            if (stream == null) return false;
            var json = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : keys) {
                if (!json.has(key)) {
                    LOG.error("[mill-smoke] missing {} key {}", language, key);
                    return false;
                }
            }
            return true;
        } catch (java.io.IOException error) {
            return false;
        }
    }

    private static ItemStack item(String path) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(Corvus.ID, path));
        if (item == null) throw new IllegalStateException("Unregistered item " + path);
        return new ItemStack(item);
    }

    private static int count(List<ItemStack> stacks, Item item) {
        int count = 0;
        for (ItemStack stack : stacks) if (stack.is(item)) count += stack.getCount();
        return count;
    }

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

    private static ServerPlayer fakePlayer() {
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("corvus-mill-smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "MillSmoke");
        return net.minecraftforge.common.util.FakePlayerFactory.get(level, profile);
    }

    private static void finish(MinecraftServer current) {
        LOG.info("[mill-smoke] ==== {} passed, {} failed ====", passed, FAILURES.size());
        for (String failure : FAILURES) LOG.error("[mill-smoke] FAILED {}", failure);
        ran = true;
        current.halt(false);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.info("[mill-smoke] PASS {}", name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(String name, String detail) {
        FAILURES.add(name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }
}
