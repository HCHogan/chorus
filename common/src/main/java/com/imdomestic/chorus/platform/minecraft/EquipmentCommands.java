package com.imdomestic.chorus.platform.minecraft;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Minimal self-service server interface; item creation/stamping remains an operator action. */
public final class EquipmentCommands {
    private EquipmentCommands() {}
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(value -> Component.literal(String.valueOf(value)));
    private static int execute(CommandSourceStack source, Consumer<ServerPlayer> action) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        try {
            action.accept(player);
            source.sendSuccess(() -> Component.literal("Chorus equipment revision=" + PlayerEquipment.get(player).revision()
                    + " slots=" + PlayerEquipment.get(player).snapshot().items().keySet()
                    + " drawn=" + PlayerEquipment.get(player).snapshot().drawn().orElse("none")
                    + PlayerEquipment.get(player).inactiveReason().map(reason -> " inactive=" + reason).orElse("")), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { throw ERROR.create(error.getMessage()); }
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chorus").then(Commands.literal("equipment")
                .then(Commands.literal("status").executes(c -> execute(c.getSource(), _ -> {})))
                .then(Commands.literal("swap").then(Commands.argument("slot", IdentifierArgument.id())
                        .then(Commands.argument("inventory", IntegerArgumentType.integer(0)).then(Commands.argument("revision", LongArgumentType.longArg(0))
                                .executes(c -> execute(c.getSource(), p -> PlayerEquipment.get(p).swap(p, c.getArgument("slot", Identifier.class).toString(), IntegerArgumentType.getInteger(c, "inventory"), LongArgumentType.getLong(c, "revision"))))))))
                .then(Commands.literal("draw").then(Commands.argument("slot", IdentifierArgument.id()).then(Commands.argument("revision", LongArgumentType.longArg(0))
                        .executes(c -> execute(c.getSource(), p -> PlayerEquipment.get(p).draw(p, Optional.of(c.getArgument("slot", Identifier.class).toString()), LongArgumentType.getLong(c, "revision")))))))
                .then(Commands.literal("stow").then(Commands.argument("revision", LongArgumentType.longArg(0))
                        .executes(c -> execute(c.getSource(), p -> PlayerEquipment.get(p).draw(p, Optional.empty(), LongArgumentType.getLong(c, "revision"))))))
                .then(Commands.literal("move").then(Commands.argument("from", IdentifierArgument.id()).then(Commands.argument("to", IdentifierArgument.id())
                        .then(Commands.argument("revision", LongArgumentType.longArg(0)).executes(c -> execute(c.getSource(), p -> PlayerEquipment.get(p).move(p,
                                c.getArgument("from", Identifier.class).toString(), c.getArgument("to", Identifier.class).toString(), LongArgumentType.getLong(c, "revision"))))))))
                .then(Commands.literal("stamp").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("slot", IdentifierArgument.id()).then(Commands.argument("definition", IdentifierArgument.id())
                                .then(Commands.argument("choices", StringArgumentType.greedyString()).executes(c -> execute(c.getSource(), p -> {
                                    var runtime = MinecraftEffectRuntime.installed(p.level()).orElseThrow(() -> new IllegalStateException("Stamp requires an installed ruleset"));
                                    var held = p.getInventory().getSelectedItem();
                                    if (held.getCount() != 1 || held.has(ChorusComponents.EQUIPMENT.get())) throw new IllegalArgumentException("Stamp requires one unstamped held item");
                                    Map<String,String> choices;
                                    try { choices = Codec.unboundedMap(Codec.STRING, Codec.STRING).parse(JsonOps.INSTANCE, JsonParser.parseString(StringArgumentType.getString(c, "choices"))).getOrThrow(); }
                                    catch (RuntimeException invalid) { throw new IllegalArgumentException("Choices must be a JSON object of socket/option strings", invalid); }
                                    var gear = new Loadout.Gear(UUID.randomUUID().toString(), c.getArgument("definition", Identifier.class).toString(), choices);
                                    runtime.program().equipment().validate(new Loadout(Map.of(c.getArgument("slot", Identifier.class).toString(), gear), Optional.empty()));
                                    held.set(ChorusComponents.EQUIPMENT.get(), gear); p.getInventory().setChanged(); p.inventoryMenu.broadcastChanges();
                                }))))))));
    }
}
