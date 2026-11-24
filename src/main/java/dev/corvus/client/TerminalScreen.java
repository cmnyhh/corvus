package dev.corvus.client;

import dev.corvus.Corvus;
import dev.corvus.DroneRemote;
import dev.corvus.Frames;
import dev.corvus.HudOptions;
import dev.corvus.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The terminal panel.
 *
 * <p>Every number on this screen comes from {@link TerminalMenu}'s data slots, never from the client's
 * own copy of the controller: the panel is a view of what the server just validated. The two buttons
 * send inventory-button ids; the server re-checks ownership, range and identity before acting.</p>
 */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    private static final ResourceLocation PANEL = new ResourceLocation(Corvus.ID, "textures/gui/terminal.png");
    private static final int INK = 0x303030;
    /** Its own id: the panel button still goes through {@code handleInventoryButtonClick}, the server
     *  answers {@code false} for an id it does not own, and the flip is a client config change. */
    private static final int BUTTON_HUD = 2;
    private int selected = -1;
    private Button cargo, barks, hud;
    private int cargoState = Integer.MIN_VALUE, barksState = Integer.MIN_VALUE, hudState = Integer.MIN_VALUE;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 264;
        imageHeight = 226;
        inventoryLabelX = 110;
        inventoryLabelY = 132;
    }

    private static ResourceLocation previews(String frame) {
        return new ResourceLocation(Corvus.ID, "textures/gui/paint_" + frame + ".png");
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("terminal.dronepal." + key, args);
    }

    private int preview() { return selected < 0 ? menu.paint() : selected; }

    private Component paintName(int index) {
        return text("paint." + TerminalMenu.PAINTS.get(Math.floorMod(index, TerminalMenu.PAINTS.size())));
    }

    @Override protected void init() {
        super.init();
        cargoState = Integer.MIN_VALUE;
        barksState = Integer.MIN_VALUE;
        hudState = Integer.MIN_VALUE;
        hud = addRenderableWidget(Button.builder(hudLabel(), b -> toggleHud())
                .bounds(leftPos + 110, topPos + 3, 56, 14).tooltip(Tooltip.create(Component.translatable("hud.dronepal.option")))
                .build());
        cargo = addRenderableWidget(Button.builder(text("cargo"), b -> press(TerminalMenu.BUTTON_CARGO))
                .bounds(leftPos + 110, topPos + 87, 56, 16).build());
        barks = addRenderableWidget(Button.builder(barksLabel(), b -> press(TerminalMenu.BUTTON_BARKS))
                .bounds(leftPos + 110, topPos + 105, 56, 16).build());
        syncToggles();
        arrow(-1, 175, "previous");
        arrow(1, 236, "next");
    }

    private void press(int button) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    private Component barksLabel() { return text(menu.barksOn() ? "barks.on" : "barks.off"); }

    private Component hudLabel() {
        return Component.translatable(HudOptions.hudEnabled() ? "hud.dronepal.enabled" : "hud.dronepal.disabled");
    }

    /**
     * The HUD switch is a client display setting, so the flip itself is local. It still leaves through
     * the panel's button path first: the server re-checks the click and answers {@code false} for an id
     * it does not own, which keeps one code path for every button on this screen.
     */
    private void toggleHud() {
        press(BUTTON_HUD);
        try {
            HudOptions.ENABLED.set(!HudOptions.hudEnabled());
            HudOptions.SPEC.save();
            hudState = Integer.MIN_VALUE;
        } catch (RuntimeException unloaded) {
            // Client config not loaded; leave the switch alone rather than pretending it worked.
        }
    }

    /** Fitted module count for the panel header; the frame decides how many slots exist. */
    private int installedModules() {
        int count = 0;
        for (int slot : new int[]{1, 2, 3, 7}) {
            if (menu.getSlot(slot).hasItem()) count++;
        }
        return count;
    }

    /** The server decides whether a hold exists and is powered; the buttons only mirror it. */
    private void syncToggles() {
        int cargoNow = menu.cargoState();
        if (cargo != null && cargoNow != cargoState) {
            cargoState = cargoNow;
            cargo.active = cargoNow > 0;
            cargo.setTooltip(Tooltip.create(cargoNow > 0 ? text("cargo.open", cargoNow)
                    : text(cargoNow < 0 ? "cargo.offline" : "cargo.missing")));
        }
        int barksNow = menu.barksOn() ? 1 : 0;
        if (barks != null && barksNow != barksState) {
            barksState = barksNow;
            barks.setMessage(barksLabel());
            barks.setTooltip(Tooltip.create(text("barks.tip")));
        }
        int hudNow = HudOptions.hudEnabled() ? 1 : 0;
        if (hud != null && hudNow != hudState) {
            hudState = hudNow;
            hud.setMessage(hudLabel());
        }
    }

    @Override protected void containerTick() {
        super.containerTick();
        syncToggles();
    }

    private void arrow(int delta, int x, String key) {
        Component description = text(key);
        addRenderableWidget(Button.builder(Component.literal(delta < 0 ? "<" : ">"),
            b -> selected = Math.floorMod(preview() + delta, TerminalMenu.PAINTS.size()))
            .bounds(leftPos + x, topPos + 117, 21, 18).tooltip(Tooltip.create(description))
            .createNarration(defaultMessage -> description.copy()).build());
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        g.blit(PANEL, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, imageWidth, imageHeight);
        g.blit(previews(menu.frameId()), leftPos + 177, topPos + 38, 0.0F, (float) preview() * 60.0F, 78, 60, 78,
                TerminalMenu.PAINTS.size() * 60);
        for (int slot : new int[]{1, 2, 3, 7, 6, 8, 9, 10, 11, 12, 13, 14}) {
            var module = menu.getSlot(slot);
            if (module.isActive())
                g.blit(PANEL, leftPos + module.x - 1, topPos + module.y - 1, 7.0F, 35.0F, 18, 18, imageWidth, imageHeight);
        }
        for (int i = 0; i < 15; i++) {
            var slot = menu.getSlot(i);
            if (!slot.isActive() || slot.hasItem()) continue;
            String icon = switch (i) {
                case 0 -> "slot_chip";
                case 1, 2, 3, 7 -> "slot_module";
                case 4 -> "slot_battery";
                case 5 -> "slot_gun";
                default -> "slot_ammo";
            };
            GuiIcons.draw(g, icon, leftPos + slot.x, topPos + slot.y, 16);
        }
        GuiIcons.draw(g, "vendor_" + vendor(), leftPos + 175, topPos + 3, 14);
        GuiIcons.draw(g, "source_" + source(), leftPos + 175, topPos + 205, 14);
        int capacity = menu.capacity();
        int fill = capacity == 0 ? 0 : (int) (59L * menu.energy() / capacity);
        g.fill(leftPos + 106, topPos + 80, leftPos + 106 + fill, topPos + 85, low() ? 0xffb03834 : 0xff47745b);
    }

    private boolean low() { return menu.capacity() > 0 && menu.energy() * 5L < menu.capacity(); }

    /**
     * FE/t the server just measured for this controller, from the wheel status the client already polls
     * once a second. {@code -1} means the server sent no figure (no controller, or no answer yet); the
     * panel then prints "—" rather than a zero it cannot vouch for.
     */
    private int drain() {
        DroneRemote.Status state = DroneWheel.status();
        return state == null ? -1 : state.drain();
    }

    private String vendor() {
        return switch (menu.frameId()) {
            case "bedrock" -> "ahi";
            case "scrap" -> "rust";
            default -> "cvs";
        };
    }

    private String source() {
        return switch (menu.frameId()) {
            case "owl", "bedrock" -> "military";
            case "scrap" -> "loot";
            default -> "craft";
        };
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawString(font, text(key), x, y, INK, false);
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        g.drawString(font, Frames.name(menu.frameId()), 192, 6, INK, false);
        g.drawString(font, text("source." + source()), 192, 208, INK, false);
        label(g, "chip", 8, 24);
        // Caption plus a count, built from the shared caption + a number so this needs no new lang key.
        // The power figure has its own row below; this one is just how full the module slots are.
        g.drawString(font, text("modules").copy().append(Component.literal(" " + installedModules() + "/" + menu.moduleSlots())),
                49, 24, INK, false);
        label(g, "battery", 126, 24);
        label(g, "gun", 8, 60);
        label(g, "ammo", 31, 60);
        String energy = menu.capacity() == 0 ? "—" : (menu.energy() * 100L / menu.capacity()) + "%";
        g.drawString(font, Component.literal(energy), 105, 70, low() ? 0xff932f28 : INK, false);
        // Drawn from the server's own measurement (online modules' drain_watts after the vendor
        // multiplier), never from the client's copy of the parts. Red while the pack is browning out.
        int drain = drain();
        g.drawString(font, Component.translatable("hud.dronepal.rate", drain < 0 ? "—" : Integer.toString(drain)),
                105, 122, drain < 0 ? INK : low() ? 0xff932f28 : INK, false);
        label(g, "preview", 175, 24);
        g.drawCenteredString(font, paintName(preview()), 216, 105, 0xffffff);
        g.drawString(font, (preview() + 1) + "/" + TerminalMenu.PAINTS.size(), 207, 122, INK, false);
        label(g, "applied", 175, 144);
        g.drawString(font, paintName(menu.paint()), 175, 156, INK, false);
        g.drawWordWrap(font, text("spray"), 175, 174, 81, INK);
        // The toggle column owns x 108..166 in this band, so the feedback text wraps before it.
        g.drawWordWrap(font, text("message." + menu.feedback()), 8, 111, 96, INK);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
        if (isHovering(175, 3, 14, 14, mouseX, mouseY))
            g.renderTooltip(font, Component.translatable("dronepal.vendor." + vendor()), mouseX, mouseY);
        if (isHovering(124, 22, 41, 13, mouseX, mouseY))
            g.renderTooltip(font, text("max_battery", menu.maxBatteryTier()), mouseX, mouseY);
        if (isHovering(105, 60, 61, 20, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(text("energy", menu.energy(), menu.capacity()));
            lines.add(Component.translatable("hud.dronepal.rate", drain() < 0 ? "—" : Integer.toString(drain())));
            if (low())
                lines.add(Component.translatable("hud.dronepal.low", menu.capacity() == 0 ? 0 : menu.energy() * 100L / menu.capacity())
                        .copy().withStyle(ChatFormatting.RED));
            g.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
        }
    }
}
