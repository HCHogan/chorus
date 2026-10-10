package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AmmoProgramTest {
    static EffectSource sourceFor(String weapon) { return new EffectSource(weapon, "test:ammo", "player", new BuffInstance.Origin("player", "perk-" + weapon, weapon, ""), Set.of()); }
    static EffectEvent input(String weapon, double rounds, double ceiling) {
        return new EffectEvent("player", "target", sourceFor(weapon).origin(), Set.of(), Map.of("rounds", new Measure(rounds, Unit.ROUND), "ceiling", new Measure(ceiling, Unit.ROUND)));
    }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<HealingCommand> heals = new ArrayList<>(); final List<String> cues = new ArrayList<>(); final List<AmmoActions.Observation> observations = new ArrayList<>();
        Harness() throws Exception { this(load("ammunition")); }
        Harness(CompiledEffects program) {
            this.program = program;
            session = new EffectSession(engine(program), EffectState.empty().withSource(sourceFor("a")).withSource(sourceFor("b")), request -> {
                if (request.command() instanceof HealingCommand heal) { heals.add(heal); return HealingReceipt.unapplied(request.id().toString(), heal, HealingReceipt.Outcome.MISSING); }
                if (request.command() instanceof Action.CueCommand cue) {
                    cues.add(cue.cue());
                    for (var frame : current().engine().frames()) for (var value : frame.bindings().values()) if (value instanceof AmmoActions.Observation view) observations.add(view);
                    return RuleEngine.Empty.INSTANCE;
                }
                throw new AssertionError(request.command());
            });
        }
        TimelineEngine.State<EffectState> current() { return session.state(); }
        EffectState state() { return current().engine().domain(); }
        AmmoState ammo(String weapon) { return state().ammunition().get(weapon); }
        void send(String type, String weapon, double rounds, double ceiling) { session.start(state().buffs().timeMicros(), new RuleEngine.Signal(type, input(weapon, rounds, ceiling))); }
        void initialize(String weapon) { send("test:initialize", weapon, 0, 0); }
    }
    @Test void weaponInstancesStaySeparateAndRefillsAndGenerationCannotImpersonateReloads() throws Exception {
        var h = new Harness(); h.initialize("a"); h.initialize("b");
        h.send("test:refill", "a", 99, 12); assertEquals(12, h.ammo("a").magazine()); assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(2, h.ammo("b").magazine()); assertEquals(10, h.ammo("b").reserve().orElseThrow().rounds());
        h.send("test:generate", "b", 2, 6); assertEquals(4, h.ammo("b").magazine()); assertEquals(10, h.ammo("b").reserve().orElseThrow().rounds());
        assertEquals(List.of(10.0, 2.0), h.heals.stream().map(HealingCommand::amount).toList());
        assertEquals(List.of("test:ammo_changed", "test:ammo_changed"), h.cues);
        h.send("chorus:reload_finished", "a", 0, 0); assertEquals("test:reload", h.cues.getLast());
    }
    @Test void initializationIsIdempotentAndDoesNotResetAmmoWhenSourceIsRebound() throws Exception {
        var h = new Harness(); h.initialize("a"); h.send("test:spend", "a", 1, 0);
        h.session.start(0, SourceChange.remove("a")); h.session.start(0, SourceChange.bind(sourceFor("a"))); h.initialize("a");
        assertEquals(1, h.ammo("a").magazine()); assertEquals(10, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(1, h.cues.size());
    }
    @Test void partialRefillAndFailedSpendUseActualAmountsWithoutRecoveringOverflowLater() throws Exception {
        var h = new Harness(); h.initialize("a"); h.send("test:refill", "a", 100, 6);
        assertEquals(4, h.heals.getLast().amount()); assertEquals(6, h.ammo("a").reserve().orElseThrow().rounds());
        int calls = h.heals.size(), changes = h.cues.size(); h.send("test:spend", "a", 7, 0);
        assertEquals(6, h.ammo("a").magazine()); assertEquals(calls, h.heals.size()); assertEquals(changes, h.cues.size());
        h.send("test:spend", "a", 2, 0); assertEquals(4, h.ammo("a").magazine());
        h.send("test:generate", "a", 10, 6); assertEquals(2, h.heals.getLast().amount());
        h.send("test:spend", "a", 6, 0); assertEquals(0, h.ammo("a").magazine()); assertEquals(6, h.ammo("a").reserve().orElseThrow().rounds());
    }
    @Test void detachedDelayedRefillAndUnrelatedStateChangesPreserveTheAccount() throws Exception {
        var h = new Harness(); h.initialize("a"); h.send("test:defer", "a", 20, 12);
        h.session.start(0, SourceChange.remove("a")); h.session.start(1_000_000, new RuleEngine.Signal("chorus:tick", RuleEngine.Empty.INSTANCE));
        assertEquals(12, h.ammo("a").magazine()); assertEquals(10, h.heals.getLast().amount()); assertTrue(h.current().idle());
        var state = h.state(); var changed = state.withMode(EffectState.Mode.PVP).withAbilities("player", com.imdomestic.chorus.effect.ability.AbilityLoadout.EMPTY)
                .withEquipment("player", com.imdomestic.chorus.effect.equipment.Loadout.EMPTY).withBuffs(state.buffs()).withSources(state.sources());
        assertEquals(state.ammunition(), changed.ammunition());
    }
    @Test void observationDistinguishesMissingUnlimitedAndFiniteWithoutInventingZeroAmounts() throws Exception {
        var h = new Harness(); h.send("test:inspect", "a", 0, 0); var missing = h.observations.getLast();
        assertFalse(AmmoActions.OBSERVATION.flag("available", missing)); assertThrows(RuntimeException.class, () -> AmmoActions.OBSERVATION.read("magazine", missing));
        var data = json("ammunition"); initializer(data).addProperty("reserves", "unlimited"); var unlimited = new Harness(compile(data)); unlimited.initialize("a");
        unlimited.send("test:inspect", "a", 0, 0); var present = unlimited.observations.getLast();
        assertTrue(AmmoActions.OBSERVATION.flag("infinite_reserves", present)); assertFalse(AmmoActions.OBSERVATION.flag("finite_reserves", present));
        assertThrows(IllegalArgumentException.class, () -> AmmoActions.OBSERVATION.read("reserves", present));
        unlimited.send("test:refill", "a", 99, 12); assertEquals(12, unlimited.ammo("a").magazine()); assertTrue(unlimited.ammo("a").reserve().isEmpty());
    }
    @Test void ammoReadsAndRoundingCaptureSourceValuesButLeaveVictimReadsForImpact() {
        var event = new RuleEngine.Event(1, 1, Optional.empty(), 0, new RuleEngine.Signal("test:read", new EffectEvent("player", "victim", sourceFor("a").origin(), Set.of(), Map.of())));
        var context = new RuleEngine.Context(event, "read", sourceFor("a"), Map.of());
        var state = EffectState.empty().withAmmo(new AmmoState("a", 3, 6, Optional.empty())).withAmmo(new AmmoState("victim", 5, 6, Optional.empty()));
        var first = new Evaluation(state, context, Map.of(), Map.of());
        Value source = new Value.Round(new Value.Scale(new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.MAGAZINE), .6, Unit.ROUND, Unit.ROUND), Value.Rounding.CEILING);
        var saved = source.snapshot(first); var victim = new Value.Ammo(Evaluation.Target.VICTIM, AmmoState.Field.MAGAZINE).snapshot(first);
        var later = new Evaluation(state.withAmmo(state.ammunition().get("a").magazine(0)).withAmmo(state.ammunition().get("victim").magazine(1)), context, Map.of(), Map.of());
        assertEquals(2, saved.evaluate(later).value()); assertEquals(0, source.evaluate(later).value()); assertEquals(1, victim.evaluate(later).value());
    }
    private static JsonObject initializer(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject(); }
    @Test void dynamicFractionalAmountsAndReinitializationWithDifferentCapacityFailWithoutMutation() throws Exception {
        var h = new Harness(); h.initialize("a"); var before = h.ammo("a");
        assertThrows(IllegalStateException.class, () -> h.send("test:refill", "a", .5, 12));
        assertEquals(before, h.ammo("a")); assertTrue(h.heals.isEmpty()); assertTrue(h.cues.isEmpty());
        var data = json("ammunition"); initializer(data).add("capacity", JsonParser.parseString("{\"type\":\"chorus:event_number\",\"name\":\"ceiling\",\"unit\":\"round\"}"));
        var dynamic = new Harness(compile(data)); dynamic.send("test:initialize", "a", 0, 6); dynamic.send("test:spend", "a", 1, 0);
        var spent = dynamic.ammo("a");
        assertThrows(IllegalStateException.class, () -> dynamic.send("test:initialize", "a", 0, 7));
        assertEquals(spent, dynamic.ammo("a")); assertEquals(1, dynamic.heals.size());
    }
    @Test void factsDistinguishTheAffectedWeaponFromTheInitiatorAndDoNotInventUnlimitedAmounts() {
        var origin = sourceFor("a").origin(); var account = new AmmoState("b", 2, 6, Optional.empty());
        var result = Ammunition.refill(account, 8, 6); var facts = AmmoFacts.changed("player", origin, result);
        assertEquals(origin, facts.source()); assertEquals("b", facts.victim()); assertEquals("b", facts.references().get("weapon"));
        assertEquals(4, facts.numbers().get("applied").value()); assertEquals(Unit.ROUND, facts.numbers().get("applied").unit());
        assertFalse(facts.numbers().containsKey("reserves")); assertFalse(facts.numbers().containsKey("reserve_delta"));
        assertTrue(facts.flags().get("infinite_reserves")); assertFalse(facts.flags().get("complete"));
    }
    @Test void fixtureRoundTripsAndTypedRoundedCountsExecuteWhileInvalidInitializationFailsCompilation() throws Exception {
        var data = json("ammunition"); var p = compile(data).program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        var h = new Harness(); h.initialize("a"); h.send("test:rounded", "a", 0, 0); assertEquals(6, h.ammo("a").magazine()); assertEquals(10, h.ammo("a").reserve().orElseThrow().rounds());
        for (String fault : List.of("fraction", "zero", "unit", "negative", "capacity", "spelling", "field")) {
            var invalid = data.deepCopy(); var init = initializer(invalid);
            switch (fault) {
                case "fraction" -> init.getAsJsonObject("magazine").addProperty("value", .5);
                case "zero" -> init.getAsJsonObject("capacity").addProperty("value", 0);
                case "unit" -> init.getAsJsonObject("capacity").addProperty("unit", "charge_fraction");
                case "negative" -> init.getAsJsonObject("reserves").getAsJsonObject("amount").addProperty("value", -1);
                case "capacity" -> init.getAsJsonObject("reserves").getAsJsonObject("capacity").addProperty("value", 1);
                case "spelling" -> init.addProperty("reserves", "infinite");
                case "field" -> init.addProperty("typo", true);
            }
            assertThrows(RuntimeException.class, () -> compile(invalid), fault);
        }
    }
}
