package dev.dronepal;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal") @PrefixGameTestTemplate(false)
public final class AutoScanGameTest {
    @GameTest(template="curios_test_room")
    public static void completedReportsUseLiveNearestTargets(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var pos = test.absolutePos(new BlockPos(2, 2, 2));
        player.teleportTo(pos.getX()+.5, pos.getY(), pos.getZ()+.5);
        var controller = Frames.create("peregrine");
        var cell = Hardware.BATTERIES.getFirst().toStack();
        var scanner = Hardware.moduleStack("scan_mk2");
        Hardware.save(controller, List.of(cell, scanner));
        CuriosTestSlots.set(player, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = (DroneEntity)test.getLevel().getEntity(DroneItem.serial(controller));
        var level = test.getLevel();
        var near = pos.above();
        level.setBlockAndUpdate(near, Blocks.COAL_ORE.defaultBlockState());
        // More than a packet's worth of ore encountered before reaching the near contact.
        for (int i=0; i<36; i++) level.setBlockAndUpdate(pos.offset(i%6-8,1,i/6-8), Blocks.IRON_ORE.defaultBlockState());
        var nearMob = test.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3,2,2));
        var removed = test.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2,2,3));
        var reports = new java.util.ArrayList<ScanSnapshot>();
        DroneSystems systems = new DroneSystems((owner, snapshot, automatic) -> {
            test.assertTrue(owner == player && !automatic, "wrong recipient or trigger");
            reports.add(snapshot);
        });
        test.assertTrue(systems.execute(drone, player, controller, "scan"), "scan not accepted");
        removed.discard();
        for (int tick=0; tick<220; tick++) systems.tick(drone, player);
        test.assertTrue(reports.size()==1, "expected one final report, received " + reports.size());
        var result = reports.getFirst();
        test.assertTrue(result.ores().size()==32 && result.ores().getFirst().pos().equals(near), "near ore lost in dense vein");
        test.assertTrue(result.hostiles().getFirst().uuid().equals(nearMob.getUUID()) && result.hostiles().stream().noneMatch(h -> h.uuid().equals(removed.getUUID())), "reported a stale enemy");
        for (String interrupt : List.of("power", "removed", "dimension")) {
            Hardware.save(controller, List.of(cell, scanner));
            net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,controller,t -> t.remove("dronepal_scan_ready"));
            test.assertTrue(systems.execute(drone, player, controller, "scan"), "failed to start " + interrupt);
            if (interrupt.equals("power")) Hardware.setEnergy(controller,0);
            else if (interrupt.equals("removed")) Hardware.save(controller,List.of(cell));
            else player.teleportTo(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER), 0, 80, 0, 0, 0);
            for (int tick=0; tick<220; tick++) systems.tick(drone, player);
            test.assertTrue(reports.size()==1, "unexpected completion after " + interrupt);
        }
        test.succeed();
    }
    @GameTest(template="curios_test_room", timeoutTicks=760)
    public static void wheelPersistsPatrol(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var pos = test.absolutePos(new BlockPos(2,2,2));
        player.setNoGravity(true);
        player.teleportTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        var terminal = Frames.create("peregrine");
        Hardware.save(terminal,List.of(Hardware.moduleStack("scan_mk1"), Hardware.BATTERIES.getFirst().toStack()));
        CuriosTestSlots.set(player,terminal);
        DroneLifecycle.reconcile(player,terminal);
        var id = DroneItem.serial(terminal);
        var old = (DroneEntity)test.getLevel().getEntity(id);
        int bit = 1 << DroneRemote.Action.SCAN.ordinal();
        var initial = DroneRemote.query(player,new DroneRemote.Query(19,1));
        test.assertTrue(initial != null && (initial.enabled() & bit) != 0 && (initial.off() & bit) == 0 && initial.labels().get(3).endsWith("scan_on"), "default patrol status");
        test.assertTrue(Hardware.execute(player,"scan") && old.scanSnapshot != null,"manual setup scan failed");
        var off = DroneRemote.command(player,new DroneRemote.Command(id,DroneRemote.Action.SCAN,19,2));
        test.assertTrue(off != null && (off.off() & bit) != 0 && off.labels().get(3).endsWith("scan_off") && old.scanSnapshot == null && !old.isScanActive(),"wheel did not immediately cancel patrol");
        test.assertTrue(!Hardware.execute(player,"scan"),"manual scan bypassed disabled scanner");
        CuriosTestSlots.set(player,net.minecraft.world.item.ItemStack.EMPTY);
        DroneLifecycle.reconcile(player,net.minecraft.world.item.ItemStack.EMPTY);
        var restored = net.minecraft.world.item.ItemStack.parse(player.registryAccess(),terminal.save(player.registryAccess())).orElseThrow();
        CuriosTestSlots.set(player,restored);
        DroneLifecycle.reconcile(player,restored);
        test.runAfterDelay(335, () -> {
            var drone = (DroneEntity)test.getLevel().getEntity(id);
            test.assertTrue(drone != old && drone.scanSnapshot == null && DroneRemote.disabled(restored,"scan"),"patrol restarted after re-equip");
            var on = DroneRemote.command(player,new DroneRemote.Command(id,DroneRemote.Action.SCAN,19,3));
            test.assertTrue(on != null && (on.off() & bit) == 0 && on.labels().get(3).endsWith("scan_on"), "wheel failed to arm patrol");
        });
        test.runAfterDelay(625, () -> test.assertTrue(((DroneEntity)test.getLevel().getEntity(id)).scanSnapshot == null,"scan ran before the patrol interval"));
        test.runAfterDelay(660, () -> {
            var drone = (DroneEntity)test.getLevel().getEntity(id);
            test.assertTrue(drone.scanSnapshot != null,"rearmed patrol did not scan");
            Hardware.setEnergy(restored,1);
            test.assertTrue(DroneRemote.status(player,19).labels().get(3).endsWith("scan_standby"),"missing low-power standby state");
        });
        test.runAfterDelay(663, () -> {
            var drone = (DroneEntity)test.getLevel().getEntity(id);
            test.assertTrue(drone.scanSnapshot == null,"low-power marks survived a tick");
            Hardware.save(restored,List.of(Hardware.BATTERIES.getFirst().toStack()));
            test.assertTrue((DroneRemote.status(player,19).enabled() & bit)==0,"empty sector remained enabled");
            test.succeed();
        });
    }
    @GameTest(template="curios_test_room", timeoutTicks=460)
    public static void automaticOwnerSnapshot(GameTestHelper test) {
        var player = GameTestPlayers.join(test);
        var position = test.absolutePos(new BlockPos(2, 2, 2));
        player.setNoGravity(true);
        player.teleportTo(position.getX()+.5, position.getY(), position.getZ()+.5);
        var controller = Frames.create("peregrine");
        var cell = Hardware.BATTERIES.getFirst().toStack();
        Hardware.save(controller, List.of(cell, Hardware.moduleStack("scan_mk3")));
        CuriosTestSlots.set(player, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = (DroneEntity)test.getLevel().getEntity(DroneItem.serial(controller));
        var ore = drone.blockPosition().offset(1,0,0);
        test.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
        var target = test.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3,1,3));
        target.setNoGravity(true);
        test.runAfterDelay(280, () -> test.assertTrue(drone.scanSnapshot == null, "early automatic scan"));
        test.runAfterDelay(350, () -> {
            var scan = drone.scanSnapshot;
            test.assertTrue(scan != null && scan.expiresAt() > test.getLevel().getGameTime(), "automatic scanner did not start");
            test.assertTrue(scan.serial().equals(drone.getUUID()) && scan.dimension().equals(test.getLevel().dimension().location()), "incorrect owner binding");
            test.assertTrue(scan.hostiles().stream().anyMatch(e -> e.entityId() == target.getId() && e.uuid().equals(target.getUUID())), "missing hostile contact");
            test.assertTrue(scan.ores().stream().anyMatch(e -> e.pos().equals(ore) && e.type().equals(ResourceLocation.withDefaultNamespace("diamond_ore"))), "missing diamond contact");
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), test.getLevel().registryAccess());
            try { ScanSnapshot.CODEC.encode(buffer, scan); test.assertTrue(ScanSnapshot.CODEC.decode(buffer).equals(scan), "snapshot wire mismatch"); }
            finally { buffer.release(); }
            Hardware.setEnergy(controller, 10);
        });
        test.runAfterDelay(375, () -> {
            test.assertTrue(drone.scanSnapshot == null && !drone.isScanActive(), "low power retained targets");
            Hardware.save(controller, List.of(cell, Hardware.moduleStack("scan_mk1")));
            test.assertTrue(Hardware.execute(player, "scan"), "manual scanner unavailable after recovery");
            Hardware.save(controller, List.of(cell));
        });
        test.runAfterDelay(402, () -> {
            test.assertTrue(drone.scanSnapshot == null && !drone.isScanActive(), "removed scanner retained targets");
            test.succeed();
        });
    }
}
