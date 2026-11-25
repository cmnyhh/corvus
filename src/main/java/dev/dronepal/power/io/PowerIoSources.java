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
