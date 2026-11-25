package dev.corvus;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An active job owns its reserved stacks until its output commits. Breaking the station refunds them.
 *
 * <p>1.20.1 keeps block-entity state in a plain {@link CompoundTag}: every inventory slot gets its own
 * index key, because {@code SimpleContainer}'s compact tag format would shift slots when a hole
 * appears. A job in progress is stored too, so closing the window or leaving the chunk does not
 * cancel or duplicate it.</p>
 */
public final class AssemblyStation extends BlockEntity {
    static final int INPUTS = 9, OUTPUT = 9;
    final SimpleContainer inventory = new SimpleContainer(10);
    private final SimpleContainer reserved = new SimpleContainer(INPUTS);
    String selected = "";
    private ItemStack pending = ItemStack.EMPTY;
    int elapsed, duration;

    public AssemblyStation(BlockPos pos, BlockState state) {
        super(SurvivalWorks.STATION.get(), pos, state);
        inventory.addListener(ignored -> setChanged());
    }

    SurvivalWorks.Plan plan() {
        int index = selectedIndex();
        return index < 0 ? null : SurvivalWorks.plans.get(index);
    }

    /** Index of the selected blueprint in server list order, or -1 when nothing valid is selected. */
    int selectedIndex() {
        if (selected.isEmpty()) return -1;
        List<SurvivalWorks.Plan> plans = SurvivalWorks.plans;
        for (int i = 0; i < plans.size(); i++) if (plans.get(i).id().toString().equals(selected)) return i;
        return -1;
    }

    boolean select(int index) {
        if (index < 0 || index >= SurvivalWorks.plans.size()) return false;
        return select(SurvivalWorks.plans.get(index));
    }

    boolean select(SurvivalWorks.Plan plan) {
        if (busy() || !SurvivalWorks.plans.contains(plan)) return false;
        selected = plan.id().toString();
        setChanged();
        return true;
    }

    boolean busy() { return !pending.isEmpty(); }
    ItemStack pendingStack() { return pending; }

    /** Stacks currently held by a running job; the smoke harness checks that a refund returns them. */
    int reservedCount() {
        int count = 0;
        for (int i = 0; i < reserved.getContainerSize(); i++) if (!reserved.getItem(i).isEmpty()) count++;
        return count;
    }

    private boolean room(ItemStack result) {
        ItemStack output = inventory.getItem(OUTPUT);
        return output.isEmpty() || ItemStack.isSameItemSameTags(output, result)
                && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }

    boolean start() {
        SurvivalWorks.Plan plan = plan();
        if (busy() || plan == null) return false;
        ItemStack result = plan.result();
        if (result.isEmpty() || !room(result)) return false;
        int[] allocation = new int[INPUTS];
        for (SurvivalWorks.Cost cost : plan.costs()) {
            int need = cost.count();
            for (int i = 0; i < INPUTS && need > 0; i++) {
                ItemStack item = inventory.getItem(i);
                if (!cost.product().matches(item)) continue;
                int take = Math.min(need, item.getCount() - allocation[i]);
                allocation[i] += take;
                need -= take;
            }
            if (need > 0) return false;
        }
        for (int i = 0; i < INPUTS; i++) if (allocation[i] > 0) reserved.setItem(i, inventory.removeItem(i, allocation[i]));
        pending = result;
        elapsed = 0;
        duration = plan.ticks();
        setChanged();
        return true;
    }

    void tick() {
        if (!busy() || !room(pending)) return;
        if (++elapsed >= duration) {
            ItemStack output = inventory.getItem(OUTPUT);
            if (output.isEmpty()) inventory.setItem(OUTPUT, pending);
            else output.grow(pending.getCount());
            pending = ItemStack.EMPTY;
            reserved.clearContent();
            elapsed = duration = 0;
        }
        setChanged();
    }

    void dropContents() {
        if (level == null || level.isClientSide) return;
        Containers.dropContents(level, worldPosition, inventory);
        Containers.dropContents(level, worldPosition, reserved);
        inventory.clearContent();
        reserved.clearContent();
        pending = ItemStack.EMPTY;
        elapsed = duration = 0;
        setChanged();
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // SimpleContainer's normal tag format compacts away empty slots. Each persistent slot needs its
        // index here, or a gap would shift every stack above it.
        for (int i = 0; i < inventory.getContainerSize(); i++)
            tag.put("Slot" + i, inventory.getItem(i).save(new CompoundTag()));
        ListTag kept = new ListTag();
        for (int i = 0; i < reserved.getContainerSize(); i++) {
            ItemStack stack = reserved.getItem(i);
            if (stack.isEmpty()) continue;
            CompoundTag entry = stack.save(new CompoundTag());
            entry.putInt("ReservedSlot", i);
            kept.add(entry);
        }
        tag.put("Reserved", kept);
        tag.put("Pending", pending.save(new CompoundTag()));
        tag.putString("Selected", selected);
        tag.putInt("Elapsed", elapsed);
        tag.putInt("Duration", duration);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            CompoundTag slot = tag.getCompound("Slot" + i);
            inventory.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : ItemStack.of(slot));
        }
        reserved.clearContent();
        ListTag kept = tag.getList("Reserved", Tag.TAG_COMPOUND);
        for (int i = 0; i < kept.size(); i++) {
            CompoundTag entry = kept.getCompound(i);
            int slot = Mth.clamp(entry.getInt("ReservedSlot"), 0, INPUTS - 1);
            reserved.setItem(slot, ItemStack.of(entry));
        }
        pending = ItemStack.of(tag.getCompound("Pending"));
        selected = tag.getString("Selected");
        duration = pending.isEmpty() ? 0 : Mth.clamp(tag.getInt("Duration"), 1, 32000);
        elapsed = Mth.clamp(tag.getInt("Elapsed"), 0, duration);
    }
}
