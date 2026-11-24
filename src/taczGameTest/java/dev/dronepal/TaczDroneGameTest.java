package dev.dronepal;

import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class TaczDroneGameTest {
    @GameTest(template = "combat_test_room")
    public static void frameAcceptsActualGunTypes(GameTestHelper test) {
        var rifle = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:m4a1")).build();
        var rpg = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:rpg7")).build();
        test.assertTrue(CombatSlots.gunType(rifle).equals("rifle"), "unrecognized TaCZ rifle");
        var hardware = List.of(Hardware.moduleStack("combat_mk1"));
        test.assertTrue(!Hardware.installError(Frames.create("owl"), rifle, hardware).isEmpty(), "owl accepted rifle");
        test.assertTrue(Hardware.installError(Frames.create("peregrine"), rifle, hardware).isEmpty(), "peregrine rejected rifle");
        test.assertTrue(!Hardware.installError(Frames.create("scrap"), rpg, hardware).isEmpty(), "scrap accepted RPG");
        test.assertTrue(Hardware.installError(Frames.create("bedrock"), rpg, hardware).isEmpty(), "bedrock rejected RPG");
        test.succeed();
    }
    @GameTest(template = "combat_test_room", batch = "shooting_range", timeoutTicks = 2500)
    public static void realShotConsumesLastRound(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var pos = test.absolutePos(new BlockPos(1, 14, 1));
        owner.setNoGravity(true);
        var arena = new net.minecraft.world.level.ChunkPos(pos);
        owner.teleportTo(arena.getMinBlockX() + 7.5, pos.getY(), arena.getMinBlockZ() + 7.5);
        owner.setYRot(0);
        var controller = Dronepal.DRONE.toStack();
        var module = Hardware.MODULE.toStack();
        CustomData.update(DataComponents.CUSTOM_DATA, module, t -> { t.putString("module_family", "combat"); t.putInt("module_tier", 3); });
        var gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:glock_17")).setFireMode(FireMode.SEMI).setAmmoInBarrel(true).build();
        test.assertTrue(CombatSlots.isGun(gun), "built-in gun pack missing");
        var reserve = com.tacz.guns.api.item.builder.AmmoItemBuilder.create().setId(ResourceLocation.parse("tacz:9mm")).setCount(1).build();
        Hardware.save(controller, List.of(module, Hardware.BATTERIES.get(0).toStack(), gun, reserve));
        EquippedTestDrone.equip(test, owner, controller, (equipped, drone) -> exerciseCombat(test, owner, equipped, drone));
    }

    private static void exerciseCombat(GameTestHelper test, net.minecraft.server.level.ServerPlayer owner,
                                       ItemStack controller, DroneEntity drone) {
        test.assertTrue(drone != null && DroneCombat.automatic(controller) && DroneCombat.mountPitch(drone) == 85, "automatic weapon should start stowed");
        var zombie = test.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(2, 2, 3));
        zombie.setNoGravity(true);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(1.5);
        var start = drone.getEyePosition();
        zombie.setPos(start.x + 4.5, start.y - .8, start.z);
        test.assertTrue(Engagement.mode(controller).equals("retaliate") && Engagement.select(drone,16)==null, "default drone acquired an unprovoked mob");
        drone.hurt(drone.damageSources().mobAttack(zombie), 1F);
        test.assertTrue(Engagement.select(drone,16)==zombie, "default drone did not retaliate, attacker="+drone.getLastHurtByMob()+", sight="+drone.hasLineOfSight(zombie)+", distance="+drone.distanceTo(zombie));
        drone.setLastHurtByMob(null);
        owner.setLastHurtByMob(zombie);
        test.assertTrue(Engagement.select(drone,16)==zombie, "default drone did not retaliate for its owner");
        var cow = test.spawnWithNoFreeWill(EntityType.COW,new BlockPos(2,2,2));
        cow.setNoGravity(true);
        cow.setPos(start.x-5,start.y-.8,start.z);
        owner.setLastHurtMob(cow);
        var command = new DroneRemote.Command(drone.getUUID(), DroneRemote.Action.MODE);
        test.assertTrue(DroneRemote.execute(owner,command) && Engagement.mode(controller).equals("assist") && Engagement.select(drone,16)==cow,"assist selection: mode="+Engagement.mode(controller)+", target="+Engagement.select(drone,16)+", ownerTarget="+owner.getLastHurtMob()+", visible="+drone.hasLineOfSight(cow)+", distance="+drone.distanceTo(cow));
        test.assertTrue(DroneRemote.execute(owner,command) && Engagement.mode(controller).equals("aggressive") && Engagement.select(drone,16)==zombie,"aggressive selected a neutral target");
        test.assertTrue(DroneRemote.execute(owner,command) && Engagement.mode(controller).equals("passive") && Engagement.select(drone,16)==null,"passive returned fire");
        test.assertTrue(DroneRemote.execute(owner,command) && Engagement.mode(controller).equals("hostile_only") && Engagement.select(drone,16)==zombie,"hostile-only ignored monster");
        test.assertTrue(DroneRemote.execute(owner,command) && Engagement.mode(controller.copy()).equals("retaliate"),"wheel failed to persist mode");
        cow.discard();
        var villager = test.spawnWithNoFreeWill(EntityType.VILLAGER,new BlockPos(2,2,2));
        villager.setNoGravity(true);
        villager.setPos(start.x+2.5,start.y-.8,start.z);
        test.assertTrue(!Engagement.clearOfAllies(drone,zombie), "shot through a villager");
        test.runAfterDelay(3, () -> {
            float pitch = DroneCombat.mountPitch(drone);
            test.assertTrue(pitch > 0 && pitch < 85 && zombie.getHealth() == zombie.getMaxHealth(), "weapon fired before unfolding");
        });
        test.runAfterDelay(25, () -> {
            test.assertTrue(zombie.getHealth()==zombie.getMaxHealth() && villager.getHealth()==villager.getMaxHealth(), "blocked lane dealt damage");
            villager.discard();
        });
        // TaCZ's animation timers use wall time while GameTest accelerates game ticks.
        // Keep the hostile active across the separate reload/spent-round checks.
        test.onEachTick(() -> {
            java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
            owner.setLastHurtByMob(zombie);
        });
        test.succeedWhen(() -> {
            var actual = Hardware.contents(controller).stream().filter(CombatSlots::isGun).findFirst().orElse(ItemStack.EMPTY);
            var held = IGun.getIGunOrNull(actual);
            test.assertTrue(held != null && !held.hasBulletInBarrel(actual) && held.getCurrentAmmoCount(actual) == 0, "round not spent, pitch=" + DroneCombat.mountPitch(drone) + ", ticks=" + drone.tickCount + ", removed=" + drone.isRemoved() + ", target=" + drone.getTarget() + ", pos=" + drone.position() + ", zombie=" + zombie.position() + ", sight=" + drone.hasLineOfSight(zombie) + ", gun=" + drone.getMainHandItem() + ", status=" + drone.combat.lastResult);
            test.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "TaCZ shot did not hit husk");
            test.assertTrue(Hardware.contents(controller).stream().noneMatch(CombatSlots::isAmmo), "reload did not consume reserve round, result=" + drone.combat.lastResult);
            test.assertTrue("NO_AMMO".equals(drone.combat.lastResult), "TaCZ did not stop on empty chamber");
        });
    }
}
