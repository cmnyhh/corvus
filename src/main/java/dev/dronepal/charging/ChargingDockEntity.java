package dev.dronepal.charging;

import dev.dronepal.DroneEntity;
import dev.dronepal.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
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
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A charging pad. Coal or cable FE fills a 32,000 FE buffer, which tops up the single cell (or the
 * battery inside a docked controller) sitting in the pad.
 * <p>
 * Everything the client draws travels in {@link #getUpdateTag}: the buffer, the docked stack and the
 * stats of its cell. Tags are pushed at most once every {@value #SYNC_INTERVAL} ticks while a value
 * is moving, and immediately on insert/take/coal so the hologram never shows stale numbers.
 */
public final class ChargingDockEntity extends BlockEntity implements IEnergyStorage {
    private static final int BUFFER = 32_000;
    static final int BASE_CABLE_RATE = 2_000;
    private static final int COAL_YIELD = 4_000;
    private static final int COAL_RATE = 200;
    /** Ticks between two throttled update packets (10 ticks = twice a second). */
    static final int SYNC_INTERVAL = 10;
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

    ChargingDockEntity(BlockPos pos, BlockState state) { super(ChargingDockRegistry.TYPE.get(), pos, state); }

    boolean insert(ItemStack stack) {
        if (!target.isEmpty() || !DockEnergy.chargeable(stack)) return false;
        target = stack.split(1);
        refreshTarget();
        requestSync();
        return true;
    }

    ItemStack take() {
        ItemStack result = target;
        target = ItemStack.EMPTY;
        refreshTarget();
        requestSync();
        return result;
    }

    int addCoal(int offered) {
        int accepted = Math.clamp(offered, 0, 64 - coal);
        if (accepted > 0) {
            coal += accepted;
            requestSync();
        }
        return accepted;
    }

    Component status() {
        Component detail = target.isEmpty() ? Component.translatable("message.dronepal.dock_empty") :
                Component.empty().append(target.getHoverName()).append(" " + DockEnergy.energy(target) + "/" + DockEnergy.capacity(target) + " FE");
        return Component.translatable("message.dronepal.dock_status", energy, BUFFER, coal, detail);
    }

    void dropContents() {
        if (level == null || level.isClientSide) return;
        if (!target.isEmpty()) Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), take());
        if (coal > 0) Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), new ItemStack(Items.COAL, coal));
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
                var dockZone = new AABB(worldPosition).expandTowards(0, 0.75, 0);
                for (var drone : world.getEntitiesOfClass(DroneEntity.class, dockZone)) {
                    if (transfer(drone.controller())) break;
                }
            }
        }
        refreshTarget();
        pushSync(world);
    }

    private boolean transfer(ItemStack stack) {
        int received = DockEnergy.charge(stack, energy, false);
        if (received == 0) return false;
        energy -= received;
        setChanged();
        return true;
    }

    /**
     * Recomputes the numbers the client shows for the docked stack. Both sides can read the cell, but
     * the server value is the one shipped in the update tag, so a client never has to guess.
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

    // ---- Client-visible state (also read by dev.dronepal.client.ChargingDockRenderer) ----

    public int storedEnergy() { return energy; }
    public int buffer() { return BUFFER; }
    public int coalCount() { return coal; }
    public ItemStack targetStack() { return target; }
    public int targetEnergy() { return targetEnergy; }
    public int targetCapacity() { return targetCapacity; }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        refreshTarget();
        CompoundTag tag = new CompoundTag();
        tag.putInt("Energy", energy);
        tag.putInt("Buffer", BUFFER);
        tag.putInt("Coal", coal);
        tag.putInt("TargetEnergy", targetEnergy);
        tag.putInt("TargetCapacity", targetCapacity);
        tag.put("Target", target.saveOptional(registries));
        syncedEnergy = energy;
        syncedCoal = coal;
        syncedTarget = target.copy();
        return tag;
    }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
        coal = Mth.clamp(tag.getInt("Coal"), 0, 64);
        target = ItemStack.parseOptional(registries, tag.getCompound("Target"));
        targetEnergy = Math.max(0, tag.getInt("TargetEnergy"));
        targetCapacity = Math.max(0, tag.getInt("TargetCapacity"));
        syncPending = false;
    }

    @Override public int receiveEnergy(int amount, boolean simulate) {
        if (level == null || level.isClientSide) return 0;
        long now = level.getGameTime();
        int used = cableTick == now ? cableReceived : 0;
        int accepted = Math.clamp(amount, 0, Math.min(cableRate() - used, BUFFER - energy));
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
        return (int)Math.clamp(Math.round(BASE_CABLE_RATE * dev.dronepal.ServerOptions.chargeMultiplier()), 1, 1_000_000);
    }
    @Override public int getEnergyStored() { return storedEnergy(); }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canReceive() { return true; }
    @Override public boolean canExtract() { return false; }
    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Target", target.saveOptional(registries));
        tag.putInt("Coal", coal);
        tag.putInt("BurnRemaining", burnRemaining);
        tag.putInt("Energy", energy);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        target = ItemStack.parseOptional(registries, tag.getCompound("Target"));
        coal = Mth.clamp(tag.getInt("Coal"), 0, 64);
        burnRemaining = Mth.clamp(tag.getInt("BurnRemaining"), 0, COAL_YIELD);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
        refreshTarget();
    }
}
