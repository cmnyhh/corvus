package dev.dronepal;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Assembly bench menu. Blueprints are picked by list index ({@link #SELECT} + index) with 0/1 as a
 * keyboard/button fallback; the panel geometry lives here so the screen cannot drift from the slots.
 */
public final class AssemblyMenu extends AbstractContainerMenu {
    /** Preview slot. Inputs are 0..8 and the output slot is 9. */
    public static final int PREVIEW = 10;
    public static final int INPUT_COUNT = 9;
    /** Inventory-button id for direct selection: {@code SELECT + index}. 0/1 = previous/next, 2 = start. */
    public static final int SELECT = 100;
    public static final int PANEL_WIDTH = 320, PANEL_HEIGHT = 232;
    /** Blueprint list, in the same panel space as slot coordinates. Must stay clear of every slot. */
    public static final int LIST_X = 8, LIST_Y = 22, LIST_WIDTH = 160, LIST_ROWS = 6, ROW_HEIGHT = 18;
    private final AssemblyStation station;
    private final SimpleContainer preview = new SimpleContainer(1);
    private final ContainerData data = new SimpleContainerData(4);

    AssemblyMenu(int id, Inventory player) { this(id, player, null); }
    AssemblyMenu(int id, Inventory player, AssemblyStation station) {
        super(SurvivalWorks.MENU.get(), id);
        this.station = station;
        // Catalog first, so the screen already has names/icons when the open-screen packet lands.
        if (station != null && player.player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, SurvivalWorks.catalog());
        }
        var container = station == null ? new SimpleContainer(10) : station.inventory;
        for (int i = 0; i < INPUT_COUNT; i++) addSlot(new Slot(container, i, 176 + 18 * (i % 3), 152 + 18 * (i / 3)));
        addSlot(new Slot(container, AssemblyStation.OUTPUT, 244, 170) { @Override public boolean mayPlace(ItemStack stack) { return false; } });
        addSlot(new Slot(preview, 0, 176, 22) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player p) { return false; }
        });
        for (int i = 0; i < 27; i++) addSlot(new Slot(player, i+9, 8+18*(i%9), 152+18*(i/9)));
        for (int i = 0; i < 9; i++) addSlot(new Slot(player, i, 8+18*i, 210));
        addDataSlots(data);
        if (station != null && station.plan() == null && !station.busy() && !SurvivalWorks.plans.isEmpty()) station.select(0);
        refresh();
    }
    private void refresh() {
        if (station == null) return;
        var plan = station.plan();
        data.set(0, station.elapsed);
        data.set(1, station.busy() ? station.duration : plan == null ? 0 : plan.ticks());
        data.set(2, station.busy() ? 1 : 0);
        data.set(3, station.selectedIndex() + 1);
        var icon = plan == null ? ItemStack.EMPTY : plan.icon(station.inventory);
        if (!ItemStack.matches(preview.getItem(0), icon)) preview.setItem(0, icon);
    }
    public int elapsed() { return data.get(0); }
    public int duration() { return data.get(1); }
    public boolean busy() { return data.get(2) != 0; }
    public int selection() { return data.get(3); }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean stillValid(Player player) {
        return station == null || !station.isRemoved() && player.level() == station.getLevel() && player.level().getBlockEntity(station.getBlockPos()) == station && player.distanceToSqr(station.getBlockPos().getCenter()) <= 64;
    }
    @Override public boolean clickMenuButton(Player player, int button) {
        if (station == null || player.containerMenu != this || !stillValid(player) || player.isSpectator()) return false;
        // A running job owns its reserved materials and its plan, so nothing may move it.
        if (station.busy()) return false;
        if (button == 2) {
            if (station.plan() == null) return false;
            if (!station.start()) player.displayClientMessage(Component.translatable("assembly.dronepal.blocked"), true);
        } else if (button == 0 || button == 1) {
            var plans = SurvivalWorks.plans;
            if (plans.isEmpty()) return false;
            int current = station.selectedIndex();
            int index = Math.floorMod(current + (button == 0 ? -1 : 1), plans.size());
            if (!station.select(index)) return false;
        } else if (button >= SELECT) {
            if (!station.select(button - SELECT)) return false;
        } else return false;
        broadcastChanges();
        return true;
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot != PREVIEW) super.clicked(slot, button, type, player);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || index == PREVIEW) return ItemStack.EMPTY;
        Slot slot = getSlot(index);
        ItemStack source = slot.getItem(), original = source.copy();
        if (source.isEmpty()) return ItemStack.EMPTY;
        boolean moved = index < PREVIEW ? moveItemStackTo(source, PREVIEW+1, slots.size(), true) : moveItemStackTo(source, 0, AssemblyStation.INPUTS, false);
        if (!moved) return ItemStack.EMPTY;
        if (source.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, source);
        return original;
    }
}
