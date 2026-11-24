package dev.dronepal;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class GameTestPlayers {
    static ServerPlayer join(GameTestHelper test) {
        UUID id = UUID.randomUUID();
        return join(test, new GameProfile(id, "drone-" + id.toString().substring(0, 8)));
    }
    static ServerPlayer join(GameTestHelper test, GameProfile profile) {
        var world = test.getLevel();
        var cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(world.getServer(), world, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        world.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        // Embedded connections are outside the server socket listener's tick list.
        test.onEachTick(() -> { if (!player.isRemoved()) player.doTick(); });
        return player;
    }
}
