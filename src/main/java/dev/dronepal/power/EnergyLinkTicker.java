package dev.dronepal.power;

import dev.dronepal.Dronepal;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs the cable networks once per server tick, after every block entity has ticked.
 *
 * <p>Post (not pre) on purpose: the sources have already burnt their fuel and pushed to their direct
 * neighbours, and the sinks have already pulled from theirs, so the offers and the free room the plan
 * sees are the numbers a player would read off the blocks. The pass is also reachable directly
 * ({@link EnergyLink#tick}) so a GameTest or a command never depends on this handler having run.</p>
 */
@EventBusSubscriber(modid = Dronepal.ID)
public final class EnergyLinkTicker {
    private EnergyLinkTicker() {}

    @SubscribeEvent
    static void postTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) EnergyLink.tick(level);
    }
}
