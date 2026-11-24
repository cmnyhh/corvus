package dev.dronepal;

import com.tacz.guns.api.TimelessAPI;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DialogueGunGameTest {
    @GameTest(template = "combat_test_room")
    public static void installedGunPackLookup(GameTestHelper test) {
        var id = ResourceLocation.parse("tacz:m4a1");
        var data = TimelessAPI.getCommonGunIndex(id).orElseThrow().getGunData();
        var text = DialogueLookup.answer(GameTestPlayers.join(test), Frames.create("owl"), "lookup.gun", "枪械数据 tacz:m4a1");
        test.assertTrue(text.contains("基础弹容 " + data.getAmmoAmount()) && text.contains("弹种 " + data.getAmmoId()) && text.contains(id.toString()), text);
        test.succeed();
    }
}
