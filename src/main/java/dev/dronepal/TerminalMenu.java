package dev.dronepal;

import java.util.Arrays;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class TerminalMenu extends AbstractContainerMenu {
    public static final List<String> PAINTS = PaintCans.PALETTE;
    /** Inventory-button id: open the cargo hold of the fitted carry module. */
    public static final int BUTTON_CARGO = 0;
    /** Inventory-button id: flip the personality bark switch on the terminal. */
    public static final int BUTTON_BARKS = 1;
    public static final List<String> MESSAGES = List.of("hint", "installed", "removed", "full", "duplicate", "invalid", "unrelated", "inventory_full", "battery_tier", "gun_type", "combat_required", "remove_gun_first");
    private static final int[] AMMO_SLOTS = {6, 8, 9, 10, 11, 12, 13, 14};
    static int ammoSlot(int ordinal) { return AMMO_SLOTS[ordinal]; }
    private static final int[] X = {8, 49, 67, 85, 126, 8, 31, 103, 49, 67, 85, 31, 49, 67, 85};
    private static final int[] Y = {36, 36, 36, 36, 36, 72, 72, 36, 72, 72, 72, 90, 90, 90, 90};
    private final SimpleContainer display = new SimpleContainer(X.length);
    private final int[] state = new int[16];
    private final DroneEntity drone;
    private final ItemStack controller;
    private final Inventory inventory;
    // Snapshot of the physical stacks represented by this view. Unknown overflow is preserved.
    private List<ItemStack> backing = List.of();
    private List<ItemStack> overflow = List.of();
    private boolean editing;


    TerminalMenu(int id, Inventory inventory) { this(id, inventory, null, ItemStack.EMPTY); }

    TerminalMenu(int id, Inventory inventory, DroneEntity drone, ItemStack controller) {
        super(TerminalMenus.TERMINAL.get(), id);
        this.inventory = inventory;
        this.drone = drone;
        this.controller = controller;
        for (int i = 0; i < X.length; i++) addDisplay(display, i, X[i], Y[i]);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addDisplay(inventory, 9 + row * 9 + col, 8 + 18 * col, 144 + row * 18);
        for (int col = 0; col < 9; col++) addDisplay(inventory, col, 8 + 18 * col, 202);
        for (int i = 0; i < state.length; i++) addDataSlot(DataSlot.shared(state, i));
        syncDisplay();
    }

    private void addDisplay(Container source, int index, int x, int y) {
        if (source != display) { addSlot(new Slot(source, index, x, y)); return; }
        addSlot(new Slot(source, index, x, y) {
            @Override public boolean isActive() {
                if (hasItem()) return true; // Permit recovery after a data pack reduces capacity.
                for (int i = 0; i < AMMO_SLOTS.length; i++) if (index == AMMO_SLOTS[i]) return i < ammoSlots();
                int module = index == 7 ? 4 : index;
                return index == 4 || module < 1 || module > 4 || module <= moduleSlots();
            }
            @Override public int getMaxStackSize() { return ammo(index) ? super.getMaxStackSize() : 1; }
            @Override public boolean mayPlace(ItemStack part) {
                if (!isActive() || part.isEmpty() || !kindMatches(index, part)) return false;
                if (drone == null) return true; // Server data pack decides the actual installation rules.
                return permitted(Hardware.installError(controller, part, partsExcept(index)));
            }
            @Override public boolean mayPickup(Player player) {
                return drone == null || permitted(Hardware.removalError(getItem(), partsExcept(-1)));
            }
        });
    }
    private static boolean ammo(int i) { return i == 6 || i >= 8; }
    private static boolean kindMatches(int i, ItemStack part) {
        if (ammo(i)) return CombatSlots.isAmmo(part);
        return switch (i) {
            case 0 -> !Hardware.chipId(part).isEmpty();
            case 4 -> Batteries.model(part) != null;
            case 5 -> CombatSlots.isGun(part);
            default -> part.is(Hardware.MODULE.get());
        };
    }
    private boolean permitted(String error) {
        if (error.isEmpty()) return true;
        if (editing) state[5] = Math.max(0, MESSAGES.indexOf(error));
        return false;
    }
    private List<ItemStack> partsExcept(int excluded) {
        var result = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < X.length; i++) if (i != excluded && !display.getItem(i).isEmpty()) result.add(display.getItem(i));
        result.addAll(overflow);
        return result;
    }
    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }
    private static int[] indices(List<ItemStack> contents) {
        int[] mapping = new int[X.length];
        Arrays.fill(mapping, -1);
        int module = 1, ammo = 0;
        for (int i = 0; i < contents.size(); i++) {
            ItemStack part = contents.get(i);
            int slot;
            if (part.is(Hardware.MODULE.get())) {
                if (module > 4) continue;
                slot = module == 4 ? 7 : module;
                module++;
            } else if (Hardware.batteryTier(part) > 0) slot = 4;
            else if (CombatSlots.isGun(part)) slot = 5;
            else if (CombatSlots.isAmmo(part)) {
                if (ammo == AMMO_SLOTS.length) continue;
                slot = AMMO_SLOTS[ammo++];
            }
            else slot = 0;
            if (mapping[slot] == -1) mapping[slot] = i;
        }
        return mapping;
    }

    private void syncDisplay() {
        if (drone == null) return;
        List<ItemStack> parts = Hardware.contents(controller);
        if (!same(parts, backing)) {
            int[] mapping = indices(parts);
            var used = new boolean[parts.size()];
            for (int i = 0; i < mapping.length; i++) {
                int index = mapping[i];
                if (index >= 0) used[index] = true;
                display.setItem(i, index < 0 ? ItemStack.EMPTY : parts.get(index).copy());
            }
            var rest = new java.util.ArrayList<ItemStack>();
            for (int i=0; i<used.length; i++) if (!used[i]) rest.add(parts.get(i).copy());
            overflow = rest;
            backing = parts.stream().map(ItemStack::copy).toList();
        }
        // Vanilla container data packets can carry signed shorts. Keep the full FE range.
        int energy = Hardware.energy(controller), capacity = Hardware.capacity(controller);
        state[0] = energy & 0xffff;
        state[1] = energy >>> 16;
        state[2] = capacity & 0xffff;
        state[3] = capacity >>> 16;
        state[4] = Math.max(0, PAINTS.indexOf(Frames.paint(controller)));
        Frames.Frame frame = Frames.of(controller);
        state[6] = frame.moduleSlots();
        state[7] = Frames.IDS.indexOf(frame.id());
        state[8] = frame.maxBatteryTier();
        state[13] = Frames.ammoSlots(frame.id());
        // 0 = no carry module, -1 = fitted but offline, otherwise the slot count it exposes.
        int cargo = CarryLight.slots(controller);
        state[14] = cargo <= 0 ? 0 : CarryLight.available(controller) ? cargo : -1;
        state[15] = DroneRemote.barksOn(controller) ? 1 : 0;
        var sample = drone.telemetry;
        int drain = sample == null ? 0 : sample.consumed(), solar = sample == null ? 0 : sample.generated();
        state[9] = drain & 0xffff; state[10] = drain >>> 16;
        state[11] = solar & 0xffff; state[12] = solar >>> 16;
    }

    public int ammoSlots() { return state[13]; }
    static int firstInventorySlot() { return X.length; }
    public int consumed() { return (state[10] << 16) | (state[9] & 0xffff); }
    public int generated() { return (state[12] << 16) | (state[11] & 0xffff); }
    public int moduleSlots() { return state[6]; }
    public String frameId() { return Frames.IDS.get(state[7]); }
    public int maxBatteryTier() { return state[8]; }
    public int energy() { return (state[1] << 16) | (state[0] & 0xffff); }
    public int capacity() { return (state[3] << 16) | (state[2] & 0xffff); }
    public int paint() { return state[4]; }
    /** 0 = no carry module, -1 = fitted but offline, otherwise the slot count of the hold. */
    public int cargoState() { return state[14]; }
    /** Personality chip barks stay on by default; the switch only silences them. */
    public boolean barksOn() { return state[15] != 0; }
    public String feedback() { return MESSAGES.get(state[5]); }

    /**
     * Panel buttons are requests, never writes: the owner, the distance and the identity of the
     * terminal are all checked here again before anything on the item changes.
     */
    @Override public boolean clickMenuButton(Player player, int id) {
        if (drone == null || !(player instanceof ServerPlayer server)
                || server.containerMenu != this || !stillValid(player)) return false;
        if (id == BUTTON_BARKS) {
            DroneRemote.toggleBarks(controller);
            syncDisplay();
            broadcastChanges();
            return true;
        }
        if (id != BUTTON_CARGO) return false;
        String refusal = TerminalMenus.cargoRefusal(server, drone, controller);
        if (!refusal.isEmpty()) {
            server.displayClientMessage(Component.translatable("terminal.dronepal.cargo." + refusal), true);
            return false;
        }
        DroneCombat.flush(drone);
        TerminalMenus.openCargo(server, drone, controller);
        return true;
    }

    @Override public void broadcastChanges() { if (!editing) syncDisplay(); super.broadcastChanges(); }

    @Override public boolean stillValid(Player player) {
        return player == inventory.player && (player.level().isClientSide || player instanceof ServerPlayer server && TerminalMenus.authorized(server, drone, controller));
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot source = getSlot(index);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem(), original = stack.copy();
        if (index < X.length) {
            if (!moveItemStackTo(stack, X.length, slots.size(), false)) {
                permitted("inventory_full");
                return ItemStack.EMPTY;
            }
        } else {
            // Move through legal slots only. Vanilla range merging skips mayPlace on occupied slots.
            for (int pass=0; pass<2 && !stack.isEmpty(); pass++) {
                for (int i=0; i<X.length && !stack.isEmpty(); i++) {
                    Slot target = getSlot(i);
                    if (target.hasItem() != (pass == 0) || !target.mayPlace(stack)) continue;
                    target.safeInsert(stack);
                }
            }
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY); else source.setChanged();
        source.onTake(player, stack);
        return original;
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot < -1 && slot != -999 || slot >= slots.size()) return;
        if (drone == null) { super.clicked(slot,button,type,player); return; }
        if (!(player instanceof ServerPlayer server) || server.containerMenu != this || !stillValid(player)) return;
        DroneCombat.flush(drone);
        ItemStack previous = slot >= 0 && slot < X.length ? display.getItem(slot).copy() : ItemStack.EMPTY;
        syncDisplay();
        if (slot >= 0 && slot < X.length && !ItemStack.matches(previous, display.getItem(slot))) {
            broadcastFullState();
            return;
        }
        // Native drag distribution validates its entire candidate set before depositing items.
        // Capture the cursor and slots so a same-family multi-drop can be rejected atomically.
        List<ItemStack> before = java.util.stream.IntStream.range(0, X.length).mapToObj(i -> display.getItem(i).copy()).toList();
        var inventoryBefore = type == ClickType.QUICK_CRAFT ? inventory.items.stream().map(ItemStack::copy).toList() : List.<ItemStack>of();
        var carriedBefore = getCarried().copy();
        editing = true;
        try {
            super.clicked(slot,button,type,player);
            List<ItemStack> contents = partsExcept(-1);
            if (!same(contents, backing)) {
                if (type == ClickType.QUICK_CRAFT) {
                    for (int i=0; i<X.length; i++) {
                        ItemStack after = display.getItem(i);
                        if (after.isEmpty() || ItemStack.matches(before.get(i), after)) continue;
                        if (!permitted(Hardware.installError(controller, after, partsExcept(i)))) {
                            for (int j=0; j<X.length; j++) display.setItem(j,before.get(j));
                            for (int j=0; j<inventoryBefore.size(); j++) inventory.setItem(j,inventoryBefore.get(j));
                            setCarried(carriedBefore);
                            broadcastFullState();
                            return;
                        }
                    }
                }
                Hardware.save(controller,contents);
                backing = contents.stream().map(ItemStack::copy).toList();
                DroneCombat.refresh(drone);
            }
        } finally { editing = false; }
        broadcastChanges();
    }
}
