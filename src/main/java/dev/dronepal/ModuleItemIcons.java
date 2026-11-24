package dev.dronepal;

import java.util.List;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = Dronepal.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ModuleItemIcons {
    // The one-based index divided by 32 is the exact predicate value in the module item model.
    private static final List<String> PRODUCTS = List.of(
            "carry_mk1", "carry_mk2", "carry_mk3", "carry_rust_g1",
            "combat_mk1", "combat_mk2", "combat_mk3", "combat_rust_g1",
            "light_mk1", "light_mk2", "light_mk3", "light_rust_g1",
            "link_mk1", "link_mk2", "link_mk3",
            "medic_mk1", "medic_mk2", "medic_mk3", "medic_rust_g1",
            "scan_mk1", "scan_mk2", "scan_mk3", "scan_rust_g1",
            "solar_mk1", "solar_mk2", "solar_mk3", "solar_rust_g1");

    private ModuleItemIcons() {}

    @SubscribeEvent
    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(Hardware.MODULE.get(),
                ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "module_product"),
                (stack, level, holder, seed) -> {
                    ResourceLocation id = Hardware.productId(stack);
                    return Dronepal.ID.equals(id.getNamespace()) ? (PRODUCTS.indexOf(id.getPath()) + 1) / 32.0F : 0.0F;
                }));
    }
}
