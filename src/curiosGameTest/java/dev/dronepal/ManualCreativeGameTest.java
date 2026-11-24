package dev.dronepal;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The manual must appear in our tab exactly when Patchouli is installed, and never crash without it. */
@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class ManualCreativeGameTest {
    @GameTest(template = "curios_test_room")
    public static void manualOnlyWithPatchouli(GameTestHelper test) {
        boolean present = ModList.get().isLoaded("patchouli");
        ItemStack manual = Dronepal.manualStack();
        test.assertTrue(present == !manual.isEmpty(), "manual stack without/with Patchouli mismatch: present=" + present);
        if (!present) { test.succeed(); return; }
        var level = test.getLevel();
        Dronepal.CATALOG.get().buildContents(
                new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), false, level.registryAccess()));
        var listed = Dronepal.CATALOG.get().getDisplayItems().stream().filter(s -> s.is(manual.getItem())).toList();
        test.assertTrue(listed.size() == 1, "guide book must appear exactly once in the creative tab, found " + listed.size());
        // Component identity differs from our helper's ResourceLocation form, so compare the serialized id.
        test.assertTrue(listed.getFirst().getComponents().toString().contains("corvus_manual"),
                "the listed book points elsewhere: " + listed.getFirst().getComponents());
        test.succeed();
    }
}
