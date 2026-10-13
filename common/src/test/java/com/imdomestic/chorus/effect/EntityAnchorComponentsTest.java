package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityAnchorComponentsTest {
    static EntityAnchor.Result anchor(String target, String life, String world, double y, TargetQuery.Anchor anchor, boolean alive) {
        var ref = new EntityAnchor.Reference(target, life, world);
        return new EntityAnchor.Result(new EntityAnchor.Query(target, anchor, Optional.empty()), EntityAnchor.Outcome.AVAILABLE,
                Optional.of(new EntityAnchor.Observation(ref, new WorldPosition(world, 1, y, 3), anchor, alive)));
    }
    static Evaluation evaluation(Map<String, RuleEngine.ActionResult> bindings) {
        return new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0,
                new RuleEngine.Signal("test:compare", RuleEngine.Empty.INSTANCE)), "test", RuleEngine.Empty.INSTANCE, bindings), Map.of(),
                Map.of("a", EntityAnchorActions.RESULT, "b", EntityAnchorActions.RESULT));
    }
    @Test void lifeComparisonIncludesTargetWorldAndLifeButNotAnchorPositionOrHistoricalHealth() {
        var a = anchor("player", "life-1", "world", 42, TargetQuery.Anchor.EYES, true);
        var same = new EntityAnchorActions.SameLife("a", "b");
        for (var b : List.of(anchor("player", "life-1", "world", 40, TargetQuery.Anchor.FEET, false), a)) {
            var e = evaluation(Map.of("a", a, "b", b)); assertTrue(same.test(e)); assertEquals(new Condition.Constant(true), same.snapshot(e));
        }
        for (var b : List.of(anchor("other", "life-1", "world", 42, TargetQuery.Anchor.EYES, true), anchor("player", "life-2", "world", 42, TargetQuery.Anchor.EYES, true),
                anchor("player", "life-1", "nether", 42, TargetQuery.Anchor.EYES, true), new EntityAnchor.Result(a.query(), EntityAnchor.Outcome.MISSING, Optional.empty()))) {
            assertFalse(same.test(evaluation(Map.of("a", a, "b", b))));
            assertFalse(same.test(evaluation(Map.of("a", b, "b", a))));
        }
        assertEquals(same, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, same).getOrThrow()).getOrThrow());
    }
    @Test void writesRemainImmutableAndEveryExistingComponentUpdatePreservesTheReference() {
        var a = anchor("player", "life-1", "world", 42, TargetQuery.Anchor.FEET, true); var old = new BuffSchema(Map.of("count", new Measure(0, Unit.COUNT)), Set.of("seen"), Set.of("credit"), Set.of("members"), Set.of("point"), Set.of("damage"), Set.of("initial")).initial();
        assertTrue(old.entityAnchors().isEmpty()); var saved = old.entityAnchor("initial", a);
        var changed = saved.number("count", BuffComponents.Update.ADD, 1).remember("seen", "first").forget("seen", "first").reference("credit", "source")
                .targets("members", Targets.Identities.of(List.of("ally"))).position("point", a.position()).damageSnapshot("damage", Optional.empty());
        assertEquals(Map.of("initial", a), changed.entityAnchors()); assertTrue(old.entityAnchors().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> saved.entityAnchors().clear());
        assertEquals(changed, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(changed), BuffComponents.class));
    }
    static class Harness {
        final CompiledEffects p; EffectSession session; String life = "life-1"; boolean present = true, alive = true, unknownCue; double y = 42;
        final List<TargetQuery> queries = new ArrayList<>(); int cues, reads;
        Harness() throws Exception {
            p = load("entity_anchor_components"); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(source("test:arm")), this::world);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case EntityAnchor.Query q -> {
                    reads++; var a = anchor(q.target(), life, "world", y, q.anchor(), alive);
                    yield !present ? new EntityAnchor.Result(q, EntityAnchor.Outcome.MISSING, Optional.empty())
                            : q.expected().filter(ref -> !ref.equals(a.requireObservation().reference())).isPresent() ? new EntityAnchor.Result(q, EntityAnchor.Outcome.REPLACED, Optional.empty())
                            : new EntityAnchor.Result(q, EntityAnchor.Outcome.AVAILABLE, a.observation());
                }
                case TargetQuery q -> { queries.add(q); yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, List.of()); }
                case Action.CueCommand c -> { cues++; if (unknownCue) throw new IllegalStateException("Unknown completion"); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(r.command());
            };
        }
        void arm() { session.start(now(), new RuleEngine.Signal("test:arm", new EffectEvent("player", "player", new BuffInstance.Origin("player", "cast", "", "test:dive"), Set.of(), Map.of()))); }
        long now() { return session.state().engine().timeMicros(); }
        boolean pending() { return session.state().engine().domain().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:pending")); }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
        void land() { session.start(now(), GroundContactTest.landed().signal()); }
    }
    @Test void separateLandingEventAfterCheckpointUsesStoredLifeThenKeepsItsDetachedHistoricalLocation() throws Exception {
        var h = new Harness(); h.arm(); assertTrue(h.pending()); h.restore(); h.y = 40; h.land(); assertFalse(h.pending()); assertEquals(1, h.reads);
        h.restore(); h.y = 80; h.session.observe(100_000, List.of()); assertEquals(1, h.cues); assertEquals(2, h.reads);
        assertEquals(GroundContactTest.POINT, ((TargetQuery.PositionCenter)h.queries.getFirst().center()).position().orElseThrow());
    }
    @Test void anotherLifeCannotCompleteThePendingCastAndUnavailableResultsAreNotInventedObservations() throws Exception {
        var h = new Harness(); h.life = "life-2"; h.arm(); h.restore(); h.land(); assertTrue(h.pending()); h.session.observe(100_000, List.of()); assertEquals(0, h.cues); assertEquals(1, h.reads);
        h = new Harness(); h.present = false; h.arm(); h.restore(); h.land(); assertTrue(h.pending()); assertEquals(0, h.cues);
        var stored = h.session.state().engine().domain().buffs().instances().values().iterator().next().components().entityAnchors().get("initial");
        assertEquals(EntityAnchor.Outcome.MISSING, stored.outcome()); assertTrue(stored.observation().isEmpty());
    }
    @Test void replacementOrDeathDuringDelayedCompletionDoesNotReceiveOldBenefitsAndUnknownWorkIsNotReplayed() throws Exception {
        for (String failure : List.of("replacement", "death", "missing")) {
            var h = new Harness(); h.arm(); h.land(); switch (failure) { case "replacement" -> h.life = "life-2"; case "death" -> h.alive = false; default -> h.present = false; }
            h.session.observe(100_000, List.of()); assertEquals(0, h.cues); assertFalse(h.pending());
        }
        var h = new Harness(); h.arm(); h.land(); h.unknownCue = true; assertThrows(IllegalStateException.class, () -> h.session.observe(100_000, List.of()));
        assertFalse(h.pending()); assertEquals(1, h.cues); assertThrows(IllegalStateException.class, () -> h.session.observe(200_000, List.of())); assertEquals(1, h.cues);
    }
    @Test void declarationAndTypedBindingValidationRejectPositionSubstitutionAndUnwrittenReads() throws Exception {
        var data = json("entity_anchor_components"); var definition = data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition");
        definition.getAsJsonObject("components").add("entity_anchors", new com.google.gson.JsonArray());
        var undeclared = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow();
        assertThrows(IllegalArgumentException.class, () -> new CompiledEffects(undeclared));
        var duplicate = json("entity_anchor_components"); definition = duplicate.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition");
        definition.getAsJsonObject("components").add("positions", com.google.gson.JsonParser.parseString("[\"initial\"]"));
        assertTrue(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, duplicate).error().isPresent());
        var invalid = json("entity_anchor_components"); invalid.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("type", "chorus:capture_position");
        var untyped = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, invalid).getOrThrow();
        assertThrows(IllegalArgumentException.class, () -> new CompiledEffects(untyped));
        var unwritten = json("entity_anchor_components"); unwritten.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").remove(2);
        var p = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, unwritten).getOrThrow(); var base = new Harness();
        var s = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(source("test:arm")), base::world);
        s.start(0, new RuleEngine.Signal("test:arm", new EffectEvent("player", "player", new BuffInstance.Origin("player", "cast", "", ""), Set.of(), Map.of())));
        var failure = assertThrows(IllegalStateException.class, () -> s.start(0, GroundContactTest.landed().signal()));
        assertTrue(failure.getMessage().contains("Entity anchor component has not been written: initial"));
    }
    @Test void exactOldComponentAndSchemaTreesMigrateWithoutDiscardingFieldsOrAcceptingNewFieldsUnderOldIds() {
        var schema = new BuffSchema(Map.of("n", new Measure(7, Unit.COUNT)), Set.of("s"), Set.of("r"), Set.of("t"), Set.of("p"), Set.of("d"));
        var value = schema.initial().remember("s", "seen").reference("r", "origin").targets("t", Targets.Identities.of(List.of("enemy")))
                .position("p", Optional.of(new WorldPosition("world", 1, 2, 3)));
        var codec = EffectCheckpointCodec.DATA;
        for (var item : List.of(value, schema)) {
            var node = codec.encode(item).getAsJsonObject(); node.addProperty("$", item instanceof BuffSchema ? "chorus:effect/buff/buff_schema" : "chorus:effect/buff/buff_components");
            node.getAsJsonObject("v").remove("entityAnchors"); assertEquals(item, codec.decode(node, item.getClass()));
            node.getAsJsonObject("v").add("entityAnchors", codec.encode(Map.of())); assertThrows(IllegalArgumentException.class, () -> codec.decode(node, item.getClass()));
        }
    }
}
