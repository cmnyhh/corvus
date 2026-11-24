package dev.dronepal;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT)
final class ModuleTooltipConnection {
    @SubscribeEvent static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        ModuleTooltips.clearRemote();
        BatteryItem.disconnected();
    }
}
