package dev.corvus.power.grid;

import dev.corvus.core.Grid;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The tiny API the rest of the mod uses to ask the wireless grid for power.
 *
 * <p>Two call shapes, because two kinds of device tick at different rates:</p>
 * <ul>
 *   <li>{@link #draw(ServerLevel, BlockEntity, int, int, IntSupplier)} — a block entity that runs every
 *       tick (ore mill, charging dock). The demand supplier is read once per tick by the allocator;
 *       the returned FE is what this call may take out of its banked grant. Registering happens on the
 *       first call, so a machine placed next to a tower keeps working without a setup step.</li>
 *   <li>{@link #drawDrone(ServerLevel, Entity, int)} — the deployed drone ticks its power system once
 *       per second. Its per-tick demand is {@code ceil(perSecond / 20)}, its credit cap is one second,
 *       and the second-boundary call withdraws the banked grant.</li>
 * </ul>
 *
 * <p>Everything is server-side; the client overlay uses block-state lamp values and a position list,
 * never this API.</p>
 */
public final class GridLink {
    public static final int PRIORITY_DRONE = Grid.PRIORITY_DRONE;
    public static final int PRIORITY_MILL = Grid.PRIORITY_MILL;
    public static final int PRIORITY_DOCK = Grid.PRIORITY_DOCK;

    private GridLink() {}

    /**
     * Tries the wireless grid before a machine falls back to wires/fuel.
     *
     * @param max      most FE this call may use this tick (the machine's own input budget)
     * @param priority {@link #PRIORITY_MILL} or {@link #PRIORITY_DOCK}
     * @param demand   the machine's current per-tick need, polled by the allocator
     * @return delivered FE, 0 when off-grid or unlucky
     */
    public static int draw(ServerLevel level, BlockEntity owner, int max, int priority, IntSupplier demand) {
        if (level == null || level.isClientSide || owner == null || max <= 0) return 0;
        return TowerGridService.drawBlock(level, owner, max, priority, demand);
    }

    /**
     * Registers/refreshes the deployed drone's grid load and withdraws what it banked since the last
     * call. Returns deliverable FE for this second (0 when off-grid).
     */
    public static int drawDrone(ServerLevel level, Entity drone, int perSecondFe) {
        if (level == null || level.isClientSide || drone == null || perSecondFe <= 0) return 0;
        return TowerGridService.drawDrone(level, drone, perSecondFe);
    }

    /** True when a tower is attached to this drone, even if the battery is full. */
    public static boolean droneAttached(ServerLevel level, Entity drone) {
        return level != null && !level.isClientSide && drone != null && TowerGridService.droneAttached(level, drone);
    }

    /** True when the last allocation gave this drone a supplier tower within five hops. */
    public static boolean droneInGrid(ServerLevel level, Entity drone) {
        return level != null && !level.isClientSide && drone != null && TowerGridService.droneInGrid(level, drone);
    }

    /** Registers a test/utility load; production devices register through {@link #draw}. */
    public static void registerLoad(ServerLevel level, GridLoad load) {
        if (level != null && !level.isClientSide && load != null) TowerGridService.register(level, load);
    }

    public static void unregisterLoad(ServerLevel level, GridLoad load) {
        if (level != null && !level.isClientSide && load != null) TowerGridService.unregister(level, load);
    }

    /** Withdraws a load's banked grant (test seam; devices normally use the two methods above). */
    public static int withdraw(ServerLevel level, GridLoad load, int max) {
        if (level == null || level.isClientSide || load == null) return 0;
        return TowerGridService.draw(level, load, max);
    }

    /** Test/command readout of a load's attachment, hop count and banked credit. */
    public static String debug(ServerLevel level, GridLoad load) {
        if (level == null || level.isClientSide || load == null) return "offline";
        return TowerGridService.debug(level, load);
    }

    /** Forces one allocation for the current tick (test/command seam). */
    public static void forceAllocate(ServerLevel level) {
        if (level != null && !level.isClientSide) TowerGridService.forceAllocate(level);
    }

    /** True when a tower covers {@code pos}; used by the HUD fallback and by smoke helpers. */
    public static boolean covered(ServerLevel level, BlockPos pos) {
        return level != null && !level.isClientSide && TowerGridService.hasTowerNear(level, pos);
    }

    /** Live numbers for the tower's right-click readout. */
    public static TowerGridService.NetworkInfo info(ServerLevel level, BlockPos towerPos) {
        return TowerGridService.info(level, towerPos);
    }
}
