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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * A charging pad. Coal or cable FE fills a 32,000 FE buffer, which tops up the single cell (or the
 * battery inside a docked controller) sitting in the pad.
 *
 * <p>1.20.1 exposes energy through Forge's {@link IEnergyStorage} capability: the pad itself is the
 * storage and {@code ChargingCapabilities} publishes it through {@code AttachCapabilitiesEvent}.</p>
 *
 * <p>Everything the client draws travels in {@link #getUpdateTag}: the buffer, the docked stack and the
 * stats of its cell. Tags are pushed at most once every {@value #SYNC_INTERVAL} ticks while a value is
 * moving, and immediately on insert/take/coal so the hologram never shows stale numbers.</p>
 */
public final class ChargingDockEntity extends BlockEntity implements IEnergyStorage {
    public static final int BUFFER = 32_000;
    static final int BASE_CABLE_RATE = 2_000;
    static final int COAL_YIELD = 4_000;
    static final int COAL_RATE = 200;
    /** Ticks between two throttled update packets (10 ticks = twice a second). */
    public static final int SYNC_INTERVAL = 10;

    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> this);
    private ItemStack target = ItemStack.EMPTY;
    private int coal;
    private int burnRemaining;
    private int energy;
    private int targetEnergy;
    private int targetCapacity;
    private long cableTick = Long.MIN_VALUE;
    private int cableReceived;
    /** Snapshot of what the last update tag told the clients, used to throttle packets. */
    private int syncedEnergy;
    private int syncedCoal;
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

    public int addCoal(int offered) {
        int accepted = Mth.clamp(offered, 0, 64 - coal);
        if (accepted > 0) {
            coal += accepted;
            requestSync();
        }
        return accepted;
    }

    public Component status() {
        Component detail = target.isEmpty()
                ? Component.translatable("message.dronepal.dock_empty")
                : Component.empty().append(target.getHoverName())
                        .append(" " + DockEnergy.energy(target) + "/" + DockEnergy.capacity(target) + " FE");
        return Component.translatable("message.dronepal.dock_status", energy, BUFFER, coal, detail);
    }

    public void dropContents() {
        if (level == null || level.isClientSide) return;
        if (!target.isEmpty())
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), take());
        if (coal > 0)
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                    new ItemStack(Items.COAL, coal));
        coal = 0;
    }

    void tick(ServerLevel world) {
        if (energy < BUFFER && (burnRemaining > 0 || coal > 0)) {
            if (burnRemaining == 0) { coal--; burnRemaining = COAL_YIELD; }
            int generated = Math.min(Math.min(COAL_RATE, burnRemaining), BUFFER - energy);
            energy += generated;
            burnRemaining -= generated;
            setChanged();
        }
        if (energy > 0) {
            if (!target.isEmpty()) {
                transfer(target);
            } else {
                // A projected drone touching the pad charges its owner's actual controller stack.
                AABB dockZone = new AABB(worldPosition).expandTowards(0, 0.75, 0);
                for (DroneEntity drone : world.getEntitiesOfClass(DroneEntity.class, dockZone)) {
                    if (transfer(controllerOf(drone))) break;
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

    private boolean transfer(ItemStack stack) {
        int received = DockEnergy.charge(stack, energy, false);
        if (received == 0) return false;
        energy -= received;
        setChanged();
        return true;
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
        boolean changed = energy != syncedEnergy || coal != syncedCoal || !ItemStack.matches(target, syncedTarget);
        if (!syncPending && !changed) return;
        long now = world.getGameTime();
        if (!syncPending && now - syncedTick < SYNC_INTERVAL) return;
        if (getBlockState().isAir()) { syncPending = false; return; }
        syncPending = false;
        syncedTick = now;
        syncedEnergy = energy;
        syncedCoal = coal;
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
    public int coalCount() { return coal; }
    public ItemStack targetStack() { return target; }
    public int targetEnergy() { return targetEnergy; }
    public int targetCapacity() { return targetCapacity; }
    public LazyOptional<IEnergyStorage> energyCapability() { return energyCapability; }

    @Override public CompoundTag getUpdateTag() {
        refreshTarget();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Energy", energy);
        tag.putInt("Buffer", BUFFER);
        tag.putInt("Coal", coal);
        tag.putInt("TargetEnergy", targetEnergy);
        tag.putInt("TargetCapacity", targetCapacity);
        tag.put("Target", target.save(new CompoundTag()));
        syncedEnergy = energy;
        syncedCoal = coal;
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
        coal = Mth.clamp(tag.getInt("Coal"), 0, 64);
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
        tag.putInt("Coal", coal);
        tag.putInt("BurnRemaining", burnRemaining);
        tag.putInt("Energy", energy);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        target = ItemStack.of(tag.getCompound("Target"));
        coal = Mth.clamp(tag.getInt("Coal"), 0, 64);
        burnRemaining = Mth.clamp(tag.getInt("BurnRemaining"), 0, COAL_YIELD);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
        refreshTarget();
    }
}
