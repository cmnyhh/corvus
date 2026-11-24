package dev.dronepal;

import java.util.*;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal") @PrefixGameTestTemplate(false)
public final class ModuleTooltipGameTest {
    @GameTest(template="curios_test_room")
    public static void productTooltipsAndRemoteRoundTrip(GameTestHelper test) throws Exception {
        var catalog = Hardware.tooltipModules();
        test.assertTrue(catalog.size() == 27, "incomplete catalog");
        for (String locale : List.of("en_us", "zh_cn")) {
            try (var input = ModuleTooltipGameTest.class.getResourceAsStream("/assets/huhu_dronepal/lang/"+locale+".json");
                 var reader = new java.io.InputStreamReader(Objects.requireNonNull(input), java.nio.charset.StandardCharsets.UTF_8)) {
                var translations = JsonParser.parseReader(reader).getAsJsonObject();
                for (var entry : catalog.entrySet()) {
                    var stack = Hardware.moduleStack(entry.getKey().toString());
                    var lines = stack.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL);
                    var expected = ModuleTooltips.describe(entry.getValue());
                    test.assertTrue(lines.size() >= expected.size()+1 && lines.subList(1, expected.size()+1).equals(expected), "native stack omits or reorders descriptions: "+entry.getKey());
                    for (Component line : expected) {
                        var text = (TranslatableContents) line.getContents();
                        test.assertTrue(translations.has(text.getKey()), locale+" missing "+text.getKey());
                        var localized = String.format(Locale.ROOT, translations.get(text.getKey()).getAsString(), text.getArgs());
                        test.assertTrue(!localized.isBlank(), "empty localized line");
                    }
                }
            }
        }
        var scan = Hardware.moduleStack("scan_mk1");
        var id = Hardware.productId(scan);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            List<Component> changed = List.of(Component.translatable("dronepal.tip.scan", "42", "3"));
            ModuleTooltips.Catalog.CODEC.encode(buffer, new ModuleTooltips.Catalog(Map.of(id, changed)));
            ModuleTooltips.accept(ModuleTooltips.Catalog.CODEC.decode(buffer));
            var lines = scan.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL);
            test.assertTrue(lines.get(1).equals(changed.getFirst()), "remote values did not replace bundled numbers");
        } finally { ModuleTooltips.clearRemote(); buffer.release(); }
        test.succeed();
    }
}
