package dev.corvus.mill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Eight machine slots (3 ore in, 3 out, 2 upgrades) above the player inventory, transcribed from the
 * 1.21.1 panel. The water and energy tanks are internal, so they travel as synced data slots instead of
 * slots.
 *
 * <p>The three output wells are role-specific and never share a stack: well 0 takes the primary product
 * (concentrate, or the rare shard), well 1 the mineral's own coarse by-product and well 2 the extra rare
 * roll. A job whose target well is full stops the machine instead of spilling into another well.</p>
 *
 * <p>1.20.1 syncs container data as shorts, so both the 60,000 FE buffer and the 4,000 mB tank travel as
 * two halves. Everything the screen draws — progress, cost, speed, water, upgrade flags, the lamp
 * reason — is read from the block entity on the server every broadcast and mirrored through the data
 * slots.</p>
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
    public static final int STATE_PROGRESS = 0, STATE_DURATION = 1, STATE_ENERGY_LOW = 2,
            STATE_ENERGY_HIGH = 3, STATE_LINK = 4, STATE_SPEED = 5, STATE_COST = 6, STATE_SIEVE = 7,
            STATE_COOLING = 8, STATE_ASSIGNED = 9, STATE_WATER = 10, STATE_WATER_CAPACITY = 11;

    private final OreMillEntity mill;
    private final ContainerData data = new SimpleContainerData(STATE_COUNT);

    /** Client-side constructor required by {@code MenuType}. */
    public OreMillMenu(int id, Inventory inventory) { this(id, inventory, null); }

    public OreMillMenu(int id, Inventory inventory, OreMillEntity mill) {
        super(OreMillRegistry.MENU.get(), id);
        this.mill = mill;
        Container machine = mill == null ? new SimpleContainer(MACHINE_SLOTS) : mill.inventory();
        for (int i = 0; i < MACHINE_SLOTS; i++) addSlot(machineSlot(machine, i));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + row * 9 + col, INVENTORY_X + 18 * col,
                        INVENTORY_Y + 18 * row));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INVENTORY_X + 18 * col, HOTBAR_Y));
        addDataSlots(data);
        refresh();
    }

    private Slot machineSlot(Container machine, int index) {
        return new Slot(machine, index, SLOT_X[index], SLOT_Y[index]) {
            @Override public boolean mayPlace(ItemStack stack) {
                if (stack.isEmpty()) return false;
                if (index < INPUT_COUNT) return OreKind.of(stack) != null;
                if (index >= UPGRADE_FIRST) return OreMillRegistry.isUpgrade(stack);
                return false;
            }

            @Override public int getMaxStackSize() { return index >= UPGRADE_FIRST ? 1 : super.getMaxStackSize(); }
        };
    }

    /** Reads the block entity once per broadcast; on the client the same array is fed by packets. */
    private void refresh() {
        if (mill == null) return;
        int energy = mill.energy(), water = mill.water();
        data.set(STATE_PROGRESS, mill.progress() & 0xffff);
        data.set(STATE_DURATION, mill.duration());
        data.set(STATE_ENERGY_LOW, energy & 0xffff);
        data.set(STATE_ENERGY_HIGH, energy >>> 16);
        data.set(STATE_LINK, mill.link().ordinal());
        data.set(STATE_SPEED, mill.speedPercent());
        data.set(STATE_COST, mill.currentCost());
        data.set(STATE_SIEVE, mill.sieveFitted() ? 1 : 0);
        data.set(STATE_COOLING, mill.coolingFitted() ? 1 : 0);
        data.set(STATE_ASSIGNED, mill.assignedDrone() == null ? 0 : 1);
        data.set(STATE_WATER, water);
        data.set(STATE_WATER_CAPACITY, mill.waterCapacity());
    }

    @Override public void broadcastChanges() {
        refresh();
        super.broadcastChanges();
    }

    public int progress() { return data.get(STATE_PROGRESS); }

    public int duration() { return data.get(STATE_DURATION); }

    public int energy() { return (data.get(STATE_ENERGY_HIGH) << 16) | (data.get(STATE_ENERGY_LOW) & 0xffff); }

    public OreMillEntity.Link link() {
        int ordinal = data.get(STATE_LINK);
        return ordinal >= 0 && ordinal < OreMillEntity.Link.values().length
                ? OreMillEntity.Link.values()[ordinal] : OreMillEntity.Link.NONE;
    }

    public int speedPercent() { return data.get(STATE_SPEED); }

    public int energyPerItem() { return data.get(STATE_COST); }

    public boolean sieve() { return data.get(STATE_SIEVE) != 0; }

    public boolean cooling() { return data.get(STATE_COOLING) != 0; }

    public boolean assigned() { return data.get(STATE_ASSIGNED) != 0; }

    public int water() { return data.get(STATE_WATER); }

    public int waterCapacity() { return data.get(STATE_WATER_CAPACITY); }

    public int waterPerItem() {
        return link() == OreMillEntity.Link.WORKING
                ? OreMillRules.WATER_PER_ITEM_DRONE : OreMillRules.WATER_PER_ITEM;
    }

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
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = getSlot(index);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem(), original = stack.copy();
        if (index < MACHINE_SLOTS) {
            if (!moveItemStackTo(stack, MACHINE_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Route by kind through legal slots only; vanilla range merging ignores mayPlace on occupied slots.
            for (int pass = 0; pass < 3 && !stack.isEmpty(); pass++) {
                for (int i = 0; i < MACHINE_SLOTS && !stack.isEmpty(); i++) {
                    if (pass == 0 && getSlot(i).hasItem()) continue;
                    if (!getSlot(i).mayPlace(stack)) continue;
                    moveItemStackTo(stack, i, i + 1, false);
                }
            }
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        if (stack.isEmpty()) source.set(ItemStack.EMPTY); else source.setChanged();
        source.onTake(player, stack);
        return original;
    }
}
