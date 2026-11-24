package dev.dronepal;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TerminalMenus {
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Dronepal.ID);
    public static final DeferredHolder<MenuType<?>, MenuType<TerminalMenu>> TERMINAL = MENUS.register("terminal",
        () -> new MenuType<>(TerminalMenu::new, FeatureFlags.DEFAULT_FLAGS));
    /** The cargo grid has to agree on its row count before the server sends the contents. */
    public static final DeferredHolder<MenuType<?>, MenuType<CargoMenu>> CARGO_ONE = MENUS.register("cargo_one",
        () -> new MenuType<>((id, inventory) -> new CargoMenu(id, inventory, 1), FeatureFlags.DEFAULT_FLAGS));
    public static final DeferredHolder<MenuType<?>, MenuType<CargoMenu>> CARGO_TWO = MENUS.register("cargo_two",
        () -> new MenuType<>((id, inventory) -> new CargoMenu(id, inventory, 2), FeatureFlags.DEFAULT_FLAGS));
    public static final DeferredHolder<MenuType<?>, MenuType<CargoMenu>> CARGO_THREE = MENUS.register("cargo_three",
        () -> new MenuType<>((id, inventory) -> new CargoMenu(id, inventory, 3), FeatureFlags.DEFAULT_FLAGS));
    public static final List<DeferredHolder<MenuType<?>, MenuType<CargoMenu>>> CARGO = List.of(CARGO_ONE, CARGO_TWO, CARGO_THREE);

    static void register(IEventBus bus) {
        MENUS.register(bus);
        // HIGHEST + receiveCanceled: several mods bind sneak-use on entities (pick-up / inspect) at normal
        // priority and can consume the click, which made the drone vanish instead of opening the panel.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, true, TerminalMenus::interact);
    }

    static MenuType<CargoMenu> cargoType(int rows) {
        return switch (Math.clamp(rows, 1, 3)) {
            case 1 -> CARGO_ONE.get();
            case 2 -> CARGO_TWO.get();
            default -> CARGO_THREE.get();
        };
    }

    static boolean authorized(ServerPlayer player, DroneEntity drone, ItemStack controller) {
        return drone != null && drone.isAlive() && player.isAlive() && !player.isSpectator()
            && drone.level() == player.level() && player.getUUID().equals(drone.owner)
            && player.canInteractWithEntity(drone, 0) && !controller.isEmpty()
            && drone.getUUID().equals(DroneItem.serial(controller)) && Hardware.controller(player) == controller;
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
        DroneCombat.flush(drone);
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new TerminalMenu(id, inventory, drone, controller),
                Component.translatable("terminal.dronepal.title")));
        return true;
    }

    private static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof DroneEntity drone) || !event.getEntity().isShiftKeyDown()
                || !event.getItemStack().isEmpty()) return;
        if (event.getEntity() instanceof ServerPlayer player) openTerminal(player, drone);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
    }

    private TerminalMenus() {}
}
