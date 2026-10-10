package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.network.ProjectileCatchNetworkServer.Reply.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.EffectClock;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.platform.minecraft.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ProjectileCatchGameTest {
    private static ProjectileGameTest.Harness harness(GameTestHelper h, String fixture, java.util.function.Consumer<JsonObject> edit) throws Exception {
        return new ProjectileGameTest.Harness(h, fixture, edit, true);
    }
    private static JsonObject returning(JsonObject data) { return data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(5).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject(); }
    private static double energy(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().resources().get(new ResourceState.Key(t.owner.getUUID().toString(), "test:energy")).value(); }
    private static ProjectileCatchPayload input(ProjectileGameTest.Harness t, long n) { return new ProjectileCatchPayload(n, t.h.getLevel().dimension().identifier().toString()); }
    private static void nearby(ProjectileGameTest.Harness t, EffectProjectile p, double distance) { p.setPos(t.owner.getBoundingBox().getCenter().add(0, distance, 0)); p.setDeltaMovement(Vec3.ZERO); }
    private static EffectProjectile returning(ProjectileGameTest.Harness t) {
        if (t.entities.isEmpty()) t.cow(2.5, 46, 3.5);
        if (t.runtime.state().engine().domain().abilities().isEmpty()) t.runtime.abilities(new AbilityChange(t.owner.getUUID().toString(), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))));
        int count = t.projectiles.size();
        t.h.assertValueEqual(t.runtime.useAbility(t.owner, "test:melee").outcome(), AbilityUse.Outcome.ACCEPTED, "paid cast accepted");
        var outward = t.projectiles.getLast();
        for (int tick = 0; tick < 10 && t.projectiles.size() == count + 1; tick++) outward.tick();
        t.h.assertValueEqual(t.projectiles.size(), count + 2, "physical hit launched return");
        var back = t.projectiles.getLast(); nearby(t, back, 2); return back;
    }
    @GameCase public void networkRoundTripCatchesDetachedReturnAndDuplicateCannotRunActionsAgain(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_catch", _ -> {})) {
            var p = returning(t); p.tick(); var network = new ProjectileCatchNetworkServer();
            t.runtime.unbind("launch"); t.runtime.unbind("boost");
            t.runtime.abilities(new AbilityChange(t.owner.getUUID().toString(), t.runtime.state().engine().domain().abilities().get(t.owner.getUUID().toString()), AbilityLoadout.EMPTY));
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                ProjectileCatchPayload.CODEC.encode(buffer, input(t, 1)); var wire = ProjectileCatchPayload.CODEC.decode(buffer);
                h.assertValueEqual(wire, input(t, 1), "wire preserves sequence and dimension");
                h.assertValueEqual(network.request(t.owner, wire), CAUGHT, "connection owner catches return");
                h.assertValueEqual(network.request(t.owner, wire), DUPLICATE, "input replay rejected");
            } finally { buffer.release(); }
            near(h, energy(t), 2, "full caught refund"); near(h, t.owner.getHealth(), 20, "refund-dependent actual healing");
            h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:caught"), "only caught callback");
            h.assertTrue(p.isRemoved() && p.progress().terminal() && p.progress().entityContacts() == 0, "catch consumes without a hit");
            h.assertTrue(t.runtime.state().engine().domain().retainedCosts().isEmpty(), "caught closes retained cost");
        }
        h.succeed();
    }
    @GameCase public void windowAndDistanceAreCheckedAtInputAndMissedInputDoesNotLatch(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_catch", _ -> {})) {
            var p = returning(t); var network = new ProjectileCatchNetworkServer();
            h.assertValueEqual(network.request(t.owner, input(t, 1)), NO_PROJECTILE, "before window opens");
            p.tick(); nearby(t, p, 2.001);
            h.assertValueEqual(network.request(t.owner, input(t, 2)), NO_PROJECTILE, "outside closed catch radius");
            nearby(t, p, 2); h.assertValueEqual(network.request(t.owner, input(t, 2)), DUPLICATE, "miss cannot turn into a later catch");
            h.assertValueEqual(network.request(t.owner, input(t, 3)), CAUGHT, "fresh input at exact radius and open time");
        }
        try (var t = harness(h, "projectile_catch", _ -> {})) {
            var p = returning(t); for (int tick = 0; tick < 10; tick++) p.tick();
            h.assertTrue(t.runtime.catchProjectile(t.owner).isEmpty(), "exact closing time is excluded");
            nearby(t, p, 0); p.tick();
            h.assertValueEqual(t.cues.getLast().cue(), "test:arrived", "late input leaves automatic arrival intact"); near(h, energy(t), 1.25, "automatic arrival uses smaller data-defined refund");
        }
        h.succeed();
    }
    @GameCase public void receiverIdentityPlayerStateAndDimensionAreServerOwned(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_catch", _ -> {})) {
            var p = returning(t); p.tick(); var network = new ProjectileCatchNetworkServer(); var other = h.makeMockServerPlayerInLevel();
            try {
                other.setPos(t.owner.position());
                h.assertValueEqual(network.request(other, input(t, 1)), NO_PROJECTILE, "another nearby player cannot steal captured receiver's flight");
                t.owner.setGameMode(GameType.SPECTATOR);
                h.assertValueEqual(network.request(t.owner, input(t, 1)), REJECTED, "spectator input rejected"); t.owner.setGameMode(GameType.SURVIVAL);
                t.owner.setHealth(0); h.assertValueEqual(network.request(t.owner, input(t, 2)), REJECTED, "dead receiver rejected"); t.owner.setHealth(10);
                h.assertValueEqual(network.request(t.owner, new ProjectileCatchPayload(3, "test:another_dimension")), REJECTED, "old dimension input rejected");
                h.assertValueEqual(network.request(t.owner, input(t, 3)), DUPLICATE, "rejected input is still spent");
                h.assertValueEqual(network.request(t.owner, input(t, 4)), CAUGHT, "live owner sends new input");
            } finally { other.discard(); }
        }
        h.succeed();
    }
    @GameCase public void lineOfSightUsesWorldGeometryUnlessExplicitlyDisabled(GameTestHelper h) throws Exception {
        for (boolean visible : List.of(true, false)) try (var t = harness(h, "projectile_catch", d -> returning(d).getAsJsonObject("projectile").getAsJsonObject("destination").getAsJsonObject("catch").addProperty("line_of_sight", visible))) {
            var p = returning(t); p.tick(); var pos = BlockPos.containing(t.owner.getBoundingBox().getCenter().add(0, 1, 0));
            t.blocks.put(pos, h.getLevel().getBlockState(pos)); h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            h.assertValueEqual(t.runtime.catchProjectile(t.owner).isPresent(), !visible, "catch visibility follows declared policy");
            near(h, energy(t), visible ? 1 : 2, "occluded catch cannot refund");
        }
        h.succeed();
    }
    @GameCase public void eachInputConsumesOneNearestFlightWithDeterministicTies(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_catch", d -> {
            var resource = d.getAsJsonArray("resources").get(0).getAsJsonObject(); resource.addProperty("capacity", 4); resource.addProperty("initial", 4);
        })) {
            var a = returning(t); a.tick(); var b = returning(t); b.tick(); var nearest = returning(t); nearest.tick(); nearby(t, nearest, 1);
            h.getLevel().getServer().getCommands().getDispatcher().execute("chorus ability catch", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS));
            h.assertTrue(nearest.isRemoved() && !a.isRemoved() && !b.isRemoved(), "self-service input consumes only nearest flight");
            var first = a.getUUID().compareTo(b.getUUID()) < 0 ? a : b; var second = first == a ? b : a;
            var network = new ProjectileCatchNetworkServer(); h.assertValueEqual(network.request(t.owner, input(t, 1)), CAUGHT, "new input catches next");
            h.assertTrue(first.isRemoved() && !second.isRemoved(), "equal distance uses UUID order");
            h.assertValueEqual(network.request(t.owner, input(t, 1)), DUPLICATE, "duplicate cannot catch remaining flight");
            h.assertTrue(!second.isRemoved(), "remaining flight retained");
            h.assertValueEqual(network.request(t.owner, input(t, 2)), CAUGHT, "next intentional input may catch remaining flight"); near(h, energy(t), 4, "each independent cost refunded once");
        }
        h.succeed();
    }
    @GameCase public void replacingRuntimeCannotCatchFlightsOwnedByPreviousInstance(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_catch", _ -> {})) {
            var p = returning(t); p.tick(); var state = t.runtime.state().engine().domain(); t.runtime.close();
            try (var replacement = MinecraftEffectRuntime.install(h.getLevel(), t.runtime.program(), state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), t.world::apply, MinecraftEffectRuntime::nativeSource)) {
                h.assertTrue(replacement.catchProjectile(t.owner).isEmpty(), "replacement cannot execute old captured continuation");
                p.tick(); h.assertTrue(p.isRemoved(), "old flight abandoned"); h.assertTrue(t.cues.isEmpty(), "no old refund body");
            }
        }
        h.succeed();
    }
    @GameCase public void unknownCatchHealingKeepsRefundAndConsumesFlightAndInputWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_catch", _ -> {})) {
            var p = returning(t); p.tick(); var network = new ProjectileCatchNetworkServer(); t.failAfterHealing = true;
            h.assertValueEqual(network.request(t.owner, input(t, 1)), FAILED, "unknown world outcome reported");
            near(h, energy(t), 2, "confirmed refund remains"); near(h, t.owner.getHealth(), 20, "completed world heal remains");
            h.assertTrue(p.isRemoved() && t.runtime.failure().isPresent(), "flight consumed and runtime stopped");
            h.assertValueEqual(network.request(t.owner, input(t, 1)), DUPLICATE, "failed input cannot repeat"); t.failAfterHealing = false; p.tick();
            h.assertValueEqual(t.cues.size(), 1, "no replayed catch callback");
        }
        h.succeed();
    }
    @GameCase public void projectilesWithoutDeclaredCatchRemainOrdinaryFlights(GameTestHelper h) throws Exception {
        try (var t = harness(h, "projectile_return", _ -> {})) {
            var p = returning(t); p.tick(); h.assertTrue(t.runtime.catchProjectile(t.owner).isEmpty() && !p.isRemoved(), "destination alone does not grant catchability");
            nearby(t, p, 0); p.tick(); near(h, energy(t), 2, "ordinary auto arrival still works");
        }
        h.succeed();
    }
}
