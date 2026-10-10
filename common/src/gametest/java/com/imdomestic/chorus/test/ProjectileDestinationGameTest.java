package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ProjectileDestinationGameTest {
    private static JsonArray outboundBody(JsonObject data) { return data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(5).getAsJsonObject().getAsJsonArray("do"); }
    private static JsonObject returning(JsonObject data) { return outboundBody(data).get(1).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject(); }
    private static JsonObject destination(JsonObject data) { return returning(data).getAsJsonObject("projectile").getAsJsonObject("destination"); }
    private static double energy(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().resources().get(new ResourceState.Key(t.owner.getUUID().toString(), "test:energy")).value(); }
    private static void cast(ProjectileGameTest.Harness t) throws Exception {
        t.runtime.abilities(new AbilityChange(t.owner.getUUID().toString(), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))));
        t.h.getLevel().getServer().getCommands().getDispatcher().execute("chorus ability use test:melee", t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS));
    }
    private static EffectProjectile returnFlight(ProjectileGameTest.Harness t) throws Exception {
        t.cow(2.5, 46, 3.5); cast(t); var outbound = t.projectiles.getFirst();
        for (int tick = 0; tick < 10 && t.projectiles.size() < 2; tick++) outbound.tick();
        t.h.assertValueEqual(t.projectiles.size(), 2, "outbound physical collision launches returning flight");
        t.h.assertValueEqual(t.hits.size(), 1, "one actual outward hit"); return t.projectiles.getLast();
    }
    private static void placeBlock(ProjectileGameTest.Harness t, BlockPos pos) { t.blocks.putIfAbsent(pos, t.h.getLevel().getBlockState(pos)); t.h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()); }
    private static Vec3 body(LivingEntity entity) { return entity.getBoundingBox().getCenter(); }
    private static void healthy(ProjectileGameTest.Harness t) { t.h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "return runtime failed: " + t.runtime.failure()); }
    @GameCase(environment = "chorus_gametest:projectile_return", maxTicks = 40)
    public void realTicksReturnFromImpactToMovingCasterAndRefundOnlyAfterArrival(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "projectile_return", _ -> {});
        try {
            var target = t.cow(2.5, 46, 3.5); cast(t); near(h, energy(t), 1, "cost remains paid during outgoing flight");
            t.runtime.unbind("launch"); t.runtime.unbind("boost");
            t.runtime.abilities(new AbilityChange(t.owner.getUUID().toString(), t.runtime.state().engine().domain().abilities().get(t.owner.getUUID().toString()), AbilityLoadout.EMPTY));
            h.runAfterDelay(2, () -> t.owner.setPos(h.absoluteVec(new Vec3(5, 40, 3.5))));
            t.finish(18, () -> {
                near(h, target.getHealth(), 90, "real outward damage"); near(h, energy(t), 2, "original cost returned after reaching moved owner"); near(h, t.owner.getHealth(), 20, "actual refund-dependent healing");
                h.assertValueEqual(t.projectiles.size(), 2, "separate outgoing and returning phases"); h.assertValueEqual(t.hits.size(), 1, "arrival is not a second damage hit");
                var back = t.projectiles.getLast(); h.assertTrue(back.isRemoved() && back.progress().terminal() && back.progress().entityContacts() == 0, "arrival terminates without a collision hit");
                h.assertTrue(back.position().distanceTo(body(t.owner)) <= .30001, "arrival uses current receiver position");
                h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:arrived"), "one arrived outcome");
                h.assertTrue(t.runtime.state().engine().domain().retainedCosts().isEmpty(), "return closes the shared cost right");
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase public void highSpeedSweepFindsArrivalBeforeCrossingReceiverOrGeometryBeyondIt(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_return", _ -> {})) {
            var p = returnFlight(t); var center = body(t.owner); p.setPos(center.add(0, 5, 0)); p.setDeltaMovement(0, -10, 0);
            placeBlock(t, BlockPos.containing(center.add(0, -2, 0))); p.tick(); healthy(t);
            near(h, p.getY(), center.y + .3, "first sphere entry despite both segment endpoints outside"); near(h, energy(t), 2, "arrival refund");
            h.assertTrue(p.isRemoved() && p.progress().entityContacts() == 0, "receiver is an arrival destination, not collision damage");
            h.assertValueEqual(t.cues.getLast().cue(), "test:arrived", "geometry beyond arrival does not block completed travel");
        }
        h.succeed();
    }
    @GameCase public void wallBeforeArrivalStopsReturnWithoutRefunding(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_return", _ -> {})) {
            var p = returnFlight(t); var center = body(t.owner); p.setPos(center.add(0, 5, 0)); p.setDeltaMovement(0, -10, 0);
            placeBlock(t, BlockPos.containing(center.add(0, 3, 0))); p.tick(); healthy(t);
            h.assertValueEqual(t.cues.getLast().cue(), "test:block", "wall precedes arrival"); near(h, energy(t), 1, "blocked return retains paid cost"); near(h, t.owner.getHealth(), 10, "no refund-dependent heal");
            h.assertTrue(p.isRemoved(), "blocked flight terminates");
        }
        h.succeed();
    }
    @GameCase public void otherLivingEntitiesBlockOrAllowReturnAccordingToExplicitCollisionPolicy(GameTestHelper h) throws Exception {
        for (boolean collide : List.of(true, false)) try (var t = new ProjectileGameTest.Harness(h, "projectile_return", d -> destination(d).addProperty("collide_entities", collide))) {
            var p = returnFlight(t); var obstacle = t.cow(2.5, 43, 3.5); var center = body(t.owner); p.setPos(center.add(0, 4, 0)); p.setDeltaMovement(0, -10, 0); p.tick(); healthy(t);
            h.assertValueEqual(t.cues.getLast().cue(), collide ? "test:entity" : "test:arrived", "explicit intermediate entity policy");
            near(h, energy(t), collide ? 1 : 2, "only arrival refunds"); near(h, obstacle.getHealth(), 100, "collision callback does not automatically damage");
            h.assertTrue(!collide || p.progress().hits().containsKey(obstacle.getUUID().toString()), "the intermediate living entity caused the collision");
        }
        h.succeed();
    }
    @GameCase public void missingOrDeadDestinationEndsWithoutAcquiringNearbyReplacement(GameTestHelper h) throws Exception {
        for (boolean dead : List.of(true, false)) try (var t = new ProjectileGameTest.Harness(h, "projectile_return", _ -> {})) {
            var p = returnFlight(t); t.cow(4, 43, 3.5);
            if (dead) t.owner.setHealth(0); else t.owner.discard(); p.tick(); healthy(t);
            h.assertValueEqual(t.cues.getLast().cue(), "test:target_lost", "fixed receiver becomes unavailable");
            h.assertTrue(p.isRemoved() && p.trackingTarget().isEmpty(), "missing identity cannot switch to another entity"); near(h, energy(t), 1, "no phantom arrival refund");
        }
        h.succeed();
    }
    @GameCase public void destinationSteeringUsesAngularBudgetAndStationaryOverlapStillArrives(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_return", d -> destination(d).getAsJsonObject("turn_rate").addProperty("value", 180))) {
            var p = returnFlight(t); var center = body(t.owner); p.setPos(center.add(5, 0, 0)); p.setDeltaMovement(0, .1, 0); p.tick(); healthy(t);
            near(h, p.getDeltaMovement().x, -.1 * Math.sin(Math.toRadians(9)), "nine degrees this tick"); near(h, p.getDeltaMovement().length(), .1, "speed retained");
            h.assertValueEqual(p.trackingTarget(), Optional.of(t.owner.getUUID().toString()), "explicit owner identity bypasses nearest-enemy exclusion");
            p.setPos(center); p.setDeltaMovement(Vec3.ZERO); p.tick(); healthy(t);
            h.assertValueEqual(t.cues.getLast().cue(), "test:arrived", "stationary overlap is an arrival"); near(h, energy(t), 2, "overlap refunds once");
        }
        h.succeed();
    }
    @GameCase public void explicitLoopBoundReceiverIsCapturedAndArrivalDoesNotStrikeItAgain(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_return", data -> {
            var back = returning(data).deepCopy(); back.getAsJsonObject("projectile").getAsJsonObject("destination").add("target", JsonParser.parseString("{\"binding\":\"victim\"}"));
            var body = outboundBody(data); body.get(0).getAsJsonObject().getAsJsonArray("do").add(back); body.remove(1);
        })) {
            var p = returnFlight(t); var receiver = t.entities.getFirst();
            h.assertValueEqual(p.launch().orElseThrow().parameters().destination().orElseThrow().target(), receiver.getUUID().toString(), "loop element frozen as destination identity");
            for (int i = 0; i < 5 && !p.isRemoved(); i++) p.tick(); healthy(t);
            near(h, receiver.getHealth(), 90, "arrival does not reuse damage callback"); near(h, energy(t), 2, "refund belongs to original payer, not destination");
            h.assertValueEqual(t.cues.getLast().cue(), "test:arrived", "explicit non-owner destination reached");
        }
        h.succeed();
    }
    @GameCase public void unknownArrivalHealingKeepsSpentFlightAndCommittedRefundWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_return", _ -> {})) {
            var p = returnFlight(t); p.setPos(body(t.owner)); p.setDeltaMovement(Vec3.ZERO); t.failAfterHealing = true; p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved() && p.progress().terminal(), "unknown arrival world result consumes flight");
            near(h, energy(t), 2, "confirmed refund remains"); near(h, t.owner.getHealth(), 20, "actual world mutation remains");
            near(h, t.runtime.state().engine().domain().retainedCosts().values().iterator().next().receipt().refundClaimed(), 1, "claim remains spent before close step");
            t.failAfterHealing = false; p.tick(); MinecraftEffectRuntime.tick(h.getLevel()); near(h, t.owner.getHealth(), 20, "failed arrival cannot replay healing");
            h.assertValueEqual(t.cues.size(), 1, "arrival body observed only once");
        }
        h.succeed();
    }
}
