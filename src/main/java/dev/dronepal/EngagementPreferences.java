package dev.dronepal;

import java.util.WeakHashMap;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit, expiring two-step opt-in; vanilla server PVP always takes precedence. */
final class EngagementPreferences {
    private static final String OPT_IN = "dronepal_pvp";
    private static final WeakHashMap<ServerPlayer,Long> pending = new WeakHashMap<>();
    static boolean accepts(ServerPlayer owner, Player target) {
        return owner.serverLevel().getServer().isPvpAllowed() && owner.getPersistentData().getBoolean(OPT_IN) && owner.canHarmPlayer(target);
    }
    static void register() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(Commands.literal("dronepvp")
            .then(Commands.literal("enable").executes(ctx -> {
                var p=ctx.getSource().getPlayerOrException();
                if (!p.serverLevel().getServer().isPvpAllowed()) { p.sendSystemMessage(Component.translatable("dronepal.pvp.blocked")); return 0; }
                pending.put(p,p.serverLevel().getGameTime()+200);
                p.sendSystemMessage(Component.translatable("dronepal.pvp.confirm")); return 1;
            }))
            .then(Commands.literal("confirm").executes(ctx -> {
                var p=ctx.getSource().getPlayerOrException();
                Long until=pending.remove(p);
                if (until==null || until<p.serverLevel().getGameTime() || !p.serverLevel().getServer().isPvpAllowed()) return 0;
                p.getPersistentData().putBoolean(OPT_IN,true); p.sendSystemMessage(Component.translatable("dronepal.pvp.on")); return 1;
            }))
            .then(Commands.literal("off").executes(ctx -> {
                var p=ctx.getSource().getPlayerOrException();
                pending.remove(p); p.getPersistentData().remove(OPT_IN); p.sendSystemMessage(Component.translatable("dronepal.pvp.off")); return 1;
            }))));
    }
}
