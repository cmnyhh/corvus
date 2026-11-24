package dev.corvus;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Live view of the terminal's cargo tag: a mutation is on the item before the call returns.
 *
 * <p>Three menu types cover the shipped capacities (9 / 12 / 18 / 27 slots). The row count has to be
 * fixed when the client builds the menu, so {@code slotCount} is the grid and {@code capacity} is
 * what the fitted module can actually use. Items parked outside a capacity that a data pack shrank
 * are never dropped: they stay in the tag and reappear when the module grows back.</p>
 *
 * <p>Nothing here needs a player, so the conservation rules can be exercised by the server smoke
 * harness without a connected client.</p>
 */
public final class CargoHold implements Container {
    private final ItemStack controller;
    private final int capacity;
    private final List<ItemStack> items;
    private final List<ItemStack> parked;
    private List<ItemStack> written;

    public CargoHold(ItemStack controller, int capacity, int slotCount) {
        this.controller = controller;
        this.capacity = Math.max(0, Math.min(capacity, slotCount));
        List<ItemStack> cargo = CarryLight.read(controller);
        this.items = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++)
            this.items.add(i < this.capacity && i < cargo.size() ? cargo.get(i) : ItemStack.EMPTY);
        int keep = Math.min(this.capacity, cargo.size());
        this.parked = new ArrayList<>(cargo.subList(keep, cargo.size()));
        this.written = snapshot();
    }

    /** Everything the tag holds, in slot order; the write side of the hold. */
    private List<ItemStack> snapshot() {
        List<ItemStack> all = new ArrayList<>(items.size() + parked.size());
        for (ItemStack stack : items) if (!stack.isEmpty()) all.add(stack.copy());
        for (ItemStack stack : parked) if (!stack.isEmpty()) all.add(stack.copy());
        return all;
    }

    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }

    /** Pushes the view into the controller tag when, and only when, it changed. */
    public void flush() {
        List<ItemStack> next = snapshot();
        if (same(next, written)) return;
        written = next;
        CarryLight.save(controller, next);
    }

    public int capacity() { return capacity; }

    /** Items kept in the tag after a data pack shrank the fitted module. */
    public int parked() {
        int count = 0;
        for (ItemStack stack : parked) count += stack.getCount();
        return count;
    }

    public ItemStack controller() { return controller; }

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return itemCount() == 0; }

    private int itemCount() {
        int count = 0;
        for (ItemStack stack : items) count += stack.getCount();
        for (ItemStack stack : parked) count += stack.getCount();
        return count;
    }

    @Override public ItemStack getItem(int index) { return items.get(index); }

    @Override public ItemStack removeItem(int index, int count) {
        ItemStack removed = ContainerHelper.removeItem(items, index, count);
        if (!removed.isEmpty()) flush();
        return removed;
    }

    // Flushing even here keeps the terminal truthful: a stale tag could resurrect a taken stack.
    @Override public ItemStack removeItemNoUpdate(int index) {
        ItemStack taken = ContainerHelper.takeItem(items, index);
        flush();
        return taken;
    }

    @Override public void setItem(int index, ItemStack stack) {
        items.set(index, stack);
        flush();
    }

    @Override public int getMaxStackSize() { return 64; }
    @Override public void setChanged() { flush(); }
    @Override public boolean stillValid(Player player) { return true; }

    @Override public void clearContent() {
        for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
        parked.clear();
        flush();
    }
}
