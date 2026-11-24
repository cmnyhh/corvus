package dev.dronepal;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DialogueGameTest {
    @GameTest(template = "curios_test_room")
    public static void deployedConsole(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var output = new StringBuilder();
        var source = player.createCommandSourceStack().withSource(new CommandSource() {
            public void sendSystemMessage(Component message) { output.append(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        });
        run(test, source, output, "say 你好", "部署");
        var controller = Frames.create("owl");
        controller.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("云雀"));
        CuriosTestSlots.set(player, controller);
        DroneLifecycle.reconcile(player, controller);
        test.assertTrue(Hardware.controller(player) == controller, "equipped controller did not deploy");
        run(test, source, output, "say 你好", "芯片");
        for (String chip : List.of("tactical", "chatty")) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "chip_" + chip));
            Hardware.save(controller, List.of(new ItemStack(item)));
            run(test, source, output, "say 你是谁", chip.equals("tactical") ? "OW-03" : "云雀");
            test.assertTrue(output.toString().contains("夜枭"), "wrong physical frame: " + output);
            run(test, source, output, "配方 minecraft:torch", "minecraft:torch×4");
            run(test, source, output, "下一步干嘛", "下一项进度");
            run(test, source, output, "进度 minecraft:story/mine_stone", "未完成");
            run(test, source, output, "枪械数据 tacz:ak47", "未安装 TaCZ");
            run(test, source, output, "扫描", "扫描模块");
            run(test, source, output, "say 电量", "0 FE");
            run(test, source, output, "帮我放个方块", "我没有机械臂。");
        }
        controller.set(Frames.NAME.get(), "小云");
        run(test, source, output, "say 你是谁", "小云");
        for (var identity : java.util.Map.of("peregrine", "PR-07", "owl", "OW-03", "bedrock", "BR-12", "scrap", "RN-00").entrySet()) {
            test.assertTrue(DroneIdentity.of(Frames.create(identity.getKey())).callsign().equals(identity.getValue()), identity.toString());
        }
        var scanModule = Hardware.MODULE.toStack();
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, scanModule,
            tag -> { tag.putString("module_family", "scan"); tag.putInt("module_tier", 1); });
        var chip = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "chip_tactical"));
        Hardware.save(controller, List.of(new ItemStack(chip), scanModule, Hardware.BATTERIES.getFirst().toStack()));
        run(test, source, output, "say 扫描", "扫描完成");
        var drone = (DroneEntity)player.serverLevel().getEntity(DroneItem.serial(controller));
        test.assertTrue(drone != null && drone.isScanActive(), "command did not start physical scan");
        run(test, source, output, "say 扫描", "无法执行");
        test.succeed();
    }
    static java.util.List<CommsMessage> subtitles(net.minecraft.server.level.ServerPlayer player) {
        var channel = (io.netty.channel.embedded.EmbeddedChannel)player.connection.getConnection().channel();
        channel.runPendingTasks();
        var result = new java.util.ArrayList<CommsMessage>();
        Object item;
        while ((item = channel.readOutbound()) != null) {
            if (item instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet
                    && packet.payload() instanceof CommsMessage line) result.add(line);
            io.netty.util.ReferenceCountUtil.release(item);
        }
        return result;
    }
    private static void run(GameTestHelper test, CommandSourceStack source, StringBuilder output, String command, String expected) {
        output.setLength(0);
        try {
            source.getServer().getCommands().getDispatcher().execute("dronepal " + command, source);
        } catch (CommandSyntaxException ex) { throw new AssertionError(ex); }
        var received = subtitles(source.getPlayer());
        test.assertTrue(received.size() <= 1 && (received.isEmpty() || output.isEmpty()), "duplicated dialogue: " + output + received);
        received.forEach(line -> output.append(line.text().getString()));
        test.assertTrue(output.toString().contains(expected), command + " -> " + output + ", expected " + expected);
    }
}
