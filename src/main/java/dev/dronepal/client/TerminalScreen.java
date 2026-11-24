package dev.dronepal.client;

import dev.dronepal.Dronepal;
import dev.dronepal.Frames;
import dev.dronepal.DroneTelemetry;
import dev.dronepal.HudOptions;
import dev.dronepal.TerminalMenu;
import dev.dronepal.TerminalMenus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "textures/gui/terminal.png");
    private static ResourceLocation previews(String frame) { return ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "textures/gui/paint_" + frame + ".png"); }
    private static final int INK = 0x303030;
    private int selected = -1;
    private Button cargo, barks;
    private int cargoState = Integer.MIN_VALUE, barksState = Integer.MIN_VALUE;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 264;
        imageHeight = 226;
        inventoryLabelX = 110;
        inventoryLabelY = 132;
    }
    @SubscribeEvent public static void register(RegisterMenuScreensEvent event) {
        event.register(TerminalMenus.TERMINAL.get(), TerminalScreen::new);
    }
    private static Component text(String key, Object... args) { return Component.translatable("terminal.dronepal." + key, args); }
    private int preview() { return selected < 0 ? menu.paint() : selected; }
    private Component paintName(int index) { return text("paint." + TerminalMenu.PAINTS.get(index)); }
    @Override protected void init() {
        super.init();
        cargoState = Integer.MIN_VALUE;
        barksState = Integer.MIN_VALUE;
        cargo = addRenderableWidget(Button.builder(text("cargo"), b -> press(TerminalMenu.BUTTON_CARGO))
                .bounds(leftPos + 110, topPos + 89, 56, 18).build());
        barks = addRenderableWidget(Button.builder(barksLabel(), b -> press(TerminalMenu.BUTTON_BARKS))
                .bounds(leftPos + 110, topPos + 109, 56, 18).build());
        syncToggles();
        addRenderableWidget(Button.builder(hudButton(), b -> {
            HudOptions.ENABLED.set(!HudOptions.ENABLED.get());
            HudOptions.ENABLED.save();
            b.setMessage(hudButton());
        }).bounds(leftPos + 93, topPos + 3, 73, 17).build());
        arrow(-1, 175, "previous");
        arrow(1, 236, "next");
    }
    private void press(int button) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }
    private Component barksLabel() { return text(menu.barksOn() ? "barks.on" : "barks.off"); }
    /** The server decides whether a hold exists and is powered; the buttons only mirror it. */
    private void syncToggles() {
        int cargoNow = menu.cargoState();
        if (cargo != null && cargoNow != cargoState) {
            cargoState = cargoNow;
            cargo.active = cargoNow > 0;
            cargo.setTooltip(Tooltip.create(cargoNow > 0 ? text("cargo.open", cargoNow) : text(cargoNow < 0 ? "cargo.offline" : "cargo.missing")));
        }
        int barksNow = menu.barksOn() ? 1 : 0;
        if (barks != null && barksNow != barksState) {
            barksState = barksNow;
            barks.setMessage(barksLabel());
            barks.setTooltip(Tooltip.create(text("barks.tip")));
        }
    }
    @Override protected void containerTick() {
        super.containerTick();
        syncToggles();
    }
    private Component hudButton() { return Component.translatable(HudOptions.ENABLED.get() ? "hud.dronepal.enabled" : "hud.dronepal.disabled"); }
    private void arrow(int delta, int x, String key) {
        Component description = text(key);
        addRenderableWidget(Button.builder(Component.literal(delta < 0 ? "<" : ">"),
            b -> selected = Math.floorMod(preview() + delta, TerminalMenu.PAINTS.size()))
            .bounds(leftPos + x, topPos + 117, 21, 18).tooltip(Tooltip.create(description))
            .createNarration(defaultMessage -> description.copy()).build());
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        g.blit(PANEL, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        g.blit(previews(menu.frameId()), leftPos + 177, topPos + 38, 0, preview() * 60, 78, 60, 78, TerminalMenu.PAINTS.size() * 60);
        for (int slot : new int[]{1, 2, 3, 7, 6, 8, 9, 10, 11, 12, 13, 14}) {
            var module = menu.getSlot(slot);
            if (module.isActive()) g.blit(PANEL, leftPos + module.x - 1, topPos + module.y - 1, 7, 35, 18, 18, imageWidth, imageHeight);
        }
        for (int i=0;i<15;i++) {
            var slot=menu.getSlot(i);
            if (!slot.isActive() || slot.hasItem()) continue;
            String icon=switch(i) {
                case 0 -> "slot_chip";
                case 1,2,3,7 -> "slot_module";
                case 4 -> "slot_battery";
                case 5 -> "slot_gun";
                default -> "slot_ammo";
            };
            GuiIcons.draw(g,icon,leftPos+slot.x,topPos+slot.y,16);
        }
        GuiIcons.draw(g,"vendor_"+vendor(),leftPos+175,topPos+3,14);
        GuiIcons.draw(g,"source_"+source(),leftPos+175,topPos+205,14);
        int capacity = menu.capacity();
        int fill = capacity == 0 ? 0 : (int)(59L * menu.energy() / capacity);
        g.fill(leftPos + 106, topPos + 80, leftPos + 106 + fill, topPos + 85, DroneTelemetry.low(menu.energy(), menu.capacity()) ? 0xffb03834 : 0xff47745b);
    }
    private String vendor() { return switch(menu.frameId()) { case "bedrock" -> "ahi"; case "scrap" -> "rust"; default -> "cvs"; }; }
    private String source() { return switch(menu.frameId()) { case "owl", "bedrock" -> "military"; case "scrap" -> "loot"; default -> "craft"; }; }
    private void label(GuiGraphics g, String key, int x, int y) { g.drawString(font, text(key), x, y, INK, false); }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        g.drawString(font, Frames.name(menu.frameId()), 192, 6, INK, false);
        g.drawString(font,text("source."+source()),192,208,INK,false);
        label(g,"chip",8,24); label(g,"modules",49,24); label(g,"battery",126,24);
        label(g,"gun",8,60); label(g,"ammo",31,60); g.drawString(font, DroneTelemetry.rate(menu.consumed()) + " FE/t", 105, 60, INK, false);
        String energy = menu.capacity() == 0 ? "—" : (menu.energy() * 100L / menu.capacity()) + "%";
        g.drawString(font, DroneTelemetry.low(menu.energy(), menu.capacity()) ? Component.translatable("hud.dronepal.low", menu.energy()*100L/menu.capacity()) : Component.literal(energy), 105, 70, DroneTelemetry.low(menu.energy(), menu.capacity()) ? 0xff932f28 : INK, false);
        label(g,"preview",175,24);
        g.drawCenteredString(font, paintName(preview()), 216, 105, 0xffffff);
        g.drawString(font, (preview()+1) + "/" + TerminalMenu.PAINTS.size(), 207, 122, INK, false);
        label(g,"applied",175,144);
        g.drawString(font, paintName(menu.paint()), 175, 156, INK, false);
        g.drawWordWrap(font, text("spray"), 175, 174, 81, INK);
        // The toggle column owns x 108..166 in this band, so the feedback text wraps before it.
        g.drawWordWrap(font, text("message." + menu.feedback()), 8, 111, 96, INK);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
        if (isHovering(175, 3, 14, 14, mouseX, mouseY)) g.renderTooltip(font, Component.translatable("dronepal.vendor."+vendor()),mouseX,mouseY);
        if (isHovering(124, 22, 41, 13, mouseX, mouseY))
            g.renderTooltip(font, text("max_battery", menu.maxBatteryTier()), mouseX, mouseY);
        if (isHovering(105, 60, 61, 27, mouseX, mouseY))
            g.renderTooltip(font, java.util.List.of(text("energy", menu.energy(), menu.capacity()), Component.translatable("hud.dronepal.rate", DroneTelemetry.rate(menu.consumed())), Component.translatable("hud.dronepal.generation", DroneTelemetry.rate(menu.generated()))), java.util.Optional.empty(), mouseX, mouseY);
    }
}
