package dev.dronepal;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class DurabilityGameTest {
    @GameTest(template="curios_test_room")
    public static void protectionModes(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        var original = ServerOptions.INVULNERABILITY.get();
        try {
            for (var mode : ServerOptions.Protection.values()) {
                ServerOptions.INVULNERABILITY.set(mode);
                for (var gameMode : java.util.List.of(GameType.CREATIVE, GameType.SURVIVAL)) {
                    owner.setGameMode(gameMode);
                    var controller = Frames.create("owl");
                    CuriosTestSlots.set(owner, controller);
                    var serial = DroneItem.ensureSerial(controller);
                    test.assertTrue(DroneItem.deploy(owner, controller, serial), "deploy " + mode);
                    var drone = (DroneEntity)test.getLevel().getEntity(serial);
                    boolean protectedDrone = mode == ServerOptions.Protection.always ||
                            (mode == ServerOptions.Protection.creative_owner && owner.isCreative());
                    boolean damaged = drone.hurt(drone.damageSources().generic(), 1000F);
                    test.assertTrue(damaged != protectedDrone, "ordinary damage " + mode + " " + gameMode);
                    drone.kill();
                    test.assertTrue(drone.isRemoved() && DroneLifecycle.repairing(test.getLevel(), serial), "kill/repair " + mode);
                }
            }
        } finally {
            ServerOptions.INVULNERABILITY.set(original);
        }
        test.succeed();
    }

    @GameTest(template="curios_test_room")
    public static void frameDurability(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        for (String id : Frames.IDS) {
            var controller = Frames.create(id);
            CuriosTestSlots.set(owner, controller);
            var serial = DroneItem.ensureSerial(controller);
            test.assertTrue(DroneItem.deploy(owner, controller, serial), "could not deploy " + id);
            var drone = (DroneEntity)test.getLevel().getEntity(serial);
            var frame = Frames.byId(id);
            test.assertTrue(drone.getHealth() == frame.maxHealth() && drone.getMaxHealth() == frame.maxHealth(), "full frame health on deploy: " + id);
            owner.setGameMode(GameType.CREATIVE);
            owner.attack(drone);
            test.assertTrue(!drone.hurt(drone.damageSources().generic(), 1000) && drone.getHealth() == frame.maxHealth(), "creative protection: " + id);
            owner.setGameMode(GameType.SURVIVAL);
            test.assertTrue(drone.hurt(drone.damageSources().generic(), 1000) && drone.getHealth() == frame.maxHealth()-frame.hitCap(), "survival cap: " + id);
            owner.setGameMode(GameType.CREATIVE);
            drone.kill();
            test.assertTrue(drone.isRemoved() && DroneLifecycle.repairing(test.getLevel(), serial), "kill bypass and repair timer: " + id);
        }
        test.succeed();
    }
}
