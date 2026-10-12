package com.imdomestic.chorus;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.phys.Vec3;

/** Rotation-only vanilla packets change the real local aim and return through native movement input. */
public final class AimImpulseClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var game = context.worldBuilder().create()) {
            var connection = game.getConnection(); connection.waitForChunksRender();
            var position = new AtomicReference<Vec3>(); var receipt = new AtomicReference<AimImpulse.Receipt>();
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); player.setNoGravity(true);
                position.set(player.position()); player.connection.teleport(player.getX(), player.getY(), player.getZ(), 40, -85);
            });
            connection.waitForClientboundPackets(); context.waitFor(client -> Math.abs(client.player.getYRot() - 40) < .01);
            context.runOnClient(client -> { client.player.setNoGravity(true); client.player.setDeltaMovement(Vec3.ZERO); });
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer(); var id = player.getUUID().toString();
                var world = new MinecraftWorldActions(player.level(), target -> target.equals(id) ? player : null, _ -> player.level().damageSources().generic(), (_, _) -> true, _ -> {});
                receipt.set((AimImpulse.Receipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0),
                        new AimImpulse.Command(id, 3, -12, new BuffInstance.Origin(id, "test", "", ""), Set.of()))));
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> Math.abs(client.player.getYRot() - 43) < .01 && Math.abs(client.player.getXRot() + 90) < .01);
            connection.waitForServerboundPackets();
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer();
                if (receipt.get().outcome() != AimImpulse.Outcome.APPLIED || receipt.get().requireChange().pitch() != -5)
                    throw new AssertionError("Missing actual pitch limit receipt");
                if (Math.abs(player.getYRot() - 43) > .01 || Math.abs(player.getXRot() + 90) > .01)
                    throw new AssertionError("Client rotation did not return to the server");
            });
            // Native mouse turning remains available after the impulse; there is no held camera lock.
            context.runOnClient(client -> client.player.turn(100, 100));
            context.waitTicks(3); connection.waitForServerboundPackets();
            game.getServer().runOnServer(server -> {
                var player = connection.getServerPlayer();
                if (Math.abs(player.getYRot() - 58) > .01 || Math.abs(player.getXRot() + 75) > .01)
                    throw new AssertionError("Post-impulse mouse movement was overwritten");
                if (player.position().distanceTo(position.get()) > .01) throw new AssertionError("Aim impulse displaced the player");
            });
        }
    }
}
