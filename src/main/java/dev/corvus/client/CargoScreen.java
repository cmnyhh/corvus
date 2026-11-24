package dev.corvus.client;

import dev.corvus.CargoMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The cargo hold as a vanilla chest: one to three rows of slots above the player inventory.
 *
 * <p>The chest art is sliced so the player block always sits directly under the hold, which keeps the
 * well-known look for 9, 12, 18 and 27 slot modules without an extra texture. Wells the fitted module
 * cannot use are painted over instead of being hidden by a second texture.</p>
 */
public final class CargoScreen extends AbstractContainerScreen<CargoMenu> {
    private static final ResourceLocation PANEL = new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int PLAYER_PART_Y = 125, PLAYER_PART_HEIGHT = 97, INK = 0x404040;

    public CargoScreen(CargoMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 114 + menu.rows() * 18;
        inventoryLabelY = imageHeight - 94;
    }

    private Component used() { return Component.translatable("cargo.dronepal.used", count(), menu.capacity()); }

    private int usedX() { return imageWidth - 8 - font.width(used()); }

    /** Occupied slots, so the header reads like a chest ("6 / 9") instead of mixing items and slots. */
    private int count() {
        int used = 0;
        for (int i = 0; i < menu.capacity(); i++) if (!menu.getSlot(i).getItem().isEmpty()) used++;
        return used;
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        int hold = 17 + menu.rows() * 18;
        g.blit(PANEL, leftPos, topPos, 0.0F, 0.0F, imageWidth, hold, 256, 256);
        g.blit(PANEL, leftPos, topPos + hold, 0.0F, (float) PLAYER_PART_Y, imageWidth, PLAYER_PART_HEIGHT, 256, 256);
        // The art is always a full chest row; hide the wells the fitted module cannot use.
        for (int i = menu.capacity(); i < menu.slotCount(); i++) {
            Slot slot = menu.getSlot(i);
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xffc6c6c6);
        }
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        g.drawString(font, used(), usedX(), 6, menu.parked() > 0 ? 0xff932f28 : INK, false);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
        if (menu.parked() > 0 && isHovering(usedX(), 6, font.width(used()), 9, mouseX, mouseY))
            g.renderTooltip(font, Component.translatable("cargo.dronepal.overflow", menu.parked()), mouseX, mouseY);
    }
}
