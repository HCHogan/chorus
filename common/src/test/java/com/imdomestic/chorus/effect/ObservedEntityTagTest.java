package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ObservedEntityTagTest {
    static final String TAG = "chorus_gametest:elite";
    static final EffectSource SOURCE = source("test:classified_burst");
    static EntityQuery.View view(boolean player, Set<String> entity, Set<String> type) {
        return new EntityQuery.View(false, player, 0, 20, 0, entity, type);
    }
    static EntityQuery.Result receipt(EntityQuery.View view) { return new EntityQuery.Result(new EntityQuery("target"), Optional.of(view)); }
    static Evaluation evaluation(EntityQuery.Result receipt) {
        return new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(0, 0, Optional.empty(), 0,
                new RuleEngine.Signal("test:query", RuleEngine.Empty.INSTANCE)), "test:rule", SOURCE, Map.of("classified", receipt)),
                Map.of(), Map.of("classified", ResultShape.ENTITY));
    }
    @Test void instanceAndTypeTagsAreSeparateImmutableObservations() {
        var instance = new HashSet<>(Set.of(TAG)); var types = new HashSet<>(Set.of("test:type"));
        var old = view(false, instance, types); instance.clear(); types.add(TAG);
        assertTrue(old.hasTag(EntityQuery.TagSource.ENTITY, TAG)); assertFalse(old.hasTag(EntityQuery.TagSource.TYPE, TAG));
        assertTrue(old.hasTag(EntityQuery.TagSource.TYPE, "test:type")); assertFalse(old.hasTag(EntityQuery.TagSource.ENTITY, "test:type"));
        assertThrows(UnsupportedOperationException.class, () -> old.entityTags().clear());
        assertThrows(UnsupportedOperationException.class, () -> old.typeTags().add(TAG));
        assertFalse(new EntityQuery.View(true, false, 1, 20, 0).hasTag(EntityQuery.TagSource.ENTITY, TAG));
    }
    @Test void missingIsNotAnObservedNegativeAndTheBindingMustBeAnEntityObservation() {
        var c = new Condition.ObservedEntityTag("classified", EntityQuery.TagSource.ENTITY, TAG);
        c.validate(new Validation(Map.of(), Map.of("classified", ResultShape.ENTITY), false));
        assertTrue(c.test(evaluation(receipt(view(false, Set.of(TAG), Set.of())))));
        assertFalse(c.test(evaluation(receipt(view(false, Set.of(), Set.of(TAG))))));
        assertThrows(IllegalStateException.class, () -> c.test(evaluation(new EntityQuery.Result(new EntityQuery("target"), Optional.empty()))));
        assertThrows(IllegalArgumentException.class, () -> c.validate(new Validation(Map.of(), Map.of("classified", ResultShape.POSITION), false)));
        assertThrows(IllegalArgumentException.class, () -> c.validate(new Validation(Map.of(), Map.of(), false)));
        assertThrows(IllegalArgumentException.class, ResultShape.ENTITY::requireTarget);
        assertThrows(IllegalArgumentException.class, () -> c.snapshot(evaluation(receipt(view(false, Set.of(TAG), Set.of())))));
    }
    @Test void classifiedDeathRadiusSurvivesSourceRemovalAndDelayedWorldExecution() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            var engine = engine(load("classified_burst"));
            var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "chorus:kill", event(SOURCE));
            var position = complete(engine, waiting, receipt(view(kind == 3, kind == 1 ? Set.of(TAG) : Set.of(), kind == 2 ? Set.of(TAG) : Set.of())));
            var query = (PositionQuery) position.actions().getFirst().command();
            var scheduled = complete(engine, position, new PositionQuery.Result(query, Optional.of(new WorldPosition("test:world", 2, 3, 4))));
            var detached = pump(engine, engine.transition(scheduled.state(), new RuleEngine.Start(0, SourceChange.remove(SOURCE.instance()))));
            var after = pump(engine, engine.transition(detached.state(), new RuleEngine.Start(100_000, List.of())));
            var selected = (TargetQuery) after.actions().getFirst().command();
            assertEquals(kind == 0 ? 4 : 8, ((TargetShape.Sphere) selected.shape()).radius());
            assertEquals(new TargetQuery.PositionCenter(Optional.of(new WorldPosition("test:world", 2, 3, 4))), selected.center());
            assertTrue(complete(engine, after, new TargetQuery.Result(selected, TargetQuery.Outcome.AVAILABLE, List.of())).state().idle());
        }
    }
    @Test void missingObservationSkipsTheBurstAndConflictingTagsCannotRewriteAnAcceptedReceipt() throws Exception {
        var engine = engine(load("classified_burst")); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "chorus:kill", event(SOURCE));
        assertTrue(complete(engine, waiting, new EntityQuery.Result(new EntityQuery("target"), Optional.empty())).state().idle());
        var original = receipt(view(false, Set.of(TAG), Set.of())); var position = complete(engine, waiting, original);
        assertEquals(position.state(), engine.transition(position.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), original)).state());
        var conflict = receipt(view(false, Set.of(), Set.of(TAG)));
        assertThrows(IllegalArgumentException.class, () -> engine.transition(position.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), conflict)));
    }
    @Test void strictCodecRoundTripsBothChannelsAndRejectsUnknownFieldsOrSources() throws Exception {
        for (var source : EntityQuery.TagSource.values()) {
            var condition = new Condition.ObservedEntityTag("classified", source, TAG);
            assertEquals(condition, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, condition).getOrThrow()).getOrThrow());
        }
        var minimal = JsonParser.parseString("{\"type\":\"chorus:observed_entity_tag\",\"binding\":\"classified\",\"tag\":\"legacy-unqualified-tag\"}").getAsJsonObject();
        assertEquals(EntityQuery.TagSource.ENTITY, ((Condition.ObservedEntityTag) EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, minimal).getOrThrow()).source());
        minimal.addProperty("source", "unknown"); assertTrue(EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, minimal).error().isPresent());
        minimal.addProperty("source", "entity"); minimal.addProperty("typo", true); assertTrue(EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, minimal).error().isPresent());
        var program = load("classified_burst"); assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow().program());
    }
    @Test void observationConditionsCannotEscapeTheirActionBindingOrBecomeQueryModifiers() throws Exception {
        var data = json("classified_burst"); var rule = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
        rule.add("if", JsonParser.parseString("{\"type\":\"chorus:observed_entity_tag\",\"binding\":\"classified\",\"tag\":\"test:elite\"}"));
        assertThrows(RuntimeException.class, () -> compile(data));
        assertThrows(IllegalArgumentException.class, () -> new Condition.ObservedEntityTag("", EntityQuery.TagSource.ENTITY, TAG));
        assertThrows(IllegalArgumentException.class, () -> new Condition.ObservedEntityTag("classified", EntityQuery.TagSource.TYPE, " "));
    }
}
