package dev.dronepal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** An active job owns its reserved stacks until its output commits. Breaking the station refunds them. */
final class AssemblyStation extends BlockEntity {
    static final int INPUTS = 9, OUTPUT = 9;
    final SimpleContainer inventory = new SimpleContainer(10);
    private final SimpleContainer reserved = new SimpleContainer(INPUTS);
    String selected = "";
    private ItemStack pending = ItemStack.EMPTY;
    int elapsed, duration;

    AssemblyStation(BlockPos pos, BlockState state) {
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
        var plans = SurvivalWorks.plans;
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
    private boolean room(ItemStack result) {
        ItemStack output = inventory.getItem(OUTPUT);
        return output.isEmpty() || ItemStack.isSameItemSameComponents(output, result) && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }
    boolean start() {
        var plan = plan();
        if (busy() || plan == null) return false;
        ItemStack result = plan.output().stack();
        if (result.isEmpty() || !room(result)) return false;
        int[] allocation = new int[INPUTS];
        for (var cost : plan.costs()) {
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
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registry) {
        super.saveAdditional(tag, registry);
        // SimpleContainer's normal tag format compacts away empty slots. Each persistent slot needs its index here.
        for (int i = 0; i < inventory.getContainerSize(); i++) tag.put("Slot"+i, inventory.getItem(i).saveOptional(registry));
        tag.put("Reserved", reserved.createTag(registry));
        tag.put("Pending", pending.saveOptional(registry));
        tag.putString("Selected", selected);
        tag.putInt("Elapsed", elapsed);
        tag.putInt("Duration", duration);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registry) {
        super.loadAdditional(tag, registry);
        for (int i = 0; i < inventory.getContainerSize(); i++) inventory.setItem(i, ItemStack.parseOptional(registry, tag.getCompound("Slot"+i)));
        reserved.fromTag(tag.getList("Reserved", Tag.TAG_COMPOUND), registry);
        pending = ItemStack.parseOptional(registry, tag.getCompound("Pending"));
        selected = tag.getString("Selected");
        duration = pending.isEmpty() ? 0 : Math.clamp(tag.getInt("Duration"), 1, 32000);
        elapsed = Math.clamp(tag.getInt("Elapsed"), 0, duration);
    }
}
