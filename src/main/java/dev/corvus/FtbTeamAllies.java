package dev.corvus;

import java.lang.reflect.Method;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * FTB Teams membership, resolved live at every targeting decision.
 *
 * <p>FTB Teams is an optional dependency that this build does not compile against (the 1.20.1
 * artifact is not on Maven Central), so the two API calls are resolved reflectively: a missing or
 * changed API simply means "not a teammate", which is the conservative answer — the drone then
 * refuses to shoot, never the other way round.</p>
 */
final class FtbTeamAllies {
    private static final boolean LOADED = ModList.get().isLoaded("ftbteams");
    private static Method apiMethod;
    private static Method managerMethod;
    private static Method sameTeamMethod;
    private static Object manager;
    private static boolean resolved;

    static boolean available() {
        resolve();
        return sameTeamMethod != null && manager != null;
    }

    static boolean sameTeam(ServerPlayer owner, Player other) {
        if (!LOADED) return false;
        resolve();
        if (sameTeamMethod == null || manager == null) return false;
        try {
            Object result = sameTeamMethod.invoke(manager, owner.getUUID(), other.getUUID());
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError failure) {
            sameTeamMethod = null;
            return false;
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!LOADED) return;
        try {
            Class<?> api = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            apiMethod = api.getMethod("api");
            Object instance = apiMethod.invoke(null);
            managerMethod = instance.getClass().getMethod("getManager");
            manager = managerMethod.invoke(instance);
            sameTeamMethod = manager.getClass().getMethod("arePlayersInSameTeam",
                    java.util.UUID.class, java.util.UUID.class);
        } catch (ReflectiveOperationException | LinkageError absent) {
            manager = null;
            sameTeamMethod = null;
        }
    }

    private FtbTeamAllies() {}
}
