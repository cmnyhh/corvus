package dev.dronepal;

import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class HeadingGameTest {
    @GameTest(template="combat_test_room", batch="side_tracking", timeoutTicks=1600)
    public static void trackSideDuringFollow(GameTestHelper test) { verify(test, 6, 0); }
    @GameTest(template="combat_test_room", batch="rear_tracking", timeoutTicks=1600)
    public static void trackRearDuringFollow(GameTestHelper test) { verify(test, -3, -6); }

    private static void verify(GameTestHelper test, int x, int z) {
        var owner = GameTestPlayers.join(test);
        owner.setGameMode(GameType.CREATIVE);
        owner.setNoGravity(true);
        var spot = test.absolutePos(new BlockPos(1, 80, 1));
        var arena = new net.minecraft.world.level.ChunkPos(spot);
        owner.teleportTo(arena.getMinBlockX()+7.5, spot.getY(), arena.getMinBlockZ()+8.5);
        owner.setYRot(0);
        var controller = Frames.create("peregrine");
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17")).setAmmoCount(10).setAmmoInBarrel(true).setFireMode(FireMode.SEMI).build();
        Hardware.save(controller, List.of(Hardware.moduleStack("combat_mk3"), Hardware.BATTERIES.getFirst().toStack(), gun));
        EquippedTestDrone.equip(test, owner, controller, (equipped, drone) -> {
            var zombie = test.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 80, 1));
            zombie.setNoGravity(true);
            zombie.getAttribute(Attributes.SCALE).setBaseValue(1.5);
            var start = drone.getEyePosition();
            zombie.setPos(start.x + x, start.y - .8, start.z + z);
            test.onEachTick(() -> java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L));
            owner.setLastHurtByMob(zombie);
            owner.teleportTo(owner.getX(), owner.getY(), owner.getZ()+1.5);
            test.startSequence().thenExecuteAfter(2, () -> {
                test.assertTrue(drone.position().distanceToSqr(drone.xo, drone.yo, drone.zo) > .0004, "fixture did not move");
                test.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "fired during deployment");
                assertFacing(test, drone, zombie);
            }).thenWaitUntil(() -> {
                owner.setLastHurtByMob(zombie);
                assertFacing(test, drone, zombie);
                test.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "target not hit");
            }).thenSucceed();
        });
    }
    @GameTest(template="combat_test_room", batch="moving_fire", timeoutTicks=1600)
    public static void shootWhileContinuouslyFollowing(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        owner.setGameMode(GameType.CREATIVE);
        owner.setNoGravity(true);
        var center = test.absolutePos(new BlockPos(1, 110, 1)).getCenter();
        // GameTest keeps the template chunk ticking; the circular flight can cross all four borders.
        var forced = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        var anchor = new net.minecraft.world.level.ChunkPos(BlockPos.containing(center));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var chunk = new net.minecraft.world.level.ChunkPos(anchor.x + dx, anchor.z + dz);
            if (test.getLevel().setChunkForced(chunk.x, chunk.z, true)) forced.add(chunk);
        }
        owner.teleportTo(center.x + 7, center.y, center.z);
        var controller = Frames.create("peregrine");
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17"))
                .setFireMode(FireMode.SEMI).setAmmoInBarrel(true).build();
        Hardware.save(controller, List.of(Hardware.moduleStack("combat_mk3"), Hardware.BATTERIES.getFirst().toStack(), gun));
        EquippedTestDrone.equip(test, owner, controller, (equipped, drone) -> {
            var target = test.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(1, 110, 1));
            target.setNoGravity(true);
            target.getAttribute(Attributes.SCALE).setBaseValue(2.5);
            target.setPos(center.x, drone.getEyeY() - 2, center.z);
            var samples = new double[8];
            var shotSpeed = new double[]{0};
            var steps = new int[]{0};
            var spentSpeed = new double[]{-1};
            java.util.function.Consumer<com.tacz.guns.api.event.common.GunShootEvent> witness = event -> {
                if (event.getShooter() != drone || event.getLogicalSide().isClient()) return;
                shotSpeed[0] = Math.sqrt(drone.position().distanceToSqr(drone.xo, drone.yo, drone.zo));
                for (double speed : samples) shotSpeed[0] = Math.min(shotSpeed[0], speed);
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.tacz.guns.api.event.common.GunShootEvent.class, witness);
            test.onEachTick(() -> {
                int tick = ++steps[0];
                double speed = Math.sqrt(drone.position().distanceToSqr(drone.xo, drone.yo, drone.zo));
                samples[tick % samples.length] = speed;
                var mounted = Hardware.contents(equipped).stream().filter(CombatSlots::isGun).findFirst().orElseThrow();
                if (spentSpeed[0] < 0 && !com.tacz.guns.api.item.IGun.getIGunOrNull(mounted).hasBulletInBarrel(mounted)) spentSpeed[0] = speed;
                double phase = tick * .04;
                owner.teleportTo(center.x + 7 * Math.cos(phase), center.y, center.z + 7 * Math.sin(phase));
                // TaCZ uses a monotonic wall clock for draw and fire cooldowns; GameTest runs ticks unpaced.
                java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
                if (tick >= 20) owner.setLastHurtByMob(target);
            });
            test.startSequence().thenWaitUntil(() -> {
                test.assertTrue(shotSpeed[0] > .02, "moving shot=" + shotSpeed[0] + ", samples=" + java.util.Arrays.toString(samples) + ", steps=" + steps[0] + ", target=" + drone.getTarget() + ", status=" + drone.combat.lastResult + ", range=" + drone.distanceTo(target) + ", sight=" + drone.hasLineOfSight(target) + ", droneTick="+drone.tickCount + ", now="+com.tacz.guns.util.time.GunTime.nowMillis()+", draw="+com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(drone).getDataHolder().drawTimestamp);
                test.assertTrue(spentSpeed[0] > .02, "stopped before spending round");
                test.assertTrue(target.getHealth() < target.getMaxHealth(), "moving shot missed husk");
                var mounted = Hardware.contents(equipped).stream().filter(CombatSlots::isGun).findFirst().orElseThrow();
                var data = com.tacz.guns.api.item.IGun.getIGunOrNull(mounted);
                test.assertTrue(!data.hasBulletInBarrel(mounted) && data.getCurrentAmmoCount(mounted) == 0, "round not spent");
            }).thenExecute(() -> {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(witness);
                forced.forEach(chunk -> test.getLevel().setChunkForced(chunk.x, chunk.z, false));
                com.mojang.logging.LogUtils.getLogger().info("Moving TaCZ GameTest: minimum firing-window speed {}, spent-round tick speed {} blocks/tick, target HP {}, rounds remaining 0", shotSpeed[0], spentSpeed[0], target.getHealth());
            }).thenSucceed();
        });
    }

    private static void assertFacing(GameTestHelper test, DroneEntity drone, net.minecraft.world.entity.LivingEntity target) {
        float expected = (float)Math.toDegrees(Math.atan2(drone.getX()-target.getX(), target.getZ()-drone.getZ()));
        float relative = Mth.clamp(Mth.wrapDegrees(expected - drone.followHeading()), -75f, 75f);
        test.assertTrue(Math.abs(DroneCombat.gimbalYaw(drone) - relative) < 1, "gimbal did not take in-range yaw");
        for (float actual : new float[]{drone.getYRot(), drone.getYHeadRot(), drone.yBodyRot}) {
            test.assertTrue(Math.abs(Mth.wrapDegrees(actual + relative - expected)) < 1, "base and gimbal do not face target");
        }
        var direction = target.getBoundingBox().getCenter().subtract(drone.getEyePosition());
        float pitch = (float)-Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
        test.assertTrue(Math.abs(DroneCombat.aimPitch(drone) - pitch) < 1, "gimbal elevation does not face target");
    }
}
