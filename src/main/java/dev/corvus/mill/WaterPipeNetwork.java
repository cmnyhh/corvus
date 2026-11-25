package dev.corvus.mill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * Pipe topology for the pump: a breadth-first walk over connected pipes, with the 32-block segment cap
 * and the list of machines at the far end. Nothing is cached; a 32-block walk once per tick is cheap and
 * survives every placement/removal without an invalidation bug.
 */
public final class WaterPipeNetwork {
    public static final int MAX_LENGTH = 32;

    public record Endpoint(BlockPos pos, IFluidHandler handler) {}

    public record Segment(Set<BlockPos> pipes, List<Endpoint> endpoints, WaterPumpEntity pump, boolean overlong) {}

    private WaterPipeNetwork() {}

    /** Walks the pipe network touching {@code start}; {@code start} itself must be a pipe. */
    public static Segment scan(Level level, BlockPos start) {
        Set<BlockPos> pipes = new LinkedHashSet<>();
        Set<BlockPos> endpoints = new LinkedHashSet<>();
        List<Endpoint> handlers = new ArrayList<>();
        WaterPumpEntity pump = null;
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        pipes.add(start);
        boolean overlong = false;
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction side : Direction.values()) {
                BlockPos neighbour = current.relative(side);
                var state = level.getBlockState(neighbour);
                if (state.is(OreMillRegistry.PIPE.get())) {
                    if (pipes.add(neighbour)) {
                        if (pipes.size() > MAX_LENGTH) {
                            overlong = true;
                        } else {
                            queue.add(neighbour);
                        }
                    }
                    continue;
                }
                if (state.is(OreMillRegistry.PUMP.get())) {
                    if (level.getBlockEntity(neighbour) instanceof WaterPumpEntity found && pump == null) pump = found;
                    continue;
                }
                if (state.is(OreMillRegistry.SIDE.get())) {
                    BlockEntity main = level.getBlockEntity(OreMillSideBlock.mainPos(state, neighbour));
                    if (main == null) continue;
                    IFluidHandler handler = main.getCapability(ForgeCapabilities.FLUID_HANDLER,
                            side.getOpposite()).resolve().orElse(null);
                    if (handler != null && endpoints.add(neighbour)) {
                        handlers.add(new Endpoint(neighbour.immutable(), handler));
                    }
                    continue;
                }
                BlockEntity entity = level.getBlockEntity(neighbour);
                if (entity == null) continue;
                IFluidHandler handler = entity.getCapability(ForgeCapabilities.FLUID_HANDLER,
                        side.getOpposite()).resolve().orElse(null);
                if (handler != null && endpoints.add(neighbour)) handlers.add(new Endpoint(neighbour.immutable(), handler));
            }
        }
        handlers.sort(Comparator.comparingDouble(endpoint ->
                Vec3.atCenterOf(endpoint.pos()).distanceToSqr(Vec3.atCenterOf(start))));
        return new Segment(pipes, List.copyOf(handlers), pump, overlong);
    }

    /** Text the terminal-holding player sees when right-clicking a pipe. */
    public static Component describe(Level level, BlockPos pos) {
        Segment segment = scan(level, pos);
        int flow = segment.pump() == null ? 0 : segment.pump().lastPushed();
        Component body = Component.translatable("message.dronepal.water_pipe.info", flow,
                segment.endpoints().size(), segment.pipes().size());
        if (segment.overlong()) {
            return body.copy().append(" ").append(Component.translatable(
                    "message.dronepal.water_pipe.overlong", MAX_LENGTH));
        }
        if (segment.pump() == null) {
            return body.copy().append(" ").append(Component.translatable("message.dronepal.water_pipe.no_pump"));
        }
        return body;
    }

    /** Connected pipe positions, for tests and the pump. */
    public static Set<BlockPos> pipes(Level level, BlockPos start) {
        return new HashSet<>(scan(level, start).pipes());
    }

    /** Machines at the end of the pipe run, nearest first. */
    public static List<Endpoint> endpoints(Level level, BlockPos start) {
        return scan(level, start).endpoints();
    }

    static boolean isPipe(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(OreMillRegistry.PIPE.get());
    }
}
