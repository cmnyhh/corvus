package dev.dronepal;

import dev.dronepal.mill.MillItems;
import dev.dronepal.mill.OreKind;
import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillMenu;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.mill.OreMillRules;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Ore-mill behaviour: manual timing and yield, water consumption, the stop conditions, rare shards plus
 * their 9-to-1 recipes and the smelting mapping, upgrades, persistence and harvest drops.
 *
 * <p>The output side is pinned down twice over: the by-product is the input mineral's own coarse sand
 * ({@code coarse_iron} from iron ore, {@code coarse_lapis} from lapis ore, ...), nine of them craft one
 * concentrate of the same family, and the three output wells stay role-specific (primary / by-product /
 * extra) so two classes of item never share a stack. A full well stops the machine and keeps the finished
 * job — including its input — instead of voiding anything.</p>
 */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class OreMillGameTest {
    private static OreMillEntity mill(GameTestHelper test, BlockPos relative) {
        var level = test.getLevel();
        var mill = new OreMillEntity(test.absolutePos(relative), OreMillRegistry.MILL.get().defaultBlockState());
        mill.setLevel(level);
        mill.setRandom(RandomSource.create(20261125L));
        return mill;
    }

    private static void run(GameTestHelper test, OreMillEntity mill, int ticks) {
        for (int i = 0; i < ticks; i++) mill.tick(test.getLevel());
    }

    /** Keeps the three output wells empty between jobs so a statistical run never jams. */
    private static void clear(OreMillEntity mill) {
        for (int i = 0; i < dev.dronepal.mill.OreMillMenu.OUTPUT_COUNT; i++)
            mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.OUTPUT_FIRST + i, ItemStack.EMPTY);
    }

    private static int total(OreMillEntity mill, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < dev.dronepal.mill.OreMillMenu.OUTPUT_COUNT; i++)
            if (mill.output(i).is(item)) count += mill.output(i).getCount();
        return count;
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void manualJobTakesSixSecondsAndMakesOneConcentrate(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 8));
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        run(test, mill, OreMillRules.MANUAL_TICKS - 1);
        test.assertTrue(mill.output(0).isEmpty() && mill.progress() == OreMillRules.MANUAL_TICKS - 1,
                "mill finished early or lost progress: " + mill.progress());
        run(test, mill, 1);
        ItemStack result = mill.output(0);
        test.assertTrue(result.is(MillItems.refined(OreKind.IRON, 1).getItem()) && result.getCount() == 1,
                "manual job did not produce exactly one iron concentrate: " + result);
        test.assertTrue(mill.energy() == OreMillRules.BUFFER - OreMillRules.FE_PER_ITEM,
                "manual job charged " + (OreMillRules.BUFFER - mill.energy()) + " FE");
        test.assertTrue(mill.water() == OreMillRules.WATER_CAPACITY - OreMillRules.WATER_PER_ITEM,
                "manual job used " + (OreMillRules.WATER_CAPACITY - mill.water()) + " mB");
        test.assertTrue(mill.inventory().getItem(0).getCount() == 7, "manual job did not consume exactly one ore");
        // Two more jobs: three ores, three concentrates, 750 mB, 3,600 FE in total.
        run(test, mill, OreMillRules.MANUAL_TICKS * 2);
        test.assertTrue(total(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 3, "three jobs did not leave three concentrates");
        test.assertTrue(mill.water() == OreMillRules.WATER_CAPACITY - OreMillRules.WATER_PER_ITEM * 3,
                "three jobs used " + (OreMillRules.WATER_CAPACITY - mill.water()) + " mB");
        test.assertTrue(mill.energy() == OreMillRules.BUFFER - OreMillRules.FE_PER_ITEM * 3,
                "three jobs used " + (OreMillRules.BUFFER - mill.energy()) + " FE");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void manualByproductIsAboutTwentyPercent(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.setEnergy(OreMillRules.FE_PER_ITEM * 600);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        int jobs = 500, coarse = 0;
        for (int job = 0; job < jobs; job++) {
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(0, new ItemStack(Items.RAW_COPPER, 1));
            run(test, mill, OreMillRules.MANUAL_TICKS);
            coarse += total(mill, MillItems.coarse(OreKind.COPPER, 1).getItem());
            test.assertTrue(total(mill, MillItems.refined(OreKind.COPPER, 1).getItem()) == 1,
                    "manual job " + job + " produced the wrong concentrate count");
            test.assertTrue(total(mill, MillItems.shard(OreKind.DIAMOND, 1).getItem()) == 0, "common ore produced a rare shard");
            clear(mill);
        }
        test.assertTrue(coarse > jobs * 0.15 && coarse < jobs * 0.25,
                "manual coarse_copper rate off: " + coarse + "/" + jobs);
        test.assertTrue(total(mill, MillItems.coarse(OreKind.IRON, 1).getItem()) == 0,
                "copper ore produced iron sand");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void waterIsTwoHundredFiftyPerItemAndStopsWhenEmpty(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(0);
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        run(test, mill, OreMillRules.MANUAL_TICKS * 2);
        test.assertTrue(mill.output(0).isEmpty() && mill.progress() == 0, "dry mill processed ore");
        mill.setWater(OreMillRules.WATER_PER_ITEM * 4);
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        run(test, mill, OreMillRules.MANUAL_TICKS * 4);
        test.assertTrue(total(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 4 && mill.water() == 0,
                "four jobs did not drink exactly 250 mB each: left " + mill.water());
        run(test, mill, OreMillRules.MANUAL_TICKS);
        test.assertTrue(total(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 4, "mill kept running with an empty tank");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void noPowerAndFullOutputBothStop(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 4));
        mill.setWater(OreMillRules.WATER_CAPACITY);
        mill.setEnergy(0);
        run(test, mill, OreMillRules.MANUAL_TICKS + 5);
        test.assertTrue(mill.output(0).isEmpty() && mill.progress() == 0, "mill processed without power");
        mill.setEnergy(OreMillRules.FE_PER_ITEM);
        for (int i = 0; i < dev.dronepal.mill.OreMillMenu.OUTPUT_COUNT; i++)
            mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.OUTPUT_FIRST + i, new ItemStack(Items.COBBLESTONE, 64));
        run(test, mill, OreMillRules.MANUAL_TICKS + 5);
        test.assertTrue(total(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 0, "mill processed into a full output");
        clear(mill);
        run(test, mill, OreMillRules.MANUAL_TICKS);
        test.assertTrue(total(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 1,
                "mill did not resume after the output was emptied");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 120)
    public static void rareOreYieldsShardsAndNineCraftOneMineral(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.setEnergy(OreMillRules.FE_PER_ITEM * 400);
        int shards = 0, jobs = 300;
        for (int job = 0; job < jobs; job++) {
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(0, new ItemStack(Items.DIAMOND_ORE, 1));
            run(test, mill, OreMillRules.MANUAL_TICKS);
            test.assertTrue(total(mill, MillItems.shard(OreKind.DIAMOND, 1).getItem()) >= 1, "rare ore produced no shard");
            test.assertTrue(total(mill, MillItems.refined(OreKind.IRON, 1).getItem()) == 0, "rare ore produced a common concentrate");
            shards += total(mill, MillItems.shard(OreKind.DIAMOND, 1).getItem());
            clear(mill);
        }
        // Rare ores hand out one shard per job plus the 12% bonus roll.
        test.assertTrue(shards >= jobs && shards <= jobs * 1.25, "rare shard rate off: " + shards + "/" + jobs);

        List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) grid.add(MillItems.shard(OreKind.DIAMOND, 1));
        var input = CraftingInput.of(3, 3, grid);
        var recipe = test.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, test.getLevel());
        test.assertTrue(recipe.isPresent(), "no shapeless recipe matched nine diamond shards");
        ItemStack crafted = recipe.get().value().assemble(input, test.getLevel().registryAccess());
        test.assertTrue(crafted.is(Items.DIAMOND) && crafted.getCount() == 1, "nine shards did not craft one diamond");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void smeltingKeepsTheOresOwnProduct(GameTestHelper test) {
        var recipes = test.getLevel().getRecipeManager();
        for (OreKind kind : OreKind.values()) {
            if (!kind.hasConcentrate()) continue;
            var found = recipes.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(MillItems.refined(kind, 1)), test.getLevel());
            test.assertTrue(found.isPresent(), "no smelting recipe for " + kind.id() + " concentrate");
            ItemStack out = found.get().value().getResultItem(test.getLevel().registryAccess());
            test.assertTrue(out.is(kind.product()) && out.getCount() == kind.productCount(),
                    kind.id() + " concentrate smelted into " + out);
        }
        for (OreKind kind : OreKind.values()) {
            if (!kind.hasConcentrate()) continue;
            var sand = recipes.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(MillItems.coarse(kind, 1)), test.getLevel());
            test.assertTrue(sand.isEmpty(), kind.id() + " coarse sand still smelts into something");
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 200)
    public static void upgradesChangeSpeedCostAndChances(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.UPGRADE_FIRST + 1, new ItemStack(MillItems.COOLING.get()));
        test.assertTrue(mill.hasSieve() && mill.hasCooling(), "upgrade cards were not recognised");
        test.assertTrue(mill.currentDuration() == 80 && mill.currentCost() == 1_680 && mill.speedPercent() == 150,
                "cooling numbers wrong: " + mill.currentDuration() + "/" + mill.currentCost() + "/" + mill.speedPercent());
        int jobs = 200, coarse = 0;
        for (int job = 0; job < jobs; job++) {
            mill.setEnergy(1_680);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
            run(test, mill, 80);
            coarse += total(mill, MillItems.coarse(OreKind.IRON, 1).getItem());
            clear(mill);
        }
        // The sieve is +15 percentage points: drone 35% -> 50%, hand 20% -> 35%.
        test.assertTrue(coarse > jobs * 0.27 && coarse < jobs * 0.43, "sieve by-product rate off: " + coarse + "/" + jobs);
        test.assertTrue(OreMillRules.byproductChance(true, false) == 0.35 && OreMillRules.byproductChance(true, true) == 0.50
                        && OreMillRules.byproductChance(false, true) == 0.35,
                "sieve by-product chances wrong");
        test.assertTrue(OreMillRules.rareChance(true) == 0.18 && OreMillRules.rareChance(false) == 0.12,
                "sieve rare chances wrong");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void saveLoadKeepsTankProgressAndAssignment(GameTestHelper test) {
        var level = test.getLevel();
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 6));
        mill.setEnergy(12_345);
        mill.setWater(1_234);
        mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        UUID drone = UUID.randomUUID();
        mill.assignDrone(drone, UUID.randomUUID());
        run(test, mill, 17);
        var tag = mill.saveWithoutMetadata(level.registryAccess());
        var restored = new OreMillEntity(mill.getBlockPos(), mill.getBlockState());
        restored.setLevel(level);
        restored.loadWithComponents(tag, level.registryAccess());
        test.assertTrue(restored.energy() == mill.energy() && restored.water() == mill.water()
                        && restored.progress() == mill.progress() && restored.duration() == mill.duration(),
                "save/load lost energy, water or progress");
        test.assertTrue(drone.equals(restored.assignedDrone()) && restored.hasSieve(), "save/load lost the assignment or upgrade");
        test.assertTrue(restored.inventory().getItem(0).getCount() == 6, "save/load lost the input stack");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void breakingDropsTheBlockAndItsContents(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, OreMillRegistry.MILL.get().defaultBlockState());
        var mill = (OreMillEntity) level.getBlockEntity(pos);
        test.assertTrue(mill != null, "mill block entity missing");
        mill.inventory().setItem(0, new ItemStack(Items.RAW_GOLD, 5));
        mill.inventory().setItem(dev.dronepal.mill.OreMillMenu.UPGRADE_FIRST + 1, new ItemStack(MillItems.COOLING.get()));
        mill.setWater(2_000);
        level.destroyBlock(pos, true);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream().map(ItemEntity::getItem).toList();
        test.assertTrue(drops.stream().anyMatch(stack -> stack.is(OreMillRegistry.MILL_ITEM.get())), "mill did not drop itself");
        test.assertTrue(drops.stream().anyMatch(stack -> stack.is(Items.RAW_GOLD)), "ore inside the mill was lost");
        test.assertTrue(drops.stream().anyMatch(stack -> stack.is(MillItems.COOLING.get())), "upgrade inside the mill was lost");
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void assemblyBlueprintsCoverTheMachines(GameTestHelper test) {
        var ore = plan("ore_mill");
        var pump = plan("water_pump");
        var sieve = plan("sieve");
        var cooling = plan("cooling_tower");
        test.assertTrue(costs(ore).equals(java.util.Map.of("minecraft:iron_ingot", 6, "minecraft:copper_ingot", 4,
                        "minecraft:redstone", 3, "minecraft:piston", 2, "minecraft:iron_pickaxe", 1)),
                "ore_mill blueprint wrong: " + costs(ore));
        test.assertTrue(costs(pump).equals(java.util.Map.of("minecraft:copper_ingot", 6, "minecraft:iron_ingot", 4,
                        "minecraft:glass", 2, "minecraft:piston", 2)),
                "water_pump blueprint wrong: " + costs(pump));
        test.assertTrue(costs(sieve).equals(java.util.Map.of("minecraft:iron_ingot", 2, "minecraft:copper_ingot", 1,
                        "minecraft:sand", 3)), "sieve blueprint wrong: " + costs(sieve));
        test.assertTrue(costs(cooling).equals(java.util.Map.of("minecraft:copper_ingot", 4, "minecraft:iron_ingot", 2,
                        "minecraft:water_bucket", 1)), "cooling_tower blueprint wrong: " + costs(cooling));

        // And a real bench job for the pump, proving the blueprint list reaches the station.
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(4, 1, 1));
        level.setBlockAndUpdate(pos, SurvivalWorks.BENCH.get().defaultBlockState());
        var station = (AssemblyStation) level.getBlockEntity(pos);
        station.select(pump);
        station.inventory.setItem(0, new ItemStack(Items.COPPER_INGOT, 6));
        station.inventory.setItem(1, new ItemStack(Items.IRON_INGOT, 4));
        station.inventory.setItem(2, new ItemStack(Items.GLASS, 2));
        station.inventory.setItem(3, new ItemStack(Items.PISTON, 2));
        test.assertTrue(station.start(), "pump blueprint did not start with its materials");
        for (int i = 0; i < 100; i++) station.tick();
        ItemStack out = station.inventory.getItem(9);
        test.assertTrue(out.is(OreMillRegistry.WATER_PUMP_ITEM.get()) && out.getCount() == 1, "bench did not build the pump: " + out);
        test.succeed();
    }

    private static SurvivalWorks.Plan plan(String id) {
        return SurvivalWorks.plans.stream().filter(plan -> plan.id().getPath().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing blueprint " + id));
    }

    private static java.util.Map<String, Integer> costs(SurvivalWorks.Plan plan) {
        var result = new java.util.HashMap<String, Integer>();
        for (var cost : plan.costs()) result.merge(cost.product().item(), cost.count(), Integer::sum);
        return result;
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void unreadableItemsDoNotStartAJob(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.inventory().setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        run(test, mill, OreMillRules.MANUAL_TICKS);
        test.assertTrue(mill.output(0).isEmpty() && mill.progress() == 0, "cobblestone was milled");
        test.succeed();
    }

    // ---- per-kind by-products and the three role wells -------------------------------------------

    /** Runs one-ore jobs until the by-product well takes a roll; leaves that roll in the well and returns it. */
    private static ItemStack firstByproduct(GameTestHelper test, OreMillEntity mill, net.minecraft.world.item.Item ore) {
        for (int job = 0; job < 80; job++) {
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(0, new ItemStack(ore, 1));
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY, ItemStack.EMPTY);
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_BYPRODUCT, ItemStack.EMPTY);
            run(test, mill, OreMillRules.MANUAL_TICKS);
            ItemStack sand = mill.output(OreMillMenu.OUTPUT_BYPRODUCT);
            if (!sand.isEmpty()) return sand.copy();
        }
        return ItemStack.EMPTY;
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 200)
    public static void byproductFollowsTheInputMineral(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        ItemStack iron = firstByproduct(test, mill, Items.IRON_ORE);
        test.assertTrue(iron.is(MillItems.coarse(OreKind.IRON, 1).getItem()),
                "iron ore did not leave coarse_iron: " + iron);
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_PRIMARY).is(MillItems.refined(OreKind.IRON, 1).getItem()),
                "iron ore's primary well is not the iron concentrate: " + mill.output(OreMillMenu.OUTPUT_PRIMARY));

        ItemStack lapis = firstByproduct(test, mill, Items.LAPIS_ORE);
        test.assertTrue(lapis.is(MillItems.coarse(OreKind.LAPIS, 1).getItem()),
                "lapis ore did not leave coarse_lapis: " + lapis);
        test.assertTrue(!ItemStack.isSameItem(iron, lapis), "iron and lapis left the same coarse item");
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_PRIMARY).is(MillItems.refined(OreKind.LAPIS, 1).getItem()),
                "lapis ore's primary well is not the lapis concentrate: " + mill.output(OreMillMenu.OUTPUT_PRIMARY));

        // Exactly the eight common families have sand, and every id is coarse_<kind>.
        for (OreKind kind : OreKind.values()) {
            var id = ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "coarse_" + kind.id());
            Item registered = BuiltInRegistries.ITEM.get(id);
            test.assertTrue((registered != Items.AIR) == kind.hasConcentrate(),
                    "coarse_" + kind.id() + " registration does not match hasConcentrate()");
            if (kind.hasConcentrate()) {
                test.assertTrue(MillItems.coarse(kind, 1).is(registered) && MillItems.coarseKind(MillItems.coarse(kind, 1)) == kind,
                        "coarse_" + kind.id() + " is not the item MillItems hands out");
            }
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void nineCoarseCraftOneConcentrate(GameTestHelper test) {
        var recipes = test.getLevel().getRecipeManager();
        for (OreKind kind : OreKind.values()) {
            if (!kind.hasConcentrate()) continue;
            List<ItemStack> grid = new ArrayList<>();
            for (int i = 0; i < 9; i++) grid.add(MillItems.coarse(kind, 1));
            var input = CraftingInput.of(3, 3, grid);
            var recipe = recipes.getRecipeFor(RecipeType.CRAFTING, input, test.getLevel());
            test.assertTrue(recipe.isPresent(), "no crafting recipe matched nine coarse_" + kind.id());
            ItemStack crafted = recipe.get().value().assemble(input, test.getLevel().registryAccess());
            test.assertTrue(crafted.is(MillItems.refined(kind, 1).getItem()) && crafted.getCount() == 1,
                    "nine coarse_" + kind.id() + " crafted " + crafted);

            List<ItemStack> eight = new ArrayList<>(grid);
            eight.set(8, ItemStack.EMPTY);
            var shortGrid = CraftingInput.of(3, 3, eight);
            test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, shortGrid, test.getLevel()).isEmpty(),
                    "eight coarse_" + kind.id() + " already crafted a concentrate");
        }
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 200)
    public static void outputWellsHoldOneRoleEach(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        int jobs = 0;
        for (; jobs < 40; jobs++) {
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(0, new ItemStack(Items.RAW_GOLD, 1));
            run(test, mill, OreMillRules.MANUAL_TICKS);
            if (!mill.output(OreMillMenu.OUTPUT_BYPRODUCT).isEmpty()) break;
        }
        test.assertTrue(jobs < 40, "forty gold jobs left no by-product");
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_PRIMARY).is(MillItems.refined(OreKind.GOLD, 1).getItem()),
                "gold concentrate is not in the primary well: " + mill.output(OreMillMenu.OUTPUT_PRIMARY));
        test.assertTrue(MillItems.coarseKind(mill.output(OreMillMenu.OUTPUT_BYPRODUCT)) == OreKind.GOLD,
                "by-product well holds " + mill.output(OreMillMenu.OUTPUT_BYPRODUCT));
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_EXTRA).isEmpty(),
                "a common ore put something in the extra well: " + mill.output(OreMillMenu.OUTPUT_EXTRA));

        // A rare ore: the shard is primary (well 0) and the bonus roll is the extra (well 2); no sand at all.
        for (int i = 0; i < OreMillMenu.OUTPUT_COUNT; i++)
            mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + i, ItemStack.EMPTY);
        for (int job = 0; job < 40 && mill.output(OreMillMenu.OUTPUT_EXTRA).isEmpty(); job++) {
            mill.setEnergy(OreMillRules.FE_PER_ITEM);
            mill.setWater(OreMillRules.WATER_PER_ITEM);
            mill.inventory().setItem(0, new ItemStack(Items.DIAMOND_ORE, 1));
            run(test, mill, OreMillRules.MANUAL_TICKS);
        }
        ItemStack shard = MillItems.shard(OreKind.DIAMOND, 1);
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_PRIMARY).is(shard.getItem()),
                "rare primary is not in the primary well: " + mill.output(OreMillMenu.OUTPUT_PRIMARY));
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_EXTRA).is(shard.getItem()),
                "the rare bonus roll never reached the extra well: " + mill.output(OreMillMenu.OUTPUT_EXTRA));
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_BYPRODUCT).isEmpty(),
                "a rare ore put something in the by-product well: " + mill.output(OreMillMenu.OUTPUT_BYPRODUCT));
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 200)
    public static void fullWellStopsAndKeepsTheFinishedJob(GameTestHelper test) {
        var mill = mill(test, new BlockPos(1, 1, 1));
        mill.setEnergy(OreMillRules.BUFFER);
        mill.setWater(OreMillRules.WATER_CAPACITY);
        mill.inventory().setItem(0, new ItemStack(Items.IRON_ORE, 4));
        // 1) A full primary well of the same item: the job is refused before it starts.
        mill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY,
                new ItemStack(MillItems.refined(OreKind.IRON, 1).getItem(), 64));
        run(test, mill, OreMillRules.MANUAL_TICKS + 5);
        test.assertTrue(mill.link() == OreMillEntity.Link.OUTPUT_FULL,
                "a full primary well did not report OUTPUT_FULL: " + mill.link());
        test.assertTrue(mill.inventory().getItem(0).getCount() == 4, "the refused job ate an ore");
        test.assertTrue(mill.energy() == OreMillRules.BUFFER, "the refused job spent FE");
        test.assertTrue(mill.water() == OreMillRules.WATER_CAPACITY, "the refused job drank water");
        test.assertTrue(mill.output(OreMillMenu.OUTPUT_PRIMARY).getCount() == 64,
                "the refused job changed the full stack: " + mill.output(OreMillMenu.OUTPUT_PRIMARY));

        // 2) A full by-product well on a fresh mill: the job runs, then waits whole instead of half a batch.
        var wedgedMill = mill(test, new BlockPos(2, 1, 1));
        // The sieve lifts the sand roll to 35%, so a full sand well is reached inside the tick budget.
        wedgedMill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
        wedgedMill.inventory().setItem(0, new ItemStack(Items.IRON_ORE, 64));
        wedgedMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_BYPRODUCT,
                new ItemStack(MillItems.coarse(OreKind.IRON, 1).getItem(), 64));
        wedgedMill.setEnergy(OreMillRules.BUFFER);
        wedgedMill.setWater(OreMillRules.WATER_CAPACITY);
        for (int tick = 0; tick < 4_000 && wedgedMill.link() != OreMillEntity.Link.OUTPUT_FULL; tick++) {
            wedgedMill.tick(test.getLevel());
            // Keep the primary well clear between attempts, but never hide a delivery made while wedged.
            if (wedgedMill.link() != OreMillEntity.Link.OUTPUT_FULL)
                wedgedMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_PRIMARY, ItemStack.EMPTY);
        }
        test.assertTrue(wedgedMill.link() == OreMillEntity.Link.OUTPUT_FULL,
                "a full by-product well never wedged the job: " + wedgedMill.link());
        int energy = wedgedMill.energy(), water = wedgedMill.water(), input = wedgedMill.inventory().getItem(0).getCount();
        test.assertTrue(wedgedMill.output(OreMillMenu.OUTPUT_PRIMARY).isEmpty(),
                "the wedged job delivered its primary while its sand well was full: " + wedgedMill.output(0));
        test.assertTrue(wedgedMill.output(OreMillMenu.OUTPUT_BYPRODUCT).getCount() == 64,
                "the wedged job touched the full well");
        run(test, wedgedMill, 200);
        test.assertTrue(wedgedMill.energy() == energy && wedgedMill.water() == water
                        && wedgedMill.inventory().getItem(0).getCount() == input,
                "the wedged job kept spending: FE " + (energy - wedgedMill.energy())
                        + " water " + (water - wedgedMill.water()));
        test.assertTrue(wedgedMill.progress() == wedgedMill.duration() - 1,
                "the finished job did not hold its last tick: " + wedgedMill.progress() + "/" + wedgedMill.duration());

        // 3) Emptying the well releases the held job: nothing was voided.
        wedgedMill.inventory().setItem(OreMillMenu.OUTPUT_FIRST + OreMillMenu.OUTPUT_BYPRODUCT, ItemStack.EMPTY);
        run(test, wedgedMill, 1);
        test.assertTrue(wedgedMill.inventory().getItem(0).getCount() == input - 1, "the held job never completed");
        test.assertTrue(wedgedMill.output(OreMillMenu.OUTPUT_PRIMARY).getCount() == 1,
                "the held job lost its concentrate: " + wedgedMill.output(OreMillMenu.OUTPUT_PRIMARY));
        // The held batch is delivered exactly as it was rolled: the sand that wedged it is part of it.
        test.assertTrue(wedgedMill.output(OreMillMenu.OUTPUT_BYPRODUCT).getCount() == 1,
                "the held job lost its by-product: " + wedgedMill.output(OreMillMenu.OUTPUT_BYPRODUCT));
        test.succeed();
    }

    @GameTest(template = "curios_test_room", timeoutTicks = 40)
    public static void genericCoarseConcentrateIsGone(GameTestHelper test) {
        var blocked = ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "coarse_concentrate");
        test.assertTrue(BuiltInRegistries.ITEM.get(blocked) == Items.AIR, "huhu_dronepal:coarse_concentrate is still registered");
        test.assertTrue(BuiltInRegistries.ITEM.keySet().stream()
                        .noneMatch(id -> id.getPath().contains("coarse_concentrate")),
                "an item id still mentions coarse_concentrate");
        test.assertTrue(test.getLevel().getRecipeManager().byKey(blocked).isEmpty(),
                "the coarse_concentrate recipe is still loaded");
        int sand = 0;
        for (OreKind kind : OreKind.values()) {
            if (!kind.hasConcentrate()) continue;
            sand++;
            var id = ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "coarse_" + kind.id());
            test.assertTrue(test.getLevel().getRecipeManager().byKey(
                            ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "coarse_" + kind.id() + "_nine")).isPresent(),
                    "missing recipe coarse_" + kind.id() + "_nine");
            test.assertTrue(BuiltInRegistries.ITEM.get(id) != Items.AIR, "coarse_" + kind.id() + " is not registered");
        }
        test.assertTrue(sand == 8, "expected eight coarse kinds, found " + sand);
        test.succeed();
    }
}
