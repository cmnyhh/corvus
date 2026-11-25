package dev.dronepal.power.grid.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dronepal.Dronepal;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Key binding and on/off state for the tower coverage overlay (default: G, off until pressed). */
@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
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
