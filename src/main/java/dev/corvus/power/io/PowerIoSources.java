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

    /** A machine of ours that makes or stores FE: burner, solar, battery bank (a tower joins here). */
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

    /** Blocks a port or a cable may pull FE out of. A port is an outlet, never a source. */
    static boolean supplying(BlockState state) {
        return state.getBlock() instanceof PowerCableBlock || machine(state);
    }
}
