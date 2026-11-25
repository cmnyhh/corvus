package dev.corvus.client;

import dev.corvus.AssemblyMenu;
import dev.corvus.SurvivalWorks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Assembly bench screen: a scrollable, clickable blueprint list (fed by the server's
 * {@code assembly_catalog} packet) with the old ‹/› buttons and arrow keys kept as a fallback.
 *
 * <p>The list is the server's catalog verbatim: the client never loads resource-pack plans, so a
 * dedicated server and its clients always agree on what is craftable.</p>
 */
public final class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {
    private static final int TEXT = 0xe4efe9, DIM = 0xa9c4b5, READY = 0xff7fd89a, SHORT = 0xffd9645a;
    private static final int ROW_H = AssemblyMenu.ROW_HEIGHT, ROWS = AssemblyMenu.LIST_ROWS;
    private static final int LIST_X = AssemblyMenu.LIST_X, LIST_Y = AssemblyMenu.LIST_Y;
    private static final int LIST_W = AssemblyMenu.LIST_WIDTH, LIST_H = ROWS * ROW_H;
    /** Right-hand column: preview, material ledger, progress, start button, inputs, output. */
    private static final int COL_X = 176, COL_W = 136, MATERIAL_Y = 44, PROGRESS_Y = 96, START_Y = 118;
    private static volatile SurvivalWorks.Catalog catalog;

    private Button previous, next, start;
    private int scroll, shownSelection = -1, hoveredRow = -1;
    /** Row the player just clicked, highlighted until the server's answer arrives. -1 = none in flight. */
    private int pendingRow = -1;
    private long pendingAt, deniedAt;
    /** How long an unanswered click keeps the row lit before the screen trusts the menu again. */
    private static final long PENDING_MS = 1000L, DENIED_MS = 1200L;

    public AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = AssemblyMenu.PANEL_WIDTH;
        imageHeight = AssemblyMenu.PANEL_HEIGHT;
    }

    /** Called from the payload handler, always before the menu's open-screen packet. */
    public static void accept(SurvivalWorks.Catalog received) { catalog = received; }

    private static List<SurvivalWorks.Catalog.Entry> entries() {
        SurvivalWorks.Catalog current = catalog;
        return current == null ? List.of() : current.entries();
    }

    private void click(int action) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
    }

    @Override protected void init() {
        super.init();
        previous = arrowButton(268, 3, "‹", -1, "assembly.dronepal.previous");
        next = arrowButton(290, 3, "›", 1, "assembly.dronepal.next");
        start = addRenderableWidget(Button.builder(Component.translatable("assembly.dronepal.start"), b -> click(2))
                .bounds(leftPos + COL_X, topPos + START_Y, COL_W, 18).build());
    }

    private Button arrowButton(int x, int y, String glyph, int delta, String tooltip) {
        Component description = Component.translatable(tooltip);
        return addRenderableWidget(Button.builder(Component.literal(glyph), b -> step(delta))
                .bounds(leftPos + x, topPos + y, 20, 14).tooltip(Tooltip.create(description)).build());
    }

    // ---- selection ----

    /**
     * Sends the clicked row straight through. The menu's {@code selection()} is only a mirror of the
     * server's ContainerData, so right after an earlier click (or on a link with any latency) it still
     * holds the previous row; comparing the click against it swallowed the packet and the row never
     * moved. {@code AssemblyMenu.clickMenuButton} resolves SELECT+index idempotently, so the packet is
     * always safe to send, and the row is lit locally so the click answers immediately.
     */
    private void selectIndex(int index) {
        if (index < 0 || index >= entries().size()) return;
        if (menu.busy()) { deniedAt = Util.getMillis(); return; }
        ensureVisible(index);
        pendingRow = index;
        pendingAt = Util.getMillis();
        click(AssemblyMenu.SELECT + index);
    }

    /** Relative step (‹ › and ↑↓): resolved by the server from its own index, so it cannot go stale. */
    private void step(int delta) {
        pendingRow = -1;
        if (menu.busy()) { deniedAt = Util.getMillis(); return; }
        click(delta < 0 ? 0 : 1);
    }

    /** Highlighted row: the click still in flight, else the server's selection. 0-based, -1 = nothing. */
    private int selectedRow() {
        if (pendingRow >= 0) {
            int server = menu.selection() - 1;
            if (server == pendingRow || Util.getMillis() - pendingAt > PENDING_MS) pendingRow = -1;
            else return pendingRow;
        }
        return menu.selection() - 1;
    }

    /** True briefly after a click was refused because a job owns the blueprint. */
    private boolean deniedNow() { return menu.busy() && Util.getMillis() - deniedAt < DENIED_MS; }

    private void ensureVisible(int index) {
        int max = Math.max(0, entries().size() - ROWS);
        if (index < scroll) scroll = index;
        else if (index >= scroll + ROWS) scroll = index - ROWS + 1;
        scroll = Mth.clamp(scroll, 0, max);
    }

    private void followSelection() {
        int selection = menu.selection() - 1;
        if (selection == shownSelection) return;
        shownSelection = selection;
        if (selection >= 0) ensureVisible(selection);
    }

    private int rowAt(double mouseX, double mouseY) {
        double lx = leftPos + LIST_X, ly = topPos + LIST_Y;
        if (mouseX < lx || mouseX >= lx + LIST_W || mouseY < ly || mouseY >= ly + LIST_H) return -1;
        int index = scroll + (int) ((mouseY - ly) / ROW_H);
        return index < entries().size() ? index : -1;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int row = rowAt(mouseX, mouseY);
        if (button == 0 && row >= 0) {
            selectIndex(row);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int size = entries().size();
        if (rowAt(mouseX, mouseY) >= 0 && size > ROWS) {
            int notches = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
            if (notches != 0) {
                scroll = Mth.clamp(scroll + notches, 0, size - ROWS);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int size = entries().size();
        if (size > 0) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> { step(-1); return true; }
                case GLFW.GLFW_KEY_DOWN -> { step(1); return true; }
                case GLFW.GLFW_KEY_PAGE_UP -> { scroll = Math.max(0, scroll - ROWS); return true; }
                case GLFW.GLFW_KEY_PAGE_DOWN -> { scroll = Mth.clamp(scroll + ROWS, 0, Math.max(0, size - ROWS)); return true; }
                default -> { }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- material ledger (client view of the server's ingredient list) ----
    private int owned(SurvivalWorks.Catalog.Ingredient ingredient) {
        int count = 0;
        for (int i = 0; i < AssemblyMenu.INPUT_COUNT; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (ingredient.matches(stack)) count += stack.getCount();
        }
        return count;
    }

    private boolean ready(SurvivalWorks.Catalog.Entry entry) {
        for (var ingredient : entry.ingredients()) if (owned(ingredient) < ingredient.count()) return false;
        return true;
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        followSelection();
        hoveredRow = rowAt(mouseX, mouseY);
        start.active = !menu.busy() && selectedRow() >= 0;
        start.setMessage(Component.translatable(menu.busy() ? "assembly.dronepal.working" : "assembly.dronepal.start"));
        previous.active = next.active = !menu.busy();
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
        rowTooltip(g, mouseX, mouseY);
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xff486458);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xff27382f);
        g.fill(x + 4, y + 18, x + imageWidth - 4, y + 19, 0xff3c5a4b);
        g.fill(x + 170, y + 21, x + 171, y + 213, 0xff3c5a4b);
        int lx = x + LIST_X, ly = y + LIST_Y;
        g.fill(lx - 1, ly - 1, lx + LIST_W + 1, ly + LIST_H + 1, 0xff638173);
        g.fill(lx, ly, lx + LIST_W, ly + LIST_H, 0xff16251f);
        rows(g, lx, ly);
        if (menu.busy()) g.fill(lx, ly, lx + LIST_W, ly + LIST_H, 0x9a0f1b16);
        // A refused click has to answer too: the list gets a red frame for a moment.
        if (deniedNow()) {
            g.fill(lx - 1, ly - 1, lx + LIST_W + 1, ly, SHORT);
            g.fill(lx - 1, ly + LIST_H, lx + LIST_W + 1, ly + LIST_H + 1, SHORT);
            g.fill(lx - 1, ly - 1, lx, ly + LIST_H + 1, SHORT);
            g.fill(lx + LIST_W, ly - 1, lx + LIST_W + 1, ly + LIST_H + 1, SHORT);
        }
        for (var slot : menu.slots) {
            int sx = x + slot.x, sy = y + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xff638173);
            g.fill(sx, sy, sx + 16, sy + 16, 0xff16251f);
        }
        g.fill(x + COL_X, y + PROGRESS_Y, x + COL_X + COL_W, y + PROGRESS_Y + 8, 0xff15251d);
        int progress = menu.duration() <= 0 ? 0 : Mth.clamp(COL_W * menu.elapsed() / menu.duration(), 0, COL_W - 2);
        g.fill(x + COL_X + 1, y + PROGRESS_Y + 1, x + COL_X + 1 + progress, y + PROGRESS_Y + 7, 0xff83dda0);
    }

    private void rows(GuiGraphics g, int lx, int ly) {
        var entries = entries();
        int selection = selectedRow();
        for (int row = 0; row < ROWS; row++) {
            int index = scroll + row;
            if (index >= entries.size()) break;
            int ry = ly + row * ROW_H;
            boolean selected = index == selection;
            if (selected) {
                g.fill(lx, ry, lx + LIST_W, ry + ROW_H - 1, 0xff33513f);
                g.fill(lx, ry, lx + 2, ry + ROW_H - 1, 0xff83dda0);
            } else if (index == hoveredRow && !menu.busy()) {
                g.fill(lx, ry, lx + LIST_W, ry + ROW_H - 1, 0x22ffffff);
            }
            if (row > 0) g.fill(lx + 2, ry - 1, lx + LIST_W - 6, ry, 0xff203529);
            var entry = entries.get(index);
            boolean ready = ready(entry);
            g.renderItem(entry.icon(), lx + 4, ry + 1);
            g.drawString(font, font.plainSubstrByWidth(entry.icon().getHoverName().getString(), LIST_W - 40),
                    lx + 24, ry + 5, selected ? 0xffffffff : TEXT, false);
            g.fill(lx + LIST_W - 12, ry + 6, lx + LIST_W - 7, ry + 11, ready ? READY : SHORT);
        }
        int size = entries.size();
        if (size > ROWS) {
            int trackX = lx + LIST_W - 4, trackY = ly + 2, trackH = LIST_H - 4;
            g.fill(trackX, trackY, trackX + 2, trackY + trackH, 0xff0d1813);
            int thumbH = Math.max(12, trackH * ROWS / size);
            int max = size - ROWS;
            int thumbY = trackY + (trackH - thumbH) * Mth.clamp(scroll, 0, max) / max;
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xff739f89);
        }
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var entries = entries();
        int index = selectedRow();
        g.drawString(font, title, 8, 6, TEXT, false);
        g.drawString(font, Component.translatable("assembly.dronepal.catalog_size", entries.size()), 178, 6, DIM, false);
        if (entries.isEmpty())
            g.drawString(font, Component.translatable("assembly.dronepal.no_catalog"), LIST_X + 6, LIST_Y + 6, SHORT, false);
        g.drawString(font, Component.translatable(menu.busy() ? "assembly.dronepal.busy_hint" : "assembly.dronepal.list_hint"),
                LIST_X + 2, 132, deniedNow() ? SHORT : DIM, false);
        g.drawString(font, playerInventoryTitle, 8, 144, TEXT, false);

        ItemStack preview = menu.getSlot(AssemblyMenu.PREVIEW).getItem();
        if (!preview.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(preview.getHoverName().getString(), COL_W - 14), COL_X + 22, 26, TEXT, false);
        }
        materials(g, index, entries);
        int seconds = Math.max(0, (menu.duration() - menu.elapsed() + 19) / 20);
        g.drawString(font, Component.translatable("assembly.dronepal.seconds", seconds), COL_X, PROGRESS_Y + 11, TEXT, false);
        g.drawString(font, Component.translatable("assembly.dronepal.materials"), COL_X, START_Y + 22, DIM, false);
        g.drawString(font, Component.translatable("assembly.dronepal.output"), 244, 158, DIM, false);
    }

    /** The ledger comes from the server catalog; an empty catalog simply draws nothing. */
    private void materials(GuiGraphics g, int index, List<SurvivalWorks.Catalog.Entry> entries) {
        if (index < 0 || index >= entries.size()) return;
        int y = MATERIAL_Y;
        for (var ingredient : entries.get(index).ingredients()) {
            if (y > MATERIAL_Y + 48) break;
            int owned = owned(ingredient);
            Component line = Component.translatable("assembly.dronepal.material", ingredient.display().getHoverName(),
                    owned, ingredient.count());
            g.drawString(font, font.plainSubstrByWidth(line.getString(), COL_W), COL_X, y,
                    owned >= ingredient.count() ? READY : SHORT, false);
            y += 10;
        }
    }

    private void rowTooltip(GuiGraphics g, int mouseX, int mouseY) {
        var entries = entries();
        if (hoveredRow < 0 || hoveredRow >= entries.size()) return;
        var entry = entries.get(hoveredRow);
        List<Component> lines = new ArrayList<>();
        lines.add(entry.icon().getHoverName().copy().withStyle(ChatFormatting.WHITE));
        for (var ingredient : entry.ingredients()) {
            int owned = owned(ingredient);
            lines.add(Component.translatable("assembly.dronepal.material", ingredient.display().getHoverName(),
                    owned, ingredient.count())
                    .withStyle(owned >= ingredient.count() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }
}
