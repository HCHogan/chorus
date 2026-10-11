package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SetMembershipTest {
    static final String BUFF = "chorus_d2:triple_tap_receipts", SET = "damage_ids";
    static BuffDefinition definition() throws Exception {
        return load("triple_tap").program().buffs().stream().map(EffectProgram.Buff::definition).filter(b -> b.id().equals(BUFF)).findFirst().orElseThrow();
    }
    static BuffInstance.Origin origin(String weapon) { return new BuffInstance.Origin("player", "perk/" + weapon, weapon, ""); }
    static EffectState mark(EffectState state, BuffDefinition def, String holder, String weapon, String entry) {
        var result = Buffs.grant(state.buffs(), def, holder, holder, origin(weapon), 1, 1, 3_000_000);
        var key = Buffs.key(def, holder, holder, origin(weapon));
        return state.withBuffs(Buffs.components(result.store(), key, result.store().active(key).orElseThrow().components().remember(SET, entry)));
    }
    static Evaluation evaluation(EffectState state, BuffDefinition def, String victim, String weapon, Map<String, String> refs) {
        var source = new EffectSource("perk/" + weapon, "chorus_d2:triple_tap", "player", origin(weapon), Set.of());
        var fact = new EffectEvent("player", victim, origin(weapon), Set.of(), Map.of(), Map.of(), refs);
        var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("test:probe", fact));
        return new Evaluation(state, new RuleEngine.Context(event, "probe", source, Map.of()), Map.of(BUFF, def), Map.of());
    }
    static Condition.SetContainsEventReference query(Evaluation.Target target) { return new Condition.SetContainsEventReference(BUFF, target, SET, "origin_source"); }
    @Test void membershipUsesExactHolderWeaponAndIdentityWithoutChangingTheLedger() throws Exception {
        var def = definition(); var initial = EffectState.empty(); var state = mark(initial, def, "player", "a", "shot/one");
        var q = query(Evaluation.Target.SELF);
        assertFalse(q.test(evaluation(initial, def, "enemy", "a", Map.of("origin_source", "shot/one"))));
        assertTrue(q.test(evaluation(state, def, "enemy", "a", Map.of("origin_source", "shot/one"))));
        assertFalse(q.test(evaluation(state, def, "enemy", "b", Map.of("origin_source", "shot/one"))));
        assertFalse(q.test(evaluation(state, def, "enemy", "a", Map.of("origin_source", "shot/two"))));
        assertFalse(query(Evaluation.Target.VICTIM).test(evaluation(state, def, "enemy", "a", Map.of("origin_source", "shot/one"))));
        assertEquals(Set.of("shot/one"), state.buffs().instances().values().iterator().next().components().sets().get(SET));
        var expired = state.withBuffs(Buffs.advanceStep(state.buffs(), 3_000_000).store());
        assertFalse(q.test(evaluation(expired, def, "enemy", "a", Map.of("origin_source", "shot/one"))));
    }
    @Test void existingOnceSetWritesAndSelectiveForgetComposeWithTheReadOnlyPredicate() throws Exception {
        var def = definition(); var state = mark(EffectState.empty(), def, "player", "a", "older");
        var e = evaluation(state, def, "enemy", "a", Map.of("origin_source", "emptying-fire"));
        var update = new Action.UpdateComponent(BUFF, Evaluation.Target.SELF, "hits", BuffComponents.Update.ADD,
                new Value.Constant(1, Unit.COUNT), Optional.of(SET), Optional.of("origin_source"));
        var changed = (RuleEngine.Local<EffectState>) update.execute(e);
        var accepted = evaluation(changed.state(), def, "enemy", "a", Map.of("origin_source", "emptying-fire"));
        assertTrue(query(Evaluation.Target.SELF).test(accepted));
        assertFalse(((Action.ComponentResult) ((RuleEngine.Local<EffectState>) update.execute(accepted)).result()).applied());
        var forgotten = (RuleEngine.Local<EffectState>) new Action.ForgetSetEntry(BUFF, Evaluation.Target.SELF, SET, "origin_source").execute(accepted);
        var after = evaluation(forgotten.state(), def, "enemy", "a", Map.of("origin_source", "emptying-fire"));
        assertFalse(query(Evaluation.Target.SELF).test(after));
        assertTrue(new Condition.SetContains(BUFF, Evaluation.Target.SELF, SET, "older").test(after));
        assertEquals(1, forgotten.state().buffs().instances().values().iterator().next().components().numbers().get("hits"));
    }
    @Test void sourceSnapshotFreezesMembershipButVictimSnapshotFreezesOnlyTheCapturedIdentity() throws Exception {
        var def = definition(); var state = mark(EffectState.empty(), def, "player", "a", "fire/one");
        var atUse = evaluation(state, def, "", "a", Map.of("origin_source", "fire/one"));
        var source = query(Evaluation.Target.SELF).snapshot(atUse);
        assertEquals(new Condition.Constant(true), source);
        var victim = query(Evaluation.Target.VICTIM).snapshot(atUse);
        assertEquals(new Condition.SetContains(BUFF, Evaluation.Target.VICTIM, SET, "fire/one"), victim);
        // A later event reference must not replace the captured identity, while victim state is live.
        var later = mark(EffectState.empty(), def, "enemy", "a", "fire/one");
        assertTrue(victim.test(evaluation(later, def, "enemy", "a", Map.of("origin_source", "fire/two"))));
        assertTrue(source.test(evaluation(later, def, "enemy", "a", Map.of())));
        assertFalse(victim.test(evaluation(later, def, "other", "a", Map.of())));
        var changed = mark(EffectState.empty(), def, "enemy", "a", "fire/two");
        assertFalse(victim.test(evaluation(changed, def, "enemy", "a", Map.of("origin_source", "fire/one"))));
    }
    @Test void schemaAndEventIdentityFailuresAreExplicitRatherThanSilentNonMatches() throws Exception {
        var def = definition(); var v = new Validation(Map.of(BUFF, def), Map.of(), false);
        query(Evaluation.Target.SELF).validate(v);
        assertThrows(IllegalArgumentException.class, () -> new Condition.SetContains(BUFF, Evaluation.Target.SELF, "hits", "x").validate(v));
        assertThrows(IllegalArgumentException.class, () -> new Condition.SetContains("test:missing", Evaluation.Target.SELF, SET, "x").validate(v));
        assertThrows(IllegalArgumentException.class, () -> query(new Evaluation.BoundTarget("missing")).validate(v));
        assertThrows(IllegalArgumentException.class, () -> new Condition.SetContainsEventReference(BUFF, Evaluation.Target.SELF, SET, " "));
        for (var refs : List.of(Map.<String, String>of(), Map.of("origin_source", ""))) {
            var e = evaluation(EffectState.empty(), def, "", "a", refs);
            assertThrows(IllegalArgumentException.class, () -> query(Evaluation.Target.SELF).test(e));
            assertThrows(IllegalArgumentException.class, () -> query(Evaluation.Target.VICTIM).snapshot(e));
        }
    }
    @Test void bothJsonFormsAndTheirCapturedQuerySurviveCheckpointSerialization() throws Exception {
        var def = definition(); var state = mark(EffectState.empty(), def, "enemy", "a", "fire/one");
        var eventForm = EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"type":"chorus:set_contains_event_reference","buff":"chorus_d2:triple_tap_receipts",
                 "target":"victim","component":"damage_ids","event_reference":"origin_source"}
                """)).getOrThrow();
        var literalForm = EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"type":"chorus:set_contains","buff":"chorus_d2:triple_tap_receipts",
                 "target":"victim","component":"damage_ids","entry":"fire/one"}
                """)).getOrThrow();
        var captured = eventForm.snapshot(evaluation(state, def, "", "a", Map.of("origin_source", "fire/one")));
        assertEquals(literalForm, captured);
        var codec = EffectCheckpointCodec.DATA; var restoredState = codec.decode(codec.encode(state), EffectState.class);
        for (var c : List.of(eventForm, literalForm, captured)) {
            assertEquals(c, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, c).getOrThrow()).getOrThrow());
            var restored = codec.decode(codec.encode(c), Condition.class);
            assertTrue(restored.test(evaluation(restoredState, def, "enemy", "a", Map.of("origin_source", "fire/one"))));
        }
    }
}
