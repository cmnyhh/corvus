package dev.corvus.power;

import dev.corvus.Corvus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block item with the two or three grey hover lines the power blocks carry.
 *
 * <p>1.20.1 has no tooltip codec, so the lines come straight from {@link #appendHoverText}. The keys
 * are {@code tooltip.<modid>.<block>.<n>} and both shipped languages define all of them; no
 * client-only helper is touched here, so the class stays loadable on a dedicated server.</p>
 */
public final class PowerBlockItem extends BlockItem {
    private static final String PREFIX = "tooltip." + Corvus.ID + ".";
    private static final int LINES = 3;

    private final String id;

    PowerBlockItem(Block block, Properties properties, String id) {
        super(block, properties);
        this.id = id;
    }

    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, level, lines, flag);
        for (int line = 1; line <= LINES; line++) {
            lines.add(Component.translatable(PREFIX + id + "." + line).withStyle(ChatFormatting.GRAY));
        }
    }
}
