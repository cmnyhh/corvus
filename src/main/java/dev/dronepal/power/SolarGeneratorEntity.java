package dev.dronepal.power;

import dev.corvus.core.Solar;
import dev.dronepal.ServerOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Daylight only: 60 FE/t at noon on a clear day (config `solarOutput`), halved by rain or thunder,
 * nothing at night and nothing under a roof or an overhanging block.
 *
 * <p>The sky test is {@link Level#canSeeSky} one block above the panel — checking the panel's own
 * position always fails, because the panel itself is in the heightmap. Output lands in the same
 * 50,000 FE buffer the other generators use, so a grey afternoon still feeds a dock for a while.</p>
 *
 * <p>The daylight/weather/rounding rule is <b>not</b> written here: it is
 * {@link dev.corvus.core.Solar}, compiled into both the 1.21.1 and the 1.20.1 build from
 * {@code corvus-core/}. This class only translates the level into its four boolean inputs and turns
 * the resulting {@link Solar.State} into the block state, the FE/t and the status line.</p>
 */
public final class SolarGeneratorEntity extends BlockEntity implements IEnergyStorage {
    public static final int BUFFER = 50_000;

    private int energy;

    SolarGeneratorEntity(BlockPos pos, BlockState state) {
        super(PowerBlocks.SOLAR_TYPE.get(), pos, state);
    }

    public int peak() { return ServerOptions.solarOutput(); }

    /** Output for this instant, before the buffer is considered. Split out so tests can read it. */
    public static int rate(Level level, BlockPos pos) {
        return Solar.ratePerTick(ServerOptions.solarOutput(), state(level, pos));
    }

    /**
     * The state the front panel shows: 2 = clear daylight with an open sky, 1 = rain or thunder
     * (half output), 0 = night or a covered sky. This is the shared core rule — {@code sunState} and
     * {@code rate} are both derived from it, so the model and the FE/t can never disagree. The same
     * call exists on the 1.20.1 side, which is how the two panels stopped drifting (rain used to be
     * judged by {@code Level#isNight}, and thunder used to be ignored there).
     */
    public static Solar.State state(Level level, BlockPos pos) {
        if (level == null) return Solar.State.OFF;
        return Solar.state(daytimeByWorldClock(level), level.canSeeSky(pos.above()),
                level.isRaining(), level.isThundering());
    }

    /** {@link #state} as the block-state value 0/1/2. */
    public static int sunState(Level level, BlockPos pos) {
        return state(level, pos).code();
    }

    /** Current state, also usable for tooltips/debug output. */
    public int sunState() {
        return level == null ? 0 : sunState(level, worldPosition);
    }

    /**
     * Daytime straight from the clock instead of {@link Level#isDay()}: the latter folds in
     * {@code skyDarken}, and rain darkens the sky enough to read as night. Rain should halve output,
     * not switch the panel off. A fixed-time dimension has no cycle and counts as night.
     */
    private static boolean daytimeByWorldClock(Level level) {
        if (level.dimensionType().hasFixedTime()) return false;
        return level.getDayTime() % 24_000L < 12_000L;
    }

    void tick(ServerLevel level) {
        int generated = 0;
        int rate = rate(level, worldPosition);
        if (rate > 0 && energy < BUFFER) {
            generated = Math.min(rate, BUFFER - energy);
            energy += generated;
        }
        int moved = 0;
        if (energy > 0) {
            PowerPush.Result pushed = PowerPush.push(level, worldPosition, Math.min(energy, peak()));
            moved = pushed.moved();
            if (moved > 0) energy -= moved;
        }
        if (generated > 0 || moved > 0) setChanged();
        syncSunState(level);
    }

    /** Writes the panel state only when it changes, never every tick. */
    private void syncSunState(ServerLevel level) {
        BlockState state = getBlockState();
        if (!state.hasProperty(SolarGeneratorBlock.SUN)) return;
        int sun = sunState(level, worldPosition);
        if (state.getValue(SolarGeneratorBlock.SUN) == sun) return;
        level.setBlock(worldPosition, state.setValue(SolarGeneratorBlock.SUN, sun), 3);
    }

    private String reasonKey(ServerLevel level) {
        int sun = sunState(level, worldPosition);
        if (sun == 2) return "message.dronepal.solar_reason_full";
        if (sun == 1) return level.isThundering() ? "message.dronepal.solar_reason_storm" : "message.dronepal.solar_reason_rain";
        return daytimeByWorldClock(level) ? "message.dronepal.solar_reason_shaded" : "message.dronepal.solar_reason_night";
    }

    Component status() {
        ServerLevel server = level instanceof ServerLevel s ? s : null;
        Component reason = Component.translatable(server == null ? "message.dronepal.solar_reason_night" : reasonKey(server));
        int rate = server == null ? 0 : rate(server, worldPosition);
        return Component.translatable("message.dronepal.solar_status", energy, BUFFER, rate, reason);
    }

    @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }
    @Override public int extractEnergy(int amount, boolean simulate) {
        int moved = Math.clamp(amount, 0, Math.min(energy, peak()));
        if (!simulate && moved > 0) { energy -= moved; setChanged(); }
        return moved;
    }
    @Override public int getEnergyStored() { return energy; }
    @Override public int getMaxEnergyStored() { return BUFFER; }
    @Override public boolean canReceive() { return false; }
    @Override public boolean canExtract() { return true; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Mth.clamp(tag.getInt("Energy"), 0, BUFFER);
    }
}
