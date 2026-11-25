package dev.corvus.client;

import dev.corvus.Corvus;
import dev.corvus.SurvivalWorks;
import dev.corvus.TerminalMenus;
import dev.corvus.mill.OreMillRegistry;
import dev.corvus.charging.ChargingDockRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Menu-to-screen binding.
 *
 * <p>1.20.1 has no {@code RegisterMenuScreensEvent}: screens go into the vanilla registry from
 * {@link FMLClientSetupEvent}. The class is client-only, so a dedicated server never loads it. The
 * charging pad's block entity renderer is registered from the same subscriber.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ScreenRegistry {
    private ScreenRegistry() {}

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(TerminalMenus.TERMINAL.get(), TerminalScreen::new);
            for (var type : TerminalMenus.CARGO) MenuScreens.register(type.get(), CargoScreen::new);
            MenuScreens.register(SurvivalWorks.MENU.get(), AssemblyScreen::new);
            MenuScreens.register(OreMillRegistry.MENU.get(), OreMillScreen::new);
        });
    }

    /** The charging pad's floating cell and its charge panel. */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        ChargingDockRenderer.register(event);
    }
}
