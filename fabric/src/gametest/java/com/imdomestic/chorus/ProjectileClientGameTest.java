package com.imdomestic.chorus;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
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

/** Native entity spawn/tracking packet and real item renderer, without a custom client effect payload. */
public final class ProjectileClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender(); var entityId = new AtomicInteger(-1);
            context.runOnClient(client -> { client.player.setYRot(0); client.player.setXRot(0); });
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var level = player.level(); String holder = player.getUUID().toString();
                player.setYRot(0); player.setXRot(0);
                try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/projectile.json")), StandardCharsets.UTF_8)) {
                    var data = JsonParser.parseReader(reader).getAsJsonObject();
                    var spec = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile");
                    spec.getAsJsonObject("speed").addProperty("value", 0); spec.getAsJsonObject("lifetime").addProperty("value", 100);
                    spec.add("collision", JsonParser.parseString("{\"block_bounces\": {\"type\": \"chorus:constant\", \"value\": 1, \"unit\": \"count\"}}"));
                    var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
                    var world = new MinecraftWorldActions(level, ref -> level.getEntity(UUID.fromString(ref)) instanceof net.minecraft.world.entity.LivingEntity e ? e : null,
                            _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
                    var source = new EffectSource("client", "test:projectile", holder, new BuffInstance.Origin(holder, "client", "", "test:bolt"), Set.of());
                    var runtime = MinecraftEffectRuntime.install(level, program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof PositionQuery q) return new PositionQuery.Result(q, Optional.of(new WorldPosition(level.dimension().identifier().toString(), player.getX(), player.getEyeY(), player.getZ() + 3)));
                        var result = world.apply(request);
                        if (result instanceof ProjectileFlight.Receipt receipt) entityId.set(Objects.requireNonNull(level.getEntity(UUID.fromString(receipt.entity().orElseThrow()))).getId());
                        return result;
                    }, MinecraftEffectRuntime::nativeSource);
                    runtime.start(new RuleEngine.Signal("test:launch", new EffectEvent(holder, holder, source.origin(), Set.of(), Map.of())));
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) instanceof EffectProjectile);
            context.waitTicks(5);
            context.runOnClient(client -> {
                var projectile = (EffectProjectile) client.level.getEntity(entityId.get());
                if (projectile == null || !projectile.getItem().is(net.minecraft.world.item.Items.AMETHYST_SHARD)) throw new AssertionError("Tracked projectile appearance missing");
            });
            context.takeScreenshot("chorus-projectile-tracked");
            game.getServer().runOnServer(server -> {
                var level = connection.getServerPlayer().level(); var projectile = (EffectProjectile) level.getEntity(entityId.get());
                var wall = net.minecraft.core.BlockPos.containing(projectile.getX(), projectile.getY(), projectile.getZ() + 2);
                level.setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                projectile.setDeltaMovement(0, 0, 0.25);
            });
            context.waitFor(client -> client.level.getEntity(entityId.get()) instanceof EffectProjectile p && p.getDeltaMovement().z < -0.1);
            game.getServer().runOnServer(server -> {
                var projectile = (EffectProjectile) connection.getServerPlayer().level().getEntity(entityId.get());
                if (projectile == null || projectile.progress().bounces() != 1 || projectile.getDeltaMovement().z >= 0)
                    throw new AssertionError("Client reflected velocity must match a real server surface bounce");
            });
            game.getServer().runOnServer(server -> connection.getServerPlayer().level().getEntity(entityId.get()).discard());
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) == null);
        }
    }
}
