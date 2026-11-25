package dev.corvus.power.grid;

import net.minecraft.core.BlockPos;

/**
 * A device that can be served by the wireless grid. The tower service polls {@link #gridDemandFe()}
 * once per server tick (before block entities and entities tick), allocates the attached tower's
 * shared throughput by priority, and lets the device withdraw the grant later in the same tick.
 *
 * <p>Implementations must be cheap and side-effect free: this is a read of "how much FE would I take
 * this tick", not a request. {@link #gridCreditCapFe()} bounds how much unspent FE may pile up for a
 * device that draws less often than once per tick (the deployed drone draws once per second).</p>
 */
public interface GridLoad {
    /** Where the device is; used to pick the nearest tower inside the configured radius. */
    BlockPos gridPos();

    /** Lower is served first: 0 drone, 1 ore mill, 2 charging dock. */
    int gridPriority();

    /** FE/t this device could use right now. */
    int gridDemandFe();

    /** Upper bound of banked FE while the device is between draws. Defaults to one tick's demand. */
    default int gridCreditCapFe() { return gridDemandFe(); }

    /** False once the backing block entity / entity is gone, so the service can drop it. */
    default boolean gridValid() { return true; }
}
