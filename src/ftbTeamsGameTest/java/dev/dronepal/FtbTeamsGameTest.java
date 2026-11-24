package dev.dronepal;

import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("huhu_dronepal")
@PrefixGameTestTemplate(false)
public final class FtbTeamsGameTest {
    @GameTest(template="combat_test_room", batch="ftb_membership")
    public static void liveTeamJoinLeaveSwitch(GameTestHelper test) {
        var owner = GameTestPlayers.join(test);
        var other = GameTestPlayers.join(test);
        var controller = Frames.create("peregrine");
        owner.setGameMode(GameType.CREATIVE);
        other.setGameMode(GameType.SURVIVAL);
        EquippedTestDrone.equip(test, owner, controller, (equipped, drone) -> {
            var server = test.getLevel().getServer();
            boolean previous = server.isPvpAllowed();
            try {
                server.setPvpAllowed(true);
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "dronepvp enable");
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "dronepvp confirm");
                other.teleportTo(drone.getX() + 5, drone.getY(), drone.getZ());
                drone.setTarget(other);
                var manager = FTBTeamsAPI.api().getManager();
                test.assertTrue(DroneCombat.mayHitPlayer(drone, other), "unaffiliated opted-in PVP target denied");
                var party = (PartyTeam)manager.createPartyTeam(owner, "FTB test party", "", Color4I.WHITE);
                party.addAlly(owner.createCommandSourceStack(), List.of(other.getGameProfile()));
                test.assertTrue(DroneCombat.mayHitPlayer(drone, other), "FTB ally list incorrectly protected nonmember");
                party.invite(owner, List.of(other.getGameProfile()));
                party.join(other);
                test.assertTrue(!DroneCombat.mayHitPlayer(drone, other) && Engagement.protectedAlly(drone,owner,other), "same FTB party allowed friendly damage");
                party.leave(other.getUUID());
                test.assertTrue(DroneCombat.mayHitPlayer(drone, other), "leave remained protected");
                manager.createPartyTeam(other, "FTB second party", "", Color4I.WHITE);
                test.assertTrue(DroneCombat.mayHitPlayer(drone, other), "different party remained protected");
                test.succeed();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
            finally { server.setPvpAllowed(previous); }
        });
    }
}
