package dev.dronepal.client;

import dev.dronepal.Dronepal;
import dev.dronepal.mill.OreMillEntity;
import dev.dronepal.mill.OreMillMenu;
import dev.dronepal.mill.OreMillRegistry;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * The mill panel, drawn in the same flat steel-green style as the assembly bench.
 *
 * <p>The panel is laid out as horizontal bands so that no two pieces of text ever share a band: the
 * centred title sits on its own rule, the lamp and the one-line status own the next band, the machine
 * block is split into a left column (three input wells, two upgrade wells, the progress bar with its
 * percentage centred inside) and a right column (water gauge, energy gauge, three output wells), and below
 * them a single information line, the full-width "call the drone" button and the player inventory. Every
 * row starts at a fixed y that is at least 12 px below the previous row's origin, and every string is
 * drawn at an explicit coordinate — nothing is padded with spaces to line up.</p>
 *
 * <p>The three output wells are told apart by their well rim, never by text: green rim = the primary
 * product (concentrate / rare shard), amber rim = the input mineral's coarse by-product, blue rim = the
 * extra rare roll. Hovering a well names its role.</p>
 *
 * <p>Every dynamic value comes from the menu's synced data slots, so the screen never guesses. The lamp
 * is grey without a drone, green while working and yellow whenever the drone is on station but stopped;
 * hovering it names the reason (no power, missing scan module, output full, no water, low battery).</p>
 */
@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OreMillScreen extends AbstractContainerScreen<OreMillMenu> {
    private static final int FRAME = 0xff486458, INNER = 0xff27382f, LINE = 0xff3c5a4b;
    private static final int WELL_EDGE = 0xff638173, WELL = 0xff16251f, WELL_OFF = 0xff33473c;
    /** Output well rims: primary green / by-product amber / extra blue, so the roles read at a glance. */
    private static final int PRIMARY_EDGE = 0xff7fd89a, BYPRODUCT_EDGE = 0xffd9a63c, EXTRA_EDGE = 0xff83a8dd;
    private static final int TEXT = 0xffe4efe9, DIM = 0xffa9c4b5, READY = 0xff7fd89a, SHORT = 0xffd9645a;
    private static final int AMBER = 0xffd9a63c;
    private static final int WATER_COLOR = 0xff4f8fd0, ENERGY_COLOR = 0xff83a8dd;

    // ---- vertical bands (panel-relative y, top of the glyph) -------------------------------------
    private static final int TITLE_Y = 6;                 // centred title, 6 px from the top edge
    private static final int RULE_TITLE_Y = 16;           // 1 px rule under the title
    private static final int LAMP_X = 10, LAMP_Y = 20, LAMP_SIZE = 9;
    private static final int STATUS_Y = 21, STATUS_X = LAMP_X + LAMP_SIZE + 5; // status owns this band
    private static final int RULE_STATUS_Y = 32;          // 1 px rule under the status band
    private static final int WATER_LABEL_Y = 38, WATER_BAR_Y = 50;
    private static final int ENERGY_LABEL_Y = 62, ENERGY_BAR_Y = 74;
    // 72 px keeps a clear 6 px gap to the output wells on its right, so the bar never merges with them.
    private static final int PROGRESS_X = 8, PROGRESS_Y = 86, PROGRESS_W = 72, PROGRESS_H = 10;
    private static final int INFO_Y = 115;                // speed and cost share exactly one line
    private static final int BUTTON_X = 8, BUTTON_Y = 129, BUTTON_W = 160, BUTTON_H = 20;
    private static final int RULE_BAG_Y = 157;            // 8 px under the button
    private static final int BAG_LABEL_Y = 159;           // top-left of the inventory, above the wells

    // ---- horizontal columns -----------------------------------------------------------------------
    private static final int LEFT_X = 8, RIGHT_X = 88, RIGHT_W = 80, BAR_H = 8;

    private Button call;

    public OreMillScreen(OreMillMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = OreMillMenu.PANEL_WIDTH;
        imageHeight = OreMillMenu.PANEL_HEIGHT;
        inventoryLabelY = BAG_LABEL_Y;
    }

    @SubscribeEvent public static void register(RegisterMenuScreensEvent event) {
        event.register(OreMillRegistry.MENU.get(), OreMillScreen::new);
    }

    private static Component text(String key, Object... args) { return Component.translatable("gui.dronepal.ore_mill." + key, args); }

    @Override protected void init() {
        super.init();
        call = addRenderableWidget(Button.builder(text("call"), b -> click(OreMillMenu.BUTTON_CALL_DRONE))
                .bounds(leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H)
                .tooltip(Tooltip.create(text("call.tip")))
                .build());
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private int lampColor() {
        OreMillEntity.Link link = menu.link();
        if (link.green()) return READY;
        if (link.grey()) return 0xff9aa0a6;
        return AMBER;
    }

    /** Green while working, grey without a link, red for a dry tank or a flat battery, amber otherwise. */
    private int statusColor() {
        OreMillEntity.Link link = menu.link();
        if (link.green()) return READY;
        if (link.grey()) return DIM;
        if (link == OreMillEntity.Link.NO_WATER || link == OreMillEntity.Link.NO_POWER) return SHORT;
        return AMBER;
    }

    private boolean waterShort() { return menu.water() < menu.waterPerItem(); }

    private boolean energyShort() { return menu.energy() < menu.energyPerItem(); }

    private int progressPercent() { return menu.duration() <= 0 ? 0 : menu.progress() * 100 / Math.max(1, menu.duration()); }

    private void bar(GuiGraphics g, int x, int y, int width, int height, int value, int max, int color) {
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, WELL_EDGE);
        g.fill(x, y, x + width, y + height, 0xff15251d);
        int filled = max <= 0 ? 0 : Math.clamp((long)width * Math.max(0, value) / max, 0, width);
        if (filled > 0) g.fill(x, y, x + filled, y + height, color);
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, FRAME);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, INNER);
        g.fill(x + 4, y + RULE_TITLE_Y, x + imageWidth - 4, y + RULE_TITLE_Y + 1, LINE);
        g.fill(x + 4, y + RULE_STATUS_Y, x + imageWidth - 4, y + RULE_STATUS_Y + 1, LINE);
        g.fill(x + 4, y + RULE_BAG_Y, x + imageWidth - 4, y + RULE_BAG_Y + 1, LINE);
        for (var slot : menu.slots) {
            int sx = x + slot.x, sy = y + slot.y;
            // A fitted upgrade lights its own well instead of adding a strip that would touch the
            // neighbouring well: the machine slots now sit in two compact rows.
            int edge = WELL_EDGE;
            if (slot.index >= OreMillMenu.OUTPUT_FIRST && slot.index < OreMillMenu.UPGRADE_FIRST) {
                edge = switch (slot.index - OreMillMenu.OUTPUT_FIRST) {
                    case OreMillMenu.OUTPUT_PRIMARY -> PRIMARY_EDGE;
                    case OreMillMenu.OUTPUT_BYPRODUCT -> BYPRODUCT_EDGE;
                    default -> EXTRA_EDGE;
                };
            }
            if (slot.index == OreMillMenu.UPGRADE_FIRST && menu.sieve()) edge = READY;
            if (slot.index == OreMillMenu.UPGRADE_FIRST + 1 && menu.cooling()) edge = READY;
            if (slot.index >= OreMillMenu.UPGRADE_FIRST && edge == WELL_EDGE) edge = WELL_OFF;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, edge);
            g.fill(sx, sy, sx + 16, sy + 16, WELL);
        }
        // Status lamp: grey = no drone, green = working, yellow = on station but stopped.
        g.fill(x + LAMP_X - 1, y + LAMP_Y - 1, x + LAMP_X + LAMP_SIZE + 1, y + LAMP_Y + LAMP_SIZE + 1, WELL_EDGE);
        g.fill(x + LAMP_X, y + LAMP_Y, x + LAMP_X + LAMP_SIZE, y + LAMP_Y + LAMP_SIZE, lampColor());
        bar(g, x + PROGRESS_X, y + PROGRESS_Y, PROGRESS_W, PROGRESS_H, progressPercent(), 100, READY);
        bar(g, x + RIGHT_X, y + WATER_BAR_Y, RIGHT_W, BAR_H, menu.water(), Math.max(1, menu.waterCapacity()),
                waterShort() ? SHORT : WATER_COLOR);
        bar(g, x + RIGHT_X, y + ENERGY_BAR_Y, RIGHT_W, BAR_H, menu.energy(), Math.max(1, menu.buffer()),
                energyShort() ? SHORT : ENERGY_COLOR);
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, (imageWidth - font.width(title)) / 2, TITLE_Y, TEXT, false);
        g.drawString(font, Component.translatable(statusKey()), STATUS_X, STATUS_Y, statusColor(), false);

        int waterPercent = menu.waterCapacity() <= 0 ? 0 : menu.water() * 100 / menu.waterCapacity();
        g.drawString(font, text("water_short", waterPercent), RIGHT_X, WATER_LABEL_Y, waterShort() ? SHORT : DIM, false);
        int energyPercent = menu.buffer() <= 0 ? 0 : menu.energy() * 100 / menu.buffer();
        g.drawString(font, text("energy_short", energyPercent), RIGHT_X, ENERGY_LABEL_Y, energyShort() ? SHORT : DIM, false);

        Component percent = text("percent", progressPercent());
        g.drawString(font, percent, PROGRESS_X + (PROGRESS_W - font.width(percent)) / 2, PROGRESS_Y + 1, TEXT, false);

        Component info = text("info", String.format(Locale.ROOT, "%.1f", menu.speedPercent() / 100.0),
                String.format(Locale.ROOT, "%,d", menu.energyPerItem()));
        g.drawString(font, info, LEFT_X, INFO_Y, menu.speedPercent() > 100 ? READY : TEXT, false);

        g.drawString(font, playerInventoryTitle, LEFT_X, BAG_LABEL_Y, TEXT, false);
    }

    /**
     * The lamp colour follows the drone, but a machine that cannot run still says why. Without a drone
     * there is no link reason, so water and power are checked directly.
     */
    private String statusKey() {
        if (menu.link().grey()) {
            if (menu.water() < menu.waterPerItem()) return "gui.dronepal.ore_mill.status.no_water";
            if (menu.energy() < menu.energyPerItem()) return "gui.dronepal.ore_mill.status.no_power";
        }
        return menu.link().key();
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
        if (isHovering(LAMP_X - 1, LAMP_Y - 1, LAMP_SIZE + 2, LAMP_SIZE + 2, mouseX, mouseY)) {
            g.renderComponentTooltip(font, List.of(Component.translatable(menu.link().key()), text("lamp.tip." + linkName(menu.link()))), mouseX, mouseY);
        } else if (isHovering(RIGHT_X, WATER_LABEL_Y, RIGHT_W, WATER_BAR_Y + BAR_H - WATER_LABEL_Y, mouseX, mouseY)) {
            g.renderComponentTooltip(font, List.of(text("water_tip", menu.water(), menu.waterCapacity(),
                    dev.dronepal.mill.OreMillRules.WATER_PER_ITEM, dev.dronepal.mill.OreMillRules.WATER_PER_ITEM_DRONE)), mouseX, mouseY);
        } else if (isHovering(RIGHT_X, ENERGY_LABEL_Y, RIGHT_W, ENERGY_BAR_Y + BAR_H - ENERGY_LABEL_Y, mouseX, mouseY)) {
            g.renderComponentTooltip(font, List.of(text("energy_tip", menu.energy(), menu.buffer(), menu.energyPerTick())), mouseX, mouseY);
        } else if (isHovering(PROGRESS_X, PROGRESS_Y, PROGRESS_W, PROGRESS_H, mouseX, mouseY)) {
            g.renderComponentTooltip(font, List.of(text("progress", progressPercent())), mouseX, mouseY);
        } else if (hoveredSlot != null && hoveredSlot.index < OreMillMenu.MACHINE_SLOTS) {
            g.renderComponentTooltip(font, List.of(slotHint(hoveredSlot.index)), mouseX, mouseY);
        }
    }

    private static String linkName(OreMillEntity.Link link) {
        return switch (link) {
            case NONE -> "none";
            case OFFLINE -> "offline";
            case WORKING -> "working";
            case IDLE -> "idle";
            case NO_POWER -> "no_power";
            case NO_MODULE -> "no_module";
            case OUTPUT_FULL -> "output_full";
            case NO_WATER -> "no_water";
            case LOW_BATTERY -> "low_battery";
        };
    }

    private Component slotHint(int index) {
        if (index < OreMillMenu.INPUT_COUNT) return text("slot.input");
        if (index < OreMillMenu.UPGRADE_FIRST) {
            return switch (index - OreMillMenu.OUTPUT_FIRST) {
                case OreMillMenu.OUTPUT_PRIMARY -> text("slot.output.primary");
                case OreMillMenu.OUTPUT_BYPRODUCT -> text("slot.output.byproduct");
                default -> text("slot.output.extra");
            };
        }
        return text("slot.upgrade");
    }
}
