package dev.dronepal.mill;

import dev.dronepal.DroneEntity;
import dev.dronepal.Frames;
import dev.dronepal.Hardware;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.minecraft.world.level.material.Fluids;

/**
 * The ore mill's server brain.
 *
 * <p>Three input slots feed one job at a time. A job costs {@link OreMillRules#FE_PER_ITEM} FE and
 * {@link OreMillRules#WATER_PER_ITEM} mB from the built-in tank (300 mB with a drone). Without a drone
 * the mill works by hand (6 s, 20% by-product); with the owner's drone hovering over it the mill switches
 * to the drone rules (1.5 s, doubled common output, 35% by-product, rare shards instead of doubling).
 * The drone's airframe, carry module, the sieve and the cooling tower all fold into those numbers
 * through {@link OreMillRules}.</p>
 *
 * <p>Outputs go into three role-specific wells: the primary product into well 0, the input mineral's own
 * coarse by-product into well 1 and any extra rare roll into well 2. The wells never share a stack, and
 * a job is delivered atomically — if any of the wells a job would touch cannot take its stack, nothing
 * is taken out of the input, no FE/water is spent and the machine reports {@link Link#OUTPUT_FULL}
 * until the well is emptied. There is no hidden overflow buffer, so an item can never be voided.</p>
 *
 * <p>Power comes in through the block capability (max {@value OreMillRules#FE_PER_TICK} FE/t) and is
 * also actively pulled from the six neighbours every tick, so a mill next to a battery bank keeps
 * running without cables. The buffer is {@value OreMillRules#BUFFER} FE.</p>
 */
public final class OreMillEntity extends BlockEntity implements IEnergyStorage, IFluidHandler {
    /** What the status lamp shows and why. {@link #WORKING} is the only green state. */
    public enum Link {
        NONE("none"), OFFLINE("offline"), WORKING("working"), IDLE("idle"),
        NO_POWER("no_power"), NO_MODULE("no_module"), OUTPUT_FULL("output_full"),
        NO_WATER("no_water"), LOW_BATTERY("low_battery");

        private final String key;
        Link(String key) { this.key = key; }
        public String key() { return "gui.dronepal.ore_mill.status." + key; }
        public boolean green() { return this == WORKING; }
        public boolean grey() { return this == NONE || this == OFFLINE; }
    }

    private final SimpleContainer inventory = new SimpleContainer(OreMillMenu.MACHINE_SLOTS);
    private final FluidTank tank = new FluidTank(OreMillRules.WATER_CAPACITY, stack -> stack.getFluid().isSame(Fluids.WATER));
    private int energy;
    private int progress;
    private int duration;
    private int activeSlot = -1;
    private boolean progressDrone;
    /**
     * The exact batch this job will hand out, decided once when the job starts. Holding it (instead of
     * rolling again per attempt) is what makes "a full well stops the mill" honest: a blocked job never
     * re-rolls itself into a different, deliverable batch and its by-product can never evaporate.
     */
    private ItemStack batchPrimary = ItemStack.EMPTY, batchByproduct = ItemStack.EMPTY, batchExtra = ItemStack.EMPTY;
    private boolean batchPlanned;
    private int inputThisTick;
    private long inputTick = Long.MIN_VALUE;
    private UUID droneId;
    private UUID droneOwner;
    private Link link = Link.NONE;
    private boolean droneMode;
    private boolean scanning;
    private boolean carrying;
    private boolean sieve;
    private boolean cooling;
    private String frameId = "peregrine";
    /** Test seam: a seeded source makes the yield rolls reproducible without touching gameplay. */
    private RandomSource random;

    public OreMillEntity(BlockPos pos, BlockState state) {
        super(OreMillRegistry.TYPE.get(), pos, state);
        inventory.addListener(container -> setChanged());
    }

    // ---- inventory ----

    public SimpleContainer inventory() { return inventory; }
    public ItemStack input(int index) { return inventory.getItem(index); }
    public ItemStack output(int index) { return inventory.getItem(OreMillMenu.OUTPUT_FIRST + index); }
    public ItemStack upgrade(int index) { return inventory.getItem(OreMillMenu.UPGRADE_FIRST + index); }

    /** The first readable ore stack, or {@code null}. */
    OreKind inputKind() {
        for (int i = 0; i < OreMillMenu.INPUT_COUNT; i++) {
            OreKind kind = OreKind.of(inventory.getItem(i));
            if (kind != null) return kind;
        }
        return null;
    }

    private int inputSlotFor(OreKind kind) {
        for (int i = 0; i < OreMillMenu.INPUT_COUNT; i++) {
            if (OreKind.of(inventory.getItem(i)) == kind) return i;
        }
        return -1;
    }

    public boolean hasSieve() {
        for (int i = 0; i < OreMillMenu.UPGRADE_COUNT; i++) if (MillItems.isSieve(inventory.getItem(OreMillMenu.UPGRADE_FIRST + i))) return true;
        return false;
    }

    public boolean hasCooling() {
        for (int i = 0; i < OreMillMenu.UPGRADE_COUNT; i++) if (MillItems.isCooling(inventory.getItem(OreMillMenu.UPGRADE_FIRST + i))) return true;
        return false;
    }

    // ---- numbers the GUI and the tests read ----

    public int energy() { return energy; }
    public int water() { return tank.getFluidAmount(); }
    public int waterCapacity() { return tank.getCapacity(); }
    /** Direct water fill used by the pump capability path, the GUI tooltip and GameTests. */
    public int addWater(int amount) { return tank.fill(new FluidStack(Fluids.WATER, Math.max(0, amount)), IFluidHandler.FluidAction.EXECUTE); }
    public void setWater(int amount) {
        tank.setFluid(amount <= 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, Math.min(amount, tank.getCapacity())));
        setChanged();
    }
    public int progress() { return progress; }
    public int duration() { return duration; }
    public Link link() { return link; }
    public UUID assignedDrone() { return droneId; }
    public UUID droneOwner() { return droneOwner; }
    /** Ticks one job takes right now (drone rules when a drone is on station). */
    public int currentDuration() {
        return OreMillRules.ticksPerItem(droneMode, frameId, carrying, hasCooling());
    }
    /** FE one job costs right now. */
    public int currentCost() { return OreMillRules.energyPerItem(hasCooling()); }
    /** mB one job needs right now. */
    public int currentWater() { return OreMillRules.waterPerItem(droneMode); }
    /** Speed multiplier shown in the GUI, in percent (100 = hand speed). */
    public int speedPercent() {
        boolean cool = hasCooling();
        double factor = droneMode ? OreMillRules.droneFactor(frameId, carrying, cool) : (cool ? OreMillRules.COOLING_SPEED : 1.0);
        return (int)Math.round(100 * factor);
    }
    public boolean droneWorking() { return droneMode; }

    /**
     * Centre of the assembled two-block machine, 0.35 above its top face: the seam between the two
     * halves, i.e. the middle of the sorting disc, and the centre the working drone orbits.
     */
    public net.minecraft.world.phys.Vec3 hoverCenter() {
        net.minecraft.world.phys.Vec3 body = worldPosition.getCenter();
        net.minecraft.world.phys.Vec3 side = OreMillBlock.sidePos(getBlockState(), worldPosition).getCenter();
        return new net.minecraft.world.phys.Vec3((body.x + side.x) / 2, worldPosition.getY() + 1.35, (body.z + side.z) / 2);
    }
    public boolean sieveFitted() { return hasSieve(); }
    public boolean coolingFitted() { return hasCooling(); }
    public String frameId() { return frameId; }

    public void setRandom(RandomSource source) { random = source; }
    private RandomSource random() { return random != null ? random : (level != null ? level.random : RandomSource.create()); }

    // ---- drone assignment ----

    /** Called from the GUI button and from the drone's work order; the caller has already checked range. */
    public boolean assignDrone(UUID drone, UUID owner) {
        if (drone == null || owner == null) return false;
        droneId = drone;
        droneOwner = owner;
        setChanged();
        return true;
    }

    public void clearDrone(UUID drone) {
        if (droneId == null || (drone != null && !droneId.equals(drone))) return;
        droneId = null;
        droneOwner = null;
        droneMode = false;
        link = Link.NONE;
        setChanged();
    }

    public boolean isAssigned(UUID drone) { return droneId != null && droneId.equals(drone); }

    /**
     * Re-reads the assigned drone. A drone is "on station" only while it is alive, its work order points
     * at this block and it hovers close enough above it; that keeps a stale UUID from faking a worker.
     *
     * @return true when the drone is physically on station, even if it is currently refused for power or
     *         a missing module. An on-station drone that cannot run stops the mill instead of silently
     *         falling back to hand speed.
     */
    private boolean refreshDrone(ServerLevel world) {
        droneMode = false;
        if (droneId == null) { link = Link.NONE; return false; }
        if (!(world.getEntity(droneId) instanceof DroneEntity drone) || !drone.isAlive()) { link = Link.OFFLINE; return false; }
        if (!worldPosition.equals(drone.workTarget())) { link = Link.OFFLINE; return false; }
        if (drone.blockPosition().distSqr(worldPosition) > 6 * 6) { link = Link.OFFLINE; return false; }
        ItemStack controller = drone.controller();
        if (controller.isEmpty()) { link = Link.OFFLINE; return false; }
        scanning = Hardware.tier(controller, "scan") > 0;
        carrying = Hardware.tier(controller, "carry") > 0;
        frameId = Frames.id(controller);
        if (!scanning) { link = Link.NO_MODULE; return true; }
        if (drone.workStalled()) { link = Link.LOW_BATTERY; return true; }
        droneMode = true;
        return true;
    }

    // ---- tick ----

    public void tick(ServerLevel world) {
        pullEnergy(world);
        boolean onStation = refreshDrone(world);
        sieve = hasSieve();
        cooling = hasCooling();
        if (onStation && !droneMode) { markChanged(); return; }
        if (!onStation) droneMode = false;

        OreKind kind = inputKind();
        if (kind == null) {
            progress = 0;
            duration = 0;
            clearBatch();
            if (onStation) link = Link.IDLE;
            markChanged();
            return;
        }
        if (!hasRoomFor(kind)) {
            // The mill stops here even without a drone: "output full" is the honest reason and the GUI
            // prints it instead of leaving the panel on the water/power fallback text.
            link = Link.OUTPUT_FULL;
            markChanged();
            return;
        }
        int water = currentWater();
        if (tank.getFluidAmount() < water) {
            if (onStation) link = Link.NO_WATER;
            markChanged();
            return;
        }
        int cost = currentCost();
        if (energy < cost) {
            if (onStation) link = Link.NO_POWER;
            markChanged();
            return;
        }
        if (onStation) link = Link.WORKING;
        advance(world, kind, cost);
    }

    private void advance(ServerLevel world, OreKind kind, int cost) {
        int slot = inputSlotFor(kind);
        if (progress > 0 && (slot != activeSlot || droneMode != progressDrone)) {
            progress = 0;
            duration = 0;
            clearBatch();
        }
        activeSlot = slot;
        if (progress == 0) {
            duration = currentDuration();
            progressDrone = droneMode;
            planBatch(kind);
        }
        if (++progress < duration) { markChanged(); return; }
        // One job completes here. Every ingredient was validated above, but re-check before spending.
        ItemStack ore = inventory.getItem(slot);
        if (ore.isEmpty() || OreKind.of(ore) != kind) { progress = 0; duration = 0; clearBatch(); return; }
        int water = currentWater();
        if (tank.getFluidAmount() < water) { progress = 0; duration = 0; clearBatch(); return; }
        // A batch restored from disk carries no rolls yet: decide it now, before judging the wells.
        if (!batchPlanned) planBatch(kind);
        // Deliver the whole batch or nothing: a job is never half-applied and no item is voided.
        if (!fits(OreMillMenu.OUTPUT_PRIMARY, batchPrimary)
                || !fits(OreMillMenu.OUTPUT_BYPRODUCT, batchByproduct)
                || !fits(OreMillMenu.OUTPUT_EXTRA, batchExtra)) {
            // Hold the finished job in place; the input, the FE and the water are untouched.
            progress = duration - 1;
            link = Link.OUTPUT_FULL;
            markChanged();
            return;
        }
        deliver(OreMillMenu.OUTPUT_PRIMARY, batchPrimary);
        deliver(OreMillMenu.OUTPUT_BYPRODUCT, batchByproduct);
        deliver(OreMillMenu.OUTPUT_EXTRA, batchExtra);
        clearBatch();
        ore.shrink(1);
        energy -= cost;
        tank.drain(water, IFluidHandler.FluidAction.EXECUTE);
        if (ore.isEmpty()) activeSlot = -1;
        progress = 0;
        duration = 0;
        markChanged();
    }

    /** Rolls the whole batch for this job once; a blocked delivery keeps exactly this batch. */
    private void planBatch(OreKind kind) {
        batchPrimary = primary(kind);
        batchByproduct = byproduct(kind);
        batchExtra = extra(kind);
        batchPlanned = true;
    }

    private void clearBatch() {
        batchPrimary = ItemStack.EMPTY;
        batchByproduct = ItemStack.EMPTY;
        batchExtra = ItemStack.EMPTY;
        batchPlanned = false;
    }

    /** Rare ores hand out their shard directly; common ores hand out one or two concentrates. */
    private ItemStack primary(OreKind kind) {
        return kind.rare() ? MillItems.shard(kind, 1)
                : MillItems.refined(kind, OreMillRules.concentrateCount(kind, droneMode));
    }

    /**
     * The by-product roll: the same chance for every mineral (20% by hand, 35% under a drone, +15
     * percentage points with the sieve), but the item is the input mineral's own coarse sand. The three
     * rare families have no coarse item — their by-product slot stays empty and their extra roll
     * (well 2) covers them instead.
     */
    private ItemStack byproduct(OreKind kind) {
        if (!kind.hasConcentrate()) return ItemStack.EMPTY;
        if (random().nextDouble() >= OreMillRules.byproductChance(droneMode, sieve)) return ItemStack.EMPTY;
        return MillItems.coarse(kind, 1);
    }

    /** The extra roll only exists for the rare families: one more shard, into its own well. */
    private ItemStack extra(OreKind kind) {
        if (!kind.rare()) return ItemStack.EMPTY;
        return random().nextDouble() < OreMillRules.rareChance(sieve) ? MillItems.shard(kind, 1) : ItemStack.EMPTY;
    }

    /** Room for the primary product in its own well, counting only the stack it would join. */
    public boolean hasRoomFor(OreKind kind) {
        return fits(OreMillMenu.OUTPUT_PRIMARY, primary(kind));
    }

    /** A well only ever holds one class of item: primary / by-product / extra never mix. */
    private static boolean accepts(int well, ItemStack stack) {
        if (stack.isEmpty()) return true;
        return switch (well) {
            case OreMillMenu.OUTPUT_PRIMARY -> MillItems.isPrimary(stack);
            case OreMillMenu.OUTPUT_BYPRODUCT -> MillItems.isCoarse(stack);
            default -> MillItems.isShard(stack);
        };
    }

    /** Whether {@code well} can take {@code result} whole. No merging into a stack of another item. */
    private boolean fits(int well, ItemStack result) {
        if (result.isEmpty()) return true;
        if (!accepts(well, result)) return false;
        ItemStack out = output(well);
        if (out.isEmpty()) return result.getCount() <= result.getMaxStackSize();
        if (!ItemStack.isSameItemSameComponents(out, result)) return false;
        return out.getMaxStackSize() - out.getCount() >= result.getCount();
    }

    private void deliver(int well, ItemStack result) {
        if (result.isEmpty()) return;
        ItemStack out = output(well);
        if (out.isEmpty()) inventory.setItem(OreMillMenu.OUTPUT_FIRST + well, result.copy());
        else out.grow(result.getCount());
    }

    /** Active pull so a mill next to a battery works without cables, capped at 20 FE/t with the cable input. */
    private void pullEnergy(ServerLevel world) {
        resetInputClock(world);
        int budget = Math.min(OreMillRules.FE_PER_TICK - inputThisTick, OreMillRules.BUFFER - energy);
        if (budget <= 0) return;
        for (Direction side : Direction.values()) {
            if (budget <= 0) break;
            IEnergyStorage storage = world.getCapability(Capabilities.EnergyStorage.BLOCK, worldPosition.relative(side), side.getOpposite());
            if (storage == null || storage == this || !storage.canExtract()) continue;
            int got = storage.extractEnergy(budget, false);
            if (got <= 0) continue;
            energy += got;
            inputThisTick += got;
            budget -= got;
            setChanged();
        }
    }

    private void resetInputClock(ServerLevel world) {
        long now = world.getGameTime();
        if (inputTick != now) { inputTick = now; inputThisTick = 0; }
    }

    /** Only touches the save flag when something a viewer can see actually moved. */
    private int lastEnergy = Integer.MIN_VALUE, lastProgress = -1, lastDuration = -1;
    private Link lastLink;
    private void markChanged() {
        if (energy == lastEnergy && progress == lastProgress && duration == lastDuration && link == lastLink) return;
        lastEnergy = energy;
        lastProgress = progress;
        lastDuration = duration;
        lastLink = link;
        setChanged();
    }

    void dropContents() {
        if (level == null || level.isClientSide) return;
        Containers.dropContents(level, worldPosition, inventory);
        inventory.clearContent();
        progress = 0;
        duration = 0;
        setChanged();
    }

    // ---- energy capability ----

    @Override public int receiveEnergy(int amount, boolean simulate) {
        if (level instanceof ServerLevel world) resetInputClock(world);
        int accepted = Math.clamp(amount, 0, Math.min(OreMillRules.FE_PER_TICK - inputThisTick, OreMillRules.BUFFER - energy));
        if (!simulate && accepted > 0) {
            energy += accepted;
            inputThisTick += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }

    // ---- fluid capability: water in only ----

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
    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return OreMillRules.BUFFER; }
    @Override public boolean canReceive() { return true; }
    @Override public boolean canExtract() { return false; }

    /** Direct fill used by the GUI tooltip and by GameTests. */
    public void setEnergy(int value) {
        energy = Mth.clamp(value, 0, OreMillRules.BUFFER);
        setChanged();
    }

    // ---- persistence ----

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (int i = 0; i < OreMillMenu.MACHINE_SLOTS; i++) tag.put("Slot" + i, inventory.getItem(i).saveOptional(registries));
        tag.putInt("Energy", energy);
        tag.putInt("Progress", progress);
        tag.putInt("Duration", duration);
        tag.putInt("ActiveSlot", activeSlot);
        tag.putBoolean("ProgressDrone", progressDrone);
        tank.writeToNBT(registries, tag);
        if (droneId != null) tag.putUUID("Drone", droneId);
        if (droneOwner != null) tag.putUUID("DroneOwner", droneOwner);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        for (int i = 0; i < OreMillMenu.MACHINE_SLOTS; i++) inventory.setItem(i, ItemStack.parseOptional(registries, tag.getCompound("Slot" + i)));
        energy = Mth.clamp(tag.getInt("Energy"), 0, OreMillRules.BUFFER);
        duration = Math.clamp(tag.getInt("Duration"), 0, 12_000);
        progress = Math.clamp(tag.getInt("Progress"), 0, Math.max(0, duration));
        activeSlot = tag.contains("ActiveSlot") ? Math.clamp(tag.getInt("ActiveSlot"), -1, OreMillMenu.INPUT_COUNT - 1) : -1;
        tank.readFromNBT(registries, tag);
        droneId = tag.hasUUID("Drone") ? tag.getUUID("Drone") : null;
        droneOwner = tag.hasUUID("DroneOwner") ? tag.getUUID("DroneOwner") : null;
        sieve = hasSieve();
        cooling = hasCooling();
    }
}
