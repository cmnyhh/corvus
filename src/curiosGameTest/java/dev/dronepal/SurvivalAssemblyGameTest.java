package dev.dronepal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class SurvivalAssemblyGameTest {
    private static ItemStack kit() { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("huhu_dronepal:upgrade_kit"))); }
    private static SurvivalWorks.Plan upgrade() { return SurvivalWorks.plans.stream().filter(p -> p.output().module().equals("carry_mk2")).findFirst().orElseThrow(); }
    @GameTest(template = "curios_test_room", timeoutTicks = 145)
    public static void exactPredecessorAndAtomicAssembly(GameTestHelper test) {
        BlockPos pos = test.absolutePos(new BlockPos(1,1,1));
        var level = test.getLevel();
        level.setBlockAndUpdate(pos, SurvivalWorks.BENCH.get().defaultBlockState());
        var station = (AssemblyStation) level.getBlockEntity(pos);
        var player = GameTestPlayers.join(test);
        player.setPos(pos.getCenter());
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id,inv,p) -> new AssemblyMenu(id, inv, station), SurvivalWorks.BENCH.get().getName()));
        var menu = (AssemblyMenu) player.containerMenu;
        for (int i=0; i<SurvivalWorks.plans.size() && !upgrade().equals(station.plan()); i++) menu.clickMenuButton(player, 1);
        test.assertTrue(station.plan().equals(upgrade()), "blueprint selection failed");
        menu.setCarried(Hardware.moduleStack("carry_rust_g1"));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        menu.setCarried(kit());
        menu.clicked(1, 0, ClickType.PICKUP, player);
        test.assertTrue(!station.start() && station.inventory.getItem(1).is(kit().getItem()), "wrong vendor upgraded or lost kit");
        station.inventory.setItem(0, Hardware.moduleStack("carry_mk1"));
        station.inventory.setItem(9, new ItemStack(Items.COBBLESTONE,64));
        test.assertTrue(!station.start() && !station.inventory.getItem(0).isEmpty(), "full output consumed predecessor");
        station.inventory.setItem(9, ItemStack.EMPTY);
        menu.clickMenuButton(player, 2);
        test.assertTrue(station.busy() && station.inventory.isEmpty() && !station.start(), "duplicate start or instant output");
        player.closeContainer();
        test.runAfterDelay(10, () -> {
            test.assertTrue(station.elapsed > 0 && station.inventory.getItem(9).isEmpty(), "closed station failed to advance");
            var saved = station.saveWithoutMetadata(level.registryAccess());
            var restored = new AssemblyStation(pos, station.getBlockState());
            restored.loadWithComponents(saved, level.registryAccess());
            level.removeBlockEntity(pos);
            level.setBlockEntity(restored);
            test.assertTrue(restored.busy() && restored.elapsed == station.elapsed, "save/load lost active progress");
        });
        test.runAfterDelay(100, () -> {
            var restored = (AssemblyStation) level.getBlockEntity(pos);
            var result = restored.inventory.removeItem(9, 64);
            test.assertTrue(!restored.busy() && result.getCount() == 1 && Hardware.productId(result).getPath().equals("carry_mk2") && restored.inventory.isEmpty(), "completed job did not deliver exactly one correct product");
            test.succeed();
        });
    }
    @GameTest(template = "curios_test_room")
    public static void dismantleRefundsWorkInProgress(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(1,1,1));
        var level = test.getLevel();
        level.setBlockAndUpdate(pos, SurvivalWorks.BENCH.get().defaultBlockState());
        var station = (AssemblyStation) level.getBlockEntity(pos);
        station.select(upgrade());
        station.inventory.setItem(0, Hardware.moduleStack("carry_mk1"));
        station.inventory.setItem(1, kit());
        station.inventory.setItem(2, new ItemStack(Items.DIAMOND,3));
        test.assertTrue(station.start(), "test job did not start");
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var drops = level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(0.5)).stream().map(ItemEntity::getItem).toList();
        test.assertTrue(drops.stream().filter(s -> s.is(kit().getItem())).mapToInt(ItemStack::getCount).sum()==1 && drops.stream().filter(s -> Hardware.productId(s).getPath().equals("carry_mk1")).mapToInt(ItemStack::getCount).sum()==1 && drops.stream().filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum()==3 && drops.stream().mapToInt(ItemStack::getCount).sum()==5, "breaking station lost or duplicated job materials");
        test.succeed();
    }

    private record Bench(AssemblyMenu menu, net.minecraft.server.level.ServerPlayer player, AssemblyStation station) {}
    private static Bench openBench(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(1,1,1));
        var level = test.getLevel();
        level.setBlockAndUpdate(pos, SurvivalWorks.BENCH.get().defaultBlockState());
        var station = (AssemblyStation) level.getBlockEntity(pos);
        var player = GameTestPlayers.join(test);
        player.setPos(pos.getCenter());
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id,inv,p) -> new AssemblyMenu(id, inv, station), SurvivalWorks.BENCH.get().getName()));
        return new Bench((AssemblyMenu) player.containerMenu, player, station);
    }

    /** Catalog order/identity over the wire plus the SELECT+index protocol and its rejections. */
    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void blueprintIndexSelectionAndCatalog(GameTestHelper test) {
        var bench = openBench(test);
        var menu = bench.menu();
        var player = bench.player();
        var station = bench.station();
        var plans = SurvivalWorks.plans;
        test.assertTrue(plans.size() > 5, "too few blueprints to exercise the list");

        var catalog = SurvivalWorks.catalog();
        test.assertTrue(catalog.entries().size() == plans.size(), "catalog is not in plan order");
        for (int i = 0; i < plans.size(); i++) {
            var entry = catalog.entries().get(i);
            test.assertTrue(entry.icon().is(plans.get(i).output().stack().getItem()) && entry.ingredients().size() == plans.get(i).costs().size(), "catalog entry " + i + " does not match the plan");
        }
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            SurvivalWorks.Catalog.CODEC.encode(buffer, catalog);
            var decoded = SurvivalWorks.Catalog.CODEC.decode(buffer);
            test.assertTrue(decoded.entries().size() == catalog.entries().size(), "catalog lost entries on the wire");
            for (int i = 0; i < catalog.entries().size(); i++) {
                test.assertTrue(decoded.entries().get(i).name().equals(catalog.entries().get(i).name())
                        && ItemStack.matches(decoded.entries().get(i).icon(), catalog.entries().get(i).icon()), "catalog entry " + i + " changed on the wire");
            }
            var first = decoded.entries().get(plans.indexOf(upgrade())).ingredients().getFirst();
            test.assertTrue(first.matches(Hardware.moduleStack("carry_mk1")) && !first.matches(Hardware.moduleStack("carry_mk3")) && !first.matches(kit()), "ingredient identity did not survive the wire");
        } finally { buffer.release(); }

        int target = 4;
        test.assertTrue(menu.clickMenuButton(player, AssemblyMenu.SELECT + target), "direct index selection was rejected");
        test.assertTrue(plans.get(target).equals(station.plan()), "direct index selected the wrong blueprint");
        test.assertTrue(!menu.clickMenuButton(player, AssemblyMenu.SELECT + plans.size()), "index past the end was accepted");
        test.assertTrue(!menu.clickMenuButton(player, AssemblyMenu.SELECT - 1), "index below the base was accepted");
        test.assertTrue(!menu.clickMenuButton(player, -7), "negative button id was accepted");
        test.assertTrue(plans.get(target).equals(station.plan()), "a rejected click still changed the selection");
        test.assertTrue(menu.clickMenuButton(player, 1) && plans.get((target + 1) % plans.size()).equals(station.plan()), "next fallback is broken");
        test.assertTrue(menu.clickMenuButton(player, 0) && plans.get(target).equals(station.plan()), "previous fallback is broken");

        var stray = new AssemblyMenu(menu.containerId + 1000, player.getInventory(), station);
        test.assertTrue(!stray.clickMenuButton(player, AssemblyMenu.SELECT), "a menu the player does not have open accepted a click");
        player.setPos(station.getBlockPos().getCenter().add(24, 0, 0));
        test.assertTrue(!menu.clickMenuButton(player, AssemblyMenu.SELECT), "an out-of-range player could still select");
        player.setPos(station.getBlockPos().getCenter());
        test.assertTrue(menu.clickMenuButton(player, AssemblyMenu.SELECT), "selection failed after returning in range");

        // Every visible list row must select exactly itself, and re-clicking the row that is already
        // selected must be accepted. The screen sends SELECT+index unconditionally: menu.selection() is
        // a mirror of this ContainerData that lags on the client, and using it to skip "redundant"
        // clicks dropped real row clicks (the "clicking a blueprint does nothing" bug).
        for (int i = 0; i < plans.size(); i++) {
            test.assertTrue(menu.clickMenuButton(player, AssemblyMenu.SELECT + i), "row " + i + " was refused");
            test.assertTrue(station.selectedIndex() == i, "row " + i + " selected index " + station.selectedIndex());
            test.assertTrue(menu.selection() == i + 1, "row " + i + " did not reach the menu data slot");
        }
        int last = plans.size() - 1;
        test.assertTrue(menu.clickMenuButton(player, AssemblyMenu.SELECT + last), "re-selecting the current row was refused");
        test.assertTrue(station.selectedIndex() == last, "re-selecting the current row moved it");

        int listRight = AssemblyMenu.LIST_X + AssemblyMenu.LIST_WIDTH;
        int listBottom = AssemblyMenu.LIST_Y + AssemblyMenu.LIST_ROWS * AssemblyMenu.ROW_HEIGHT;
        test.assertTrue(AssemblyMenu.PANEL_WIDTH <= 320 && AssemblyMenu.PANEL_HEIGHT <= 240, "panel no longer fits a small window");
        for (var slot : menu.slots) {
            test.assertTrue(slot.x >= 0 && slot.y >= 0 && slot.x + 16 <= AssemblyMenu.PANEL_WIDTH && slot.y + 16 <= AssemblyMenu.PANEL_HEIGHT, "slot " + slot.index + " leaves the panel");
            test.assertTrue(!(slot.x < listRight && slot.x + 16 > AssemblyMenu.LIST_X && slot.y < listBottom && slot.y + 16 > AssemblyMenu.LIST_Y), "slot " + slot.index + " overlaps the blueprint list");
        }
        test.succeed();
    }

    /** A running job owns its plan: every selection button and a second start must be refused. */
    @GameTest(template = "curios_test_room", timeoutTicks = 60)
    public static void busyStationRejectsSelectionChanges(GameTestHelper test) {
        var bench = openBench(test);
        var menu = bench.menu();
        var player = bench.player();
        var station = bench.station();
        int upgradeIndex = SurvivalWorks.plans.indexOf(upgrade());
        test.assertTrue(menu.clickMenuButton(player, AssemblyMenu.SELECT + upgradeIndex) && upgrade().equals(station.plan()), "could not select the upgrade blueprint by index");
        station.inventory.setItem(0, Hardware.moduleStack("carry_mk1"));
        station.inventory.setItem(1, kit());
        test.assertTrue(menu.clickMenuButton(player, 2) && station.busy(), "start did not begin a job");
        var running = station.plan();
        test.assertTrue(!menu.clickMenuButton(player, AssemblyMenu.SELECT + (upgradeIndex + 1) % SurvivalWorks.plans.size()), "busy station changed blueprint by index");
        test.assertTrue(!menu.clickMenuButton(player, 0), "busy station changed blueprint backwards");
        test.assertTrue(!menu.clickMenuButton(player, 1), "busy station changed blueprint forwards");
        test.assertTrue(!menu.clickMenuButton(player, 2), "busy station started a second job");
        test.assertTrue(station.plan() == running && station.elapsed == 0, "a rejected click disturbed the running job");
        test.succeed();
    }
}
