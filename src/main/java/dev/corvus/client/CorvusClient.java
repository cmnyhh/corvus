package dev.corvus.client;

import dev.corvus.Corvus;
import dev.corvus.Frames;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client wiring: the entity renderer and the per-frame item icon predicate used by the item model. */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CorvusClient {
    private CorvusClient() {}

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(
                Corvus.DRONE.get(),
                new ResourceLocation(Corvus.ID, "frame"),
                (stack, level, holder, seed) -> switch (Frames.id(stack)) {
                    case "owl" -> 0.25F;
                    case "bedrock" -> 0.50F;
                    case "scrap" -> 0.75F;
                    default -> 0.0F;
                }));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Corvus.ENTITY.get(), DroneRenderer::new);
        OreMillRenderer.register(event);
    }
}
