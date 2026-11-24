package dev.corvus;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The shared {@code module} item. Which of the 27 modules it is comes from the product id in NBT, so
 * one item covers every family and tier and the datapack can add products without a new registry entry.
 */
public final class ModuleItem extends Item {
    public ModuleItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        Hardware.Module module = Hardware.definition(stack);
        if (module == null) return Component.translatable("item.dronepal.unknown_module");
        return Component.translatable("dronepal.name.module",
                Component.translatableWithFallback("dronepal.vendor." + module.vendor(),
                        module.vendor().toUpperCase()),
                module.tierLabel(),
                Component.translatableWithFallback(module.productKey(), module.productName()));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        ModuleTooltips.append(stack, lines);
    }
}
