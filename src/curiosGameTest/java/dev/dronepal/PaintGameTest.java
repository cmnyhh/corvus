package dev.dronepal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class PaintGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 100)
    public static void ownerCanPaintAndKeepFinish(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var terminal = Dronepal.DRONE.toStack();
        CuriosTestSlots.set(player, terminal);
        DroneLifecycle.reconcile(player, CuriosDrones.equipped(player));
        var drone = (DroneEntity)test.getLevel().getEntity(DroneItem.serial(terminal));
        test.assertTrue(drone != null, "Deployment failed");
        var can = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "paint_can_neon")), 2);
        player.setItemInHand(InteractionHand.OFF_HAND, can);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.OFF_HAND, drone));
        test.assertTrue(drone.getPaintName().equals("neon"), "Projection did not repaint");
        test.assertTrue(terminal.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("dronepal_paint").equals("neon"), "Controller lost its finish");
        test.assertTrue(can.getCount() == (player.isCreative() ? 2 : 1), "Incorrect paint can consumption");
        test.succeed();
    }
}
