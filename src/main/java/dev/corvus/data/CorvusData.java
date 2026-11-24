package dev.corvus.data;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Everything Corvus stores on an item, behind one interface.
 *
 * <p>1.20.1 has no data components, so the only implementation is NBT on the stack itself
 * ({@link ItemTagData}). Keeping call sites on this interface means a future 1.20.5+ port can add a
 * component-backed implementation without touching item or gameplay code.</p>
 *
 * <p>Scope note: this layer only reads and writes values. It deliberately contains no gameplay
 * rules — the follow/equip logic lives in {@code dev.corvus}.</p>
 */
public interface CorvusData {
    boolean contains(String key);

    String getString(String key);

    CorvusData setString(String key, String value);

    int getInt(String key);

    CorvusData setInt(String key, int value);

    /** Game-time deadlines (module faults) need a 64-bit tick counter. */
    long getLong(String key);

    CorvusData setLong(String key, long value);

    boolean getBoolean(String key);

    CorvusData setBoolean(String key, boolean value);

    /** Ordered copy; entries are empty stacks only if they were written that way. */
    List<ItemStack> getItemList(String key);

    CorvusData setItemList(String key, List<ItemStack> stacks);

    CorvusData remove(String key);

    /** The storage for this stack. 1.20.1: NBT via {@code getOrCreateTag()}. */
    static CorvusData of(ItemStack stack) {
        return new ItemTagData(stack);
    }
}
