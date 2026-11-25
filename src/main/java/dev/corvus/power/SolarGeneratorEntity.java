package dev.corvus.power;

import dev.corvus.ServerOptions;
import dev.corvus.core.Solar;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Solar generator: a 50,000 FE buffer fed by the sky.
 *
 * <p>Peak output is {@link ServerOptions#solarOutput()} (60 FE/t) and it only counts when the block can
 * actually see the sky — {@code canSeeSky} is a sky-light test, so a single block above the panel, a
 * roof or an overhang drops output to 0. Rain and thunder halve it, night gives nothing. Nothing is
 * generated while the buffer is full.</p>
 *
 * <p>The daylight/weather rule and the half-output rounding come from {@link Solar} in
 * {@code corvus-core/}, the same class the 1.21.1 build compiles in. This file only reads the level
 * into the four boolean inputs and maps the returned {@link Solar.State} onto the {@code sun} block
 * state, the FE/t and the status line — it no longer has a daylight branch of its own. Night is read
 * from the world clock rather than {@code Level#isNight}: the latter folds in {@code skyDarken}, so a
 * rainy afternoon could read as night and switch the panel off instead of halving it.</p>
 *
 * <p>A fixed 2,000 FE/t push ceiling ({@link #PUSH_RATE}) means the buffer behaves like a small tank
 * rather than a throttle: in steady daylight everything generated leaves in the same tick, and a full
 * buffer empties quickly once a consumer appears.</p>
 */
public final class SolarGeneratorEntity extends BlockEntity implements IEnergyStorage {
    public static final int BUFFER = 50_000;
    /**
     * How much sun the panel is getting, as a block state so the model can show it: 0 = nothing (night
     * or a blocked sky), 1 = dim (rain or thunder, half output), 2 = full daylight. Written only when the
     * segment changes, never once per tick.
     */
    public static final IntegerProperty SUN = IntegerProperty.create("sun", 0, 2);
    /** Ceiling on how fast the buffer can be handed to neighbours. */
    public static final int PUSH_RATE = 2_000;

    private final net.minecraftforge.common.util.LazyOptional<net.minecraftforge.energy.IEnergyStorage> energyCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> this);
    private int energy;

    public SolarGeneratorEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.SOLAR_TYPE.get(), pos, state);
    }

    /**
     * What this block would produce right now, in FE/t.
     *
     * <p>{@code above()} is the position that matters: a solar generator is a solid cube, so its own
     * position is occupied by something that blocks sky light.</p>
     */
    public static int outputNow(Level level, BlockPos pos) {
        return Solar.ratePerTick(ServerOptions.solarOutput(), state(level, pos));
    }

    /**
     * Shared daylight/weather rule ({@link Solar}) evaluated for this panel. Exposed as the enum so
     * callers can talk about "dim" without magic numbers; {@link #sunLevel} is its block-state code.
     */
    public static Solar.State state(Level level, BlockPos pos) {
        if (level == null) return Solar.State.OFF;
        return Solar.state(daytimeByWorldClock(level), level.canSeeSky(pos.above()),
                level.isRaining(), level.isThundering());
    }

    /**
     * Sun segment for a position: 0 nothing, 1 dim, 2 full.
     *
     * <p>{@code above()} is the position that matters: a solar generator is a solid cube, so its own
     * position is occupied by something that blocks sky light.</p>
     */
    public static int sunLevel(Level level, BlockPos pos) {
        return state(level, pos).code();
    }

    public int sunLevel() { return sunLevel(getLevel(), worldPosition); }

    /**
     * Daytime straight from the clock, not {@code Level#isDay()} / {@code Level#isNight()}: those fold
     * in {@code skyDarken}, and rain darkens the sky enough to read as night. A fixed-time dimension
     * has no day/night cycle and counts as night.
     */
    private static boolean daytimeByWorldClock(Level level) {
        if (level.dimensionType().hasFixedTime()) return false;
        return level.getDayTime() % 24_000L < 12_000L;
    }

    public int outputNow() {
        return outputNow(getLevel(), worldPosition);
    }

    public void tick(ServerLevel world) {
        syncSun(world);
        int generated = outputNow(world, worldPosition);
        if (generated > 0 && energy < BUFFER) {
            int added = Math.min(generated, BUFFER - energy);
            if (added > 0) {
                energy += added;
                setChanged();
            }
        }
        int budget = Math.min(PUSH_RATE, energy);
        if (budget > 0) {
            int sent = EnergyLink.push(world, worldPosition, budget);
            if (sent > 0) {
                energy -= sent;
                setChanged();
            }
        }
    }

    /**
     * Writes the sun segment into the block state, and only when it changed: the panel crosses a
     * boundary twice per day plus once per weather change, so this never becomes a per-tick update.
     */
    private void syncSun(ServerLevel world) {
        BlockState state = getBlockState();
        if (!state.hasProperty(SUN)) return;
        int level = sunLevel(world, worldPosition);
        if (state.getValue(SUN) == level) return;
        world.setBlock(worldPosition, state.setValue(SUN, level), Block.UPDATE_CLIENTS);
    }

    /** Why the panel is where it is: night, blocked sky, rain, or plain daylight. */
    public String reasonKey() {
        Level level = getLevel();
        if (level == null) return "message.dronepal.solar_blocked";
        return switch (state(level, worldPosition)) {
            case BRIGHT -> "message.dronepal.solar_clear";
            case DIM -> "message.dronepal.solar_rain";
            case OFF -> daytimeByWorldClock(level)
                    ? "message.dronepal.solar_blocked" : "message.dronepal.solar_night";
        };
    }

    public Component status() {
        // Argument order matches the shared format string ("... %3$s FE/t · %4$s"): rate first, then
        // the reason. The 1.21.1 build fills the same two slots in the same order.
        return Component.translatable("message.dronepal.solar_status", energy, BUFFER, outputNow(),
                Component.translatable(reasonKey()));
    }

    public int storedEnergy() { return energy; }
    public int buffer() { return BUFFER; }

    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }
    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canReceive() { return false; }
    @Override public boolean canExtract() { return false; }

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
        tag.putInt("Energy", energy);
    }

    @Override public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
    }
}
