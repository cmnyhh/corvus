package dev.corvus;

import java.util.WeakHashMap;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * Explicit, expiring two-step opt-in before a drone may shoot a player.
 *
 * <p>Vanilla server PVP always takes precedence: if the server has PVP off, no drone ever targets a
 * player, and an owner who never opts in is only ever helped, never endangered.</p>
 */
final class EngagementPreferences {
    private static final String OPT_IN = "dronepal_pvp";
    private static final WeakHashMap<ServerPlayer, Long> pending = new WeakHashMap<>();

    static boolean accepts(ServerPlayer owner, Player target) {
        return owner.serverLevel().getServer().isPvpAllowed()
                && owner.getPersistentData().getBoolean(OPT_IN)
                && owner.canHarmPlayer(target);
    }

    static void register() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher()
                .register(Commands.literal("dronepvp")
                        .then(Commands.literal("enable").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!player.serverLevel().getServer().isPvpAllowed()) {
                                player.sendSystemMessage(Component.translatable("dronepal.pvp.blocked"));
                                return 0;
                            }
                            pending.put(player, player.serverLevel().getGameTime() + 200);
                            player.sendSystemMessage(Component.translatable("dronepal.pvp.confirm"));
                            return 1;
                        }))
                        .then(Commands.literal("confirm").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            Long until = pending.remove(player);
                            if (until == null || until < player.serverLevel().getGameTime()
                                    || !player.serverLevel().getServer().isPvpAllowed()) return 0;
                            player.getPersistentData().putBoolean(OPT_IN, true);
                            player.sendSystemMessage(Component.translatable("dronepal.pvp.on"));
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            pending.remove(player);
                            player.getPersistentData().remove(OPT_IN);
                            player.sendSystemMessage(Component.translatable("dronepal.pvp.off"));
                            return 1;
                        }))));
    }

    private EngagementPreferences() {}
}
