package dev.dronepal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The terminal's cargo hold as a plain vanilla container.
 *
 * <p>The equipped terminal stays the only truth: every slot here reads and writes
 * {@code dronepal_cargo} on the controller, so closing the screen can neither duplicate nor lose a
 * stack. The drone's own pickup and delivery cycles stand down while this window is open
 * ({@link CarryLight#viewing}); otherwise they would rewrite the tag behind the view.
 *
 * <p>Three menu types cover the shipped capacities (9 / 12 / 18 / 27 slots): the row count has to be
 * fixed when the client builds the menu, while the exact capacity arrives in a data slot so a
 * shortened data pack only greys out the surplus slots. Items parked outside the current capacity by
 * such a downgrade are never dropped; they stay in the tag and reappear when the module grows back.
 */
public final class CargoMenu extends AbstractContainerMenu {
    public static final int COLUMNS = 9, MAX_ROWS = 3;
    private final Inventory inventory;
    private final DroneEntity drone;
    private final ItemStack controller;
    private final Container view;
    private final int rows, slotCount;
    /** 0 = slots the fitted module can use, 1 = items parked outside that capacity. */
    private final SimpleContainerData data = new SimpleContainerData(2);

    /** Server view: the capacity comes from the fitted carry module. */
    CargoMenu(int id, Inventory inventory, DroneEntity drone, ItemStack controller) {
        this(id, inventory, drone, controller, CarryLight.slots(controller));
    }

    private CargoMenu(int id, Inventory inventory, DroneEntity drone, ItemStack controller, int capacity) {
        super(TerminalMenus.cargoType(rowsFor(capacity)), id);
        this.inventory = inventory;
        this.drone = drone;
        this.controller = controller;
        this.rows = rowsFor(capacity);
        this.slotCount = rows * COLUMNS;
        int usable = Math.clamp(capacity, 1, slotCount);
        Hold hold = new Hold((ServerPlayer) inventory.player, controller, usable, slotCount);
        this.view = hold;
        data.set(0, usable);
        data.set(1, hold.parked());
        layout();
        addDataSlots(data);
    }

    /** Client mirror: the menu type already fixes the row count; capacity arrives from the data slot. */
    CargoMenu(int id, Inventory inventory, int rows) {
        super(TerminalMenus.cargoType(rows), id);
        this.inventory = inventory;
        this.drone = null;
        this.controller = ItemStack.EMPTY;
        this.rows = Math.clamp(rows, 1, MAX_ROWS);
        this.slotCount = this.rows * COLUMNS;
        this.view = new SimpleContainer(this.slotCount);
        layout();
        addDataSlots(data);
    }

    static int rowsFor(int capacity) {
        return Math.clamp((Math.max(capacity, 1) + COLUMNS - 1) / COLUMNS, 1, MAX_ROWS);
    }

    private void layout() {
        for (int i = 0; i < slotCount; i++) {
            int index = i;
            addSlot(new Slot(view, i, 8 + 18 * (i % COLUMNS), 18 + 18 * (i / COLUMNS)) {
                @Override public boolean isActive() { return index < capacity(); }
                // A surplus slot never holds anything, but it must not accept a drop either.
                @Override public boolean mayPlace(ItemStack stack) { return isActive(); }
                @Override public boolean mayPickup(Player player) { return isActive(); }
            });
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < COLUMNS; col++)
            addSlot(new Slot(inventory, COLUMNS + row * COLUMNS + col, 8 + 18 * col, 31 + rows * 18 + 18 * row));
        for (int col = 0; col < COLUMNS; col++) addSlot(new Slot(inventory, col, 8 + 18 * col, 89 + rows * 18));
    }

    public int rows() { return rows; }
    public int slotCount() { return slotCount; }
    public int firstInventorySlot() { return slotCount; }
    /** Usable slots; the client shows every well until the data slot lands. */
    public int capacity() {
        int value = data.get(0);
        return value <= 0 || value > slotCount ? slotCount : value;
    }
    /** Items kept in the tag from a data pack that shrank the module. */
    public int parked() { return data.get(1); }
    boolean controls(ItemStack stack) { return drone != null && stack == controller; }

    @Override public boolean stillValid(Player player) {
        if (player != inventory.player) return false;
        if (player.level().isClientSide) return true;
        return drone != null && player instanceof ServerPlayer server
            && TerminalMenus.authorized(server, drone, controller) && CarryLight.available(controller);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = getSlot(index);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem(), original = stack.copy();
        if (index < slotCount) {
            if (!moveItemStackTo(stack, slotCount, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, capacity(), false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY); else source.setChanged();
        source.onTake(player, stack);
        return original;
    }

    @Override public void removed(Player player) {
        super.removed(player);
        if (view instanceof Hold hold) hold.flush();
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot < -1 && slot != -999 || slot >= slots.size()) return;
        if (drone == null) { super.clicked(slot, button, type, player); return; }
        // Every write needs a live, owned, in-range terminal that is still the equipped one.
        if (!(player instanceof ServerPlayer server) || server.containerMenu != this || !stillValid(player)) return;
        super.clicked(slot, button, type, player);
        broadcastChanges();
    }

    /** Live view of the terminal's cargo tag: a mutation is on the item before it returns. */
    private static final class Hold implements Container {
        private final ServerPlayer owner;
        private final ItemStack controller;
        private final List<ItemStack> items;
        private final List<ItemStack> parked;
        private List<ItemStack> written;

        Hold(ServerPlayer owner, ItemStack controller, int capacity, int slotCount) {
            this.owner = owner;
            this.controller = controller;
            List<ItemStack> cargo = CarryLight.read(owner, controller);
            this.items = new ArrayList<>(slotCount);
            for (int i = 0; i < slotCount; i++)
                this.items.add(i < capacity && i < cargo.size() ? cargo.get(i) : ItemStack.EMPTY);
            this.parked = new ArrayList<>(cargo.subList(Math.min(capacity, cargo.size()), cargo.size()));
            this.written = snapshot();
        }

        private List<ItemStack> snapshot() {
            var all = new ArrayList<ItemStack>(items.size() + parked.size());
            for (ItemStack stack : items) if (!stack.isEmpty()) all.add(stack.copy());
            for (ItemStack stack : parked) if (!stack.isEmpty()) all.add(stack.copy());
            return all;
        }

        private static boolean same(List<ItemStack> a, List<ItemStack> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
            return true;
        }

        private void flush() {
            List<ItemStack> next = snapshot();
            if (same(next, written)) return;
            written = next;
            CarryLight.save(owner, controller, next);
        }

        int parked() { int count = 0; for (ItemStack stack : parked) count += stack.getCount(); return count; }

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
        @Override public void setItem(int index, ItemStack stack) { items.set(index, stack); flush(); }
        @Override public int getMaxStackSize() { return 64; }
        @Override public void setChanged() { flush(); }
        @Override public boolean stillValid(Player player) { return player == owner && owner.isAlive(); }
        @Override public void clearContent() {
            for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
            parked.clear();
            flush();
        }
    }
}
