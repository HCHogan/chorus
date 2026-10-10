package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.test.AbilityInputGameTest;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

/** Real registered V key, paired packets, server clock conversion and GUI cancellation. */
public final class AbilityInputClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender();
            var runtime = new AtomicReference<MinecraftEffectRuntime>(); var serverRef = new AtomicReference<MinecraftServer>();
            var heals = new CopyOnWriteArrayList<HealingCommand>(); var observedHold = new AtomicLong(-1);
            game.getServer().runOnServer(server -> {
                serverRef.set(server); var player = connection.getServerPlayer(); player.setGameMode(GameType.SURVIVAL);
                player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); player.setHealth(10); player.getFoodData().setFoodLevel(0);
                String holder = player.getUUID().toString(); var world = new MinecraftWorldActions(player.level(), id -> id.equals(holder) ? player : null,
                        _ -> player.level().damageSources().generic(), (_, _) -> true, _ -> {});
                runtime.set(MinecraftEffectRuntime.install(player.level(), AbilityInputGameTest.program(), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                    if (request.command() instanceof HealingCommand c) heals.add(c); return world.apply(request);
                }, MinecraftEffectRuntime::nativeSource));
                runtime.get().abilities(new AbilityChange(holder, AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(AbilityInputGameTest.SLOT, "test:tap"))));
                runtime.get().bind(new EffectSource("convert", "test:conversion", holder, new BuffInstance.Origin(holder, "convert", "", ""), Set.of()));
            });
            try {
                connection.waitForClientboundPackets(); context.getInput().pressKey(InputConstants.KEY_V); connection.waitForServerboundPackets();
                context.waitFor(client -> heals.size() == 1);
                if (!heals.getFirst().source().ability().equals("test:tap")) throw new AssertionError("Short key input converted unexpectedly");
                context.getInput().holdKey(InputConstants.KEY_V); connection.waitForServerboundPackets();
                context.waitFor(client -> {
                    serverRef.get().execute(() -> observedHold.set(runtime.get().heldAbilityInput(connection.getServerPlayer(), AbilityInputGameTest.SLOT).orElse(-1)));
                    return observedHold.get() >= 500_000;
                });
                if (heals.size() != 1) throw new AssertionError("Hold fired without release");
                context.getInput().releaseKey(InputConstants.KEY_V); connection.waitForServerboundPackets(); context.waitFor(client -> heals.size() == 2);
                if (!heals.getLast().source().ability().equals("test:hold") || heals.getLast().amount() < 5) throw new AssertionError("Server held duration did not select charged cast");
                context.getInput().holdKey(InputConstants.KEY_V); connection.waitForServerboundPackets();
                context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(client.player))); context.waitTicks(3); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> {
                    if (runtime.get().heldAbilityInput(connection.getServerPlayer(), AbilityInputGameTest.SLOT).isPresent()) throw new AssertionError("GUI retained held input");
                });
                context.getInput().releaseKey(InputConstants.KEY_V); context.runOnClient(client -> client.gui.setScreen(null)); context.waitTicks(3); connection.waitForServerboundPackets();
                game.getServer().runOnServer(server -> {
                    var player = connection.getServerPlayer(); double energy = runtime.get().state().engine().domain().resources().get(new ResourceState.Key(player.getUUID().toString(), "test:energy")).value();
                    if (heals.size() != 2 || energy != 8 || runtime.get().failure().isPresent()) throw new AssertionError("Cancelled key input fired or paid again");
                });
            } finally {
                context.getInput().releaseKey(InputConstants.KEY_V); context.runOnClient(client -> client.gui.setScreen(null));
                game.getServer().runOnServer(server -> runtime.get().close());
            }
        }
    }
}
