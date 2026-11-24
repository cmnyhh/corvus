package dev.corvus;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Menu types and the shift-use entry point for the terminal panel and the cargo hold.
 *
 * <p>1.20.1 registers menus with {@code ForgeRegistries.MENU_TYPES} and binds screens from the client
 * setup event (see {@code client.ScreenRegistry}); there is no {@code RegisterMenuScreensEvent} yet.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class TerminalMenus {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Corvus.ID);
    public static final RegistryObject<MenuType<TerminalMenu>> TERMINAL = MENUS.register("terminal",
            () -> new MenuType<TerminalMenu>(TerminalMenu::new, FeatureFlags.DEFAULT_FLAGS));
    /** The cargo grid has to agree on its row count before the server sends the contents. */
    public static final RegistryObject<MenuType<CargoMenu>> CARGO_ONE = MENUS.register("cargo_one",
            () -> new MenuType<CargoMenu>((id, inventory) -> new CargoMenu(id, inventory, 1), FeatureFlags.DEFAULT_FLAGS));
    public static final RegistryObject<MenuType<CargoMenu>> CARGO_TWO = MENUS.register("cargo_two",
            () -> new MenuType<CargoMenu>((id, inventory) -> new CargoMenu(id, inventory, 2), FeatureFlags.DEFAULT_FLAGS));
    public static final RegistryObject<MenuType<CargoMenu>> CARGO_THREE = MENUS.register("cargo_three",
            () -> new MenuType<CargoMenu>((id, inventory) -> new CargoMenu(id, inventory, 3), FeatureFlags.DEFAULT_FLAGS));
    public static final List<RegistryObject<MenuType<CargoMenu>>> CARGO = List.of(CARGO_ONE, CARGO_TWO, CARGO_THREE);

    private TerminalMenus() {}

    public static void register(IEventBus bus) {
        MENUS.register(bus);
        // HIGHEST + receiveCanceled: several mods bind sneak-use on entities (pick-up / inspect) at
        // normal priority and can consume the click, which made the drone vanish instead of opening
        // the panel.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, TerminalMenus::interact);
    }

    static RegistryObject<MenuType<CargoMenu>> cargoType(int rows) {
        return switch (Mth.clamp(rows, 1, 3)) {
            case 1 -> CARGO_ONE;
            case 2 -> CARGO_TWO;
            default -> CARGO_THREE;
        };
    }

    /**
     * Owner, liveness, dimension, serial and equip state. 1.20.1 has no
     * {@code Player#canInteractWithEntity}, so the interaction range is checked directly.
     */
    static boolean authorized(ServerPlayer player, DroneEntity drone, ItemStack controller) {
        if (drone == null || !drone.isAlive() || !player.isAlive() || player.isSpectator()) return false;
        if (drone.level() != player.level()) return false;
        UUID owner = drone.ownerId();
        if (owner == null || !owner.equals(player.getUUID())) return false;
        if (controller == null || controller.isEmpty()) return false;
        UUID serial = DroneItem.serial(controller);
        if (serial == null || !serial.equals(drone.getUUID())) return false;
        ItemStack equipped = Hardware.controller(player);
        if (equipped.isEmpty() || !ItemStack.matches(equipped, controller)) return false;
        return player.distanceToSqr(drone) <= 64.0D;
    }

    /** Empty when the hold may be used, otherwise the reason shown to the owner. */
    static String cargoRefusal(ServerPlayer player, DroneEntity drone, ItemStack controller) {
        if (!authorized(player, drone, controller)) return "unauthorized";
        if (CarryLight.slots(controller) <= 0) return "missing";
        if (!CarryLight.available(controller)) return "offline";
        return "";
    }

    static void openCargo(ServerPlayer player, DroneEntity drone, ItemStack controller) {
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new CargoMenu(id, inventory, drone, controller),
                Component.translatable("cargo.dronepal.title")));
    }

    /** Opens the hardware panel. Returns false when the caller is not this drone's owner or is out of range. */
    public static boolean openTerminal(ServerPlayer player, DroneEntity drone) {
        ItemStack controller = Hardware.controller(player);
        if (!authorized(player, drone, controller)) return false;
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new TerminalMenu(id, inventory, drone, controller),
                Component.translatable("terminal.dronepal.title")));
        return true;
    }

    private static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof DroneEntity drone) || !event.getEntity().isShiftKeyDown()
                || !event.getItemStack().isEmpty()) return;
        if (event.getEntity() instanceof ServerPlayer player) openTerminal(player, drone);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        event.setCanceled(true);
    }
}
