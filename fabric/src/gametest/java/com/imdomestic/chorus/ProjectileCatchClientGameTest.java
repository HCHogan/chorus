package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Real key press, registered C2S payload, server catch callback and tracked entity removal. */
public final class ProjectileCatchClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender();
            var entityId = new AtomicInteger(-1); var catches = new AtomicInteger();
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var level = player.level(); String holder = player.getUUID().toString(); player.setHealth(10);
                try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/projectile_catch.json")), StandardCharsets.UTF_8)) {
                    var data = JsonParser.parseReader(reader).getAsJsonObject();
                    var body = data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use");
                    body.get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("duration").addProperty("value", 100);
                    var returning = body.get(5).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject().deepCopy();
                    var spec = returning.getAsJsonObject("projectile"); spec.addProperty("position", "muzzle");
                    spec.getAsJsonObject("speed").addProperty("value", 0); spec.getAsJsonObject("lifetime").addProperty("value", 100);
                    var policy = spec.getAsJsonObject("destination").getAsJsonObject("catch");
                    policy.getAsJsonObject("radius").addProperty("value", 3); policy.getAsJsonObject("opens_after").addProperty("value", 0); policy.getAsJsonObject("closes_after").addProperty("value", 90);
                    body.set(5, returning);
                    var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
                    var world = new MinecraftWorldActions(level, id -> id.equals(holder) ? player : null,
                            _ -> level.damageSources().generic(), (_, _) -> true, cue -> { if (cue.cue().equals("test:caught")) catches.incrementAndGet(); });
                    var runtime = MinecraftEffectRuntime.install(level, program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof PositionQuery q) {
                            var center = player.getBoundingBox().getCenter();
                            return new PositionQuery.Result(q, Optional.of(new WorldPosition(level.dimension().identifier().toString(), center.x, center.y + 2, center.z)));
                        }
                        var result = world.apply(request);
                        if (result instanceof ProjectileFlight.Receipt receipt) entityId.set(Objects.requireNonNull(level.getEntity(UUID.fromString(receipt.entity().orElseThrow()))).getId());
                        return result;
                    }, MinecraftEffectRuntime::nativeSource);
                    runtime.abilities(new AbilityChange(holder, AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))));
                    runtime.useAbility(player, "test:melee");
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) instanceof EffectProjectile);
            context.getInput().pressKey(InputConstants.KEY_G); connection.waitForServerboundPackets();
            context.waitFor(client -> catches.get() == 1 && client.level.getEntity(entityId.get()) == null);
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var runtime = MinecraftEffectRuntime.installed(player.level()).orElseThrow();
                double energy = runtime.state().engine().domain().resources().get(new ResourceState.Key(player.getUUID().toString(), "test:energy")).value();
                if (energy != 2 || player.getHealth() != 20 || runtime.failure().isPresent()) throw new AssertionError("Catch key did not perform server-authoritative refund and healing");
            });
            context.getInput().pressKey(InputConstants.KEY_G); connection.waitForServerboundPackets();
            game.getServer().runOnServer(server -> { if (catches.get() != 1) throw new AssertionError("Consumed projectile caught twice"); });
        }
    }
}
