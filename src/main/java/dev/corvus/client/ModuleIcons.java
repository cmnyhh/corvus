package dev.corvus.client;

import dev.corvus.Corvus;
import dev.corvus.Hardware;
import java.util.List;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * One {@code module} item covers all 27 products, so the icon is chosen by the product id in NBT.
 * The predicate value is the one-based catalogue index over 32, matching the overrides in
 * {@code models/item/module.json}; the order here must stay identical to that file.
 */
@Mod.EventBusSubscriber(modid = Corvus.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModuleIcons {
    private static final List<String> PRODUCTS = List.of(
            "carry_mk1", "carry_mk2", "carry_mk3", "carry_rust_g1",
            "combat_mk1", "combat_mk2", "combat_mk3", "combat_rust_g1",
            "light_mk1", "light_mk2", "light_mk3", "light_rust_g1",
            "link_mk1", "link_mk2", "link_mk3",
            "medic_mk1", "medic_mk2", "medic_mk3", "medic_rust_g1",
            "scan_mk1", "scan_mk2", "scan_mk3", "scan_rust_g1",
            "solar_mk1", "solar_mk2", "solar_mk3", "solar_rust_g1");

    private ModuleIcons() {}

    @SubscribeEvent
    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(Hardware.MODULE.get(),
                new ResourceLocation(Corvus.ID, "module_product"),
                (stack, level, entity, seed) -> {
                    ResourceLocation id = Hardware.productId(stack);
                    int index = id == null ? -1 : PRODUCTS.indexOf(id.getPath());
                    return index < 0 ? 0.0F : (index + 1) / 32.0F;
                }));
    }
}
