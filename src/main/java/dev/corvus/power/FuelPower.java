package dev.corvus.power;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.common.ForgeHooks;

/**
 * Furnace fuel as a power source, shared by the charging dock and the burner generator.
 *
 * <p>1.20.1 has no data components, so fuel is the plain item stack. Both entry points are the ones a
 * vanilla furnace uses, which means every datapack or mod that implements {@code getBurnTime} works
 * here for free:</p>
 *
 * <ul>
 *   <li>{@link AbstractFurnaceBlockEntity#isFuel(ItemStack)} for "may this stack be burned at all";</li>
 *   <li>{@link ForgeHooks#getBurnTime(ItemStack, net.minecraft.world.item.crafting.RecipeType)} for
 *       the burn duration in ticks. Forge fires {@code FurnaceFuelBurnTimeEvent} from there, so a pack
 *       that re-prices a fuel sees the same number on the pad as in a furnace. {@code recipeType} is
 *       {@code null} on purpose: these blocks have no recipe book of their own, which is exactly what
 *       {@code null} means to Forge.</li>
 * </ul>
 *
 * <p>One item is worth {@code burnTicks x 2.5 FE}: coal 1,600 ticks → 4,000 FE, coal block 16,000 →
 * 40,000, blaze rod 2,400 → 6,000, lava bucket 20,000 → 50,000. The multiplier is a constant rather
 * than a config value so a pack cannot silently re-balance every existing base.</p>
 */
public final class FuelPower {
    /** FE produced per tick of vanilla burn time. */
    public static final double FE_PER_BURN_TICK = 2.5D;

    private FuelPower() {}

    /** True when the stack is ordinary furnace fuel (and non-empty). */
    public static boolean fuel(ItemStack stack) {
        return stack != null && !stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack);
    }

    /** Burn time in ticks, 0 when the stack is not a fuel. Never negative. */
    public static int burnTicks(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return Math.max(0, ForgeHooks.getBurnTime(stack, null));
    }

    /** FE stored in one item of this stack; 0 when it is not a fuel. */
    public static int fe(ItemStack stack) {
        return fe(burnTicks(stack));
    }

    /**
     * What the fuel leaves behind when it is consumed, e.g. an empty bucket for a lava bucket. Empty
     * when there is no container. Callers must store or drop it: a lava bucket that eats its bucket is
     * a silently lost iron ingot.
     */
    public static ItemStack remainder(ItemStack fuel) {
        if (fuel == null || fuel.isEmpty()) return ItemStack.EMPTY;
        net.minecraft.world.item.Item item = fuel.getItem().getCraftingRemainingItem();
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * FE for a burn duration. Rounded so odd modded burn times still convert to whole FE.
     *
     * <p>The arithmetic lives in {@link dev.corvus.core.Fuel}, the class the 1.21.1 build compiles too,
     * so a pack cannot get one price on one loader and another price on the other.</p>
     */
    public static int fe(int burnTicks) {
        return dev.corvus.core.Fuel.flushRemainder(burnTicks);
    }
}
