package dev.dronepal.mill;

import java.util.Locale;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * A water pump: it only runs while it touches a water source block (or is itself submerged), draws
 * {@value OreMillRules#PUMP_RATE} mB/t into an {@value OreMillRules#PUMP_BUFFER} mB buffer and pushes
 * water into every neighbouring fluid receiver, i.e. the ore mill next door. A full buffer or full
 * neighbour means "pumping stops", never "water is voided".
 */
public final class WaterPumpEntity extends BlockEntity implements IFluidHandler {
    private final FluidTank tank = new FluidTank(OreMillRules.PUMP_BUFFER, stack -> stack.getFluid().isSame(Fluids.WATER));
    private boolean source;
    private boolean infinite = true;
    private int sourceRemaining = 1_000;
    private BlockPos sourcePos;

    public WaterPumpEntity(BlockPos pos, BlockState state) {
        super(OreMillRegistry.WATER_PUMP_TYPE.get(), pos, state);
    }

    public int water() { return tank.getFluidAmount(); }
    public int capacity() { return tank.getCapacity(); }
    /** Whether the pump can currently draw: an adjacent source block, or the pump itself is submerged. */
    public boolean source() { return level instanceof ServerLevel world ? findSource(world) != null : source; }
    public boolean infiniteSource() { return infinite; }
    /** Remaining mB in a finite source block (0..1000); 1000 while the source qualifies as infinite. */
    public int sourceRemaining() { return sourcePos == null ? 0 : sourceRemaining; }

    public void setWater(int amount) {
        tank.setFluid(amount <= 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, Math.min(amount, tank.getCapacity())));
        setChanged();
    }

    private record Source(BlockPos pos, boolean infinite) {}

    private static boolean sourceAt(ServerLevel world, BlockPos pos) {
        FluidState fluid = world.getFluidState(pos);
        return fluid.isSource() && fluid.is(Fluids.WATER);
    }

    /** Vanilla infinite-water test: two horizontal sources, or a source directly above. */
    private static boolean unlimited(ServerLevel world, BlockPos pos) {
        int horizontal = 0;
        for (Direction side : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            if (sourceAt(world, pos.relative(side))) horizontal++;
        }
        return horizontal >= 2 || sourceAt(world, pos.above());
    }

    boolean hasSource(ServerLevel world) { return findSource(world) != null; }

    /** The adjacent source block to drain, if any. The pump's own water block is never removed. */
    private Source findSource(ServerLevel world) {
        FluidState own = world.getFluidState(worldPosition);
        if (!own.isEmpty() && own.is(Fluids.WATER)) return new Source(worldPosition, true);
        for (Direction side : Direction.values()) {
            BlockPos neighbour = worldPosition.relative(side);
            if (sourceAt(world, neighbour)) return new Source(neighbour, unlimited(world, neighbour));
        }
        return null;
    }

    public void tick(ServerLevel world) {
        Source found = findSource(world);
        source = found != null;
        if (found == null) {
            sourcePos = null;
            sourceRemaining = 0;
            return;
        }
        if (!found.pos().equals(sourcePos)) {
            sourcePos = found.pos();
            infinite = found.infinite();
            sourceRemaining = 1_000;
        }
        int pumped = 0;
        if (tank.getFluidAmount() < tank.getCapacity()) {
            pumped = tank.fill(new FluidStack(Fluids.WATER, OreMillRules.PUMP_RATE), IFluidHandler.FluidAction.EXECUTE);
            if (pumped > 0) setChanged();
        }
        if (pumped > 0 && !infinite) {
            sourceRemaining -= pumped;
            if (sourceRemaining <= 0) {
                // A finite source yields exactly 1,000 mB, then the block is gone.
                world.setBlockAndUpdate(sourcePos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                for (ServerPlayer nearby : world.players()) {
                    if (nearby.distanceToSqr(worldPosition.getCenter()) < 24 * 24) {
                        nearby.displayClientMessage(Component.translatable("message.dronepal.water_pump.exhausted"), false);
                    }
                }
                sourceRemaining = 0;
                sourcePos = null;
                source = false;
            }
        }
        int pushed = push(world);
        if (pushed > 0) setChanged();
    }

    /** Even split of up to 200 mB/t into the neighbours that still have room. */
    private int push(ServerLevel world) {
        if (tank.getFluidAmount() <= 0) return 0;
        List<IFluidHandler> targets = new java.util.ArrayList<>(6);
        for (Direction side : Direction.values()) {
            IFluidHandler handler = world.getCapability(Capabilities.FluidHandler.BLOCK, worldPosition.relative(side), side.getOpposite());
            if (handler == null || handler == this) continue;
            if (handler.fill(new FluidStack(Fluids.WATER, 1), IFluidHandler.FluidAction.SIMULATE) <= 0) continue;
            targets.add(handler);
        }
        if (targets.isEmpty()) return 0;
        int budget = Math.min(OreMillRules.PUMP_RATE, tank.getFluidAmount());
        int share = budget / targets.size(), extra = budget % targets.size(), moved = 0;
        for (IFluidHandler handler : targets) {
            int offer = share + (extra > 0 ? 1 : 0);
            if (extra > 0) extra--;
            if (offer <= 0) continue;
            moved += handler.fill(new FluidStack(Fluids.WATER, offer), IFluidHandler.FluidAction.EXECUTE);
        }
        if (moved > 0) tank.drain(moved, IFluidHandler.FluidAction.EXECUTE);
        return moved;
    }

    Component status() {
        return Component.translatable("message.dronepal.water_pump.status",
                String.format(Locale.ROOT, "%,d", tank.getFluidAmount()),
                String.format(Locale.ROOT, "%,d", tank.getCapacity()),
                source() ? Component.translatable(infinite ? "message.dronepal.water_pump.infinite"
                        : "message.dronepal.water_pump.remaining", String.format(Locale.ROOT, "%.1f", sourceRemaining / 1000.0))
                        : Component.translatable("message.dronepal.water_pump.dry"));
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tank.writeToNBT(registries, tag);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tank.readFromNBT(registries, tag);
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

    /** Shared tooltip text, so the item and the block stay in sync with the live constants. */
    public static List<Component> tooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.water_pump.tip.rate",
                        OreMillRules.PUMP_RATE, String.format(Locale.ROOT, "%,d", OreMillRules.PUMP_BUFFER)).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pump.tip.source").withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pump.tip.push").withStyle(ChatFormatting.GRAY));
    }
}
