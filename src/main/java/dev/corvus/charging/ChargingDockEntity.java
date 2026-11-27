package dev.corvus.charging;

import dev.corvus.DroneEntity;
import dev.corvus.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * A charging pad. Furnace fuel or cable FE fills a 32,000 FE buffer, which tops up the single cell (or
 * the battery inside a docked controller) sitting in the pad.
 *
 * <p>Any vanilla fuel is accepted, not just coal: the pad asks the furnace rules
 * ({@code AbstractFurnaceBlockEntity.isFuel} / {@code ForgeHooks.getBurnTime}) and turns one item into
 * {@code burnTicks x 2.5} FE ({@link dev.corvus.power.FuelPower}) released at 200 FE/t. Coal 4,000,
 * coal block 40,000, blaze rod 6,000, lava bucket 50,000.</p>
 *
 * <p>1.20.1 exposes energy through Forge's {@link IEnergyStorage} capability: the pad itself is the
 * storage and {@code ChargingCapabilities} publishes it through {@code AttachCapabilitiesEvent}.</p>
 *
 * <p>Everything the client draws travels in {@link #getUpdateTag}: the buffer, the docked stack and the
 * stats of its cell. Tags are pushed at most once every {@value #SYNC_INTERVAL} ticks while a value is
 * moving, and immediately on insert/take/fuel so the hologram never shows stale numbers.</p>
 */
public final class ChargingDockEntity extends BlockEntity implements IEnergyStorage {
    public static final int BUFFER = 32_000;
    static final int BASE_CABLE_RATE = 2_000;
    /**
     * FE released per tick while fuel is burning; the fuel's own value sets how long that lasts.
     *
     * <p>Was 200, which capped every recharged cell at a fraction of its receive rate: a 700,000 FE
     * pack needed 29 minutes on a pad it could have swallowed at 800 FE/t. This is the pad's output;
     * the cell's own receive ceiling still applies on top.</p>
     */
    static final int FUEL_RATE = 800;
    /** How many items the fuel slot holds. */
    public static final int FUEL_LIMIT = 64;
    /** Guard against a forged NBT value; real fuels are far below this. */
    private static final int MAX_BURN = 10_000_000;
    /** Ticks between two throttled update packets (10 ticks = twice a second). */
    public static final int SYNC_INTERVAL = 10;

    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> this);
    private ItemStack target = ItemStack.EMPTY;
    private ItemStack fuel = ItemStack.EMPTY;
    private int burnRemaining;
    /** FE the item now burning was worth when it started: the fuel's yield, for status and checks. */
    private int burnTotal;
    private int energy;
    private int targetEnergy;
    private int targetCapacity;
    /** FE/t this pad handed to a projected drone's controller on the previous tick, 0 when none. */
    private int droneRate;
    private long cableTick = Long.MIN_VALUE;
    private int cableReceived;
    /** Snapshot of what the last update tag told the clients, used to throttle packets. */
    private int syncedEnergy;
    private ItemStack syncedFuel = ItemStack.EMPTY;
    private ItemStack syncedTarget = ItemStack.EMPTY;
    private long syncedTick = Long.MIN_VALUE;
    private boolean syncPending;

    ChargingDockEntity(BlockPos pos, BlockState state) { super(ChargingDockRegistry.type(), pos, state); }

    public boolean insert(ItemStack stack) {
        if (!target.isEmpty() || !DockEnergy.chargeable(stack)) return false;
        target = stack.split(1);
        refreshTarget();
        requestSync();
        return true;
    }

    public ItemStack take() {
        ItemStack result = target;
        target = ItemStack.EMPTY;
        refreshTarget();
        requestSync();
        return result;
    }

    /**
     * Loads the fuel slot with any furnace fuel. Only one item type fits at a time (like a furnace's
     * fuel slot), and the slot tops out at {@value #FUEL_LIMIT} items.
     *
     * @return how many items of {@code offered} were taken
     */
    public int addFuel(ItemStack offered) {
        if (offered == null || offered.isEmpty() || !dev.corvus.power.FuelPower.fuel(offered)) return 0;
        if (!fuel.isEmpty() && !ItemStack.isSameItemSameTags(fuel, offered)) return 0;
        int accepted = Mth.clamp(offered.getCount(), 0, FUEL_LIMIT - fuel.getCount());
        if (accepted <= 0) return 0;
        if (fuel.isEmpty()) {
            fuel = offered.copy();
            fuel.setCount(accepted);
        } else {
            fuel.grow(accepted);
        }
        requestSync();
        return accepted;
    }

    public Component status() {
        Component detail = target.isEmpty()
                ? Component.translatable("message.dronepal.dock_empty")
                : Component.empty().append(target.getHoverName())
                        .append(" " + DockEnergy.energy(target) + "/" + DockEnergy.capacity(target) + " FE");
        // A projected drone hovering on the pad is the case players ask about: name the rate it gets.
        if (droneRate > 0) detail = detail.copy().append(" · ")
                .append(Component.translatable("message.dronepal.dock_drone", droneRate));
        Component fuelDetail = fuel.isEmpty()
                ? Component.translatable("message.dronepal.fuel_empty")
                : Component.empty().append(fuel.getHoverName()).append(" ×" + fuel.getCount());
        return Component.translatable("message.dronepal.dock_status", energy, BUFFER, fuelDetail, detail);
    }

    public void dropContents() {
        if (level == null || level.isClientSide) return;
        if (!target.isEmpty())
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), take());
        if (!fuel.isEmpty())
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), fuel);
        fuel = ItemStack.EMPTY;
    }

    void tick(ServerLevel world) {
        // TODO(grid): an in-grid pad fills its buffer from the wireless grid before burning fuel.
        int gridRoom = Math.min(BASE_CABLE_RATE, BUFFER - energy);
        if (gridRoom > 0) {
            int fromGrid = dev.corvus.power.grid.GridLink.draw(world, this, gridRoom,
                    dev.corvus.power.grid.GridLink.PRIORITY_DOCK,
                    () -> Math.min(BASE_CABLE_RATE, BUFFER - energy));
            if (fromGrid > 0) {
                energy += fromGrid;
                setChanged();
            }
        }
        if (energy < BUFFER && (burnRemaining > 0 || !fuel.isEmpty())) {
            if (burnRemaining <= 0 && !fuel.isEmpty()) {
                // Any furnace fuel: the item's own burn time decides how much FE it is worth.
                burnTotal = dev.corvus.power.FuelPower.fe(fuel);
                burnRemaining = burnTotal;
                ItemStack burnt = fuel.copy();
                fuel.shrink(1);
                // A lava bucket leaves its bucket behind; never swallow it.
                ItemStack remainder = dev.corvus.power.FuelPower.remainder(burnt);
                if (!remainder.isEmpty()) {
                    Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.6D,
                            worldPosition.getZ() + 0.5D, remainder);
                }
            }
            if (burnRemaining > 0) {
                int generated = Math.min(Math.min(FUEL_RATE, burnRemaining), BUFFER - energy);
                if (generated > 0) {
                    energy += generated;
                    burnRemaining -= generated;
                }
            }
            setChanged();
        }
        droneRate = 0;
        if (energy > 0) {
            if (!target.isEmpty()) {
                transfer(target);
            } else {
                // A projected drone touching the pad charges its owner's actual controller stack.
                AABB dockZone = new AABB(worldPosition).expandTowards(0, 0.75, 0);
                for (DroneEntity drone : world.getEntitiesOfClass(DroneEntity.class, dockZone)) {
                    int received = transfer(controllerOf(drone));
                    if (received <= 0) continue;
                    droneRate = received;
                    break;
                }
            }
        }
        refreshTarget();
        pushSync(world);
    }

    /** The physical controller behind a projection, resolved through its owner every time. */
    static ItemStack controllerOf(DroneEntity drone) {
        if (drone == null || drone.level().isClientSide) return ItemStack.EMPTY;
        java.util.UUID owner = drone.ownerId();
        if (owner == null) return ItemStack.EMPTY;
        net.minecraft.world.entity.player.Player player = drone.level().getPlayerByUUID(owner);
        return player == null ? ItemStack.EMPTY : Hardware.controller(player);
    }

    /** Moves what the cell will take out of the buffer; returns the FE that actually moved. */
    private int transfer(ItemStack stack) {
        int received = DockEnergy.charge(stack, energy, false);
        if (received == 0) return 0;
        energy -= received;
        setChanged();
        return received;
    }

    /** FE/t this pad handed to a projected drone's controller on the last tick (0 when none). */
    public int droneRate() {
        return droneRate;
    }

    /**
     * Recomputes the numbers the client shows for the docked stack. The server value is the one
     * shipped in the update tag, so a client never has to guess.
     */
    private void refreshTarget() {
        targetEnergy = target.isEmpty() ? 0 : Math.max(0, DockEnergy.energy(target));
        targetCapacity = target.isEmpty() ? 0 : Math.max(0, DockEnergy.capacity(target));
    }

    private void requestSync() {
        setChanged();
        syncPending = true;
        if (level instanceof ServerLevel server) pushSync(server);
    }

    /**
     * Sends the update tag when something the client displays actually moved. Forced sends (item in,
     * item out, coal in) skip the interval; ordinary charge drift is limited to one packet per
     * {@value #SYNC_INTERVAL} ticks so a charging pad never becomes a per-tick packet source.
     */
    private void pushSync(ServerLevel world) {
        boolean changed = energy != syncedEnergy || !ItemStack.matches(fuel, syncedFuel)
                || !ItemStack.matches(target, syncedTarget);
        if (!syncPending && !changed) return;
        long now = world.getGameTime();
        if (!syncPending && now - syncedTick < SYNC_INTERVAL) return;
        if (getBlockState().isAir()) { syncPending = false; return; }
        syncPending = false;
        syncedTick = now;
        syncedEnergy = energy;
        syncedFuel = fuel.copy();
        syncedTarget = target.copy();
        world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** 1.20.1 culls through the block entity, not the renderer: cover the item and the panel. */
    @Override public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition).expandTowards(0.0, 1.9, 0.0).inflate(1.2);
    }

    // ---- Client-visible state (also read by dev.corvus.client.ChargingDockRenderer) ----

    public int storedEnergy() { return energy; }
    public int buffer() { return BUFFER; }
    /** Fuel items left in the slot (kept under the old name so the renderer and dev tools still read it). */
    public int coalCount() { return fuel.getCount(); }
    public ItemStack fuelStack() { return fuel; }
    /** FE still queued from the item currently burning; the yield checks read this. */
    public int burnRemainingFe() { return burnRemaining; }
    /** What the item now burning was worth in total (coal 4,000, coal block 40,000). */
    public int burnTotalFe() { return burnTotal; }
    public ItemStack targetStack() { return target; }
    public int targetEnergy() { return targetEnergy; }
    public int targetCapacity() { return targetCapacity; }
    public LazyOptional<IEnergyStorage> energyCapability() { return energyCapability; }

    @Override public CompoundTag getUpdateTag() {
        refreshTarget();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Energy", energy);
        tag.putInt("Buffer", BUFFER);
        tag.put("Fuel", fuel.save(new CompoundTag()));
        tag.putInt("TargetEnergy", targetEnergy);
        tag.putInt("TargetCapacity", targetCapacity);
        tag.put("Target", target.save(new CompoundTag()));
        syncedEnergy = energy;
        syncedFuel = fuel.copy();
        syncedTarget = target.copy();
        return tag;
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        handleUpdateTag(packet.getTag());
    }

    @Override public void handleUpdateTag(CompoundTag tag) {
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
        fuel = ItemStack.of(tag.getCompound("Fuel"));
        if (fuel.getCount() > FUEL_LIMIT) fuel.setCount(FUEL_LIMIT);
        target = ItemStack.of(tag.getCompound("Target"));
        targetEnergy = Math.max(0, tag.getInt("TargetEnergy"));
        targetCapacity = Math.max(0, tag.getInt("TargetCapacity"));
        syncPending = false;
    }

    // ---- Forge energy ----

    @Override public int receiveEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide) return 0;
        long now = level.getGameTime();
        int used = cableTick == now ? cableReceived : 0;
        int accepted = Mth.clamp(amount, 0, Math.min(cableRate() - used, BUFFER - energy));
        if (!simulate && accepted > 0) {
            cableTick = now;
            cableReceived = used + accepted;
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    /** Cable input for this tick, scaled by the server's charge-rate multiplier. */
    private static int cableRate() {
        return (int) Mth.clamp(Math.round(BASE_CABLE_RATE * dev.corvus.ServerOptions.chargeMultiplier()), 1, 1_000_000);
    }

    @Override public int getEnergyStored() { return storedEnergy(); }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canReceive() { return true; }
    @Override public boolean canExtract() { return false; }
    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Target", target.save(new CompoundTag()));
        tag.put("Fuel", fuel.save(new CompoundTag()));
        tag.putInt("BurnRemaining", burnRemaining);
        tag.putInt("BurnTotal", burnTotal);
        tag.putInt("Energy", energy);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        target = ItemStack.of(tag.getCompound("Target"));
        fuel = ItemStack.of(tag.getCompound("Fuel"));
        if (fuel.getCount() > FUEL_LIMIT) fuel.setCount(FUEL_LIMIT);
        burnRemaining = Mth.clamp(tag.getInt("BurnRemaining"), 0, MAX_BURN);
        burnTotal = Mth.clamp(tag.getInt("BurnTotal"), 0, MAX_BURN);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
        refreshTarget();
    }
}
