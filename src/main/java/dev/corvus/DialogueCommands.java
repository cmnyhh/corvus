package dev.corvus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * The {@code /dronepal ...} tree: bare {@code /dronepal} shows help, {@code /dronepal <text>} and
 * {@code /dronepal say <text>} both route to the same handler, so the offline integration test can
 * drive the exact tree the server registers.
 */
final class DialogueCommands {
    interface Handler<S> {
        int say(S source, String input) throws CommandSyntaxException;
    }

    static <S> void register(CommandDispatcher<S> dispatcher, Handler<S> handler) {
        RequiredArgumentBuilder<S, String> text = RequiredArgumentBuilder
                .<S, String>argument("message", StringArgumentType.greedyString())
                .executes(ctx -> handler.say(ctx.getSource(), StringArgumentType.getString(ctx, "message")));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("dronepal")
                .executes(ctx -> handler.say(ctx.getSource(), "帮助"))
                .then(LiteralArgumentBuilder.<S>literal("say").then(text))
                .then(text));
    }

    private DialogueCommands() {}
}
