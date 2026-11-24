package dev.dronepal;

import com.tacz.guns.api.item.builder.GunItemBuilder;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class WeaponMountGameTest {
    @GameTest(template = "combat_test_room", batch = "creative_range", timeoutTicks = 1600)
    public static void creativeOwnerUsesHostileOnly(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var companion = GameTestPlayers.join(test);
        companion.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        companion.setNoGravity(true);
        var level = test.getLevel();
        test.assertTrue(level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL, "surviving hostile requires non-peaceful difficulty");
        var pos = test.absolutePos(new net.minecraft.core.BlockPos(2, 50, 2));
        player.setNoGravity(true);
        var arena = new net.minecraft.world.level.ChunkPos(pos);
        player.teleportTo(arena.getMinBlockX() + 6.5, pos.getY(), arena.getMinBlockZ() + 7.5);
        var controller = Frames.create("peregrine");
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17")).setFireMode(com.tacz.guns.api.item.gun.FireMode.SEMI).setAmmoInBarrel(true).build();
        Hardware.save(controller, List.of(Hardware.moduleStack("combat_mk3"), Hardware.BATTERIES.getFirst().toStack(), gun));
        EquippedTestDrone.equip(test, player, controller, (equipped, drone) -> {
            var spot = drone.blockPosition().offset(5, 0, 0);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                level.setBlockAndUpdate(spot.offset(x, -1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(spot.offset(x, 3, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
            var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.spawn(level, spot, net.minecraft.world.entity.MobSpawnType.COMMAND);
            test.assertTrue(zombie != null, "vanilla zombie failed to spawn");
            zombie.setNoAi(true);
            var start = drone.getEyePosition();
            var end = zombie.getBoundingBox().getCenter();
            var between = start.lerp(end, .75);
            companion.teleportTo(between.x, between.y - .9, between.z);
            test.assertTrue(companion.getBoundingBox().clip(start, end).isPresent(), "companion is outside test lane");
            test.startSequence().thenExecuteAfter(8, () -> {
            var probe = new com.tacz.guns.entity.EntityKineticBullet(com.tacz.guns.entity.EntityKineticBullet.TYPE, level);
            probe.setPos(start); probe.setDeltaMovement(end.subtract(start));
            probe.setOwner(player);
            var ordinary = com.tacz.guns.util.EntityUtil.findEntityOnPath(probe, start, end);
            test.assertTrue(ordinary != null && ordinary.getEntity() == companion, "player-fired TaCZ round did not intersect companion");
            probe.setOwner(drone);
            var filtered = com.tacz.guns.util.EntityUtil.findEntityOnPath(probe, start, end);
            test.assertTrue(filtered != null && filtered.getEntity() == zombie, "companion consumed drone TaCZ collision");
            // Move the player into the policy's minimum engagement range and verify opt-in selection.
            boolean previousPvp = level.getServer().isPvpAllowed();
            level.getServer().setPvpAllowed(true);
            var board = level.getScoreboard();
            var vanillaTeam = board.addPlayerTeam("vanilla_ff");
            vanillaTeam.setAllowFriendlyFire(true);
            board.addPlayerToTeam(player.getScoreboardName(), vanillaTeam);
            board.addPlayerToTeam(companion.getScoreboardName(), vanillaTeam);
            test.assertTrue(player.isAlliedTo(companion) && !Engagement.protectedAlly(drone, player, companion), "vanilla friendly-fire team was treated as FTB membership");
            drone.setTarget(companion);
            level.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "dronepvp enable");
            level.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "dronepvp confirm");
            var pvp = com.tacz.guns.util.EntityUtil.findEntityOnPath(probe, start, end);
            boolean legal = DroneCombat.mayHitPlayer(drone, companion);
            level.getServer().setPvpAllowed(previousPvp);
            test.assertTrue(pvp != null && pvp.getEntity() == companion, "consented selected PVP target was filtered: legal=" + legal + ", distance=" + drone.distanceTo(companion) + ", consent=" + player.getPersistentData().getBoolean("dronepal_pvp") + ", candidate=" + (pvp == null ? null : pvp.getEntity()));
            level.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "dronepvp off");
            drone.setTarget(null);
            }).thenExecuteAfter(10, () -> {
                test.assertTrue(player.isCreative() && player.getLastHurtByMob() == null && drone.getTarget() == null && DroneCombat.mountPitch(drone) == 85, "unprovoked creative owner should stay stowed");
                var command = new DroneRemote.Command(drone.getUUID(), DroneRemote.Action.MODE);
                for (int i = 0; i < 5 && !Engagement.mode(equipped).equals("hostile_only"); i++) test.assertTrue(DroneRemote.execute(player, command), "wheel mode rejected");
            }).thenWaitUntil(() -> {
                test.assertTrue(drone.getTarget() == zombie && DroneCombat.mountPitch(drone) == 0 && zombie.getHealth() < zombie.getMaxHealth(), "automatic shot did not hit vanilla zombie");
                test.assertTrue(player.getLastHurtByMob() == null, "owner had to take damage");
                test.assertTrue(companion.getHealth() == companion.getMaxHealth() && companion.getLastHurtByMob() == null, "drone projectile damaged companion");
            }).thenExecute(zombie::discard).thenExecuteAfter(5, () -> {
                test.assertTrue(drone.getTarget() == null && DroneCombat.mountPitch(drone) == 0, "weapon did not hold briefly after target loss");
            }).thenExecuteAfter(30, () -> {
                test.assertTrue(DroneCombat.mountPitch(drone) == 85, "weapon failed to stow after grace period");
                test.assertTrue(DroneCombat.toggle(drone) && !DroneCombat.automatic(equipped.copy()), "safety preference not saved");
            }).thenSucceed();
        });
    }

    @GameTest(template = "combat_test_room")
    public static void mountRequiresHardwareAndLegacyGunIsRecoverable(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17")).setAmmoCount(7).build();
        var stack = Frames.create("peregrine");
        owner.getInventory().setItem(0, gun.copy());
        test.assertTrue("combat_required".equals(Hardware.installFromInventory(owner, stack, 0)), "installed gun without module");
        owner.getInventory().setItem(1, Hardware.moduleStack("combat_mk1"));
        test.assertTrue(Hardware.installFromInventory(owner, stack, 1).isEmpty() && Hardware.installFromInventory(owner, stack, 0).isEmpty(), "failed normal installation");
        test.assertTrue("remove_gun_first".equals(Hardware.removeToInventory(owner, stack, 0)), "removed module before gun");
        test.assertTrue(Hardware.removeToInventory(owner, stack, 1).isEmpty() && Hardware.removeToInventory(owner, stack, 0).isEmpty(), "failed normal removal");
        owner.getInventory().clearContent();
        Hardware.save(stack, List.of(gun.copy()));
        var old = ItemStack.parse(owner.registryAccess(), stack.save(owner.registryAccess())).orElseThrow();
        EquippedTestDrone.equip(test, owner, old, (equipped, drone) -> {
            DroneCombat.refresh(drone);
            test.assertTrue(drone.getMainHandItem().isEmpty(), "legacy gun visually mounted without module");
            test.assertTrue(!DroneCombat.setDeployed(drone, true), "armed legacy gun");
            DroneCombat.flush(drone);
            test.assertTrue(Hardware.removeToInventory(owner, equipped, 0).isEmpty() && Hardware.contents(equipped).isEmpty(), "legacy gun not recoverable");
            test.assertTrue(owner.getInventory().items.stream().filter(s -> ItemStack.matches(s, gun)).count() == 1, "legacy gun lost its magazine or duplicated");
            test.succeed();
        });
    }
}
