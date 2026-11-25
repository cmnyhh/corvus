package dev.corvus.client;

import dev.corvus.Corvus;
import dev.corvus.DroneEntity;
import dev.corvus.DroneRemote;
import dev.corvus.Frames;
import dev.corvus.Hardware;
import dev.corvus.HudOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * The upper-left drone status block: frame, follow/hold state, battery with a low-charge red, and how
 * many fitted modules are online.
 *
 * <p>Where the numbers come from matters. 1.20.1 has no {@code DroneTelemetry} packet, so the HUD
 * reads the controller that Curios has already synced to this client (frame, battery, fitted modules)
 * and uses the wheel's status answers for what only the server knows: signal, the measured FE/t
 * ({@link DroneRemote.Status#drain()}), the photovoltaic output for the current weather and the
 * per-module switches. Nothing is invented — with no readable controller the block degrades to the
 * module/signal summary instead of printing a fake percentage, and a status without a drain figure
 * simply omits the rate.</p>
 *
 * <p>Registered as a Forge GUI overlay (1.20.1 has {@code RegisterGuiOverlaysEvent} instead of 1.21's
 * {@code RegisterGuiLayersEvent}) and gated by {@link HudOptions#hudEnabled()}.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DroneHud {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-hud");
    private static final String CURIO_SLOT = "drone";
    private static final int TEXT = 0xfff2f4f6, DIM = 0xffe0e4e8, GOOD = 0xffa4ddbe, LOW = 0xffff8079;
    /** Client-side estimate of the grid charge rate, sampled once per second. */
    private static int lastEnergy, chargeRate;
    private static long lastSample;
    private static final int BOX = 0xb51c2024, TRACK = 0xff57616a;

    private static ItemStack worn = ItemStack.EMPTY;
    private static int refreshTicks;
    private static boolean sourceLogged;
    /** Whether {@link #worn} has been read at least once, and whether Curios could be read at all. */
    private static boolean refreshed, readable = true;

    private DroneHud() {}

    /**
     * The wheel's no-drone gate: {@code TRUE} controller visible, {@code FALSE} positively no
     * controller, {@code null} unknown (not read yet, or Curios could not answer).
     */
    static Boolean controllerWorn() {
        if (!refreshed || !readable) return null;
        return !worn.isEmpty();
    }

    // ------------------------------------------------------------------ overlay

    @SubscribeEvent
    static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "drone_status",
                (gui, graphics, partialTick, screenWidth, screenHeight) -> draw(graphics));
    }

    /** Called from the wheel's client tick; re-reads the worn controller once a second. */
    static void tick() {
        if (++refreshTicks < 10) return;
        refreshTicks = 0;
        worn = equipped();
        refreshed = true;
        if (!worn.isEmpty() && !sourceLogged) {
            sourceLogged = true;
            LOGGER.info("[corvus-hud] controller read from the synced Curios slot: frame={} paint={} energy={}/{} modules={}",
                    Frames.id(worn), Frames.paint(worn), Hardware.energy(worn), Hardware.capacity(worn),
                    Hardware.installed(worn).size());
        }
    }

    /** The worn controller as this client sees it (Curios syncs the stacks of the owner). */
    private static ItemStack equipped() {
        Minecraft game = Minecraft.getInstance();
        if (game.player == null) return ItemStack.EMPTY;
        try {
            return CuriosApi.getCuriosInventory(game.player).resolve()
                    .flatMap(inventory -> inventory.getStacksHandler(CURIO_SLOT))
                    .map(handler -> {
                        var stacks = handler.getStacks();
                        for (int i = 0; i < stacks.getSlots(); i++) {
                            ItemStack stack = stacks.getStackInSlot(i);
                            if (stack.is(Corvus.DRONE.get())) return stack;
                        }
                        return ItemStack.EMPTY;
                    })
                    .orElse(ItemStack.EMPTY);
        } catch (RuntimeException | LinkageError missing) {
            readable = false;
            if (!sourceLogged) {
                sourceLogged = true;
                LOGGER.warn("[corvus-hud] the worn controller is not readable client-side ({}); "
                        + "falling back to the server's status summary", missing.toString());
            }
            return ItemStack.EMPTY;
        }
    }

    /** True when this client is drawing a live drone of its own; proves a controller is fitted. */
    static boolean deployedVisible() {
        return droneInWorld(Minecraft.getInstance());
    }

    /** True when this client can see a live drone of its own, used when no status has arrived yet. */
    private static boolean droneInWorld(Minecraft game) {
        if (game.level == null || game.player == null) return false;
        for (Entity entity : game.level.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone && drone.isAlive()
                    && game.player.getUUID().equals(drone.ownerId())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing

    private static Component text(String key, Object... args) {
        return Component.translatable("hud.dronepal." + key, args);
    }

    private static void draw(GuiGraphics g) {
        Minecraft game = Minecraft.getInstance();
        if (!HudOptions.hudEnabled() || game.player == null || game.level == null) return;
        if (game.options.hideGui || game.screen != null || game.options.renderDebug) return;
        if (!game.player.isAlive() || game.player.isSpectator()) return;
        DroneRemote.Status state = DroneWheel.status();
        ItemStack stack = worn;
        boolean held = stack != null && !stack.isEmpty();
        if (!held && (state == null || state.enabled() == 0)) return;

        boolean signal = state != null ? state.signal() : droneInWorld(game);
        boolean holding = held ? DroneRemote.disabled(stack, "follow")
                : state != null && (state.off() & 1) != 0;

        Component header;
        if (held) {
            Component where = signal ? text(holding ? "holding" : "following") : Component.translatable("dronepal.wheel.signal");
            header = Component.empty().append(Frames.name(Frames.id(stack))).append(" · ").append(where);
        } else {
            header = Component.translatable(signal ? "hud.dronepal.following" : "dronepal.wheel.signal");
        }

        int capacity = held ? Hardware.capacity(stack) : 0;
        int energy = held ? Hardware.energy(stack) : 0;
        boolean low = capacity > 0 && energy * 5L < capacity;
        // No readable cell means no percentage; the panel never guesses a charge level.
        Component power = capacity <= 0 ? text("no_battery") : text(low ? "low" : "charge", energy * 100L / capacity);
        // The rate is the server's own measurement (online modules' drain_watts after vendor traits);
        // without one the block stays silent instead of printing a zero.
        if (state != null && state.drain() >= 0) {
            power = power.copy().append("   ").append(text("rate", Component.literal(Integer.toString(state.drain()))));
        }
        if (held && game.level != null && game.player != null) {
            // TODO(grid): in-grid / charging / full state for the deployed drone's power readout.
            boolean onGrid = dev.corvus.power.grid.client.TowerClientState.covered(game.level, game.player.blockPosition());
            Component gridText;
            if (!onGrid) {
                gridText = text("grid_offline");
                lastEnergy = energy;
                lastSample = game.level.getGameTime();
                chargeRate = 0;
            } else {
                long now = game.level.getGameTime();
                if (now - lastSample >= 20) {
                    chargeRate = Math.max(0, energy - lastEnergy);
                    lastEnergy = energy;
                    lastSample = now;
                }
                gridText = capacity > 0 && energy >= capacity ? text("grid_full")
                        : chargeRate > 0 ? text("grid_charging", chargeRate) : text("grid_online");
            }
            power = power.copy().append("   ").append(gridText);
        }

        int fitted = 0, online = 0;
        if (held) {
            for (Hardware.Module module : Hardware.installed(stack)) {
                fitted++;
                if (Hardware.online(stack, module.family())) online++;
            }
        } else if (state != null) {
            // Bits 1..7 are the module slots; the count is all the server exposes without energy.
            fitted = Integer.bitCount(state.enabled() & ~1);
            online = fitted;
        }
        Component summary = text("modules", online, fitted);
        if (state != null && state.solar() >= 0) summary = summary.copy().append(" · ").append(text("generation", state.solar()));

        var font = game.font;
        int width = Math.max(font.width(header), Math.max(font.width(power), font.width(summary)));
        int x = 6, y = 6;
        g.fill(x, y, x + width + 23, y + 41, BOX);
        GuiIcons.draw(g, holding ? "wheel_hold" : "wheel_follow", x + 3, y + 2, 11);
        GuiIcons.draw(g, "wheel_energy", x + 3, y + 14, 11);
        GuiIcons.draw(g, modeIcon(state), x + 3, y + 29, 11);
        g.drawString(font, header, x + 18, y + 3, TEXT, false);
        g.drawString(font, power, x + 18, y + 15, low ? LOW : GOOD, false);
        g.fill(x + 18, y + 25, x + 18 + width, y + 27, TRACK);
        if (capacity > 0) {
            g.fill(x + 18, y + 25, x + 18 + (int) (width * (long) energy / capacity), y + 27, low ? LOW : GOOD);
        }
        g.drawString(font, summary, x + 18, y + 30, DIM, false);
    }

    /** The engage-mode icon when a combat module is fitted, otherwise the generic module sprite. */
    private static String modeIcon(DroneRemote.Status state) {
        if (state == null || (state.enabled() & 4) == 0) return "slot_module";
        return GuiIcons.mode(state.labels().get(2).replace("dronepal.engage.", ""));
    }
}
