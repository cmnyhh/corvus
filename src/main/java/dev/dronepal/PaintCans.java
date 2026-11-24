package dev.dronepal;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PaintCans {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final Map<String, DeferredItem<Item>> CANS = new LinkedHashMap<>();

    public static final java.util.List<String> PALETTE = java.util.List.of(
            "standard", "arctic", "sand", "jungle", "crimson", "neon", "void", "chrome", "rust");

    static void register(IEventBus bus) {
        for (String color : PALETTE) {
            CANS.put(color, ITEMS.registerSimpleItem("paint_can_" + color));
        }
        ITEMS.register(bus);
        NeoForge.EVENT_BUS.addListener(PaintCans::onUse);
    }

    static void addToTab(net.minecraft.world.item.CreativeModeTab.Output output) {
        CANS.values().forEach(can -> output.accept(can.get()));
    }

    private static void onUse(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof DroneEntity drone)) return;
        ItemStack can = event.getItemStack();
        for (var entry : CANS.entrySet()) {
            if (!can.is(entry.getValue().get())) continue;
            if (event.getEntity() instanceof ServerPlayer player) {
                if (!player.getUUID().equals(drone.owner)) return;
                ItemStack controller = drone.controller();
                if (!drone.getUUID().equals(DroneItem.serial(controller))) return;
                String paint = entry.getKey();
                CustomData.update(DataComponents.CUSTOM_DATA, controller, tag -> tag.putString("dronepal_paint", paint));
                drone.setPaint(paint);
                if (!player.isCreative()) can.shrink(1);
            }
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
            event.setCanceled(true);
            return;
        }
    }
}
