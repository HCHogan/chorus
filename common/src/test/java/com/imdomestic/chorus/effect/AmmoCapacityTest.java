package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AmmoCapacityTest {
    private static AmmoProgramTest.Harness harness() throws Exception { return new AmmoProgramTest.Harness(load("ammo_capacity")); }
    private static void tick(AmmoProgramTest.Harness h, long time) { h.session.start(time, new RuleEngine.Signal("chorus:tick", RuleEngine.Empty.INSTANCE)); }
    private static int capacity(AmmoProgramTest.Harness h, String weapon) { return h.program.ammoCapacity(h.state(), weapon).capacity(); }
    private static JsonObject init(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject(); }
    private static JsonObject modifier(JsonObject data) { return data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject(); }
    private static Evaluation evaluation(CompiledEffects p, EffectState state, String victim) {
        var source = AmmoProgramTest.sourceFor("a");
        var event = new EffectEvent("player", victim, source.origin(), Set.of(), Map.of());
        return new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("test:input", event)), "test", source, Map.of()),
                Map.of(), Map.of(), Map.of(), Map.of(), Optional.of(p));
    }
    @Test void expiryAndWeaponIsolationNeverRewriteTheProfileInputOrDestroyLoadedOverflow() throws Exception {
        var h = harness(); h.initialize("a"); h.initialize("b"); h.send("test:expand", "a", 0, 0);
        var before = h.state(); for (int i = 0; i < 5; i++) assertEquals(10, capacity(h, "a"));
        assertEquals(before, h.state()); assertEquals(5, capacity(h, "b")); assertEquals(5, h.ammo("a").capacity());
        h.send("test:overflow", "a", 0, 0); assertEquals(20, h.ammo("a").magazine()); assertEquals(31, h.ammo("a").reserve().orElseThrow().rounds());
        h.send("test:inspect", "a", 0, 0); var observed = h.observations.getLast();
        tick(h, 199_999); assertEquals(10, capacity(h, "a")); tick(h, 200_000); assertEquals(5, capacity(h, "a"));
        assertEquals(20, h.ammo("a").magazine()); assertEquals(0, h.program.ammoCapacity(h.state(), "a").read(AmmoState.Field.MISSING));
        assertEquals(10, AmmoActions.OBSERVATION.read("capacity", observed).value());
        assertEquals(5, AmmoActions.OBSERVATION.read("unmodified_capacity", observed).value());
        h.send("test:refill", "a", 0, 0); assertEquals(0, h.heals.getLast().amount()); assertEquals(20, h.ammo("a").magazine());
    }
    @Test void percentagesAndDefaultGrantLimitsReadEffectiveCapacityAndKeepGenerationSeparate() throws Exception {
        var h = harness(); h.initialize("a"); h.send("test:percent", "a", 0, 0);
        assertEquals(3, h.ammo("a").magazine()); assertEquals(2, h.heals.getLast().amount()); // ceil(25% of 5)
        h.send("test:expand", "a", 0, 0); h.send("test:percent", "a", 0, 0);
        assertEquals(6, h.ammo("a").magazine()); assertEquals(3, h.heals.getLast().amount()); // ceil(25% of 10)
        h.send("test:generate", "a", 0, 0); assertEquals(10, h.ammo("a").magazine()); assertEquals(4, h.heals.getLast().amount());
        assertEquals(50, h.ammo("a").reserve().orElseThrow().rounds());
        tick(h, 200_000); h.send("test:generate", "a", 0, 0); assertEquals(0, h.heals.getLast().amount()); assertEquals(10, h.ammo("a").magazine());
    }
    @Test void detachedCaptureRetainsCapacityWhileUncapturedReadsObserveExpiration() throws Exception {
        var h = harness(); h.initialize("a"); h.send("test:expand", "a", 0, 0);
        Value value = new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.CAPACITY); var frozen = value.snapshot(evaluation(h.program, h.state(), "b"));
        h.send("test:freeze", "a", 0, 0); h.session.start(0, SourceChange.remove("a")); tick(h, 400_000);
        assertEquals(5, capacity(h, "a")); assertEquals(10, h.ammo("a").magazine()); assertEquals(9, h.heals.getLast().amount());
        assertEquals(10, frozen.evaluate(evaluation(h.program, h.state(), "b")).value());
        assertEquals(5, value.evaluate(evaluation(h.program, h.state(), "b")).value());
    }
    @Test void profileCombinesStaticAndBuffContributionsThenRoundsOnceWithAVisibleTrace() throws Exception {
        var data = json("ammo_capacity"); var extra = new JsonObject(); extra.addProperty("id", "test:static");
        var modifiers = new JsonArray(); var m = modifier(data).deepCopy(); m.getAsJsonObject("value").addProperty("value", .1);
        m.add("if", JsonParser.parseString("{\"type\":\"chorus:source_is\",\"source\":\"this_weapon\"}")); m.addProperty("stacking_key", "test:static"); modifiers.add(m); extra.add("modifiers", modifiers); data.getAsJsonArray("bundles").add(extra);
        var h = new AmmoProgramTest.Harness(compile(data)); h.initialize("a"); h.initialize("b");
        h.session.start(0, SourceChange.bind(new EffectSource("static", "test:static", "player", AmmoProgramTest.sourceFor("a").origin(), Set.of())));
        assertEquals(6, capacity(h, "a")); h.send("test:expand", "a", 0, 0);
        var view = h.program.ammoCapacity(h.state(), "a"); assertEquals(11, view.capacity()); assertEquals(5, capacity(h, "b"));
        assertEquals(5, view.calculation().orElseThrow().inputs().base().value());
        assertEquals(2, view.calculation().orElseThrow().trace().contributions().stream().filter(x -> x.selected()).count());
        h.session.start(0, SourceChange.remove("static")); assertEquals(10, capacity(h, "a"));
    }
    @Test void crossHolderGrantUsesTheRecipientsCapacityAndFactsKeepTheInitiator() throws Exception {
        var data = json("ammo_capacity"); var extra = new JsonObject(); extra.addProperty("id", "test:global"); var modifiers = new JsonArray(); modifiers.add(modifier(data).deepCopy()); extra.add("modifiers", modifiers); data.getAsJsonArray("bundles").add(extra);
        var p = compile(data); var a = new AmmoState("a", 1, 5, Optional.empty(), Optional.of(new AmmoState.CapacityProfile("player", "test:capacity")));
        var b = new AmmoState("b", 1, 5, Optional.empty(), Optional.of(new AmmoState.CapacityProfile("recipient", "test:capacity")));
        var state = EffectState.empty().withAmmo(a).withAmmo(b).withSource(new EffectSource("global", "test:global", "player", AmmoProgramTest.sourceFor("a").origin(), Set.of()));
        assertEquals(10, p.ammoCapacity(state, "a").capacity()); assertEquals(5, p.ammoCapacity(state, "b").capacity());
        var outcome = (RuleEngine.Local<EffectState>) new AmmoActions.Refill(Evaluation.Target.VICTIM, Optional.empty(), Optional.empty()).execute(evaluation(p, state, "b"));
        var result = (AmmoActions.Change) outcome.result(); assertEquals(4, result.mutation().applied()); assertEquals(5, result.view().capacity());
        var fact = (EffectEvent) outcome.emitted().getFirst().payload(); assertEquals("a", fact.source().weapon()); assertEquals("b", fact.victim());
        assertEquals(5, fact.numbers().get("capacity").value()); assertEquals(5, fact.numbers().get("unmodified_capacity").value());
        assertEquals(1, outcome.state().ammunition().get("a").magazine());
    }
    @Test void codecRoundTripRejectsUnknownOrWrongUnitProfilesAndInitializationCannotChangeTheirOwnership() throws Exception {
        var data = json("ammo_capacity"); var program = compile(data).program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        var wrong = data.deepCopy(); init(wrong).addProperty("capacity_profile", "test:missing"); assertThrows(RuntimeException.class, () -> compile(wrong));
        var invalid = data.deepCopy(); invalid.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("input_unit", "damage"); assertThrows(RuntimeException.class, () -> compile(invalid));
        var h = harness(); h.initialize("a"); var account = h.ammo("a");
        var redefine = new AmmoActions.Initialize(Evaluation.Target.THIS_WEAPON, new Value.Constant(5, Unit.ROUND), new Value.Constant(1, Unit.ROUND),
                new AmmoActions.ReserveSpec(Optional.of(new AmmoActions.FiniteReserve(new Value.Constant(50, Unit.ROUND), new Value.Constant(100, Unit.ROUND)))), Optional.of("test:capacity"), Evaluation.Target.VICTIM);
        assertThrows(IllegalArgumentException.class, () -> redefine.execute(evaluation(h.program, h.state(), "recipient"))); assertEquals(account, h.ammo("a"));
        assertThrows(IllegalArgumentException.class, () -> account.read(AmmoState.Field.CAPACITY)); assertEquals(5, account.read(AmmoState.Field.UNMODIFIED_CAPACITY));
    }
    @Test void receiptAndFactsRetainTheCapacityUsedEvenWhenTheWriteChangesALaterQuery() throws Exception {
        var data = json("ammo_capacity"); var extra = new JsonObject(); extra.addProperty("id", "test:reactive");
        var m = modifier(data).deepCopy(); m.add("value", JsonParser.parseString("""
                {"type":"chorus:choose","if":{"type":"chorus:compare","left":{"type":"chorus:ammo","field":"magazine"},"op":"gt","right":{"type":"chorus:constant","value":1,"unit":"round"}},
                 "then":{"type":"chorus:constant","value":1,"unit":"delta"},"else":{"type":"chorus:constant","value":0,"unit":"delta"}}
                """));
        var modifiers = new JsonArray(); modifiers.add(m); extra.add("modifiers", modifiers); data.getAsJsonArray("bundles").add(extra); var p = compile(data);
        var account = new AmmoState("a", 1, 5, Optional.empty(), Optional.of(new AmmoState.CapacityProfile("player", "test:capacity")));
        var state = EffectState.empty().withAmmo(account).withSource(new EffectSource("reactive", "test:reactive", "player", AmmoProgramTest.sourceFor("a").origin(), Set.of()));
        var outcome = (RuleEngine.Local<EffectState>) new AmmoActions.Refill(Evaluation.Target.THIS_WEAPON, Optional.empty(), Optional.empty()).execute(evaluation(p, state, "b"));
        var receipt = (AmmoActions.Change) outcome.result(); assertEquals(4, receipt.mutation().applied()); assertEquals(5, receipt.view().capacity());
        assertEquals(10, p.ammoCapacity(outcome.state(), "a").capacity());
        assertEquals(5, ((EffectEvent) outcome.emitted().getFirst().payload()).numbers().get("capacity").value());
        assertEquals(5, AmmoActions.CHANGE.read("capacity", receipt).value()); assertEquals(0, AmmoActions.CHANGE.read("missing", receipt).value());
    }
    @Test void nonIntegralZeroAndOutOfRangeCapacityFailBeforeInitializationCanCommit() throws Exception {
        for (double value : new double[] {0, .5, 2147483648.0}) {
            var data = json("ammo_capacity"); var steps = data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps");
            steps.add(JsonParser.parseString("{\"type\":\"chorus:clamp\",\"id\":\"bad\",\"minimum\":" + value + ",\"maximum\":" + value + "}"));
            var h = new AmmoProgramTest.Harness(compile(data)); assertThrows(IllegalStateException.class, () -> h.initialize("a")); assertTrue(h.state().ammunition().isEmpty());
        }
    }
    @Test void numericSelfReferenceIsDiagnosedWhileRawCapacityAndLoadedRoundInputsRemainReadable() throws Exception {
        var data = json("ammo_capacity"); var m = modifier(data); m.add("value", JsonParser.parseString("{\"type\":\"chorus:scale\",\"of\":{\"type\":\"chorus:ammo\",\"field\":\"unmodified_capacity\"},\"factor\":0.2,\"from\":\"round\",\"to\":\"delta\"}"));
        var h = new AmmoProgramTest.Harness(compile(data)); h.initialize("a"); h.send("test:expand", "a", 0, 0); assertEquals(10, capacity(h, "a"));
        for (boolean indirect : List.of(false, true)) {
            var cyclic = data.deepCopy(); var extra = new JsonObject(); extra.addProperty("id", "test:cyclic"); var modifiers = new JsonArray(); var modified = modifier(cyclic).deepCopy();
            modified.getAsJsonObject("value").getAsJsonObject("of").addProperty("field", "capacity");
            if (indirect) modified.add("if", JsonParser.parseString("{\"type\":\"chorus:not\",\"of\":{\"type\":\"chorus:source_is\",\"source\":\"this_weapon\"}}"));
            modifiers.add(modified); extra.add("modifiers", modifiers); cyclic.getAsJsonArray("bundles").add(extra); var p = compile(cyclic);
            var state = EffectState.empty();
            for (String weapon : List.of("a", "b")) state = state.withAmmo(new AmmoState(weapon, 1, 5, Optional.empty(), Optional.of(new AmmoState.CapacityProfile("player", "test:capacity"))))
                    .withSource(new EffectSource(weapon, "test:cyclic", "player", AmmoProgramTest.sourceFor(weapon).origin(), Set.of()));
            var queryState = state; var error = assertThrows(IllegalArgumentException.class, () -> p.ammoCapacity(queryState, "a")); assertTrue(error.getMessage().contains("Circular ammunition"));
        }
    }
}
