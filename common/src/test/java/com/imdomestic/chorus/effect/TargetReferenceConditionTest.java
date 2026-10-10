package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TargetReferenceConditionTest {
    static Evaluation evaluation(String actor, String victim, boolean facts) {
        var origin = new BuffInstance.Origin("holder", "perk", "", ""); var source = new EffectSource("perk", "test:perk", "holder", origin, Set.of());
        RuleEngine.Payload payload = facts ? new EffectEvent(actor, victim, new BuffInstance.Origin(actor, "attack", "", ""), Set.of(), Map.of()) : RuleEngine.Empty.INSTANCE;
        return new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(0, 0, Optional.empty(), 0, new RuleEngine.Signal("test:query", payload)), "rule", source, Map.of()), Map.of(), Map.of());
    }
    @Test void missingFactOrIdentityCanBeGuardedButRequiredTargetAccessStillFails() {
        var e = evaluation("", "", true);
        assertTrue(new Condition.TargetRefPresent(Evaluation.Target.SELF).test(e));
        for (var target : List.of(Evaluation.Target.EVENT_ACTOR, Evaluation.Target.VICTIM, Evaluation.Target.EVENT_WEAPON, Evaluation.Target.THIS_WEAPON)) {
            assertFalse(new Condition.TargetRefPresent(target).test(e)); assertThrows(IllegalArgumentException.class, () -> e.target(target));
        }
        assertFalse(new Condition.TargetRefPresent(Evaluation.Target.EVENT_ACTOR).test(evaluation("", "", false)));
        assertTrue(new Condition.TargetRefPresent(Evaluation.Target.VICTIM).test(evaluation("", "not-loaded-in-any-world", true)), "presence is not entity existence or liveness");
    }
    @Test void snapshotFreezesSourcePresenceButDefersVictimPresenceUntilImpact() {
        var use = evaluation("", "", true); var hit = evaluation("new-actor", "victim", true);
        var actor = new Condition.TargetRefPresent(Evaluation.Target.EVENT_ACTOR).snapshot(use);
        var victim = new Condition.TargetRefPresent(Evaluation.Target.VICTIM).snapshot(use);
        assertEquals(new Condition.Constant(false), actor); assertFalse(actor.test(hit));
        assertInstanceOf(Condition.TargetRefPresent.class, victim); assertFalse(victim.test(use)); assertTrue(victim.test(hit));
    }
    @Test void codecIsStrictAndBrokenLexicalBindingsAreErrorsRatherThanAbsence() {
        var c = new Condition.TargetRefPresent(Evaluation.Target.EVENT_ACTOR);
        var encoded = EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, c).getOrThrow(); assertEquals(c, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertTrue(EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"type\":\"chorus:target_ref_present\",\"target\":\"typo\"}")).error().isPresent());
        var bound = new Condition.TargetRefPresent(new Evaluation.BoundTarget("missing"));
        assertThrows(IllegalArgumentException.class, () -> bound.validate(new Validation(Map.of(), Map.of(), false)));
        assertThrows(IllegalArgumentException.class, () -> bound.test(evaluation("actor", "victim", true)));
        assertThrows(IllegalArgumentException.class, () -> bound.snapshot(evaluation("actor", "victim", true)));
    }
}
