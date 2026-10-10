package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectProgram;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.Set;
import java.util.function.IntSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Administrative engine controls; gameplay equipment/skills will use typed server-side APIs. */
public final class EffectCommands {
    private EffectCommands() {}
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(value -> Component.literal(String.valueOf(value)));
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        EquipmentCommands.register(dispatcher);
        AbilityCommands.register(dispatcher);
        WeaponCommands.register(dispatcher);
        dispatcher.register(Commands.literal("chorus").then(Commands.literal("engine")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(context -> {
                    var ids = EffectPrograms.ids(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.literal("Chorus programs: " + ids), false); return ids.size();
                }))
                .then(Commands.literal("start").then(Commands.argument("program", IdentifierArgument.id())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(EffectPrograms.ids(context.getSource().getServer()), builder))
                        .executes(context -> start(context.getSource(), context.getArgument("program", Identifier.class), EffectState.Mode.PVE))
                        .then(Commands.literal("pve").executes(context -> start(context.getSource(), context.getArgument("program", Identifier.class), EffectState.Mode.PVE)))
                        .then(Commands.literal("pvp").executes(context -> start(context.getSource(), context.getArgument("program", Identifier.class), EffectState.Mode.PVP)))))
                .then(Commands.literal("status").executes(context -> checked(() -> {
                    var runtime = runtime(context.getSource()); var state = runtime.state().engine().domain();
                    context.getSource().sendSuccess(() -> Component.literal("Chorus version=" + runtime.program().program().version()
                            + " mode=" + state.mode() + " time=" + state.buffs().timeMicros() + "us sources=" + state.sources().size()
                            + " buffs=" + state.buffs().instances().size() + " timers=" + state.timers().size()
                            + " failure=" + runtime.failure().map(MinecraftEffectRuntime.Failure::message).orElse("none")), false); return 1;
                })))
                .then(Commands.literal("stop").executes(context -> checked(() -> {
                    runtime(context.getSource()).close();
                    context.getSource().sendSuccess(() -> Component.literal("Chorus runtime stopped; transient state discarded."), true); return 1;
                })))
                .then(Commands.literal("attach").then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("bundle", IdentifierArgument.id()).suggests((context, builder) -> {
                            var installed = MinecraftEffectRuntime.installed(context.getSource().getLevel());
                            return SharedSuggestionProvider.suggestResource(installed.stream().flatMap(value -> value.program().program().bundles().stream())
                                    .filter(bundle -> bundle.scope() == EffectProgram.Scope.SOURCE).map(bundle -> Identifier.parse(bundle.id())), builder);
                        }).then(Commands.argument("slot", StringArgumentType.word()).executes(context -> attach(context.getSource(),
                                EntityArgument.getEntity(context, "target"), context.getArgument("bundle", Identifier.class), StringArgumentType.getString(context, "slot")))))))
                .then(Commands.literal("detach").then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("slot", StringArgumentType.word()).executes(context -> detach(context.getSource(),
                                EntityArgument.getEntity(context, "target"), StringArgumentType.getString(context, "slot"))))))));
    }
    private static int checked(IntSupplier operation) throws CommandSyntaxException {
        try { return operation.getAsInt(); }
        catch (IllegalArgumentException | IllegalStateException failure) { throw ERROR.create(failure.getMessage()); }
    }
    private static MinecraftEffectRuntime runtime(CommandSourceStack source) {
        return MinecraftEffectRuntime.installed(source.getLevel()).orElseThrow(() -> new IllegalArgumentException("No Chorus runtime in this dimension"));
    }
    private static int start(CommandSourceStack source, Identifier program, EffectState.Mode mode) throws CommandSyntaxException {
        return checked(() -> {
            if (MinecraftEffectRuntime.installed(source.getLevel()).isPresent()) throw new IllegalStateException("A runtime already exists; stop it explicitly before starting another program");
            var runtime = EffectPrograms.install(source.getLevel(), program, mode);
            source.sendSuccess(() -> Component.literal("Chorus started " + program + " version=" + runtime.program().program().version() + " mode=" + mode), true); return 1;
        });
    }
    private static String instance(CommandSourceStack source, Entity target, String slot) {
        if (!(target instanceof LivingEntity) || target.level() != source.getLevel()) throw new IllegalArgumentException("Target must be a living entity in this dimension");
        if (!slot.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid source slot");
        return target.getUUID() + "/admin/" + slot;
    }
    private static int attach(CommandSourceStack source, Entity target, Identifier bundle, String slot) throws CommandSyntaxException {
        return checked(() -> {
            String id = instance(source, target, slot); String holder = target.getUUID().toString();
            if (!target.isAlive()) throw new IllegalArgumentException("Cannot attach a source to a dead entity");
            runtime(source).bind(new EffectSource(id, bundle.toString(), holder, new BuffInstance.Origin(holder, id, "", ""), Set.of("chorus:admin_source")));
            source.sendSuccess(() -> Component.literal("Chorus attached " + bundle + " to " + id), true); return 1;
        });
    }
    private static int detach(CommandSourceStack source, Entity target, String slot) throws CommandSyntaxException {
        return checked(() -> {
            String id = instance(source, target, slot); runtime(source).unbind(id);
            source.sendSuccess(() -> Component.literal("Chorus detached " + id), true); return 1;
        });
    }
}
