package dev.corvus.power.grid;

import dev.corvus.Corvus;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Refreshes every level's tower topology and per-tick budget before blocks and entities tick. */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class TowerGridTicker {
    private TowerGridTicker() {}

    @SubscribeEvent
    static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        for (ServerLevel level : event.getServer().getAllLevels()) TowerGridService.newTick(level);
    }
}
