package dev.dronepal.mill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Eight machine slots (3 ore in, 3 out, 2 upgrades) above the player inventory. The water and energy
 * tanks are internal, so they travel as synced data slots instead of slots.
 *
 * <p>The three output wells are role-specific and never share a stack: well 0 takes the primary product
 * (concentrate, or the rare shard), well 1 the mineral's own coarse by-product and well 2 the extra rare
 * roll. A job whose target well is full stops the machine instead of spilling into another well.</p>
 *
 * <p>Vanilla container data syncs shorts, so both the 60,000 FE buffer and the 4,000 mB tank travel as
 * two halves. Everything the screen draws — progress, cost, speed, water, upgrade flags, the lamp
 * reason — is derived from the block entity on the server every broadcast and mirrored through the
 * data slots.</p>
 */
public final class OreMillMenu extends AbstractContainerMenu {
    public static final int MACHINE_SLOTS = 8;
    public static final int INPUT_COUNT = 3;
    public static final int OUTPUT_FIRST = 3;
    public static final int OUTPUT_COUNT = 3;
    /** Output well roles, by index inside the output block (i.e. {@code output(0..2)}). */
    public static final int OUTPUT_PRIMARY = 0, OUTPUT_BYPRODUCT = 1, OUTPUT_EXTRA = 2;
    public static final int UPGRADE_FIRST = 6;
    public static final int UPGRADE_COUNT = 2;

    public static final int BUTTON_CALL_DRONE = 0;

    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 250;
    // Left column: three input wells, then two upgrade wells. Right column: three output wells below the
    // water and energy gauges (primary / by-product / extra, left to right). Kept in the menu so slots,
    // tooltips and hitboxes share one grid.
    private static final int[] SLOT_X = {8, 28, 48, 88, 108, 128, 8, 28};
    private static final int[] SLOT_Y = {38, 38, 38, 86, 86, 86, 58, 58};
    private static final int INVENTORY_X = 8, INVENTORY_Y = 172, HOTBAR_Y = 230;

    private static final int STATE_COUNT = 12;
    private final int[] state = new int[STATE_COUNT];
    private final OreMillEntity mill;
    private final Container inventory;

    public OreMillMenu(int id, Inventory inventory) { this(id, inventory, null); }

    public OreMillMenu(int id, Inventory inventory, OreMillEntity mill) {
        super(OreMillRegistry.MENU.get(), id);
        this.inventory = inventory;
        this.mill = mill;
        SimpleContainer machine = mill == null ? new SimpleContainer(MACHINE_SLOTS) : mill.inventory();
        for (int i = 0; i < MACHINE_SLOTS; i++) addSlot(machineSlot(machine, i));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, 9 + row * 9 + col, INVENTORY_X + 18 * col, INVENTORY_Y + 18 * row));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INVENTORY_X + 18 * col, HOTBAR_Y));
        for (int i = 0; i < STATE_COUNT; i++) addDataSlot(DataSlot.shared(state, i));
        refresh();
    }

    private Slot machineSlot(Container machine, int index) {
        return new Slot(machine, index, SLOT_X[index], SLOT_Y[index]) {
            @Override public boolean mayPlace(ItemStack stack) {
                if (stack.isEmpty()) return false;
                if (index < INPUT_COUNT) return OreKind.of(stack) != null;
                if (index >= UPGRADE_FIRST) return MillItems.isSieve(stack) || MillItems.isCooling(stack);
                return false;
            }
            @Override public int getMaxStackSize() { return index >= UPGRADE_FIRST ? 1 : super.getMaxStackSize(); }
        };
    }

    /** Reads the block entity once per broadcast; on the client the same array is fed by packets. */
    private void refresh() {
        if (mill == null) return;
        int energy = mill.energy(), water = mill.water();
        state[0] = mill.progress() & 0xffff;
        state[1] = mill.duration();
        state[2] = energy & 0xffff;
        state[3] = energy >>> 16;
        state[4] = mill.link().ordinal();
        state[5] = mill.speedPercent();
        state[6] = mill.currentCost();
        state[7] = mill.sieveFitted() ? 1 : 0;
        state[8] = mill.coolingFitted() ? 1 : 0;
        state[9] = mill.assignedDrone() == null ? 0 : 1;
        state[10] = water;
        state[11] = mill.waterCapacity();
    }

    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }

    public int progress() { return state[0]; }
    public int duration() { return state[1]; }
    public int energy() { return (state[3] << 16) | (state[2] & 0xffff); }
    public OreMillEntity.Link link() {
        int ordinal = state[4];
        return ordinal >= 0 && ordinal < OreMillEntity.Link.values().length ? OreMillEntity.Link.values()[ordinal] : OreMillEntity.Link.NONE;
    }
    public int speedPercent() { return state[5]; }
    public int energyPerItem() { return state[6]; }
    public boolean sieve() { return state[7] != 0; }
    public boolean cooling() { return state[8] != 0; }
    public boolean assigned() { return state[9] != 0; }
    public int water() { return state[10]; }
    public int waterCapacity() { return state[11]; }
    public int waterPerItem() { return link() == OreMillEntity.Link.WORKING ? OreMillRules.WATER_PER_ITEM_DRONE : OreMillRules.WATER_PER_ITEM; }
    public int energyPerTick() { return OreMillRules.FE_PER_TICK; }
    public int buffer() { return OreMillRules.BUFFER; }
    public boolean droneWorked() { return link() == OreMillEntity.Link.WORKING; }

    public static int slotX(int index) { return SLOT_X[index]; }
    public static int slotY(int index) { return SLOT_Y[index]; }
    public static int inventoryX() { return INVENTORY_X; }
    public static int inventoryY() { return INVENTORY_Y; }
    public static int hotbarY() { return HOTBAR_Y; }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (mill == null || !(player instanceof ServerPlayer server)) return false;
        if (server.containerMenu != this || !stillValid(player)) return false;
        if (id != BUTTON_CALL_DRONE) return false;
        return DroneWork.callToMill(server, mill);
    }

    @Override public boolean stillValid(Player player) {
        if (mill == null) return false;
        if (mill.getLevel() != player.level()) return false;
        return player.distanceToSqr(mill.getBlockPos().getCenter()) <= 8 * 8
                && mill.getLevel().getBlockEntity(mill.getBlockPos()) == mill;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot source = getSlot(index);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem(), original = stack.copy();
        if (index < MACHINE_SLOTS) {
            if (!moveItemStackTo(stack, MACHINE_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Route by kind through legal slots only; vanilla range merging ignores mayPlace on occupied slots.
            for (int pass = 0; pass < 3 && !stack.isEmpty(); pass++) {
                for (int i = 0; i < MACHINE_SLOTS && !stack.isEmpty(); i++) {
                    Slot target = getSlot(i);
                    if (!target.mayPlace(stack)) continue;
                    if (pass == 0 && target.hasItem()) continue;
                    target.safeInsert(stack);
                }
            }
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY); else source.setChanged();
        source.onTake(player, stack);
        return original;
    }
}
