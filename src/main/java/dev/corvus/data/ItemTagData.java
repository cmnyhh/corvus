package dev.corvus.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 1.20.1 implementation of {@link CorvusData}: plain NBT on the item stack.
 *
 * <p>Writes go through {@code getOrCreateTag()}. Empty strings and empty lists remove the key
 * instead of leaving noise behind, so two stacks written with the same values end up with equal
 * NBT (which matters for stack comparison and for the {@code /give} path).</p>
 */
public final class ItemTagData implements CorvusData {
    /** Defensive cap: an item list on a controller is never anywhere near this long. */
    private static final int MAX_LIST = 256;

    private final ItemStack stack;

    public ItemTagData(ItemStack stack) {
        this.stack = Objects.requireNonNull(stack, "stack");
    }

    private CompoundTag existing() {
        return stack.getTag();
    }

    private CompoundTag mutable() {
        return stack.getOrCreateTag();
    }

    @Override
    public boolean contains(String key) {
        CompoundTag tag = existing();
        return tag != null && tag.contains(key);
    }

    @Override
    public String getString(String key) {
        CompoundTag tag = existing();
        return tag == null ? "" : tag.getString(key);
    }

    @Override
    public CorvusData setString(String key, String value) {
        if (value == null || value.isEmpty()) return remove(key);
        mutable().putString(key, value);
        return this;
    }

    @Override
    public int getInt(String key) {
        CompoundTag tag = existing();
        return tag == null ? 0 : tag.getInt(key);
    }

    @Override
    public CorvusData setInt(String key, int value) {
        mutable().putInt(key, value);
        return this;
    }

    @Override
    public long getLong(String key) {
        CompoundTag tag = existing();
        return tag == null ? 0L : tag.getLong(key);
    }

    @Override
    public CorvusData setLong(String key, long value) {
        mutable().putLong(key, value);
        return this;
    }

    @Override
    public boolean getBoolean(String key) {
        CompoundTag tag = existing();
        return tag != null && tag.getBoolean(key);
    }

    @Override
    public CorvusData setBoolean(String key, boolean value) {
        if (!value) return remove(key);
        mutable().putBoolean(key, true);
        return this;
    }

    @Override
    public List<ItemStack> getItemList(String key) {
        CompoundTag tag = existing();
        if (tag == null || !tag.contains(key, Tag.TAG_LIST)) return List.of();
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        List<ItemStack> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ItemStack stored = ItemStack.of(entry);
            if (!stored.isEmpty()) result.add(stored);
        }
        return List.copyOf(result);
    }

    @Override
    public CorvusData setItemList(String key, List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) return remove(key);
        ListTag list = new ListTag();
        int limit = Math.min(stacks.size(), MAX_LIST);
        for (int i = 0; i < limit; i++) {
            ItemStack entry = stacks.get(i);
            if (entry == null || entry.isEmpty()) continue;
            CompoundTag saved = new CompoundTag();
            entry.save(saved);
            list.add(saved);
        }
        if (list.isEmpty()) return remove(key);
        mutable().put(key, list);
        return this;
    }

    @Override
    public CorvusData remove(String key) {
        CompoundTag tag = existing();
        if (tag != null) {
            tag.remove(key);
            if (tag.isEmpty()) stack.setTag(null);
        }
        return this;
    }
}
