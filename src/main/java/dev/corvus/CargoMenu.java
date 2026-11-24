package dev.corvus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
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
 * {@code dronepal_cargo} on the controller through {@link CargoHold}, so closing the screen can
 * neither duplicate nor lose a stack. The drone's own pickup and delivery cycles stand down while
 * this window is open ({@link CarryLight#viewing}); otherwise they would rewrite the tag behind the
 * view.</p>
 *
 * <p>Three menu types cover the shipped capacities (9 / 18 / 27, and 12 for the rust hold): the row
 * count has to be fixed when the client builds the menu, while the exact capacity arrives in a data
 * slot so a shortened data pack only greys out the surplus slots.</p>
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
        super(TerminalMenus.cargoType(rowsFor(capacity)).get(), id);
        this.inventory = inventory;
        this.drone = drone;
        this.controller = controller;
        this.rows = rowsFor(capacity);
        this.slotCount = rows * COLUMNS;
        int usable = Mth.clamp(capacity, 1, slotCount);
        this.view = new CargoHold(controller, usable, slotCount);
        data.set(0, usable);
        data.set(1, ((CargoHold) view).parked());
        layout();
        addDataSlots(data);
    }

    /** Client mirror: the menu type already fixes the row count; capacity arrives from the data slot. */
    CargoMenu(int id, Inventory inventory, int rows) {
        super(TerminalMenus.cargoType(rows).get(), id);
        this.inventory = inventory;
        this.drone = null;
        this.controller = ItemStack.EMPTY;
        this.rows = Mth.clamp(rows, 1, MAX_ROWS);
        this.slotCount = this.rows * COLUMNS;
        this.view = new SimpleContainer(this.slotCount);
        layout();
        addDataSlots(data);
    }

    static int rowsFor(int capacity) {
        return Mth.clamp((Math.max(capacity, 1) + COLUMNS - 1) / COLUMNS, 1, MAX_ROWS);
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

    boolean controls(ItemStack stack) {
        return drone != null && !controller.isEmpty() && !stack.isEmpty() && ItemStack.matches(stack, controller);
    }

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
        if (view instanceof CargoHold hold) hold.flush();
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot < -1 && slot != -999 || slot >= slots.size()) return;
        if (drone == null) { super.clicked(slot, button, type, player); return; }
        // Every write needs a live, owned, in-range terminal that is still the equipped one.
        if (!(player instanceof ServerPlayer server) || server.containerMenu != this || !stillValid(player)) return;
        super.clicked(slot, button, type, player);
        broadcastChanges();
    }
}
