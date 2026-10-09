package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.ability.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.TreeMap;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Temporary self-service input; assigning an arbitrary skill remains an operator-only setup action. */
public final class AbilityCommands {
    private AbilityCommands() {}
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(value -> Component.literal(String.valueOf(value)));
    private interface Operation { String run(ServerPlayer player, MinecraftEffectRuntime runtime); }
    private static int execute(CommandSourceStack source, Operation operation) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        try {
            var runtime = MinecraftEffectRuntime.installed(player.level()).orElseThrow(() -> new IllegalStateException("No active Chorus runtime"));
            String message = operation.run(player, runtime); source.sendSuccess(() -> Component.literal(message), false); return 1;
        } catch (IllegalArgumentException | IllegalStateException invalid) { throw ERROR.create(invalid.getMessage()); }
    }
    private static String choose(ServerPlayer player, MinecraftEffectRuntime runtime, String slot, String definition) {
        String holder = player.getUUID().toString();
        var before = runtime.state().engine().domain().abilities().getOrDefault(holder, AbilityLoadout.EMPTY); var slots = new TreeMap<>(before.slots());
        if (definition.isEmpty()) slots.remove(slot); else slots.put(slot, definition);
        runtime.abilities(new AbilityChange(holder, before, new AbilityLoadout(slots))); return "Chorus abilities: " + slots;
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chorus").then(Commands.literal("ability")
                .then(Commands.literal("status").executes(c -> execute(c.getSource(), (p, r) -> "Chorus abilities: " + r.state().engine().domain().abilities().getOrDefault(p.getUUID().toString(), AbilityLoadout.EMPTY).slots())))
                .then(Commands.literal("choose").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("slot", IdentifierArgument.id()).then(Commands.argument("definition", IdentifierArgument.id())
                                .executes(c -> execute(c.getSource(), (p, r) -> choose(p, r, c.getArgument("slot", Identifier.class).toString(), c.getArgument("definition", Identifier.class).toString()))))))
                .then(Commands.literal("clear").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("slot", IdentifierArgument.id()).executes(c -> execute(c.getSource(), (p, r) -> choose(p, r, c.getArgument("slot", Identifier.class).toString(), "")))))
                .then(Commands.literal("use").then(Commands.argument("slot", IdentifierArgument.id()).executes(c -> execute(c.getSource(), (p, r) -> {
                    var result = r.useAbility(p, c.getArgument("slot", Identifier.class).toString());
                    if (result.outcome() != AbilityUse.Outcome.ACCEPTED) throw new IllegalArgumentException("Ability rejected: " + result.outcome());
                    return "Chorus ability: " + result.resolved() + " paid=" + result.cost().map(cost -> cost.receipt().paid()).orElse(0.0);
                }))))));
    }
}
