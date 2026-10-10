package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;

/** Temporary ordinary-player input; no supplied target, item identity, ammunition quantity or timing. */
public final class WeaponCommands {
    private WeaponCommands() {}
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(value -> Component.literal(String.valueOf(value)));
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chorus").then(Commands.literal("weapon")
                .then(Commands.literal("reload").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    try {
                        var runtime = MinecraftEffectRuntime.installed(player.level()).orElseThrow(() -> new IllegalStateException("No active Chorus runtime"));
                        var receipt = runtime.reload(player);
                        if (receipt.outcome() != WeaponReload.Outcome.ACCEPTED) throw new IllegalArgumentException("Reload rejected: " + receipt.outcome());
                        var plan = receipt.plan().orElseThrow();
                        context.getSource().sendSuccess(() -> Component.literal("Chorus reload: " + plan.gear().definition() + " duration=" + plan.duration().value() + "s"), false);
                        return 1;
                    } catch (IllegalArgumentException | IllegalStateException invalid) { throw ERROR.create(invalid.getMessage()); }
                }))
                .then(Commands.literal("status").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    try {
                        var runtime = MinecraftEffectRuntime.installed(player.level()).orElseThrow(() -> new IllegalStateException("No active Chorus runtime")); runtime.prepare();
                        var state = runtime.state().engine().domain(); String holder = player.getUUID().toString();
                        var gear = WeaponReload.drawn(state.equipment().getOrDefault(holder, Loadout.EMPTY)).orElseThrow(() -> new IllegalArgumentException("No drawn weapon"));
                        var ammo = runtime.program().ammoCapacity(state, gear.instance());
                        String message = "Chorus weapon: " + gear.definition() + " magazine=" + ammo.account().magazine() + "/" + ammo.capacity()
                                + " reserves=" + ammo.account().reserve().map(r -> Integer.toString(r.rounds())).orElse("unlimited") + " reloading=" + state.reloads().containsKey(holder);
                        context.getSource().sendSuccess(() -> Component.literal(message), false); return 1;
                    } catch (IllegalArgumentException | IllegalStateException invalid) { throw ERROR.create(invalid.getMessage()); }
                }))));
    }
}
