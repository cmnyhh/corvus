package dev.dronepal.power;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;

/**
 * A single fuel slot shared by every machine in the mod that burns something.
 *
 * <p>It stores the fuel stack itself (one item type, up to {@value FuelValues#SLOT_LIMIT} items) plus
 * the FE left in the item currently on the fire. Charge is therefore continuous across a stack: each
 * item adds {@code burnTicks x 2.5} FE when the previous one runs out, and a partly burnt item keeps
 * its remainder in NBT so a reload never refunds or loses energy.</p>
 */
public final class FuelBuffer {
    private ItemStack fuel = ItemStack.EMPTY;
    private int burnRemaining;

    public ItemStack stack() { return fuel; }
    public int count() { return fuel.getCount(); }
    public int burnRemaining() { return burnRemaining; }
    /** True while there is either an item on the fire or another one queued. */
    public boolean hasFuel() { return burnRemaining > 0 || !fuel.isEmpty(); }

    /** @return how many items were accepted; only one item type fits at a time. */
    public int insert(ItemStack offered, int limit) {
        if (offered.isEmpty() || !FuelValues.burnable(offered)) return 0;
        int room = limit - fuel.getCount();
        if (room <= 0) return 0;
        if (!fuel.isEmpty() && !ItemStack.isSameItemSameComponents(fuel, offered)) return 0;
        int accepted = Math.min(offered.getCount(), room);
        if (fuel.isEmpty()) {
            fuel = offered.copyWithCount(accepted);
        } else {
            fuel.grow(accepted);
        }
        return accepted;
    }

    /** Empties the slot and returns everything that was waiting in it. */
    public ItemStack takeAll() {
        ItemStack result = fuel;
        fuel = ItemStack.EMPTY;
        return result;
    }

    /**
     * Keeps the fire alive: when the current item is spent, pops the next one and converts its burn
     * time into FE. A container fuel (lava bucket) leaves its container on the ground rather than
     * vanishing with the item.
     *
     * @return true when there is something burning afterwards
     */
    public boolean ignite(ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (burnRemaining > 0) return true;
        if (fuel.isEmpty()) return false;
        ItemStack burnt = fuel.copyWithCount(1);
        burnRemaining = FuelValues.fe(burnt);
        fuel.shrink(1);
        ItemStack remainder = FuelValues.remainder(burnt);
        if (!remainder.isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.9D, pos.getZ() + 0.5D, remainder);
        }
        return burnRemaining > 0;
    }

    /** Releases at most {@code budget} FE from the current item. */
    public int draw(int budget) {
        int released = Math.min(Math.max(0, budget), burnRemaining);
        burnRemaining -= released;
        return released;
    }

    public void save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.put("Fuel", fuel.saveOptional(registries));
        tag.putInt("BurnRemaining", burnRemaining);
    }

    /** Client-side mirror of the slot: the item and count only, never the burn timer. */
    public void setMirror(ItemStack stack) {
        fuel = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        burnRemaining = 0;
    }

    public void load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag fuelTag = tag.getCompound("Fuel");
        fuel = fuelTag.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(registries, fuelTag);
        if (fuel.getCount() > FuelValues.SLOT_LIMIT) fuel.setCount(FuelValues.SLOT_LIMIT);
        burnRemaining = Math.max(0, tag.getInt("BurnRemaining"));
    }

    /**
     * The client mirror only needs the item and the count; the burn timer stays on the server. Returns
     * the bare item tag, because the caller owns the surrounding key.
     */
    public CompoundTag saveDisplay(net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.nbt.Tag saved = fuel.saveOptional(registries);
        return saved instanceof CompoundTag compound ? compound : new CompoundTag();
    }
}
