package dev.dronepal.power;

import net.minecraft.world.item.ItemStack;

/**
 * One rule for every fuel slot in the mod: a fuel item is worth its vanilla burn time in ticks times
 * {@value #FE_PER_TICK} FE.
 *
 * <p>Burn time comes from {@link ItemStack#getBurnTime}, the same lookup a furnace performs, so any
 * datapack or mod addition (a new log, a custom furnace_fuels entry) works without a list of our own.
 * The multipliers were chosen to preserve the old coal contract exactly — coal burns 1,600 ticks and
 * therefore still yields 4,000 FE — while coal blocks (16,000 t → 40,000 FE), blaze rods (2,400 t →
 * 6,000 FE) and lava buckets (20,000 t → 50,000 FE) scale the way players expect.</p>
 */
public final class FuelValues {
    /** FE produced per tick of vanilla burn time. */
    public static final double FE_PER_TICK = 2.5D;
    /** A fuel slot holds at most one stack, same ceiling as the furnace. */
    public static final int SLOT_LIMIT = 64;

    private FuelValues() {}

    /** Vanilla burn time in ticks; 0 when the stack is not a furnace fuel. */
    public static int burnTicks(ItemStack stack) {
        return stack.isEmpty() ? 0 : Math.max(0, stack.getBurnTime(null));
    }

    public static boolean burnable(ItemStack stack) {
        return burnTicks(stack) > 0;
    }

    /** Total FE one item of this stack is worth. */
    public static int fe(ItemStack stack) {
        return feFromTicks(burnTicks(stack));
    }

    public static int feFromTicks(int ticks) {
        if (ticks <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(ticks * FE_PER_TICK));
    }

    /**
     * What is left in the player's hand after the item is consumed, e.g. an empty bucket for a lava
     * bucket. Empty when the fuel leaves no container behind. Callers store or drop this; it must
     * never be silently discarded, or a lava bucket would cost an iron bucket every time.
     */
    public static ItemStack remainder(ItemStack fuel) {
        if (fuel.isEmpty()) return ItemStack.EMPTY;
        var item = fuel.getItem().getCraftingRemainingItem();
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
