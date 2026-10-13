package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityPreviousCostTest {
    static class Harness {
        final CompiledEffects p; EffectSession session; final List<HealingCommand> heals = new ArrayList<>();
        boolean failWorld; int cast; List<RuleEngine.Signal> facts = List.of();
        Harness() throws Exception { this(load("ability_cost_previous")); }
        Harness(CompiledEffects p) { this.p = p; session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            var heal = (HealingCommand)r.command(); heals.add(heal); if (failWorld) throw new IllegalStateException("Unknown consume result");
            return new HealingReceipt(r.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(RuleEngine.Signal signal) { session.start(state().buffs().timeMicros(), signal); }
        void choose(String id) { send(new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("test:grenade", "test:" + id))).signal()); }
        void bind(String name) { send(SourceChange.bind(new EffectSource(name, "test:" + name, "player", new BuffInstance.Origin("player", name, "", ""), Set.of()))); }
        AbilityUse.Request request() {
            return new AbilityUse.Request("player", "test:grenade", "cast/" + ++cast, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of("test:wisp"), Map.of(), Map.of(),
                    Map.of("base_ability", "test:middle", "replaced_ability", "test:middle", "cost_ability", "test:middle")));
        }
        AbilityUse.Receipt use() { var r = request(); var plan = p.useAbility(state(), r); facts = plan.emitted(); send(r.signal()); return (AbilityUse.Receipt)plan.result(); }
        AbilityUse.Started started() { return (AbilityUse.Started)facts.stream().filter(f -> f.type().equals(AbilityUse.STARTED)).findFirst().orElseThrow().payload(); }
        double energy(String name) { return state().resources().get(new ResourceState.Key("player", "test:" + name)).value(); }
        void until(long at) { session.observe(at, List.of()); }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void oneOrSeveralTransformsConsumeOnlyTheImmediatePredecessorsDeclaredCharge() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("convert"); var r = h.use();
        assertEquals(1, r.cost().orElseThrow().receipt().paid()); assertEquals("test:a", h.started().event().references().get("replaced_ability")); assertEquals(4, h.heals.getFirst().amount());
        h = new Harness(); h.choose("a"); h.bind("chain"); r = h.use();
        assertEquals(.2, r.cost().orElseThrow().receipt().paid()); assertEquals(2, h.energy("energy")); assertEquals(1.8, h.energy("converted_energy"), 1e-12);
        assertEquals("test:a", r.base()); assertEquals("test:converted", r.resolved()); assertEquals("test:middle", h.started().event().references().get("cost_ability")); assertEquals(8, h.heals.getFirst().amount());
        assertEquals("test:converted", h.heals.getFirst().source().ability()); assertEquals("test:a", h.state().abilities().get("player").slots().get("test:grenade"));
    }
    @Test void identityOverridesCannotChangePredecessorAndAThirdTransformReallyCan() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("identity_first"); h.bind("chain"); h.bind("identity"); h.use();
        assertEquals("test:middle", h.started().event().references().get("replaced_ability")); assertEquals(.2, h.started().receipt().cost().orElseThrow().receipt().paid());
        h.bind("refund_override"); h.use(); assertEquals("test:converted", h.started().event().references().get("replaced_ability"));
        assertEquals(1.5, h.started().receipt().cost().orElseThrow().receipt().paid()); assertEquals(.3, h.energy("converted_energy"), 1e-12);
    }
    @Test void missingPredecessorAndCostlessRelayDoNotFallBackOrEvaluateUnknownParameters() throws Exception {
        for (String selected : List.of("converted", "missing_parameter")) {
            var h = new Harness(); h.choose(selected); var before = h.state(); assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use().outcome()); assertEquals(before, h.state()); assertTrue(h.heals.isEmpty());
        }
        var h = new Harness(); h.choose("free"); h.bind("missing_override"); var before = h.state(); assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use().outcome()); assertEquals(before, h.state());
        h = new Harness(); h.choose("a"); h.bind("relay_only"); h.bind("convert"); before = h.state();
        assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use().outcome()); assertEquals(before, h.state()); assertTrue(h.heals.isEmpty());
    }
    @Test void actualConvertedCastDrivesCostModifiersWhileInputTagsCannotForgePredecessorMetadata() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("chain"); h.bind("discount"); assertEquals(.1, h.use().cost().orElseThrow().receipt().paid(), 1e-12);
        assertEquals(8, h.heals.getFirst().amount()); assertEquals("test:a", h.started().event().references().get("base_ability"));
        h = new Harness(); h.choose("a"); h.bind("convert"); h.use(); assertEquals(4, h.heals.getFirst().amount());
        h = new Harness(); h.choose("a"); h.use(); assertEquals("", h.started().event().references().get("replaced_ability"));
    }
    @Test void branchSelectionUsesItsActualPredecessorAndConflictsRemainAtomic() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("middle_alternative"); h.use();
        assertEquals(.5, h.started().receipt().cost().orElseThrow().receipt().paid()); assertEquals("test:b", h.started().event().references().get("replaced_ability")); assertEquals(4, h.heals.getFirst().amount());
        h = new Harness(); h.choose("a"); h.bind("middle_only"); h.bind("conflict"); h.bind("convert"); var before = h.state();
        assertEquals(AbilityUse.Outcome.CONFLICT, h.use().outcome()); assertEquals(before, h.state()); assertTrue(h.facts.isEmpty() && h.heals.isEmpty());
    }
    @Test void exhaustedPredecessorCannotBorrowBaseEnergyAndExplicitZeroCostRemainsFree() throws Exception {
        var h = new Harness(); h.choose("b"); h.bind("convert"); for (int i = 0; i < 4; i++) assertEquals(AbilityUse.Outcome.ACCEPTED, h.use().outcome());
        var before = h.state(); assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use().outcome()); assertEquals(before, h.state()); assertEquals(2, h.energy("energy"));
        h = new Harness(); h.choose("zero"); h.bind("convert"); assertEquals(0, h.use().cost().orElseThrow().receipt().paid()); assertTrue(h.started().event().flags().get("free"));
    }
    @Test void delayedRefundRetainsThePaidPredecessorAcrossCheckpointUnselectionAndOverrideRemoval() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("middle_only"); h.bind("refund_override"); h.use(); assertEquals(1.8, h.energy("converted_energy"), 1e-12);
        h.choose("b"); h.send(SourceChange.remove("middle_only")); h.send(SourceChange.remove("refund_override")); h.restore(); h.until(100_000);
        assertEquals(2, h.energy("converted_energy"), 1e-12); assertEquals(2, h.energy("other")); assertEquals(2, h.energy("energy")); assertEquals(1, h.heals.size()); assertTrue(h.state().retainedCosts().isEmpty());
    }
    @Test void unknownWorldResultKeepsTheActualCostAndNeverReplaysTheConvertedAction() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("chain"); h.failWorld = true; assertThrows(IllegalStateException.class, h::use);
        assertEquals(1.8, h.energy("converted_energy"), 1e-12); assertEquals(2, h.energy("energy")); assertEquals(1, h.heals.size());
        assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(1, h.heals.size());
    }
    @Test void currentDataRoundTripsAndOldCostEnumsMigrateWithoutAcceptingNewValuesUnderOldIds() throws Exception {
        var p = load("ability_cost_previous"); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        var codec = EffectCheckpointCodec.DATA;
        for (var policy : AbilityDefinition.CostFrom.values()) {
            var wire = codec.encode(policy).getAsJsonObject(); assertEquals(policy, codec.decode(wire, AbilityDefinition.CostFrom.class));
            wire.addProperty("$", "chorus:effect/ability/ability_definition/cost_from");
            if (policy == AbilityDefinition.CostFrom.PREVIOUS_DEFINITION) assertThrows(IllegalArgumentException.class, () -> codec.decode(wire, AbilityDefinition.CostFrom.class));
            else assertEquals(policy, codec.decode(wire, AbilityDefinition.CostFrom.class));
        }
        assertThrows(IllegalArgumentException.class, () -> new Condition.CastAbilityTag("cost_ability", "test:tag"));
        var invalid = json("ability_cost_previous"); invalid.getAsJsonArray("abilities").get(3).getAsJsonObject().addProperty("cost_slot", "test:other");
        assertThrows(RuntimeException.class, () -> compile(invalid));
    }
}
