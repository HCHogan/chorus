package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

public final class ConstructClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender(); var entityId = new AtomicInteger(-1);
            context.runOnClient(client -> { client.player.setYRot(0); client.player.setXRot(0); });
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var level = player.level(); String owner = player.getUUID().toString();
                var world = new MinecraftWorldActions(level, ref -> level.getEntity(UUID.fromString(ref)) instanceof net.minecraft.world.entity.LivingEntity e ? e : null,
                        _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
                MinecraftEffectRuntime.install(level, com.imdomestic.chorus.test.ConstructGameTest.program(), EffectState.empty(),
                        new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world::apply, MinecraftEffectRuntime::nativeSource);
                var spawn = new WorldConstruct.Spawn(Optional.of(new WorldPosition(level.dimension().identifier().toString(), player.getX(), player.getEyeY(), player.getZ() + 3)),
                        "test:turret", new BuffInstance.Origin(owner, "cast", "", "test:ability"), new WorldConstruct.Parameters(150, 1.25, 2.25, 100_000_000), Set.of(), "test-1");
                var receipt = (WorldConstruct.Receipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), spawn));
                entityId.set(Objects.requireNonNull(level.getEntity(UUID.fromString(receipt.entity().orElseThrow()))).getId());
            });
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) instanceof EffectConstruct);
            context.waitTicks(5);
            context.runOnClient(client -> {
                var entity = (EffectConstruct) client.level.getEntity(entityId.get());
                if (entity == null || Math.abs(entity.getBbWidth() - 1.25) > .0001 || Math.abs(entity.getBbHeight() - 2.25) > .0001)
                    throw new AssertionError("Construct dimensions did not synchronize");
                if (Math.abs(entity.getHealth() - 150) > .0001 || !entity.getItem().is(net.minecraft.world.item.Items.IRON_NUGGET))
                    throw new AssertionError("Construct health or placeholder missing");
            });
            context.takeScreenshot("chorus-construct-tracked");
            game.getServer().runOnServer(server -> MinecraftEffectRuntime.installed(connection.getServerPlayer().level()).orElseThrow().close());
            connection.waitForClientboundPackets(); context.waitFor(client -> client.level.getEntity(entityId.get()) == null);
        }
    }
}
