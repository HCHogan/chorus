package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConditionalValueTest {
    private static final Validation VALIDATION = new Validation(Map.of(), Map.of(), false);
    private static Value c(double n) { return new Value.Constant(n, Unit.COUNT); }
    private static Evaluation evaluation(Set<String> tags, Set<String> originTags, Set<String> eventTags) {
        var origin = new BuffInstance.Origin("player", "perk", "weapon", "", originTags);
        var source = new EffectSource("source", "test:source", "player", origin, tags);
        var event = new EffectEvent("player", "target", origin, eventTags, Map.of());
        return new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(0, 0, Optional.empty(), 0,
                new RuleEngine.Signal("test:event", event)), "test:rule", source, Map.of()), Map.of(), Map.of());
    }
    @Test void sourceTagTableUsesBoundMetadataAndRejectsMissingOrAmbiguousClasses() {
        var table = new Value.BySourceTag(Map.of("test:a", c(6), "test:b", new Value.Enhanced(c(3), c(2))));
        assertEquals(Unit.COUNT, table.unit(VALIDATION));
        assertEquals(6, table.evaluate(evaluation(Set.of("test:a"), Set.of(), Set.of("test:b"))).value());
        assertEquals(2, table.evaluate(evaluation(Set.of("test:irrelevant", "chorus:enhanced"), Set.of("test:b"), Set.of())).value());
        assertThrows(IllegalArgumentException.class, () -> table.evaluate(evaluation(Set.of(), Set.of(), Set.of("test:a"))));
        assertThrows(IllegalArgumentException.class, () -> table.evaluate(evaluation(Set.of("test:a"), Set.of("test:b"), Set.of())));
        assertThrows(IllegalArgumentException.class, () -> new Value.BySourceTag(Map.of()));
        assertTrue(new Condition.SourceTag("test:b").test(evaluation(Set.of(), Set.of("test:b"), Set.of())));
        assertFalse(new Condition.SourceTag("test:b").test(evaluation(Set.of(), Set.of(), Set.of("test:b"))));
    }
    @Test void chooseChecksBothBranchTypesButDoesNotEvaluateTheUnselectedBranch() {
        var missing = new Value.EventNumber("absent", Unit.COUNT);
        var e = evaluation(Set.of(), Set.of(), Set.of());
        var choose = new Value.Choose(new Condition.Constant(true), c(7), missing);
        assertEquals(Unit.COUNT, choose.unit(VALIDATION)); assertEquals(7, choose.evaluate(e).value());
        var inverted = new Value.Choose(new Condition.Constant(false), missing, c(9));
        assertEquals(9, inverted.evaluate(e).value());
        assertThrows(IllegalArgumentException.class, () -> new Value.Choose(new Condition.Constant(true), c(7), new Value.Constant(2, Unit.DAMAGE)).unit(VALIDATION));
        assertThrows(IllegalArgumentException.class, () -> new Value.BySourceTag(Map.of("test:a", c(1), "test:b", new Value.Constant(2, Unit.DAMAGE))).unit(VALIDATION));
    }
    @Test void recursiveConditionsAndValuesRoundTripAndUnknownFieldsAreRejected() {
        String json = """
                {"type":"chorus:choose","if":{"type":"chorus:compare","op":"ge",
                  "left":{"type":"chorus:choose","if":{"type":"chorus:source_tag","tag":"test:a"},
                    "then":{"type":"chorus:constant","value":2,"unit":"count"},"else":{"type":"chorus:constant","value":0,"unit":"count"}},
                  "right":{"type":"chorus:constant","value":1,"unit":"count"}},
                  "then":{"type":"chorus:by_source_tag","values":{"test:a":{"type":"chorus:constant","value":3,"unit":"count"}}},
                  "else":{"type":"chorus:constant","value":4,"unit":"count"}}
                """;
        var value = EffectCodecs.VALUE.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        assertEquals(Unit.COUNT, value.unit(VALIDATION)); assertEquals(3, value.evaluate(evaluation(Set.of("test:a"), Set.of(), Set.of())).value());
        assertEquals(4, value.evaluate(evaluation(Set.of(), Set.of(), Set.of())).value());
        assertEquals(value, EffectCodecs.VALUE.parse(JsonOps.INSTANCE, EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE, value).getOrThrow()).getOrThrow());
        var typo = JsonParser.parseString(json).getAsJsonObject(); typo.addProperty("otherwise", 1);
        assertTrue(EffectCodecs.VALUE.parse(JsonOps.INSTANCE, typo).error().isPresent());
    }
    @Test void snapshotFreezesSourceSelectionAndOnlyBindsTheSelectedSourceBranch() {
        var use = evaluation(Set.of("test:a"), Set.of(), Set.of());
        var bad = new Value.EventNumber("never_present", Unit.COUNT);
        var value = new Value.BySourceTag(Map.of("test:a", new Value.Choose(new Condition.SourceTag("test:a"), c(4), bad), "test:b", bad));
        var frozen = value.snapshot(use);
        assertEquals(c(4), frozen); assertEquals(4, frozen.evaluate(evaluation(Set.of("test:b"), Set.of(), Set.of())).value());
        assertEquals(new Condition.Constant(true), new Condition.SourceTag("test:a").snapshot(use));
    }
    @Test void snapshotKeepsVictimConditionButFreezesSourceOperandsInBothBranches() {
        var use = evaluation(Set.of("test:a"), Set.of(), Set.of());
        var selected = new Value.BySourceTag(Map.of("test:a", c(5), "test:b", c(9)));
        var value = new Value.Choose(new Condition.TargetIs(Evaluation.Target.VICTIM, Evaluation.Target.SELF), selected, c(1));
        var bound = value.snapshot(use); assertInstanceOf(Value.Choose.class, bound);
        var otherSource = evaluation(Set.of("test:b"), Set.of(), Set.of());
        assertEquals(1, bound.evaluate(otherSource).value());
        var event = new EffectEvent("player", "player", otherSource.origin(), Set.of(), Map.of());
        var ownTarget = new Evaluation(otherSource.state(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 1,
                new RuleEngine.Signal("test:event", event)), "test:rule", otherSource.context().scope(), Map.of()), Map.of(), Map.of());
        assertEquals(5, bound.evaluate(ownTarget).value());
    }
}
