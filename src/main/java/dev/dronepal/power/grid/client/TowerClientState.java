package dev.dronepal.power.grid.client;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side index of the tower block entities in loaded chunks. Block entities add themselves on
 * {@code setLevel} and remove themselves on {@code setRemoved}, so the coverage overlay never scans
 * chunks or touches the server.
 */
public final class TowerClientState {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> TOWERS = new HashMap<>();

    private TowerClientState() {}

    public static void add(Level level, BlockPos pos) {
        if (level == null) return;
        TOWERS.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos.immutable());
    }

    public static void remove(Level level, BlockPos pos) {
        if (level == null) return;
        Set<BlockPos> set = TOWERS.get(level.dimension());
        if (set == null) return;
        set.remove(pos);
        if (set.isEmpty()) TOWERS.remove(level.dimension());
    }

    public static Collection<BlockPos> towers(Level level) {
        if (level == null) return Collections.emptyList();
        Set<BlockPos> set = TOWERS.get(level.dimension());
        return set == null ? Collections.emptyList() : set;
    }
}
