package dev.dronepal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** The same command tree is used in the game and the offline integration test. */
final class DialogueCommands {
    interface Handler<S> { int say(S source, String input) throws CommandSyntaxException; }
    static <S> void register(CommandDispatcher<S> dispatcher, Handler<S> handler) {
        var text = RequiredArgumentBuilder.<S,String>argument("message", StringArgumentType.greedyString())
            .executes(ctx -> handler.say(ctx.getSource(), StringArgumentType.getString(ctx, "message")));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("dronepal")
            .executes(ctx -> handler.say(ctx.getSource(), "帮助"))
            .then(LiteralArgumentBuilder.<S>literal("say").then(text))
            .then(text));
    }
}
