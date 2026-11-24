package dev.dronepal;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Only loaded on servers with FTB Teams. Resolve live membership at each targeting decision. */
final class FtbTeamAllies {
    static boolean sameTeam(ServerPlayer owner, Player other) {
        var api = FTBTeamsAPI.api();
        return api.isManagerLoaded() && api.getManager().arePlayersInSameTeam(owner.getUUID(), other.getUUID());
    }
}
