package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaggedDamageTest {
    static JsonObject data() {
        return JsonParser.parseString("""
        {"version":"test-tags","profiles":[{"id":"test:damage","version":"test-tags","input_unit":"damage","steps":[
          {"type":"chorus:apply","id":"element","operation":"add","group":{"name":"element","reduction":"sum"}}]}],
         "bundles":[{"id":"test:producer","rules":[{"id":"capture","on":"test:fire","do":[
          {"action":{"type":"chorus:capture_tagged_damage","inherit_event_tags":["test:arc","test:solar"],"damage":{
            "amount":{"type":"chorus:constant","value":10,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"test:damage",
            "origin":"event","tags":["test:blast"],"kill_tags":["test:weapon_kill"],"proc":{"deny":["test:blocked"]}}},"as":"saved"},
          {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[{"type":"chorus:damage_snapshot","snapshot":"saved"}]}]}],
          "modifiers":[{"id":"arc","profile":"test:damage","stage":"element","group":"element","op":"add","stacking_key":"test:arc",
           "if":{"type":"chorus:event_tag","tag":"test:arc"},"value":{"type":"chorus:constant","value":5,"unit":"damage"},"reference":"synthetic tag selection","confidence":"assumed"},
          {"id":"precision","profile":"test:damage","stage":"element","group":"element","op":"add","stacking_key":"test:precision",
           "if":{"type":"chorus:event_tag","tag":"chorus:precision"},"value":{"type":"chorus:constant","value":100,"unit":"damage"},"reference":"must not inherit","confidence":"assumed"}]}]}
        """).getAsJsonObject();
    }
    static JsonObject capture(JsonObject d) { return d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action"); }
    static final BuffInstance.Origin BOUND = new BuffInstance.Origin("player", "perk", "a", "", Set.of("test:solar"));
    static final BuffInstance.Origin EVENT = new BuffInstance.Origin("player", "original-shot", "b", "ability", Set.of("test:origin_only"));
    static class Harness {
        final CompiledEffects program; EffectSession session; final List<DamageCommand> hits = new ArrayList<>(); final List<Double> amounts = new ArrayList<>();
        Harness(JsonObject data, Set<String> tags) {
            program = compile(data); session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world);
            session.start(0, SourceChange.bind(new EffectSource("producer", "test:producer", "player", BOUND, Set.of())));
            session.start(0, new RuleEngine.Signal("test:fire", new EffectEvent("player", "target", EVENT, tags, Map.of())));
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            var damage = (DamageCommand) r.command(); hits.add(damage); amounts.add(program.outgoing(session.state().engine().domain(), damage, damage.amount()).orElseThrow().output().value());
            return new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false);
        }
        void finish(boolean restore) {
            session.start(0, SourceChange.remove("producer"));
            if (restore) { var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())); session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 64, saved, this::world); }
            session.observe(100_000, List.of()); assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty());
        }
    }
    @Test void selectedTagsAffectCaptureTimeModifiersWithoutLeakingPrecisionOrCredit() {
        var h = new Harness(data(), Set.of("test:arc", "chorus:precision", "test:unlisted", "test:other_kill")); h.finish(false);
        var hit = h.hits.getFirst(); assertEquals(Set.of("test:blast", "test:arc"), hit.tags()); assertEquals(Set.of("test:weapon_kill"), hit.killTags());
        assertEquals(EVENT, hit.source()); assertEquals(Set.of("test:blocked"), hit.proc().deny()); assertEquals(15, h.amounts.getFirst());
    }
    @Test void emptyIntersectionDoesNotReadBoundOrEventOriginTags() {
        var h = new Harness(data(), Set.of()); h.finish(false); assertEquals(Set.of("test:blast"), h.hits.getFirst().tags()); assertEquals(10, h.amounts.getFirst());
    }
    @Test void boundOriginAndStaticTagUnionRemainExplicitAndDuplicateFree() {
        var data = data(); var attack = capture(data).getAsJsonObject("damage"); attack.addProperty("origin", "bound"); attack.getAsJsonArray("tags").add("test:arc");
        var h = new Harness(data, Set.of("test:arc", "test:solar")); h.finish(false);
        assertEquals(BOUND, h.hits.getFirst().source()); assertEquals(Set.of("test:blast", "test:arc", "test:solar"), h.hits.getFirst().tags()); assertEquals(15, h.amounts.getFirst());
    }
    @Test void pendingAttackAndNewActionDefinitionRoundTripThroughCheckpoint() {
        var data = data(); var action = EffectCodecs.ACTION.parse(JsonOps.INSTANCE, capture(data)).getOrThrow();
        assertEquals(action, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, action).getOrThrow()).getOrThrow());
        var h = new Harness(data, Set.of("test:arc", "chorus:precision")); h.finish(true);
        assertEquals(15, h.amounts.getFirst()); assertEquals(Set.of("test:blast", "test:arc"), h.hits.getFirst().tags()); h.session.observe(300_000, List.of()); assertEquals(1, h.hits.size());
    }
    @Test void nestedSchemaAndDamageValidationAreStrict() {
        var data = data(); capture(data).getAsJsonObject("damage").addProperty("type", "chorus:damage"); assertThrows(RuntimeException.class, () -> compile(data));
        var wrongUnit = data(); capture(wrongUnit).getAsJsonObject("damage").getAsJsonObject("amount").addProperty("unit", "meter"); assertThrows(RuntimeException.class, () -> compile(wrongUnit));
        var missing = data(); capture(missing).remove("inherit_event_tags"); assertThrows(RuntimeException.class, () -> compile(missing));
        var unknown = data(); capture(unknown).getAsJsonObject("damage").addProperty("scaling_profile", "test:unknown"); assertThrows(RuntimeException.class, () -> compile(unknown));
    }
}
