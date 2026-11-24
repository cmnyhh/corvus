package dev.dronepal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class FrameGameTest {
    @GameTest(template="curios_test_room")
    public static void frameRulesAndMigration(GameTestHelper t) {
        var p = GameTestPlayers.join(t);
        var inv = p.getInventory();
        var limits = Map.of("owl", 2, "peregrine", 3, "bedrock", 4, "scrap", 3);
        List<String> products = List.of("scan_mk1", "combat_mk1", "carry_mk1", "medic_mk1", "solar_mk1");
        for (String id : Frames.IDS) {
            var stack = Frames.create(id);
            int limit = limits.get(id);
            for (int i=0; i <= limit; i++) {
                inv.setItem(9, Hardware.moduleStack(products.get(i)));
                String result = Hardware.installFromInventory(p, stack, 9);
                t.assertTrue(i < limit ? result.isEmpty() && inv.getItem(9).isEmpty() : result.equals("full") && inv.getItem(9).getCount() == 1,
                    id + " incorrect module transaction at " + i);
            }
            for (var battery : Hardware.BATTERIES) {
                ItemStack cell = battery.toStack();
                inv.setItem(9, cell);
                boolean allowed = Hardware.batteryTier(cell) <= (id.equals("owl") || id.equals("scrap") ? 2 : 3);
                String result = Hardware.installFromInventory(p, stack, 9);
                t.assertTrue(allowed ? result.isEmpty() : result.equals("battery_tier") && inv.getItem(9) == cell && cell.getCount() == 1, id + " cell compatibility");
                if (allowed) t.assertTrue(Hardware.removeToInventory(p, stack, limit).isEmpty(), "could not remove cell");
            }
        }
        for (var alias : Map.of("standard", "peregrine", "heavy", "bedrock", "scout", "owl").entrySet()) {
            var old = Dronepal.DRONE.toStack();
            UUID serial = DroneItem.ensureSerial(old);
            Hardware.save(old, List.of(Hardware.BATTERIES.getFirst().toStack()));
            Hardware.setEnergy(old, 12345);
            CustomData.update(DataComponents.CUSTOM_DATA, old, tag -> { tag.putString("frame", "huhu_dronepal:" + alias.getKey()); tag.putString("dronepal_paint", "void"); });
            Frames.migrate(old);
            var restored = ItemStack.parse(t.getLevel().registryAccess(), old.save(t.getLevel().registryAccess())).orElseThrow();
            t.assertTrue(Frames.id(restored).equals(alias.getValue()) && serial.equals(DroneItem.serial(restored)) && Frames.paint(restored).equals("void")
                && Hardware.energy(restored) == 12345, "legacy migration lost hardware or identity");
        }
        t.assertTrue(Frames.id(Dronepal.DRONE.toStack()).equals("peregrine"), "missing frame default");
        t.succeed();
    }

    @GameTest(template="curios_test_room")
    public static void fourthSlotSurvivesRecallAndReload(GameTestHelper t) {
        var p = GameTestPlayers.join(t);
        var here = t.absolutePos(new BlockPos(2, 1, 2));
        p.teleportTo(here.getX()+.5, here.getY(), here.getZ()+.5);
        var stack = Frames.create("bedrock");
        CuriosTestSlots.set(p, stack);
        UUID serial = DroneItem.ensureSerial(stack);
        DroneLifecycle.reconcile(p, CuriosDrones.equipped(p));
        t.assertTrue(t.getLevel().getEntity(serial) instanceof DroneEntity, "deploy");
        for (String id : List.of("scan_mk1", "combat_mk1", "carry_mk1", "medic_mk1")) {
            p.getInventory().setItem(9, Hardware.moduleStack(id));
            t.assertTrue(Hardware.installFromInventory(p, stack, 9).isEmpty(), "could not install " + id);
        }
        CuriosTestSlots.set(p, ItemStack.EMPTY);
        DroneLifecycle.reconcile(p, ItemStack.EMPTY);
        var registries = t.getLevel().registryAccess();
        var restored = ItemStack.parse(registries, stack.save(registries)).orElseThrow();
        t.runAfterDelay(2, () -> {
            CuriosTestSlots.set(p, restored);
            DroneLifecycle.reconcile(p, CuriosDrones.equipped(p));
            var drone = (DroneEntity)t.getLevel().getEntity(serial);
            p.setShiftKeyDown(true);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, drone));
            t.assertTrue(p.containerMenu instanceof TerminalMenu, "terminal did not open");
            var menu = (TerminalMenu)p.containerMenu;
            t.assertTrue(menu.moduleSlots()==4 && menu.getSlot(7).isActive() && Hardware.productId(menu.getSlot(7).getItem()).getPath().equals("medic_mk1"), "fourth slot was lost");
            menu.clicked(7, 0, ClickType.QUICK_MOVE, p);
            t.assertTrue(Hardware.contents(restored).size()==3 && p.getInventory().contains(Hardware.moduleStack("medic_mk1")), "fourth module not returned");
            t.succeed();
        });
    }

    @GameTest(template="curios_test_room")
    public static void frameFollowAndDimensions(GameTestHelper t) {
        for (String id : List.of("owl", "bedrock")) {
            var drone = new DroneEntity(Dronepal.ENTITY.get(), t.getLevel());
            drone.setFrame(id);
            drone.setPos(0,0,0);
            boolean owl = id.equals("owl");
            float side = owl ? .55f : .9f;
            t.assertTrue(Math.abs(drone.getBbWidth()-side)<.001 && Math.abs(drone.getBbHeight()-side)<.001, "frame dimensions " + id);
            t.assertTrue(Math.abs(drone.followPosition(new Vec3(10,0,0)).x - (owl ? 2.5 : 1.5)) < .001, "frame response " + id);
            t.assertTrue((drone.followPosition(new Vec3(14,0,0)).x == 14) == owl, "frame teleport threshold " + id);
            if (!owl) drone.followPosition(new Vec3(30,0,0));
            drone.tickCount=19;
            t.assertTrue(drone.followPosition(new Vec3(30,0,0)).x < 30, "cooldown ignored " + id);
            drone.tickCount=20;
            t.assertTrue((drone.followPosition(new Vec3(30,0,0)).x == 30) == owl, "cooldown at 20 ticks " + id);
            drone.tickCount=80;
            t.assertTrue(drone.followPosition(new Vec3(30,0,0)).x == 30, "cooldown at 80 ticks " + id);
        }
        t.succeed();
    }
}
