package dev.corvus.power.grid.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.corvus.Corvus;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Key binding and on/off state for the tower coverage overlay (default: G, off until pressed). */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TowerGridKeys {
    static final KeyMapping TOGGLE = new KeyMapping("key.dronepal.grid_range",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.dronepal");
    private static boolean shown;

    private TowerGridKeys() {}

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) { event.register(TOGGLE); }

    public static boolean shown() { return shown; }
    public static void toggle() { shown = !shown; }
}
