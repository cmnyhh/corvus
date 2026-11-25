package dev.dronepal.power;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Block item that appends the power numbers, read live so a config change shows in the tooltip. */
public final class PowerBlockItem extends BlockItem {
    private final Supplier<List<Component>> tooltip;

    public PowerBlockItem(Block block, Item.Properties properties, Supplier<List<Component>> tooltip) {
        super(block, properties);
        this.tooltip = tooltip;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        for (Component line : tooltip.get()) lines.add(line.copy().withStyle(ChatFormatting.GRAY));
    }
}
