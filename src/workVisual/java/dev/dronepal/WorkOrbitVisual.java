package dev.dronepal;

import dev.dronepal.mill.MillItems;
import dev.dronepal.mill.OreMillBlock;
import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillMenu;
import dev.dronepal.mill.OreMillRegistry;
import dev.dronepal.mill.OreMillRules;
import dev.dronepal.power.PowerBlocks;
import dev.corvus.core.WorkCycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Screenshot harness for the two things this round changed: the drone really orbits the mill (instead
 * of spinning on the spot over it) and the Blockbench mill art is in the game.
 *
 * <p>One client session, four frames:</p>
 * <ul>
 *   <li>{@code mill-art.png} - the mill from the front, close and a little above: stepped hopper, amber
 *       sight glass and the sorting disc in the top face.</li>
 *   <li>{@code power-occlusion.png} - burner, solar and battery bank standing on stone, camera low:
 *       the stone keeps its top face under and around them.</li>
 *   <li>{@code work-orbit-1.png} / {@code work-orbit-2.png} - same camera, same working drone, two work
 *       cycles apart (6 s), on opposite sides of the machine's hover centre.</li>
 * </ul>
 *
 * <p>Every capture prints the numbers behind the pixels: the mill's hover centre, the work cycle's
 * ideal radius/bearing, the drawn (interpolated) drone offset and the server's authoritative position.
 * Run with {@code gradlew -I gradle/work-visual.init.gradle runWorkVisual}.</p>
 */
@EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class WorkOrbitVisual {
    /**
     * Within the scan the bearing turns 22.5 degrees per tick, so 2 and 10 are 180 degrees apart. The
     * capture ticks are a few later because a tracked entity is drawn a couple of ticks behind the
     * server (vanilla position interpolation): this is the tick whose position the pixels show.
     */
    private static final int SIDE_ONE_TICK = 2;
    private static final int SIDE_TWO_TICK = 10;
    /** Cycles between the two orbit frames: 2 x 60 ticks = 6 s of wall clock. */
    private static final int CYCLES_APART = 2;

    private static int phase;
    private static int ticks;
    private static long preparedAt;
    /** Set at the end of {@link #stage()}, which runs on the server thread. */
    private static volatile boolean staged;
    private static BlockPos millPos;
    private static BlockPos powerPos;
    private static int orbitFrame;
    private static long firstOrbitCycle;

    private static double cameraX, cameraY, cameraZ;
    private static float cameraYaw, cameraPitch;
    private static int cameraTicks;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null || mc.getOverlay() != null) return;
        // A headless window loses focus and the integrated server pauses, which would freeze the cycle.
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        if (phase == 0) {
            if (mc.options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE)
                mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            if (mc.screen != null || ++ticks < 40) return;
            phase = 10;
            server.execute(() -> stage(server));
        } else if (phase == 10 || phase == 11 || phase == 12) {
            // One frame per step: aiming the next camera inside the same step used to re-capture the
            // previous frame from the new camera.
            // The stage runs on the server thread and the client can tick before it lands; and the
            // client level's game time jumps when the save is applied, so never trust it alone.
            if (!staged || millPos == null) return;
            if (preparedAt == 0) preparedAt = mc.level.getGameTime();
            if (mc.level.getGameTime() < preparedAt + 12) return;
            mc.options.hideGui = true;
            if (mc.level.getGameTime() % 40 == 0) settleWeather(server);
            if (phase == 10) {
                // Front, close, a little above: the stepped hopper and the amber window on the front
                // face, the sorting disc on the top face.
                aim(server, millPos.getX() + 1.0, millPos.getY(), millPos.getZ() - 2.5, 0F, 15F);
                pinLook(mc);
                if (cameraTicks < 15) return;
                reportCamera(mc, "mill-art.png");
                reportMill(mc);
                capture(mc, "mill-art.png");
                phase = 11;
            } else if (phase == 11) {
                // Straight down onto the deck: the sorting disc, its pit rim and the glowing ring.
                aim(server, millPos.getX() + 1.0, millPos.getY() + 3.2, millPos.getZ() + 0.5, 0F, 88F);
                pinLook(mc);
                if (cameraTicks < 15) return;
                reportCamera(mc, "mill-art-top.png");
                reportMill(mc);
                capture(mc, "mill-art-top.png");
                phase = 12;
            } else {
                // Low and close for the power row.
                aim(server, powerPos.getX() + 2.0, powerPos.getY(), powerPos.getZ() - 3.1, 0F, 8F);
                pinLook(mc);
                if (cameraTicks < 15) return;
                reportCamera(mc, "power-occlusion.png");
                reportPower(mc);
                capture(mc, "power-occlusion.png");
                server.execute(() -> equipAndOrder(server));
                phase = 2;
            }
        } else if (phase == 2) {
            // One block up, five blocks south of the machine's seam, looking north over it: a 1.2 block
            // sideways offset reads as left/right of the machine instead of depth.
            aim(server, millPos.getX() + 1.0, millPos.getY() + 1.0, millPos.getZ() + 5.0, 180F, 12F);
            pinLook(mc);
            if (mc.level.getGameTime() < preparedAt + 60 + 90) return;
            DroneEntity drone = drone(mc);
            if (drone == null) return;
            double[] orbit = orbitOf(mc, drone);
            if (orbit == null) return;
            double radius = orbit[0];
            double bearing = ((orbit[1] % 360.0) + 360.0) % 360.0;
            // Each frame waits for the drawn airframe to be on a specific side of the machine instead of
            // for an absolute tick: the server cycle and the drawn interpolation are both a couple of
            // ticks away from the client's own tick counter, so tick math would aim at the wrong pose.
            boolean scanning = WorkCycle.phase((int) mc.level.getGameTime()) == WorkCycle.Phase.SCAN;
            if (orbitFrame == 0) {
                if (!scanning || radius < 0.5 || (bearing > 35 && bearing < 355)) return;
                firstOrbitCycle = mc.level.getGameTime();
                reportOrbit(mc, "work-orbit-1.png");
                capture(mc, "work-orbit-1.png");
                orbitFrame = 1;
            } else {
                if (mc.level.getGameTime() < firstOrbitCycle + CYCLES_APART * WorkCycle.PERIOD) return;
                if (!scanning || radius < 0.45 || bearing < 175 || bearing > 215) return;
                reportOrbit(mc, "work-orbit-2.png");
                capture(mc, "work-orbit-2.png");
                phase = 3;
            }
        } else {
            mc.stop();
        }
    }

    /** The first drone the client is drawing; the harness only ever stages one. */
    private static DroneEntity drone(Minecraft mc) {
        if (mc.level == null) return null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone) return drone;
        }
        return null;
    }

    /** {radius, bearing degrees} of the drawn airframe around the mill's hover centre, or null. */
    private static double[] orbitOf(Minecraft mc, DroneEntity drone) {
        if (mc.level == null || millPos == null) return null;
        if (!(mc.level.getBlockEntity(millPos) instanceof OreMillEntity mill)) return null;
        var hover = mill.hoverCenter();
        double dx = drone.getX() - hover.x;
        double dz = drone.getZ() - hover.z;
        return new double[] {Math.hypot(dx, dz), Math.toDegrees(Math.atan2(dz, dx))};
    }

    /**
     * Frames the shot: the camera body is teleported on the server (the authoritative side) when the
     * position changes and re-sent for a few ticks in case the first one raced the client's own
     * position sync; the look direction is pinned every tick.
     */
    private static void aim(MinecraftServer server, double x, double y, double z, float yaw, float pitch) {
        boolean moved = Math.abs(x - cameraX) > 1e-3 || Math.abs(y - cameraY) > 1e-3
                || Math.abs(z - cameraZ) > 1e-3 || yaw != cameraYaw || pitch != cameraPitch;
        cameraX = x; cameraY = y; cameraZ = z; cameraYaw = yaw; cameraPitch = pitch;
        cameraTicks = moved ? 0 : cameraTicks + 1;
        // Both sides every tick: the server owns the position (connection.teleport sends the packet the
        // client must accept), the client copy keeps the camera from drifting between syncs.
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            ServerPlayer player = players.get(0);
            player.teleportTo((net.minecraft.server.level.ServerLevel) player.level(), x, y, z, cameraYaw, cameraPitch);
            player.setDeltaMovement(0.0, 0.0, 0.0);
        });
    }

    private static void pinLook(Minecraft mc) {
        if (mc.player == null) return;
        mc.player.setPos(cameraX, cameraY, cameraZ);
        mc.player.setYRot(cameraYaw);
        mc.player.setXRot(cameraPitch);
        mc.player.setYHeadRot(cameraYaw);
        mc.player.setDeltaMovement(0.0, 0.0, 0.0);
    }

    /** Pins the world to a clear noon through the command system (authoritative and client-synced). */
    private static void settleWeather(MinecraftServer server) {
        var source = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, "time set day");
        server.getCommands().performPrefixedCommand(source, "weather clear 100000");
        server.overworld().setDayTime(1000L);
        server.overworld().setWeatherParameters(100000, 0, false, false);
    }

    private static void stage(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        var level = player.serverLevel();
        // Daylight and clear weather: the dev world has been running long enough to be at night, and
        // the art shots must not be shot through rain. The command path is what the client listens to.
        settleWeather(server);
        // A fixed stage, not "14 above wherever the player was left": every run would climb a platform
        // higher, and the last one went through the build limit (y 320) where blocks are dropped.
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos base = new BlockPos(spawn.getX() + 8, 250, spawn.getZ() + 8);
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                level.setBlockAndUpdate(base.below().offset(dx, 0, dz),
                        net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
                for (int dy = 0; dy <= 16; dy++) {
                    level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // A previous session may have left a controller in the Curios slot and a drone in the world; it
        // would follow the player into every frame, so clear both before framing the art shots.
        for (var entity : level.getEntitiesOfClass(DroneEntity.class,
                new net.minecraft.world.phys.AABB(base).inflate(48.0D))) {
            entity.discard();
        }
        CuriosApi.getCuriosInventory(player).ifPresent(handler ->
                handler.getStacksHandler("drone").ifPresent(stacks ->
                        stacks.getStacks().setStackInSlot(0, ItemStack.EMPTY)));

        millPos = base;
        // The orbit camera stands on this one block, one block above the plaza floor.
        level.setBlockAndUpdate(base.offset(0, 0, 5), Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(0, 2, 5), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(base.offset(0, 3, 5), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(millPos, OreMillRegistry.MILL.get().defaultBlockState()
                .setValue(OreMillBlock.FACING, Direction.NORTH));
        if (level.getBlockEntity(millPos) instanceof OreMillEntity mill) {
            mill.setEnergy(OreMillRules.BUFFER);
            mill.setWater(OreMillRules.WATER_CAPACITY);
            mill.inventory().setItem(0, new ItemStack(Items.RAW_IRON, 32));
            mill.inventory().setItem(1, new ItemStack(Items.DIAMOND_ORE, 16));
            mill.inventory().setItem(OreMillMenu.UPGRADE_FIRST, new ItemStack(MillItems.SIEVE.get()));
            mill.inventory().setItem(OreMillMenu.UPGRADE_FIRST + 1, new ItemStack(MillItems.COOLING.get()));
        }
        // The three machines that exposed the occlusion bug: each stands on its own stone block.
        powerPos = millPos.offset(-5, 0, 1);
        BlockPos burner = powerPos;
        BlockPos solar = powerPos.east(2);
        BlockPos bank = powerPos.east(4);
        for (BlockPos pos : new BlockPos[] {burner, solar, bank}) {
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos.above(2), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(burner, PowerBlocks.BURNER.get().defaultBlockState());
        level.setBlockAndUpdate(solar, PowerBlocks.SOLAR.get().defaultBlockState());
        level.setBlockAndUpdate(bank, PowerBlocks.BANK.get().defaultBlockState());
        staged = true;
        System.out.println("WORK_ORBIT_PREP mill=" + millPos + " side=" + OreMillBlock.sidePos(
                level.getBlockState(millPos), millPos) + " power=" + powerPos
                + " dayTime=" + level.getDayTime());
    }

    /** Spawns the drone through the normal Curios path, then puts it on station. */
    private static void equipAndOrder(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        var level = player.serverLevel();
        ItemStack controller = Frames.create("peregrine");
        ItemStack battery = Hardware.BATTERIES.get(2).toStack();
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                battery, tag -> tag.putInt("energy", Hardware.capacity(battery)));
        var parts = new ArrayList<ItemStack>();
        parts.add(battery);
        parts.add(Hardware.moduleStack("scan_mk1"));
        parts.add(Hardware.moduleStack("carry_mk1"));
        controller.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(parts));
        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("drone").orElseThrow()
                .getStacks().setStackInSlot(0, controller);
        DroneLifecycle.reconcile(player, controller);
        var drone = level.getEntity(DroneItem.serial(controller));
        if (!(drone instanceof DroneEntity entity)) {
            System.out.println("WORK_ORBIT_PREP no drone entity for " + DroneItem.serial(controller));
            return;
        }
        if (!(level.getBlockEntity(millPos) instanceof OreMillEntity mill)) return;
        var hover = mill.hoverCenter();
        entity.setPos(hover.x, hover.y, hover.z);
        entity.assignWork(millPos);
        mill.assignDrone(entity.getUUID(), player.getUUID());
        System.out.println("WORK_ORBIT_PREP drone=" + entity.position() + " hover=" + hover);
    }

    private static void reportCamera(Minecraft mc, String frame) {
        if (mc.player == null) return;
        System.out.printf("WORK_ORBIT_CAMERA %s player=%.2f/%.2f/%.2f yaw=%.1f pitch=%.1f%n",
                frame, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot());
    }

    private static void reportMill(Minecraft mc) {
        if (millPos == null || mc.level == null) return;
        var state = mc.level.getBlockState(millPos);
        var side = OreMillBlock.sidePos(state, millPos);
        System.out.println("WORK_ORBIT_MILL facing=" + state.getValue(OreMillBlock.FACING)
                + " side=" + side + " sideBlock=" + mc.level.getBlockState(side).getBlock()
                + " canOcclude=" + state.canOcclude()
                + " hoverCentre=" + (mc.level.getBlockEntity(millPos) instanceof OreMillEntity mill
                        ? mill.hoverCenter().toString() : "?"));
    }

    private static void reportPower(Minecraft mc) {
        if (powerPos == null || mc.level == null) return;
        for (BlockPos pos : new BlockPos[] {powerPos, powerPos.east(2), powerPos.east(4)}) {
            var state = mc.level.getBlockState(pos);
            var below = mc.level.getBlockState(pos.below());
            System.out.println("WORK_ORBIT_POWER " + state.getBlock() + " at " + pos
                    + " canOcclude=" + state.canOcclude()
                    + " solidRender=" + state.isSolidRender(mc.level, pos)
                    + " shape=" + state.getShape(mc.level, pos).bounds()
                    + " below=" + below.getBlock());
        }
    }

    private static void reportOrbit(Minecraft mc, String name) {
        if (mc.level == null || millPos == null) return;
        int cycle = (int) mc.level.getGameTime();
        int tick = WorkCycle.tickInCycle(cycle);
        double bearing = WorkCycle.orbitAngle(cycle) % 360.0D;
        double radius = WorkCycle.orbitRadius(cycle);
        var hover = mc.level.getBlockEntity(millPos) instanceof OreMillEntity mill ? mill.hoverCenter() : null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone) {
                double dx = hover == null ? Double.NaN : drone.getX() - hover.x;
                double dz = hover == null ? Double.NaN : drone.getZ() - hover.z;
                var server = mc.getSingleplayerServer();
                var authority = server == null ? null : server.overworld().getEntity(drone.getUUID());
                System.out.printf("WORK_ORBIT_CAPTURE %s tick=%d phase=%s idealRadius=%.3f idealBearing=%.1f"
                                + " drawnPos=%.3f/%.3f/%.3f drawnOffset=(%.3f, %.3f) drawnRadius=%.3f drawnBearing=%.1f"
                                + " serverPos=%s yaw=%.1f working=%s%n",
                        name, tick, WorkCycle.phase(cycle), radius, bearing,
                        drone.getX(), drone.getY(), drone.getZ(), dx, dz,
                        Math.hypot(dx, dz), Math.toDegrees(Math.atan2(dz, dx)),
                        authority == null ? "?" : String.format("%.3f/%.3f/%.3f",
                                authority.getX(), authority.getY(), authority.getZ()),
                        drone.getYRot(), drone.isWorking());
            }
        }
    }

    private static void capture(Minecraft mc, String name) {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(System.getProperty("dronepal.work.dir"));
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
            System.out.println("WORK_ORBIT_FILE " + dir.resolve(name));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private WorkOrbitVisual() {}
}
