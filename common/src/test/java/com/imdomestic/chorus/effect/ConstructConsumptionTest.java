package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConstructConsumptionTest {
    static CompiledEffects program() throws Exception { return CompiledEffects.link(List.of(load("construct").program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("construct_consumption")).getOrThrow())); }
    static final class Harness {
        final CompiledEffects p; EffectSession session; final List<WorldConstruct.Consume> requests = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        WorldConstruct.ConsumeOutcome outcome = WorldConstruct.ConsumeOutcome.CONSUMED; boolean alive = true, fail, mismatch;
        Harness() throws Exception {
            p = program(); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world);
            send(SourceChange.bind(new EffectSource("consume", "test:consume_construct", "owner", ConstructTest.ORIGIN, Set.of())));
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case EntityAnchor.Query q -> new EntityAnchor.Result(q, alive ? EntityAnchor.Outcome.AVAILABLE : EntityAnchor.Outcome.MISSING, alive ? Optional.of(new EntityAnchor.Observation(new EntityAnchor.Reference(q.target(), "life-1", "world"), new WorldPosition("world", 2, 3, 4), q.anchor(), true)) : Optional.empty());
                case WorldConstruct.Consume c -> {
                    requests.add(c); if (outcome == WorldConstruct.ConsumeOutcome.CONSUMED) alive = false;
                    if (fail) throw new IllegalStateException("Unknown consumption outcome");
                    yield new WorldConstruct.ConsumeReceipt(mismatch ? new WorldConstruct.Consume(new EntityAnchor.Reference("other", "life-2", "world")) : c, outcome);
                }
                case Action.CueCommand c -> { cues.add(c.cue()); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(RuleEngine.Signal s) { session.start(state().buffs().timeMicros(), s); }
        void consume() { send(new RuleEngine.Signal("test:consume", new EffectEvent("owner", "orb", ConstructTest.ORIGIN, Set.of(), Map.of()))); }
    }
    @Test void rewardRequiresConfirmedConsumptionOfTheObservedExactLifeAndCannotRepeat() throws Exception {
        var h = new Harness(); h.consume(); h.consume(); assertEquals(List.of("test:reward"), h.cues); assertEquals(1, h.requests.size());
        assertEquals(new EntityAnchor.Reference("orb", "life-1", "world"), h.requests.getFirst().reference()); assertFalse(h.alive);
    }
    @Test void everyKnownRejectionRetainsPriorStateButDoesNotGrantTheDependentReward() throws Exception {
        for (var outcome : WorldConstruct.ConsumeOutcome.values()) if (outcome != WorldConstruct.ConsumeOutcome.CONSUMED) {
            var h = new Harness(); h.outcome = outcome; h.consume(); assertTrue(h.alive); assertTrue(h.cues.isEmpty()); assertEquals(1, h.requests.size());
            assertTrue(h.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:paid")));
        }
    }
    @Test void unknownOrForeignReceiptCannotReplayThePhysicalClaimOrInventAReward() throws Exception {
        for (boolean mismatch : List.of(false, true)) {
            var h = new Harness(); h.mismatch = mismatch; h.fail = !mismatch;
            assertThrows(IllegalStateException.class, h::consume); assertFalse(h.alive); assertTrue(h.cues.isEmpty()); assertEquals(1, h.requests.size());
            assertThrows(IllegalStateException.class, () -> h.session.observe(1_000_000, List.of())); assertEquals(1, h.requests.size());
            assertTrue(h.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:paid")));
        }
    }
    @Test void programAndNewReceiptShapesRoundTripThroughCodecsAndRestoredSession() throws Exception {
        var h = new Harness(); assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow());
        h.session = EffectSession.restore(h.p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint())), h::world); h.consume();
        assertEquals(List.of("test:reward"), h.cues);
        var codec = EffectCheckpointCodec.DATA;
        for (var outcome : WorldConstruct.ConsumeOutcome.values()) {
            var receipt = new WorldConstruct.ConsumeReceipt(h.requests.getFirst(), outcome);
            assertEquals(receipt, codec.decode(codec.encode(receipt), WorldConstruct.ConsumeReceipt.class));
        }
    }
    @Test void missingAndPositionOnlyBindingsAreRejectedAtDefinitionValidation() throws Exception {
        for (String kind : List.of("missing", "position")) {
            var data = json("construct_consumption"); var steps = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
            if (kind.equals("position")) steps.get(1).getAsJsonObject().getAsJsonObject("action").addProperty("type", "chorus:capture_position");
            else steps.get(2).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("reference", "missing");
            var part = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow();
            assertThrows(RuntimeException.class, () -> CompiledEffects.link(List.of(load("construct").program(), part)));
        }
    }
}
