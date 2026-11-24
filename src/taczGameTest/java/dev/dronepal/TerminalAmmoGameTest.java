package dev.dronepal;

import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class TerminalAmmoGameTest {
    @GameTest(template="combat_test_room") public static void owlAmmoMenu(GameTestHelper t) { exercise(t,"owl",4); }
    @GameTest(template="combat_test_room") public static void peregrineAmmoMenu(GameTestHelper t) { exercise(t,"peregrine",6); }
    @GameTest(template="combat_test_room") public static void scrapAmmoMenu(GameTestHelper t) { exercise(t,"scrap",6); }
    @GameTest(template="combat_test_room") public static void bedrockAmmoMenu(GameTestHelper t) { exercise(t,"bedrock",8); }

    private static void exercise(GameTestHelper t, String frame, int limit) {
        var owner = GameTestPlayers.join(t);
        var pos = t.absolutePos(new BlockPos(1, 12, 1));
        owner.setNoGravity(true);
        owner.teleportTo(pos.getX()+.5, pos.getY(), pos.getZ()+.5);
        var slot = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(owner).orElseThrow()
                .getStacksHandler("drone").orElseThrow().getStacks();
        t.assertTrue(slot.insertItem(0,Frames.create(frame),false).isEmpty(), "equip failed");
        ItemStack controller = slot.getStackInSlot(0);
        DroneLifecycle.reconcile(owner,controller);
        var drone = (DroneEntity)t.getLevel().getEntity(DroneItem.serial(controller));
        t.assertTrue(drone != null, "projection missing");
        owner.setShiftKeyDown(true);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(owner, InteractionHand.MAIN_HAND, drone));
        t.assertTrue(owner.containerMenu instanceof TerminalMenu, "owner could not open menu");
        var menu = (TerminalMenu)owner.containerMenu;
        var counts = new ArrayList<Integer>();
        for (int index=0; index<8; index++) {
            t.assertTrue(menu.getSlot(TerminalMenu.ammoSlot(index)).isActive() == (index<limit), frame+" slot visibility " + index);
        }
        for (int quantity=1; quantity<=limit+1; quantity++) {
            var ammo = AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:9mm")).setCount(quantity).build();
            owner.getInventory().setItem(9,ammo);
            menu.clicked(TerminalMenu.firstInventorySlot(),0,ClickType.PICKUP,owner);
            if (quantity<=limit) {
                menu.clicked(TerminalMenu.ammoSlot(quantity-1),0,ClickType.PICKUP,owner);
                counts.add(quantity);
                t.assertTrue(owner.getInventory().getItem(9).isEmpty() && menu.getSlot(TerminalMenu.ammoSlot(quantity-1)).getItem().getCount()==quantity, "stack not installed");
            } else menu.clicked(TerminalMenu.firstInventorySlot(),0,ClickType.PICKUP,owner);
        }
        owner.getInventory().setItem(9,ItemStack.EMPTY);
        int middle = limit/2;
        int expected = counts.remove(middle);
        menu.clicked(TerminalMenu.ammoSlot(middle),0,ClickType.QUICK_MOVE,owner);
        t.assertTrue(owner.getInventory().countItem(AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:9mm")).build().getItem())==expected, "wrong quantity removed");
        var remaining = Hardware.contents(controller).stream().filter(CombatSlots::isAmmo).map(ItemStack::getCount).toList();
        t.assertTrue(remaining.equals(counts), "removed wrong same-ammo stack: " + remaining);
        t.assertTrue(menu.getSlot(TerminalMenu.ammoSlot(middle)).getItem().isEmpty(), "removed slot is not empty");
        var stack = AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:9mm")).setCount(12).build();
        owner.getInventory().setItem(9,stack);
        menu.clicked(TerminalMenu.firstInventorySlot(),1,ClickType.PICKUP,owner);
        t.assertTrue(menu.getCarried().getCount()==6 && owner.getInventory().getItem(9).getCount()==6, "right-click did not split stack");
        int first = TerminalMenu.ammoSlot(0), empty = TerminalMenu.ammoSlot(middle);
        menu.clicked(-999,0,ClickType.QUICK_CRAFT,owner);
        menu.clicked(first,1,ClickType.QUICK_CRAFT,owner);
        menu.clicked(empty,1,ClickType.QUICK_CRAFT,owner);
        menu.clicked(-999,2,ClickType.QUICK_CRAFT,owner);
        t.assertTrue(menu.getCarried().isEmpty() && menu.getSlot(first).getItem().getCount()==4 && menu.getSlot(empty).getItem().getCount()==3, "left-drag did not evenly distribute ammo");
        var saved=Hardware.contents(controller).stream().filter(CombatSlots::isAmmo).mapToInt(ItemStack::getCount).sum();
        t.assertTrue(saved==counts.stream().mapToInt(Integer::intValue).sum()+6, "drag rollback or duplication");
        t.succeed();
    }
}
