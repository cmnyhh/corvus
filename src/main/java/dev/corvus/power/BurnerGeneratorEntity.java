package dev.corvus.power;

import dev.corvus.ServerOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Burner generator: one fuel slot, a 100,000 FE buffer, 40 FE/t out of the box.
 *
 * <p>Fuel is any furnace fuel ({@link FuelPower}); one item is worth {@code burnTicks x 2.5} FE and is
 * released at {@link ServerOptions#burnerOutput()} FE/t. Power is pushed into all six neighbours every
 * tick (see {@link EnergyLink}), which is what makes "pad next to burner" work with no cable.</p>
 *
 * <p>Fuel is only spent while somebody can actually take the power: the generator probes its neighbours
 * before it lights the next item and pauses while its own buffer is full, so an idle machine never eats
 * coal. Fuel, the FE left in the current item and the buffer are all in NBT; breaking the block drops
 * the block and whatever fuel is left in the slot, never the stored FE.</p>
 */
public final class BurnerGeneratorEntity extends BlockEntity implements IEnergyStorage {
    public static final int BUFFER = 100_000;
    /** Fuel slot size, same ceiling the charging dock uses. */
    public static final int FUEL_LIMIT = 64;
    private static final int MAX_BURN = 10_000_000;

    private ItemStack fuel = ItemStack.EMPTY;
    /** FE still to be released by the item that is currently burning. */
    private int burnRemaining;
    /** FE the current item was worth when it started, for the status line. */
    private int burnTotal;
    private final net.minecraftforge.common.util.LazyOptional<net.minecraftforge.energy.IEnergyStorage> energyCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> this);
    private int energy;

    public BurnerGeneratorEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.BURNER_TYPE.get(), pos, state);
    }

    // ---- fuel slot ----

    /** Right-click insert. Returns how many items of {@code offered} were taken. */
    public int addFuel(ItemStack offered) {
        if (!FuelPower.fuel(offered)) return 0;
        if (!fuel.isEmpty() && !ItemStack.isSameItemSameTags(fuel, offered)) return 0;
        int accepted = Math.min(offered.getCount(), FUEL_LIMIT - fuel.getCount());
        if (accepted <= 0) return 0;
        if (fuel.isEmpty()) {
            fuel = offered.copy();
            fuel.setCount(accepted);
        } else {
            fuel.grow(accepted);
        }
        setChanged();
        return accepted;
    }

    /** Removes the whole fuel slot (sneak-right-click). */
    public ItemStack takeFuel() {
        ItemStack taken = fuel;
        fuel = ItemStack.EMPTY;
        if (!taken.isEmpty()) setChanged();
        return taken;
    }

    /** Drops the fuel slot on the ground; deliberately not the buffer. */
    public void dropContents() {
        if (level == null || level.isClientSide) return;
        if (!fuel.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                    worldPosition.getZ() + 0.5D, fuel);
        }
        fuel = ItemStack.EMPTY;
        burnRemaining = 0;
        burnTotal = 0;
        setChanged();
    }

    // ---- tick ----

    /**
     * One server tick: burn (when someone wants the power), then push what the buffer holds.
     *
     * <p>Burning is decided before the push so the FE produced this tick leaves in the same tick and the
     * buffer of an unloaded network does not creep upward.</p>
     */
    public void tick(ServerLevel world) {
        int output = output();
        boolean wanted = EnergyLink.receiverPresent(world, worldPosition);
        if (wanted && energy < BUFFER) {
            if (burnRemaining <= 0 && !fuel.isEmpty()) {
                burnTotal = FuelPower.fe(fuel);
                burnRemaining = burnTotal;
                ItemStack burnt = fuel.copy();
                fuel.shrink(1);
                // A lava bucket leaves its bucket behind; never swallow it.
                ItemStack remainder = FuelPower.remainder(burnt);
                if (!remainder.isEmpty()) {
                    Containers.dropItemStack(world, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.9D,
                            worldPosition.getZ() + 0.5D, remainder);
                }
                setChanged();
            }
            if (burnRemaining > 0) {
                int generated = Math.min(Math.min(output, burnRemaining), BUFFER - energy);
                if (generated > 0) {
                    energy += generated;
                    burnRemaining -= generated;
                    setChanged();
                }
            }
        }
        int budget = Math.min(output, energy);
        if (budget > 0) {
            int sent = EnergyLink.push(world, worldPosition, budget);
            if (sent > 0) {
                energy -= sent;
                setChanged();
            }
        }
    }

    // ---- reporting ----

    /** Configured output for this tick. */
    public int output() { return ServerOptions.burnerOutput(); }

    /** True while an item is actually burning; the status line reports it. */
    public boolean burning() { return burnRemaining > 0; }

    public Component status() {
        Component fuelLabel = fuel.isEmpty()
                ? Component.translatable("message.dronepal.fuel_empty")
                : Component.empty().append(fuel.getHoverName()).append(" ×" + fuel.getCount());
        return Component.translatable("message.dronepal.burner_status", energy, BUFFER, fuelLabel,
                Component.translatable(burning() ? "message.dronepal.burner_running" : "message.dronepal.burner_idle"));
    }

    public int storedEnergy() { return energy; }
    public int buffer() { return BUFFER; }
    public ItemStack fuelStack() { return fuel; }
    public int burnRemainingFe() { return burnRemaining; }
    public int burnTotalFe() { return burnTotal; }

    // ---- Forge energy ----

    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }
    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canReceive() { return false; }
    @Override public boolean canExtract() { return false; }

    // ---- persistence ----

    // ---- Forge capability: this block is the storage, exactly like the charging pad ----

    @Override public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction side) {
        if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
    }

    @Override protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Fuel", fuel.save(new net.minecraft.nbt.CompoundTag()));
        tag.putInt("BurnRemaining", burnRemaining);
        tag.putInt("BurnTotal", burnTotal);
        tag.putInt("Energy", energy);
    }

    @Override public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        fuel = ItemStack.of(tag.getCompound("Fuel"));
        if (fuel.getCount() > FUEL_LIMIT) fuel.setCount(FUEL_LIMIT);
        burnRemaining = Mth.clamp(tag.getInt("BurnRemaining"), 0, MAX_BURN);
        burnTotal = Mth.clamp(tag.getInt("BurnTotal"), 0, MAX_BURN);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
    }
}
