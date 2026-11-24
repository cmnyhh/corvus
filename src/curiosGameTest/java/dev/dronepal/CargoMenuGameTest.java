package dev.dronepal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The cargo hold screen: quantity conservation, terminal round trips, capacity limits, ownership. */
@GameTestHolder("huhu_dronepal") @PrefixGameTestTemplate(false)
public final class CargoMenuGameTest {
    @GameTest(template="curios_test_room",timeoutTicks=80)
    public static void nineSlotHoldRoundTrips(GameTestHelper t) {
        ServerPlayer p = equip(t,"carry_mk1");
        ItemStack controller = Hardware.controller(p);
        TerminalMenu panel = openTerminal(t,p);
        t.assertTrue(panel.cargoState() == 9, "a 9 slot module must advertise 9 slots, got " + panel.cargoState());
        CargoMenu cargo = hold(t,p);
        t.assertTrue(cargo.rows() == 1 && cargo.capacity() == 9 && cargo.slotCount() == 9, "9 slots must open as a single row");
        p.getInventory().setItem(0,new ItemStack(Items.PAPER,5));
        p.getInventory().setItem(1,new ItemStack(Items.IRON_INGOT,2));
        // Shift-click out of the hotbar: the real quick-move path, not a direct container poke.
        cargo.clicked(inventorySlot(cargo,0),0,ClickType.QUICK_MOVE,p);
        cargo.clicked(inventorySlot(cargo,1),0,ClickType.QUICK_MOVE,p);
        t.assertTrue(p.getInventory().getItem(0).isEmpty() && p.getInventory().getItem(1).isEmpty(),"shift-click left the source stack behind");
        List<ItemStack> stored = CarryLight.read(p,controller);
        t.assertTrue(stored.size() == 2 && total(stored) == 7, "hold did not keep every item: " + report(stored));
        t.assertTrue(stored.get(0).is(Items.PAPER) && stored.get(0).getCount() == 5 && stored.get(1).is(Items.IRON_INGOT), "hold lost the stack identity");
        t.assertTrue(cargo.getSlot(0).getItem().getCount() == 5 && cargo.getSlot(1).getItem().getCount() == 2, "view and terminal disagree");
        ItemStack reloaded = ItemStack.parse(p.registryAccess(),controller.save(p.registryAccess())).orElseThrow();
        t.assertTrue(same(CarryLight.read(p,reloaded),stored), "a serialized terminal no longer carries the hold");
        p.closeContainer();
        t.assertTrue(same(CarryLight.read(p,controller),stored), "closing the hold changed the terminal");
        t.succeed();
    }

    @GameTest(template="curios_test_room",timeoutTicks=80)
    public static void takeFromHoldUpdatesTerminal(GameTestHelper t) {
        ServerPlayer p = equip(t,"carry_mk1");
        ItemStack controller = Hardware.controller(p);
        CarryLight.save(p,controller,List.of(new ItemStack(Items.PAPER,1),new ItemStack(Items.GOLD_INGOT,3)));
        CargoMenu cargo = hold(t,p);
        t.assertTrue(cargo.getSlot(0).getItem().getCount() == 1 && cargo.getSlot(1).getItem().getCount() == 3,"the hold did not show what the terminal stores");
        cargo.clicked(0,0,ClickType.QUICK_MOVE,p);
        t.assertTrue(inventoryCount(p,Items.PAPER) == 1,"the taken item did not reach the inventory");
        t.assertTrue(cargo.getSlot(0).getItem().isEmpty(),"the hold still holds the item it just gave away");
        List<ItemStack> stored = CarryLight.read(p,controller);
        t.assertTrue(stored.size() == 1 && stored.get(0).is(Items.GOLD_INGOT) && total(stored) == 3,"terminal was not written back after the take: " + report(stored));
        ItemStack reloaded = ItemStack.parse(p.registryAccess(),controller.save(p.registryAccess())).orElseThrow();
        t.assertTrue(same(CarryLight.read(p,reloaded),stored),"reserialized terminal disagrees with the take");
        p.closeContainer();
        t.assertTrue(inventoryCount(p,Items.PAPER) == 1 && total(CarryLight.read(p,controller)) == 3,"closing the hold duplicated or lost the take");
        t.succeed();
    }

    @GameTest(template="curios_test_room",timeoutTicks=80)
    public static void fullHoldRefusesMore(GameTestHelper t) {
        ServerPlayer p = equip(t,"carry_rust_g1");
        ItemStack controller = Hardware.controller(p);
        List<ItemStack> full = new ArrayList<>();
        for (int i=0;i<12;i++) full.add(new ItemStack(Items.PAPER,64));
        CarryLight.save(p,controller,full);
        TerminalMenu panel = openTerminal(t,p);
        t.assertTrue(panel.cargoState() == 12,"the welded basket must advertise 12 slots, got " + panel.cargoState());
        CargoMenu cargo = hold(t,p);
        t.assertTrue(cargo.capacity() == 12 && cargo.slotCount() == 18,"12 slots must use two rows with six surplus wells");
        // Every stack is full, so merging cannot make room and the spare item must stay with the player.
        p.getInventory().setItem(0,new ItemStack(Items.PAPER,1));
        cargo.clicked(inventorySlot(cargo,0),0,ClickType.QUICK_MOVE,p);
        t.assertTrue(p.getInventory().getItem(0).getCount() == 1,"a full hold swallowed the overflow stack");
        t.assertTrue(total(CarryLight.read(p,controller)) == 12 * 64,"a full hold changed its contents: " + report(CarryLight.read(p,controller)));
        // A surplus well must refuse the cursor instead of parking an item the module cannot hold.
        cargo.setCarried(new ItemStack(Items.IRON_INGOT,1));
        cargo.clicked(12,0,ClickType.PICKUP,p);
        t.assertTrue(cargo.getCarried().getCount() == 1 && cargo.getSlot(12).getItem().isEmpty(),"a surplus well accepted a stack");
        t.assertTrue(CarryLight.read(p,controller).size() == 12,"a surplus well leaked into the terminal");
        cargo.setCarried(ItemStack.EMPTY);
        t.succeed();
    }

    @GameTest(template="curios_test_room",timeoutTicks=80)
    public static void holdRequiresCarryModule(GameTestHelper t) {
        ServerPlayer p = equip(t,"light_mk1");
        ItemStack controller = Hardware.controller(p);
        TerminalMenu panel = openTerminal(t,p);
        t.assertTrue(panel.cargoState() == 0,"no carry module but the terminal reports " + panel.cargoState());
        t.assertTrue(!panel.clickMenuButton(p,TerminalMenu.BUTTON_CARGO),"the hold opened without a carry module");
        t.assertTrue(p.containerMenu == panel,"a refused open still replaced the terminal");
        // Fitting the module on the live terminal makes the hold usable...
        var parts = new ArrayList<>(Hardware.contents(controller));
        parts.add(Hardware.moduleStack("carry_mk1"));
        Hardware.save(controller,parts);
        t.assertTrue(panel.clickMenuButton(p,TerminalMenu.BUTTON_CARGO),"a powered carry module was refused");
        t.assertTrue(p.containerMenu instanceof CargoMenu,"the hold did not open");
        p.closeContainer();
        // ...and an empty battery takes it away again.
        Hardware.setEnergy(controller,0);
        TerminalMenu drained = openTerminal(t,p);
        t.assertTrue(drained.cargoState() == -1,"an unpowered hold must report offline, got " + drained.cargoState());
        t.assertTrue(!drained.clickMenuButton(p,TerminalMenu.BUTTON_CARGO),"an unpowered hold still opened");
        t.assertTrue(p.containerMenu == drained,"an offline open still replaced the terminal");
        t.succeed();
    }

    @GameTest(template="curios_test_room",timeoutTicks=80)
    public static void holdRejectsForeignPlayerAndRange(GameTestHelper t) {
        ServerPlayer owner = equip(t,"carry_mk1");
        ItemStack controller = Hardware.controller(owner);
        CarryLight.save(owner,controller,List.of(new ItemStack(Items.PAPER,1)));
        CargoMenu cargo = hold(t,owner);
        BlockPos here = t.absolutePos(new BlockPos(2,1,2));
        ServerPlayer guest = GameTestPlayers.join(t);
        guest.teleportTo(owner.getX(),owner.getY(),owner.getZ());
        cargo.clicked(0,0,ClickType.PICKUP,guest);
        t.assertTrue(cargo.getCarried().isEmpty() && cargo.getSlot(0).getItem().getCount() == 1,"another player moved the owner's cargo");
        t.assertTrue(CarryLight.read(owner,controller).size() == 1,"another player rewrote the owner's terminal");
        // Out of range: the window must close and refuse further clicks.
        owner.teleportTo(here.getX()+15,here.getY(),here.getZ());
        t.assertTrue(!cargo.stillValid(owner),"a hold stayed valid at 15 blocks");
        cargo.clicked(0,0,ClickType.PICKUP,owner);
        t.assertTrue(cargo.getCarried().isEmpty() && cargo.getSlot(0).getItem().getCount() == 1,"a remote click moved cargo");
        t.assertTrue(CarryLight.read(owner,controller).size() == 1,"a remote click rewrote the terminal");
        t.succeed();
    }

    private static ServerPlayer equip(GameTestHelper t,String product) {
        ServerPlayer p = GameTestPlayers.join(t);
        BlockPos pos = t.absolutePos(new BlockPos(2,1,2));
        p.teleportTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        ItemStack terminal = Dronepal.DRONE.toStack();
        CuriosTestSlots.set(p,terminal);
        Hardware.save(terminal,List.of(Hardware.moduleStack(product),Hardware.BATTERIES.getFirst().toStack()));
        DroneItem.ensureSerial(terminal);
        DroneLifecycle.reconcile(p,terminal);
        t.assertTrue(Hardware.controller(p) == terminal,"the terminal did not deploy");
        return p;
    }

    private static TerminalMenu openTerminal(GameTestHelper t,ServerPlayer p) {
        DroneEntity drone = (DroneEntity)t.getLevel().getEntity(DroneItem.serial(Hardware.controller(p)));
        t.assertTrue(drone != null,"no projected drone for the terminal");
        p.setShiftKeyDown(true);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p,InteractionHand.MAIN_HAND,drone));
        p.setShiftKeyDown(false);
        t.assertTrue(p.containerMenu instanceof TerminalMenu,"empty-hand crouch did not open the terminal");
        return (TerminalMenu)p.containerMenu;
    }

    private static CargoMenu hold(GameTestHelper t,ServerPlayer p) {
        TerminalMenu panel = p.containerMenu instanceof TerminalMenu menu ? menu : openTerminal(t,p);
        t.assertTrue(panel.clickMenuButton(p,TerminalMenu.BUTTON_CARGO),"the cargo button refused a usable hold");
        t.assertTrue(p.containerMenu instanceof CargoMenu,"the cargo hold did not open");
        return (CargoMenu)p.containerMenu;
    }

    /** Menu index of a player inventory slot: hotbar 0-8 comes after the main grid. */
    private static int inventorySlot(CargoMenu menu,int index) {
        return menu.firstInventorySlot() + (index < 9 ? 27 + index : index - 9);
    }

    private static int total(List<ItemStack> stacks) { return stacks.stream().mapToInt(ItemStack::getCount).sum(); }

    private static int inventoryCount(ServerPlayer p,net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : p.getInventory().items) if (stack.is(item)) count += stack.getCount();
        return count;
    }

    private static boolean same(List<ItemStack> a,List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i=0;i<a.size();i++) if (!ItemStack.matches(a.get(i),b.get(i))) return false;
        return true;
    }

    private static String report(List<ItemStack> stacks) {
        return stacks.stream().map(s -> s.getItem() + "x" + s.getCount()).toList().toString();
    }
}
