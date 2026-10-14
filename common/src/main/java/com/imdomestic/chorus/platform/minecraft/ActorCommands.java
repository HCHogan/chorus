package com.imdomestic.chorus.platform.minecraft;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.actor.ActorCodecs;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Operator configuration and read-only self status; content-specific progression stays with the host. */
public final class ActorCommands {
    private ActorCommands() {}
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(value -> Component.literal(String.valueOf(value)));
    private interface Operation { void run(); }
    private static int execute(CommandSourceStack source, ServerPlayer player, Operation action) throws CommandSyntaxException {
        try {
            action.run(); var actor = PlayerActor.get(player);
            source.sendSuccess(() -> Component.literal("Chorus actor: " + actor.snapshot() + " inactive=" + actor.inactiveReason().orElse("none")), false);
            return 1;
        } catch (RuntimeException error) { throw ERROR.create(error.getMessage()); }
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chorus").then(Commands.literal("actor")
                .then(Commands.literal("status").executes(context -> execute(context.getSource(), context.getSource().getPlayerOrException(), () -> {})))
                .then(Commands.literal("set").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("configuration", StringArgumentType.greedyString()).executes(context -> {
                            var player = EntityArgument.getPlayer(context, "player");
                            return execute(context.getSource(), player, () -> {
                                var binding = ActorCodecs.BINDING.parse(JsonOps.INSTANCE, JsonParser.parseString(StringArgumentType.getString(context, "configuration"))).getOrThrow();
                                var actor = PlayerActor.get(player); actor.configure(player, Optional.of(binding), actor.revision());
                            });
                        }))))
                .then(Commands.literal("clear").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", EntityArgument.player()).executes(context -> {
                            var player = EntityArgument.getPlayer(context, "player");
                            return execute(context.getSource(), player, () -> { var actor = PlayerActor.get(player); actor.configure(player, Optional.empty(), actor.revision()); });
                        })))));
    }
}
