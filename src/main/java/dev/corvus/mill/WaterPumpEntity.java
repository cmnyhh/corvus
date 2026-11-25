package dev.corvus.mill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraft.world.phys.Vec3;

/**
 * A water pump: it only runs while it touches a water source block (or is itself submerged), draws
 * {@value OreMillRules#PUMP_RATE} mB/t into an {@value OreMillRules#PUMP_BUFFER} mB buffer and pushes
 * water into every neighbouring fluid receiver, i.e. the ore mill next door. A full buffer or full
 * neighbour means "pumping stops", never "water is voided".
 */
public final class WaterPumpEntity extends BlockEntity implements IFluidHandler {
    private final FluidTank tank = new FluidTank(OreMillRules.PUMP_BUFFER,
            stack -> stack.getFluid().isSame(Fluids.WATER));
    private final LazyOptional<IFluidHandler> fluidCapability = LazyOptional.of(() -> this);
    private boolean source;
    /** mB generated since the last finite source block was consumed. */
    private int pumpedSinceConsume;
    /** mB actually pushed last tick, for the pipe readout. */
    private int lastPushed;
    private boolean overlong;

    public WaterPumpEntity(BlockPos pos, BlockState state) {
        super(OreMillRegistry.PUMP_TYPE.get(), pos, state);
    }

    public int water() { return tank.getFluidAmount(); }

    public int lastPushed() { return lastPushed; }

    public boolean overlong() { return overlong; }

    public int capacity() { return tank.getCapacity(); }

    /** Whether the pump can currently draw: an adjacent source block, or the pump itself is submerged. */
    public boolean source() { return level instanceof ServerLevel world ? hasSource(world) : source; }

    public void setWater(int amount) {
        tank.setFluid(amount <= 0 ? FluidStack.EMPTY
                : new FluidStack(Fluids.WATER, Math.min(amount, tank.getCapacity())));
        setChanged();
    }

    public boolean hasSource(ServerLevel world) {
        FluidState own = world.getFluidState(worldPosition);
        if (own.isSource() && own.is(Fluids.WATER)) return true;
        for (Direction side : Direction.values()) {
            FluidState fluid = world.getFluidState(worldPosition.relative(side));
            if (fluid.isSource() && fluid.is(Fluids.WATER)) return true;
        }
        return false;
    }

    /**
     * Vanilla water rules: two or more source blocks on the four horizontal faces, or a source directly
     * above, makes the supply infinite (a pond, a lake, an ocean). A lone source is finite: every
     * 1,000 mB pumped removes one block, exactly like a bucket.
     */
    public boolean infiniteSource(ServerLevel world) {
        if (isSource(world, worldPosition.above())) return true;
        int horizontal = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (isSource(world, worldPosition.relative(side))) horizontal++;
        }
        return horizontal >= 2;
    }

    private static boolean isSource(ServerLevel world, BlockPos pos) {
        FluidState fluid = world.getFluidState(pos);
        return fluid.isSource() && fluid.is(Fluids.WATER);
    }

    /** Source blocks still touching the pump; the finite-supply readout. */
    public int remainingSources(ServerLevel world) {
        int count = 0;
        for (Direction side : Direction.values()) if (isSource(world, worldPosition.relative(side))) count++;
        return count;
    }

    /** Finite supply: each 1,000 mB pumped turns one adjacent source into air. */
    private void consumeFiniteSource(ServerLevel world) {
        for (Direction side : Direction.values()) {
            BlockPos neighbour = worldPosition.relative(side);
            if (isSource(world, neighbour)) {
                world.setBlock(neighbour, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                return;
            }
        }
    }

    public void tick(ServerLevel world) {
        source = hasSource(world);
        boolean infinite = infiniteSource(world);
        if (source && tank.getFluidAmount() < tank.getCapacity()) {
            int added = tank.fill(new FluidStack(Fluids.WATER, OreMillRules.PUMP_RATE),
                    IFluidHandler.FluidAction.EXECUTE);
            if (added > 0) {
                setChanged();
                if (!infinite) {
                    pumpedSinceConsume += added;
                    while (pumpedSinceConsume >= 1_000 && hasSource(world)) {
                        pumpedSinceConsume -= 1_000;
                        consumeFiniteSource(world);
                    }
                    source = hasSource(world);
                }
            }
        }
        int pushed = push(world);
        if (pushed > 0) setChanged();
    }

    /**
     * Even split of up to 200 mB/t across every machine reachable directly or through a pipe segment.
     * The remainder goes to the nearest endpoints, so a 200 mB/t pump feeding three mills gives
     * 67/67/66. A pipe run longer than 32 blocks is not used at all and flags {@link #overlong()}.
     */
    private int push(ServerLevel world) {
        lastPushed = 0;
        overlong = false;
        if (tank.getFluidAmount() <= 0) return 0;
        List<WaterPipeNetwork.Endpoint> endpoints = new ArrayList<>(6);
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (Direction side : Direction.values()) {
            BlockPos neighbour = worldPosition.relative(side);
            if (world.getBlockState(neighbour).is(OreMillRegistry.PIPE.get())) {
                WaterPipeNetwork.Segment segment = WaterPipeNetwork.scan(world, neighbour);
                if (segment.overlong()) {
                    overlong = true;
                    continue;
                }
                for (WaterPipeNetwork.Endpoint endpoint : segment.endpoints()) {
                    if (seen.add(endpoint.pos())) endpoints.add(endpoint);
                }
                continue;
            }
            if (world.getBlockState(neighbour).is(OreMillRegistry.PUMP.get())) continue;
            BlockEntity entity = world.getBlockEntity(neighbour);
            if (world.getBlockState(neighbour).is(OreMillRegistry.SIDE.get())) {
                entity = world.getBlockEntity(OreMillSideBlock.mainPos(world.getBlockState(neighbour), neighbour));
            }
            if (entity == null) continue;
            IFluidHandler handler = entity.getCapability(ForgeCapabilities.FLUID_HANDLER,
                    side.getOpposite()).resolve().orElse(null);
            if (handler != null && seen.add(neighbour)) {
                endpoints.add(new WaterPipeNetwork.Endpoint(neighbour.immutable(), handler));
            }
        }
        if (endpoints.isEmpty()) return 0;
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        endpoints.sort(java.util.Comparator.comparingDouble(endpoint ->
                Vec3.atCenterOf(endpoint.pos()).distanceToSqr(centre)));
        int budget = Math.min(OreMillRules.PUMP_RATE, tank.getFluidAmount());
        int share = budget / endpoints.size(), extra = budget % endpoints.size(), moved = 0;
        for (int i = 0; i < endpoints.size(); i++) {
            int offer = share + (i < extra ? 1 : 0);
            if (offer <= 0) continue;
            moved += endpoints.get(i).handler().fill(new FluidStack(Fluids.WATER, offer),
                    IFluidHandler.FluidAction.EXECUTE);
        }
        if (moved > 0) {
            tank.drain(moved, IFluidHandler.FluidAction.EXECUTE);
            lastPushed = moved;
        }
        return moved;
    }

    public Component status() {
        boolean infinite = level instanceof ServerLevel world && infiniteSource(world);
        int remaining = level instanceof ServerLevel world ? remainingSources(world) : 0;
        Component status = Component.translatable("message.dronepal.water_pump.status",
                String.format(Locale.ROOT, "%,d", tank.getFluidAmount()),
                String.format(Locale.ROOT, "%,d", tank.getCapacity()),
                infinite ? Component.translatable("message.dronepal.water_pump.infinite")
                        : source ? Component.translatable("message.dronepal.water_pump.remaining",
                                String.format(Locale.ROOT, "%.1f", (double) remaining))
                        : Component.translatable("message.dronepal.water_pump.dry"));
        return overlong ? status.copy().append(" ").append(Component.translatable(
                "message.dronepal.water_pipe.overlong", WaterPipeNetwork.MAX_LENGTH)) : status;
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tank.writeToNBT(tag);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        tank.readFromNBT(tag);
    }

    // ---- fluid capability ----

    @Override public int getTanks() { return tank.getTanks(); }

    @Override public FluidStack getFluidInTank(int index) { return tank.getFluidInTank(index); }

    @Override public int getTankCapacity(int index) { return tank.getTankCapacity(index); }

    @Override public boolean isFluidValid(int index, FluidStack stack) { return tank.isFluidValid(index, stack); }

    @Override public int fill(FluidStack resource, FluidAction action) {
        int filled = tank.fill(resource, action);
        if (action.execute() && filled > 0) setChanged();
        return filled;
    }

    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }

    @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidCapability.cast();
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        fluidCapability.invalidate();
    }

    /** Shared tooltip text, so the item and the block stay in sync with the live constants. */
    public static List<Component> tooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.water_pump.tip.rate",
                        OreMillRules.PUMP_RATE, String.format(Locale.ROOT, "%,d", OreMillRules.PUMP_BUFFER))
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pump.tip.source").withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pump.tip.push").withStyle(ChatFormatting.GRAY));
    }
}
