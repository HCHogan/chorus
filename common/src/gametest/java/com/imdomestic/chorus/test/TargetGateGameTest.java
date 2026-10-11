package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;

/** Target-owned restrictions affect steering eligibility, independently of physical collision. */
public class TargetGateGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static JsonObject spec(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile"); }
    private static void number(JsonObject object, String field, double value) { object.getAsJsonObject(field).addProperty("value", value); }
    private static void addGates(JsonObject data) {
        var gates = ThreadedSpikeGameTest.json("target_gates");
        gates.getAsJsonArray("buffs").forEach(b -> b.getAsJsonObject().getAsJsonObject("definition").add("version", data.get("version")));
        GamblersDodgeGameTest.merge(data, gates);
    }
    private static ProjectileGameTest.Harness open(GameTestHelper h, Consumer<JsonObject> edit) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> {
            number(spec(data), "speed", 2); edit.accept(data);
            addGates(data);
        });
        t.runtime.bind(new EffectSource("launch", "test:projectile", id(t.owner),
                new BuffInstance.Origin(id(t.owner), "source", "", "test:bolt"), Set.of("test:trackable_origin")));
        return t;
    }
    private static void gate(ProjectileGameTest.Harness t, String name, String bundle, Entity holder) {
        t.runtime.bind(new EffectSource(name, "test:" + bundle, id(holder),
                new BuffInstance.Origin(id(t.owner), name, "", ""), Set.of()));
    }
    private static void healthy(ProjectileGameTest.Harness t) { t.h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "tracking runtime failed: " + t.runtime.failure()); }

    @GameCase public void recipientGateSkipsClosestTargetAndActorGatePreventsAllAcquisition(GameTestHelper h) throws Exception {
        try (var t = open(h, _ -> {})) {
            var hidden = t.cow(2.5, 43, 3.5); var visible = t.cow(4.5, 46, 3.5);
            gate(t, "hidden", "target_gate", hidden); t.fire(); var p = t.projectiles.getFirst(); p.tick();
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(visible)), "nearest eligible target skips the hidden recipient");
            gate(t, "blind", "actor_gate", t.owner); p.tick();
            h.assertTrue(p.trackingTarget().isEmpty() && !p.isRemoved(), "actor restriction drops lock and denies every candidate");
            t.runtime.unbind("blind"); p.tick(); healthy(t);
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(visible)), "removing actor gate allows acquisition without ignoring remaining target gate");
            near(h, hidden.getHealth(), 100, "tracking query has no damage side effect");
        } h.succeed();
    }

    @GameCase public void liveTargetGateDropsLockPreservesFlightAndUsesCapturedSourceAfterRemoval(GameTestHelper h) throws Exception {
        try (var t = open(h, d -> spec(d).getAsJsonObject("tracking").addProperty("relation", "any"))) {
            var target = t.cow(4.5, 46, 3.5); t.fire(); var p = t.projectiles.getFirst(); p.tick(); p.tick();
            var retained = t.runtime.projectileTrackingReport().orElseThrow();
            h.assertValueEqual(retained.phase(), ActionGate.Phase.CONTINUE, "retained identity checks continue phase");
            h.assertValueEqual(retained.input().references().get("projectile"), id(p), "actual projectile identity");
            h.assertValueEqual(retained.input().references().get("emitter"), id(t.owner), "physical emitter remains explicit");
            near(h, retained.input().numbers().get("tracking_age").value(), .1, "flight age is observed");
            h.assertValueEqual(retained.input().numbers().get("tracking_age").unit(), Unit.SECOND, "age unit");
            h.assertTrue(retained.input().numbers().get("tracking_distance").value() > 0, "tracking anchor distance is measured");
            h.assertValueEqual(retained.input().numbers().get("tracking_distance").unit(), Unit.METER, "distance unit");
            var observed = retained.input().observedEntities().orElseThrow();
            h.assertTrue(observed.require(id(t.owner)).orElseThrow().player() && !observed.require(id(target)).orElseThrow().player(), "current owner and candidate metadata");
            h.assertTrue(observed.requirePosition(id(target), TargetQuery.Anchor.BODY).isPresent(), "candidate body anchor observed");
            t.runtime.unbind("launch"); t.runtime.unbind("boost"); gate(t, "hidden", "target_gate", target);
            var velocity = p.getDeltaMovement(); p.tick();
            h.assertTrue(p.trackingTarget().isEmpty() && !p.isRemoved(), "new target immunity breaks existing lock");
            near(h, p.getDeltaMovement().distanceTo(velocity), 0, "denied steering preserves ballistic velocity");
            var denial = t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().denials().getFirst();
            h.assertValueEqual(denial.holder(), id(target), "denial holder is the target");
            h.assertValueEqual(denial.origin().owner(), id(t.owner), "buff/source grantor is not substituted for its holder");
            h.assertTrue(p.launch().orElseThrow().origin().tags().contains("test:trackable_origin"), "detached launch keeps source qualification");
            t.runtime.unbind("hidden"); t.owner.discard(); p.tick(); healthy(t);
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(target)), "ANY relation can reacquire after owner disappears");
            h.assertTrue(t.runtime.projectileTrackingReport().orElseThrow().input().observedEntities().orElseThrow().require(id(t.owner)).isEmpty(), "missing owner is explicitly unavailable");
        } h.succeed();
    }

    @GameCase(environment = "chorus_gametest:target_gate_expiry", maxTicks = 30)
    public void realTicksReleaseExpiredTargetBuffAndReacquireWithoutPermanentBlacklist(GameTestHelper h) throws Exception {
        var t = open(h, _ -> {});
        try {
            var target = t.cow(4.5, 46, 3.5); gate(t, "input", "tracking_inputs", t.owner);
            t.runtime.start(new RuleEngine.Signal("test:hide", new EffectEvent(id(t.owner), id(target),
                    new BuffInstance.Origin(id(t.owner), "input", "", ""), Set.of(), Map.of())));
            t.fire(); var p = t.projectiles.getFirst(); p.tick();
            h.assertTrue(p.trackingTarget().isEmpty(), "active target buff denies acquisition");
            t.finish(7, () -> {
                h.assertValueEqual(p.trackingTarget(), Optional.of(id(target)), "expired buff permits real-tick reacquisition");
                h.assertTrue(t.runtime.state().engine().domain().buffs().instances().isEmpty(), "temporary restriction actually expired");
                near(h, target.getHealth(), 100, "slow fixture has not physically reached target");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }

    @GameCase public void trackingImmuneTargetStillReceivesOrdinaryPhysicalImpact(GameTestHelper h) throws Exception {
        try (var t = open(h, d -> { number(spec(d), "speed", 200); number(spec(d).getAsJsonObject("tracking"), "turn_rate", 0); })) {
            var target = t.cow(2.5, 43, 3.5); gate(t, "hidden", "target_gate", target); t.fire(); var p = t.projectiles.getFirst(); p.tick(); healthy(t);
            h.assertTrue(p.trackingTarget().isEmpty(), "target never became a tracking lock");
            h.assertValueEqual(t.hits.stream().map(hit -> hit.target()).toList(), List.of(id(target)), "ballistic sweep still hits once");
            near(h, target.getHealth(), 80, "tracking immunity does not grant damage immunity");
            h.assertTrue(!t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), "actual acquisition query was denied");
        } h.succeed();
    }

    @GameCase public void contactRedirectSkipsImmuneNeighborAndPreservesThePhysicalPierceBudget(GameTestHelper h) throws Exception {
        try (var t = open(h, d -> {
            number(spec(d), "speed", 200); number(spec(d).getAsJsonObject("tracking"), "turn_rate", 0);
            number(spec(d).getAsJsonObject("tracking"), "acquisition_angle", 180);
            number(spec(d).getAsJsonObject("collision"), "entity_pierces", 1);
        })) {
            var first = t.cow(2.5, 43, 3.5); var hidden = t.cow(5, 43, 3.5); var next = t.cow(2.5, 46, 3.5);
            gate(t, "hidden", "target_gate", hidden); t.fire(); var p = t.projectiles.getFirst(); p.tick(); healthy(t);
            h.assertValueEqual(t.hits.stream().map(hit -> hit.target()).toList(), List.of(id(first), id(next)), "contact redirection excludes closer hidden neighbor");
            near(h, hidden.getHealth(), 100, "off-path immune target is not hit by a guessed redirect");
            h.assertTrue(p.isRemoved() && p.progress().entityContacts() == 2, "denied candidate does not spend a physical contact");
            h.assertTrue(t.runtime.projectileTrackingReport().orElseThrow().input().flags().get("tracking_redirect"), "contact query exposes redirect phase");
        } h.succeed();
    }

    @GameCase public void invalidTrackingQueryStopsRuntimeAndAbandonsAllOwnedFlightsWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = open(h, _ -> {})) {
            var target = t.cow(4.5, 46, 3.5); gate(t, "bad", "bad_tracking", target); t.fire(); t.fire();
            var p = t.projectiles.getFirst(); var before = p.position(); p.tick();
            h.assertTrue(t.runtime.failure().isPresent(), "missing predicate evidence stops runtime");
            h.assertTrue(t.projectiles.stream().allMatch(f -> f.isRemoved() && f.progress().terminal() && f.trackingTarget().isEmpty()), "current and sibling flights abandoned");
            var report = t.runtime.projectileTrackingReport().orElseThrow();
            h.assertTrue(report.failure().isPresent() && report.decision().isEmpty(), "unknown query is not a successful denial");
            for (var flight : t.projectiles) flight.tick();
            h.assertValueEqual(p.position(), before, "failed steering cannot continue movement");
            h.assertTrue(t.hits.isEmpty(), "failed flight cannot replay collision effects");
        } h.succeed();
    }

    @GameCase public void explicitReturnDestinationBypassesEnemyTrackingRestrictions(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_return", TargetGateGameTest::addGates)) {
            gate(t, "blind", "actor_gate", t.owner); gate(t, "bad", "bad_tracking", t.owner); t.cow(2.5, 46, 3.5);
            t.runtime.abilities(new AbilityChange(id(t.owner), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))));
            h.assertValueEqual(t.runtime.useAbility(t.owner, "test:melee").outcome(), AbilityUse.Outcome.ACCEPTED, "returning ability accepted");
            var outbound = t.projectiles.getFirst();
            for (int i = 0; i < 10 && t.projectiles.size() < 2; i++) outbound.tick();
            h.assertValueEqual(t.projectiles.size(), 2, "physical hit produces return flight");
            var back = t.projectiles.getLast(); for (int i = 0; i < 20 && !back.isRemoved(); i++) back.tick(); healthy(t);
            h.assertValueEqual(t.cues.getLast().cue(), "test:arrived", "explicit receiver still reached");
            h.assertTrue(t.runtime.projectileTrackingReport().isEmpty(), "destination does not run enemy acquisition gate");
            near(h, t.owner.getHealth(), 20, "arrival refund-dependent healing executes once");
        } h.succeed();
    }

    @GameCase public void nativeMeleeUsesVictimRestrictionWithoutReassigningAttackerCredit(GameTestHelper h) {
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("target_gates")).getOrThrow();
        try (var t = new NativeRangedGameTest.Harness(h, program)) {
            var actor = t.mob(EntityTypes.SKELETON, 2, 2); var target = t.mob(EntityTypes.COW, 4, 2);
            t.runtime.bind(new EffectSource("protected", "test:melee_target", id(target), new BuffInstance.Origin(id(target), "protected", "", ""), Set.of()));
            h.assertTrue(!actor.doHurtTarget(t.level, target), "recipient gate denies actual native melee");
            var report = t.runtime.nativeActionReport().orElseThrow();
            h.assertValueEqual(report.outcome(), MinecraftNativeActions.Outcome.RESTRICTED, "native outcome is restricted");
            h.assertValueEqual(report.input().actor(), id(actor), "attacker identity is preserved");
            h.assertValueEqual(report.decision().orElseThrow().denials().getFirst().holder(), id(target), "denial belongs to recipient");
            near(h, target.getHealth(), 100, "native damage prevented before side effects");
            t.runtime.unbind("protected"); h.assertTrue(actor.doHurtTarget(t.level, target), "unprotected target accepts native attack");
            h.assertTrue(target.getHealth() < 100, "actual native damage after removal"); t.settled();
        } h.succeed();
    }
}
