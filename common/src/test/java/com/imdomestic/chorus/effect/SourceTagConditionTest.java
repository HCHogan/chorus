package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceTagConditionTest {
    static final String TAG = "test:strand_subclass";
    static final Condition SELF = new Condition.HasSourceTag(TAG, Evaluation.Target.SELF);
    static EffectSource source(String holder, boolean originTag) {
        return new EffectSource(holder + "-subclass", "test:subclass", holder,
                new BuffInstance.Origin("grantor", "subclass", "", "", originTag ? Set.of(TAG) : Set.of()), originTag ? Set.of() : Set.of(TAG));
    }
    static Evaluation evaluation(EffectState state) {
        var origin = new BuffInstance.Origin("owner", "old-attack", "", "", Set.of(TAG));
        var scope = new EffectSource("retained", "test:driver", "holder", origin, Set.of());
        var event = new EffectEvent("actor", "victim", origin, Set.of(TAG), Map.of());
        return new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0,
                new RuleEngine.Signal("test:query", event)), "rule", scope, Map.of()), Map.of(), Map.of());
    }
    @Test void onlyCurrentlyBoundSourcesOnTheSelectedHolderQualify() {
        assertFalse(SELF.test(evaluation(EffectState.empty())), "retained attack/event tags are not a live loadout");
        for (boolean originTag : List.of(false, true)) {
            var source = source("holder", originTag); var state = EffectState.empty().withSource(source);
            assertTrue(SELF.test(evaluation(state)));
            assertFalse(SELF.test(evaluation(state.withoutSource(source.instance()))));
            assertFalse(SELF.test(evaluation(EffectState.empty().withSource(source("owner", originTag)))));
            assertFalse(new Condition.HasSourceTag(TAG, Evaluation.Target.SOURCE_OWNER).test(evaluation(state)));
        }
    }
    @Test void holderSnapshotFreezesButVictimQueryWaitsForImpact() {
        var source = source("holder", false); var state = EffectState.empty().withSource(source);
        var frozen = SELF.snapshot(evaluation(state));
        assertTrue(frozen.test(evaluation(EffectState.empty())));
        assertFalse(SELF.test(evaluation(EffectState.empty())));
        var victim = new Condition.HasSourceTag(TAG, Evaluation.Target.VICTIM);
        var deferred = victim.snapshot(evaluation(state)); assertEquals(victim, deferred);
        assertFalse(deferred.test(evaluation(state)));
        assertTrue(deferred.test(evaluation(state.withSource(source("victim", false)))));
    }
    @Test void codecAndTargetValidationRetainTheQueryContract() {
        var json = JsonParser.parseString("""
            {"type":"chorus:has_source_tag","tag":"test:strand_subclass"}
            """);
        var condition = EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(SELF, condition);
        assertEquals(condition, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, condition).getOrThrow()).getOrThrow());
        var validation = new Validation(Map.of(), Map.of(), false);
        condition.validate(validation);
        assertThrows(IllegalArgumentException.class, () -> new Condition.HasSourceTag(TAG, new Evaluation.BoundTarget("missing")).validate(validation));
        assertThrows(IllegalArgumentException.class, () -> new Condition.HasSourceTag(TAG, new Evaluation.BoundTarget("missing")).snapshot(evaluation(EffectState.empty())));
    }
}
