package dev.corvus;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A battery stack. It carries its own charge, so it never stacks and its durability bar is the charge
 * gauge.
 *
 * <p>1.20.1 has no {@code TooltipComponent} payload for a custom gauge widget, so the charge is shown
 * as one formatted line plus the vanilla bar — same information, no client-only renderer.</p>
 */
public final class BatteryItem extends Item {
    public BatteryItem(Properties properties) {
        super(properties);
    }

    /** Stored / capacity, read straight off the stack; "absent" means full. */
    private static int[] gauge(ItemStack stack) {
        Batteries.Model model = Batteries.model(stack);
        if (model == null) return new int[] {0, 1};
        return new int[] {Batteries.energy(stack, model), model.capacity()};
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        int[] gauge = gauge(stack);
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.ROOT);
        int percent = (int) (100L * gauge[0] / Math.max(1, gauge[1]));
        lines.add(Component.translatable("dronepal.tip.battery_charge",
                numbers.format(gauge[0]), numbers.format(gauge[1]), percent));
        Batteries.Model model = Batteries.model(stack);
        if (model != null && model.leakChance() > 0D) {
            lines.add(Component.translatable("dronepal.tip.leak",
                    Math.round(model.leakChance() * 100D), Math.round(model.leakFraction() * 100D)));
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int[] gauge = gauge(stack);
        return (int) Math.round(13D * gauge[0] / Math.max(1, gauge[1]));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        int[] gauge = gauge(stack);
        return gauge[0] * 5L < gauge[1] ? 0xe88653 : 0x52d4ab;
    }
}
