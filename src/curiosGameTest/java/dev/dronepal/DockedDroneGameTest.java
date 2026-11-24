package dev.dronepal;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DockedDroneGameTest {
    @GameTest(template = "curios_test_room", timeoutTicks = 80)
    public static void projectedDroneChargesOwnerBattery(GameTestHelper test) {
        var world = test.getLevel();
        var player = GameTestPlayers.join(test);
        var pad = new BlockPos(2, 1, 2);
        var pos = test.absolutePos(pad);
        test.setBlock(pad, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("huhu_dronepal", "charging_dock")));
        player.setNoGravity(true);
        // At yaw zero the drone hovers +1.25 X and -0.75 Z from its owner.
        player.teleportTo(pos.getX() - .75, pos.getY(), pos.getZ() + 1.25);
        player.setYRot(0);
        ItemStack cell = Hardware.BATTERIES.getFirst().toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, cell, data -> data.putInt("energy", 0));
        ItemStack terminal = Dronepal.DRONE.toStack();
        terminal.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(cell)));
        CuriosTestSlots.set(player, terminal);
        DroneLifecycle.reconcile(player, terminal);
        var drone = (DroneEntity) world.getEntity(DroneItem.serial(terminal));
        test.assertTrue(drone != null, "terminal did not project a drone");
        test.assertTrue(new net.minecraft.world.phys.AABB(pos).expandTowards(0, .75, 0).intersects(drone.getBoundingBox()),
                "deployed drone missed pad: " + drone.position() + " vs " + pos);
        var port = drone.getCapability(Capabilities.EnergyStorage.ENTITY, Direction.DOWN);
        test.assertTrue(port != null && port.receiveEnergy(2000, false) == 600 && Hardware.energy(terminal) == 600,
                "entity FE port did not update owner's original battery");
        test.assertTrue(port.receiveEnergy(2000, false) == 0, "repeated entity charge exceeded one tick's allowance");
        var dock = world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
        test.assertTrue(dock.receiveEnergy(2000, false) == 2000, "dock did not accept cable input");
        test.runAfterDelay(8, () -> {
            test.assertTrue(Hardware.energy(terminal) == 2600 && dock.getEnergyStored() == 0,
                    "nearby projected drone did not receive the dock's 2000 FE");
            test.succeed();
        });
    }
}
