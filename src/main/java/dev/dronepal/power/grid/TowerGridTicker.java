package dev.dronepal.power.grid;

import dev.dronepal.Dronepal;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Refreshes every level's tower topology and per-tick budget before blocks and entities tick. The
 * service is also refreshed lazily from a draw call, so a test or a command never depends on this
 * handler having run first.
 */
@EventBusSubscriber(modid = Dronepal.ID)
public final class TowerGridTicker {
    private TowerGridTicker() {}

    @SubscribeEvent
    static void preTick(ServerTickEvent.Pre event) {
        for (ServerLevel level : event.getServer().getAllLevels()) TowerGridService.newTick(level);
    }
}
