package dev.corvus.power.io;

import dev.corvus.power.BatteryBankBlock;
import dev.corvus.power.BurnerGeneratorBlock;
import dev.corvus.power.SolarGeneratorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What counts as "one of our power blocks" for the interface layer.
 *
 * <p>Mirror of the 1.21.1 helper: a class test, not a capability probe, so the port can never drain a
 * foreign mod's battery and can never mistake our charging dock (which accepts power) for a source.</p>
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
     * pushes into its sinks ({@link dev.corvus.power.EnergyLink}), and the port is one of them.</p>
     */
    static boolean supplying(BlockState state) {
        return machine(state);
    }
}
