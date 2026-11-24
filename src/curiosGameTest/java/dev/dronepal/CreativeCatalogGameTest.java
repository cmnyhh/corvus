package dev.dronepal;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class CreativeCatalogGameTest {
    @GameTest(template = "curios_test_room")
    public static void completeCreativeCatalog(GameTestHelper test) {
        var level = test.getLevel();
        var parameters = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), false, level.registryAccess());
        var tab = Dronepal.CATALOG.get();
        tab.buildContents(parameters);
        Set<ResourceLocation> items = new HashSet<>(), modules = new HashSet<>();
        Set<String> frames = new HashSet<>();
        for (var stack : tab.getDisplayItems()) {
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            boolean fresh = items.add(id);
            if (stack.is(Dronepal.DRONE.get())) fresh = frames.add(Frames.id(stack));
            if (stack.is(Hardware.MODULE.get())) fresh = modules.add(Hardware.productId(stack));
            test.assertTrue(fresh && stack.getCount() == 1, "duplicate creative entry " + id);
        }
        // Other mods (e.g. Patchouli inserting its guide book) may append to our tab; only our own ids are ours to cover.
        items.removeIf(id -> !id.getNamespace().equals(Dronepal.ID));
        var registered = BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals(Dronepal.ID)).collect(Collectors.toSet());
        test.assertTrue(items.equals(registered), "creative catalogue does not cover every registered item");
        long own = tab.getDisplayItems().stream()
                .filter(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(Dronepal.ID)).count();
        test.assertTrue(frames.equals(Set.copyOf(Frames.IDS)) && modules.size() == 27 && own == 78,
                "missing frame, product or supply variant (own entries=" + own + ")");
        var tools = BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(CreativeModeTabs.TOOLS_AND_UTILITIES);
        tools.buildContents(parameters);
        test.assertTrue(tools.getDisplayItems().stream().noneMatch(stack -> items.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))), "duplicate entries in vanilla tools tab");
        test.succeed();
    }
}
