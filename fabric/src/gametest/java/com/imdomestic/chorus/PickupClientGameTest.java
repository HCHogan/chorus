package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** The generic object must track and render with the vanilla item renderer, then disappear on collection. */
public final class PickupClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender(); var entityId = new AtomicInteger(-1);
            context.runOnClient(client -> { client.player.setYRot(0); client.player.setXRot(0); });
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var level = player.level(); String holder = player.getUUID().toString();
                try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/pickup.json")), StandardCharsets.UTF_8)) {
                    var data = JsonParser.parseReader(reader).getAsJsonObject();
                    data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(2).getAsJsonObject().getAsJsonObject("pickup").getAsJsonObject("lifetime").addProperty("value", 100);
                    var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
                    var world = new MinecraftWorldActions(level, ref -> level.getEntity(UUID.fromString(ref)) instanceof net.minecraft.world.entity.LivingEntity e ? e : null,
                            _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
                    var source = new EffectSource("client", "test:pickup", holder, new BuffInstance.Origin(holder, "client", "", ""), Set.of());
                    var runtime = MinecraftEffectRuntime.install(level, program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof PositionQuery q) return new PositionQuery.Result(q, Optional.of(new WorldPosition(level.dimension().identifier().toString(), player.getX(), player.getEyeY(), player.getZ()+3)));
                        var result = world.apply(request);
                        if (result instanceof WorldPickup.Receipt receipt) entityId.set(Objects.requireNonNull(level.getEntity(UUID.fromString(receipt.entity().orElseThrow()))).getId());
                        return result;
                    }, MinecraftEffectRuntime::nativeSource);
                    runtime.start(new RuleEngine.Signal("test:spawn", new EffectEvent(holder, holder, source.origin(), Set.of(), Map.of())));
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) instanceof EffectObject);
            context.waitTicks(5);
            context.runOnClient(client -> {
                var object = (EffectObject) client.level.getEntity(entityId.get());
                if (object == null || !object.getItem().is(net.minecraft.world.item.Items.GLOWSTONE_DUST)) throw new AssertionError("Tracked pickup appearance missing");
            });
            context.takeScreenshot("chorus-pickup-tracked");
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var object = (EffectObject) player.level().getEntity(entityId.get());
                object.setPos(player.position()); object.tick();
                if (!object.isRemoved()) throw new AssertionError("Server pickup not consumed");
            });
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) == null);
        }
    }
}
