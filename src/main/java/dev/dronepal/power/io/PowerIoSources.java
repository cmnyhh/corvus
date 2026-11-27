package dev.dronepal.power.io;

import dev.dronepal.power.BatteryBankBlock;
import dev.dronepal.power.BurnerGeneratorBlock;
import dev.dronepal.power.SolarGeneratorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What counts as "one of our power blocks" for the interface layer.
 *
 * <p>Deliberately a class test, not a block tag and not a capability probe: the port must only drain
 * blocks that are part of our own grid. A generic capability probe would happily drain a foreign mod's
 * battery — the port is an outlet for <em>our</em> energy, not a universal siphon — and the charging
 * dock (which is ours and accepts power) must never be mistaken for a source.</p>
 */
final class PowerIoSources {
    private PowerIoSources() {}

    /**
     * A machine of ours that makes or stores FE: burner, solar, battery bank. The power tower is not
     * here on purpose — it refuses capability extraction (devices go through the wireless grid) and
     * joins the cable network as an {@code EnergyLink.Node} instead.
     */
    static boolean machine(BlockState state) {
        return state.getBlock() instanceof BurnerGeneratorBlock
                || state.getBlock() instanceof SolarGeneratorBlock
                || state.getBlock() instanceof BatteryBankBlock;
    }

    /** Blocks a cable arm visually joins to: our machines, our ports and other cables. */
    static boolean connectable(BlockState state) {
        return state.getBlock() instanceof PowerCableBlock
                || state.getBlock() instanceof PowerPortBlock
                || machine(state);
    }

    /**
     * Blocks a {@code power_port} may pull FE out of directly, on a face other than its outlet.
     *
     * <p>A cable is deliberately <b>not</b> one of them: the run used to be drained by a port pull
     * loop, which is what made a cable useless to batteries and machines. Cables are now a network that
     * pushes into its sinks ({@link dev.dronepal.power.EnergyLink}), and the port is one of them.</p>
     */
    static boolean supplying(BlockState state) {
        return machine(state);
    }
}
