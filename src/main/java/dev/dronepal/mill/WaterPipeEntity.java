package dev.dronepal.mill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * Forwards water through a pipe network: at most 200 mB/t, no decay, split evenly between the
 * downstream pipes and machines. A pipe only forwards while it is within 32 blocks of a pump; beyond
 * that the network simply stops moving water (and the pump fills up and stops).
 */
public final class WaterPipeEntity extends BlockEntity implements IFluidHandler {
    private final FluidTank tank = new FluidTank(400, stack -> stack.getFluid().isSame(Fluids.WATER));
    private Direction entry;
    private int flow;
    private int distance;
    private int downstream;
    private int length;

    public WaterPipeEntity(BlockPos pos, BlockState state) {
        super(OreMillRegistry.WATER_PIPE_TYPE.get(), pos, state);
    }

    public int flow() { return flow; }
    public int distanceToPump() { return distance; }
    public int downstreamMachines() { return downstream; }
    public int networkLength() { return length; }

    public void tick(ServerLevel world) {
        Network network = network(world);
        distance = network.distance();
        downstream = network.machines();
        length = network.length();
        if (flow == 0 && tank.getFluidAmount() == 0) return;
        flow = 0;
        if (distance > 32) return;
        List<Receiver> targets = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if (side == entry) continue;
            BlockPos neighbour = worldPosition.relative(side);
            IFluidHandler handler = world.getCapability(Capabilities.FluidHandler.BLOCK, neighbour, side.getOpposite());
            if (handler == null || handler == this) continue;
            if (handler.fill(new FluidStack(Fluids.WATER, 1), IFluidHandler.FluidAction.SIMULATE) <= 0) continue;
            if (handler instanceof WaterPumpEntity) continue; // never push back into the pump
            int nextDistance = handler instanceof WaterPipeEntity pipe ? pipe.network(world).distance() : Integer.MAX_VALUE;
            if (nextDistance <= distance) continue; // never flow back towards the pump or around a loop
            targets.add(new Receiver(handler, neighbour));
        }
        if (targets.isEmpty()) return;
        targets.sort(Comparator.comparingDouble(receiver -> receiver.pos().distSqr(worldPosition)));
        int budget = Math.min(OreMillRules.PUMP_RATE, tank.getFluidAmount());
        int share = budget / targets.size(), extra = budget % targets.size(), moved = 0;
        for (Receiver receiver : targets) {
            int offer = share + (extra > 0 ? 1 : 0);
            if (extra > 0) extra--;
            if (offer <= 0) continue;
            moved += receiver.handler().fill(new FluidStack(Fluids.WATER, offer), IFluidHandler.FluidAction.EXECUTE);
        }
        if (moved > 0) tank.drain(moved, IFluidHandler.FluidAction.EXECUTE);
        flow = moved;
        setChanged();
    }

    /** BFS over pipes from this pipe; the nearest pump is distance 0, everything else one more. */
    Network network(ServerLevel world) {
        int best = Integer.MAX_VALUE, machines = 0, longest = 0;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        queue.add(worldPosition);
        depth.put(worldPosition, 0);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int here = depth.get(current);
            for (Direction side : Direction.values()) {
                BlockPos neighbour = current.relative(side);
                if (neighbour.distSqr(worldPosition) > 40 * 40) continue;
                if (world.getBlockEntity(neighbour) instanceof WaterPumpEntity) {
                    best = Math.min(best, here + 1);
                } else if (world.getBlockEntity(neighbour) instanceof OreMillEntity) {
                    machines++;
                    longest = Math.max(longest, here + 1);
                } else if (world.getBlockEntity(neighbour) instanceof WaterPipeEntity && !depth.containsKey(neighbour)) {
                    depth.put(neighbour, here + 1);
                    longest = Math.max(longest, here + 1);
                    queue.add(neighbour);
                }
            }
        }
        return new Network(best == Integer.MAX_VALUE ? 99 : best, machines, longest);
    }

    private record Network(int distance, int machines, int length) {}
    private record Receiver(IFluidHandler handler, BlockPos pos) {}

    public Component status() {
        return Component.translatable("message.dronepal.water_pipe.status", flow, downstream, length);
    }

    public void readStatus(ServerLevel world, ServerPlayer player) {
        Network network = network(world);
        downstream = network.machines();
        length = network.length();
        player.displayClientMessage(Component.translatable("message.dronepal.water_pipe.status",
                flow, network.machines(), network.length()), false);
    }

    @Override public int getTanks() { return tank.getTanks(); }
    @Override public FluidStack getFluidInTank(int index) { return tank.getFluidInTank(index); }
    @Override public int getTankCapacity(int index) { return tank.getTankCapacity(index); }
    @Override public boolean isFluidValid(int index, FluidStack stack) { return tank.isFluidValid(index, stack); }
    @Override public int fill(FluidStack resource, FluidAction action) {
        // A pipe holds at most one tick's worth so it cannot become a hidden reservoir.
        int accepted = tank.fill(new FluidStack(resource.getFluid(), Math.min(resource.getAmount(), OreMillRules.PUMP_RATE)), action);
        if (action.execute() && accepted > 0) setChanged();
        return accepted;
    }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tank.writeToNBT(registries, tag);
        tag.putInt("Flow", flow);
        tag.putInt("Distance", distance);
        tag.putInt("Downstream", downstream);
        tag.putInt("Length", length);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tank.readFromNBT(registries, tag);
        flow = tag.getInt("Flow");
        distance = tag.getInt("Distance");
        downstream = tag.getInt("Downstream");
        length = tag.getInt("Length");
    }
}
