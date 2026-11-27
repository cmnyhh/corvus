package dev.dronepal;

import dev.dronepal.power.io.PowerCableBlock;
import dev.dronepal.power.io.PowerIoRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Photographs a power-cable test yard in the real client: one frame per connection shape.
 *
 * <p>Same harness as {@code PipeVisual}, one block over. The yard is built by
 * <em>placing cables next to each other</em>, and every placed cable is then re-derived with
 * {@link PowerCableBlock#connectionState} — the one call the game itself uses on placement and in
 * {@code updateShape} — so no cell is left at its all-false default (a {@code setBlock} placed cell is
 * only re-derived when a <em>later</em> neighbour is placed, which would leave the last block of every
 * station rendering as a loose stub). Nothing is hand-written, so each frame shows the model the shipped
 * blockstate actually picks for that mask.</p>
 *
 * <p>Run with {@code ./gradlew --offline -I gradle/cable-visual.init.gradle runCableVisual}.</p>
 */
@EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class CableVisual {
    /** One station: which yard cell to look at, and where the camera stands relative to its centre. */
    private record Frame(String file, int station, double dx, double dy, double dz) {}

    private static final Frame[] FRAMES = {
            // horizontal straight: E-W run
            new Frame("cable-horizontal.png", 0, 1.1, 0.8, -1.2),
            // vertical straight: U-D run (no horizontal body at all)
            new Frame("cable-vertical.png", 1, 1.5, 0.6, -1.6),
            // T with an upward branch: straight E-W run + riser on top
            new Frame("cable-tee-up.png", 2, 1.6, 1.4, -1.8),
            // cross + up + down: both vertical stubs must be drawn
            new Frame("cable-cross-up-down.png", 3, 2.2, 1.2, -2.4),
            // one connection, upward: the blind end must face down
            new Frame("cable-cap-up.png", 4, 1.2, -0.7, -1.3),
            // one connection, downward: the blind end must face up
            new Frame("cable-cap-down.png", 5, 1.2, 1.2, -1.3),
            // vertical elbow: east + up
            new Frame("cable-elbow-vertical.png", 6, 1.3, 0.7, -1.4),
            // close-ups from the south side, where the horizontal arm runs left-right
            new Frame("closeup-elbow.png", 6, 1.2, 0.6, 2.2),
            new Frame("closeup-tee-up.png", 2, 1.4, 0.9, 2.4),
            new Frame("closeup-cap-up.png", 4, 1.6, -1.6, -1.8),
            // one wide frame with the T, the cross and the caps in the same picture
            new Frame("yard-overview.png", 3, -5.0, 3.4, 6.0),
    };

    /** Stations in the yard (several frames can look at the same one). */
    private static final int STATIONS = 7;
    /** Probe block of each station, filled in by {@link #stage}. */
    private static final BlockPos[] probe = new BlockPos[STATIONS];
    private static final BlockPos[] base = new BlockPos[1];

    private static int phase;
    private static int ticks;
    private static long preparedAt;
    private static volatile boolean staged;
    private static int frame;

    private static double cameraX, cameraY, cameraZ;
    private static float cameraYaw, cameraPitch;
    private static int cameraTicks;

    private static int probes;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (++probes % 100 == 0)
            System.out.println("CABLE_PROBE n=" + probes + " screen="
                    + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName())
                    + " level=" + (mc.level != null) + " server=" + (server != null)
                    + " player=" + (mc.player != null) + " tick=" + ticks
                    + " overlay=" + mc.getOverlay());
        if (server == null || mc.player == null || mc.level == null || mc.getOverlay() != null) return;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        if (phase == 0) {
            if (mc.options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE)
                mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            if (mc.screen != null || ++ticks < 40) return;
            phase = 1;
            server.execute(() -> stage(server));
        } else if (phase == 1) {
            if (!staged) return;
            if (preparedAt == 0) preparedAt = mc.level.getGameTime();
            if (mc.level.getGameTime() < preparedAt + 12) return;
            mc.options.hideGui = true;
            // the dev save is parked at night; the yard must not be shot through it
            mc.level.setDayTime(1000L);
            if (frame >= FRAMES.length) {
                phase = 2;
                return;
            }
            Frame shot = FRAMES[frame];
            BlockPos target = probe[shot.station()];
            if (target == null) return;
            double[] aim = camera(target, shot.dx(), shot.dy(), shot.dz());
            aim(server, aim[0], aim[1], aim[2], (float) aim[3], (float) aim[4]);
            pinLook(mc);
            if (cameraTicks < 16) return;
            report(mc, shot.file(), shot.station(), target);
            capture(mc, shot.file());
            frame++;
        } else {
            mc.stop();
        }
    }

    /**
     * Feet position and look angles that put the *eye* at the wanted spot.
     * The offsets are eye-relative, and the render camera sits {@link #EYE_HEIGHT} above the
     * player's feet, so aim() is given feet coordinates with that height already removed.
     */
    private static double[] camera(BlockPos target, double dx, double dy, double dz) {
        double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
        // forward = target - eye = -(dx,dy,dz); MC yaw 0 looks south (+Z), +pitch looks down
        double flat = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, flat));
        return new double[] {tx + dx, ty + dy - EYE_HEIGHT, tz + dz, yaw, pitch};
    }

    private static final double EYE_HEIGHT = 1.62;

    /**
     * Frames the shot: the camera body is teleported on the server (the authoritative side) and
     * re-sent for a few ticks, the look direction is pinned on the client every tick.
     */
    private static void aim(MinecraftServer server, double x, double y, double z, float yaw, float pitch) {
        boolean moved = Math.abs(x - cameraX) > 1e-3 || Math.abs(y - cameraY) > 1e-3
                || Math.abs(z - cameraZ) > 1e-3 || yaw != cameraYaw || pitch != cameraPitch;
        cameraX = x; cameraY = y; cameraZ = z; cameraYaw = yaw; cameraPitch = pitch;
        cameraTicks = moved ? 0 : cameraTicks + 1;
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            ServerPlayer player = players.get(0);
            player.teleportTo((net.minecraft.server.level.ServerLevel) player.level(), x, y, z, yaw, pitch);
            player.setDeltaMovement(0.0, 0.0, 0.0);
            player.setNoGravity(true);
            player.getAbilities().flying = true;
        });
    }

    private static void pinLook(Minecraft mc) {
        if (mc.player == null) return;
        mc.player.setPos(cameraX, cameraY, cameraZ);
        mc.player.setYRot(cameraYaw);
        mc.player.setXRot(cameraPitch);
        mc.player.setYHeadRot(cameraYaw);
        mc.player.setDeltaMovement(0.0, 0.0, 0.0);
        mc.player.setNoGravity(true);
    }

    private static void settleWeather(MinecraftServer server) {
        var source = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, "time set day");
        server.getCommands().performPrefixedCommand(source, "weather clear 100000");
        server.overworld().setDayTime(1000L);
        server.overworld().setWeatherParameters(100000, 0, false, false);
    }

    /**
     * Builds seven stations. Each is a run of cables placed next to each other, so the connection flags
     * come from the block's own rule instead of a hand-written state.
     */
    private static void stage(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        var level = player.serverLevel();
        settleWeather(server);
        BlockPos spawn = level.getSharedSpawnPos();
        // 24 blocks south of the water-pipe yard (PipeVisual uses z+8), so the two never touch
        BlockPos origin = new BlockPos(spawn.getX() + 8, 252, spawn.getZ() + 32);
        base[0] = origin;
        for (int station = 0; station < STATIONS; station++) {
            BlockPos c = origin.east(station * 6).above(3);
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    boolean lamp = Math.floorMod(dx, 3) == 0 && Math.floorMod(dz, 3) == 0;
                    level.setBlockAndUpdate(c.offset(dx, -4, dz),
                            lamp ? Blocks.SEA_LANTERN.defaultBlockState()
                                 : Blocks.SMOOTH_STONE.defaultBlockState());
                    for (int dy = -3; dy <= 3; dy++) {
                        level.setBlockAndUpdate(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        List<BlockPos> placed = new ArrayList<>();
        for (int station = 0; station < STATIONS; station++) {
            BlockPos c = origin.east(station * 6).above(3);
            switch (station) {
                case 0 -> {           // horizontal straight: three cables in a line
                    cable(level, placed, c.west());
                    cable(level, placed, c);
                    cable(level, placed, c.east());
                    probe[station] = c;
                }
                case 1 -> {           // vertical straight: three cables stacked
                    cable(level, placed, c.below());
                    cable(level, placed, c);
                    cable(level, placed, c.above());
                    probe[station] = c;
                }
                case 2 -> {           // T with an upward branch
                    cable(level, placed, c.west());
                    cable(level, placed, c);
                    cable(level, placed, c.east());
                    cable(level, placed, c.above());
                    probe[station] = c;
                }
                case 3 -> {           // cross + up + down
                    cable(level, placed, c);
                    cable(level, placed, c.north());
                    cable(level, placed, c.south());
                    cable(level, placed, c.west());
                    cable(level, placed, c.east());
                    cable(level, placed, c.above());
                    cable(level, placed, c.below());
                    probe[station] = c;
                }
                case 4 -> {           // single connection upward: the blind end belongs underneath
                    cable(level, placed, c);
                    cable(level, placed, c.above());
                    probe[station] = c;
                }
                case 5 -> {           // single connection downward: the blind end belongs on top
                    cable(level, placed, c);
                    cable(level, placed, c.below());
                    probe[station] = c;
                }
                case 6 -> {           // vertical elbow: east + up
                    cable(level, placed, c);
                    cable(level, placed, c.above());
                    cable(level, placed, c.east());
                    probe[station] = c;
                }
                default -> throw new IllegalStateException("station " + station);
            }
        }
        // re-derive every mask once the whole yard stands (see the class comment)
        PowerCableBlock block = PowerIoRegistry.CABLE.get();
        for (BlockPos pos : placed) {
            level.setBlockAndUpdate(pos, block.connectionState(level, pos));
        }
        staged = true;
        reportStates(level);
    }

    private static void cable(Level level, List<BlockPos> placed, BlockPos pos) {
        level.setBlockAndUpdate(pos, PowerIoRegistry.CABLE.get().defaultBlockState());
        placed.add(pos);
    }

    private static String mask(BlockState state) {
        return "n=" + flag(state, "north") + " e=" + flag(state, "east") + " s=" + flag(state, "south")
                + " w=" + flag(state, "west") + " u=" + flag(state, "up") + " d=" + flag(state, "down");
    }

    private static char flag(BlockState state, String name) {
        // The cable declares its own north/east/... properties; look them up by name.
        for (var p : state.getProperties()) {
            if (p.getName().equals(name) && p instanceof net.minecraft.world.level.block.state.properties.BooleanProperty b)
                return state.getValue(b) ? '1' : '0';
        }
        return '?';
    }

    private static void reportStates(Level level) {
        System.out.println("CABLE_WORLD dimension=" + level.dimension()
                + " dayTime=" + level.getDayTime()
                + " origin=" + base[0]);
        for (int station = 0; station < probe.length; station++) {
            BlockPos pos = probe[station];
            if (pos == null) continue;
            System.out.println("CABLE_STATE station=" + station + " pos=" + pos
                    + " " + mask(level.getBlockState(pos)));
        }
    }

    private static void report(Minecraft mc, String file, int station, BlockPos target) {
        if (mc.level == null) return;
        BlockState state = mc.level.getBlockState(target);
        System.out.println("CABLE_CAPTURE " + file + " station=" + station + " pos=" + target
                + " " + mask(state)
                + String.format(" cam=%.2f/%.2f/%.2f yaw=%.1f pitch=%.1f",
                        cameraX, cameraY, cameraZ, cameraYaw, cameraPitch));
    }

    private static void capture(Minecraft mc, String name) {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(System.getProperty("dronepal.cable.dir"));
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
            System.out.println("CABLE_FILE " + dir.resolve(name));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private CableVisual() {}
}
