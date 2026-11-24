package dev.dronepal.client;

import dev.dronepal.Dronepal;
import dev.dronepal.Frames;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT)
public final class FrameItemIcons {
    private FrameItemIcons() {}

    @SubscribeEvent
    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(Dronepal.DRONE.get(),
                ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "frame"),
                (stack, level, holder, seed) -> switch (Frames.id(stack)) {
                    case "owl" -> 0.25F;
                    case "bedrock" -> 0.50F;
                    case "scrap" -> 0.75F;
                    default -> 0.0F;
                }));
    }
}
