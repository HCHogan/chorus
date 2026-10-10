package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityCostSelectionTest {
    static class Harness {
        final CompiledEffects p = load("ability_cost_selection"); final EffectSession session;
        final List<HealingCommand> heals = new ArrayList<>(); boolean failWorld; int cast;
        Harness() throws Exception {
            session = new EffectSession(engine(p), EffectState.empty(), request -> {
                var heal = (HealingCommand) request.command(); heals.add(heal);
                if (failWorld) throw new IllegalStateException("Unknown conversion world result");
                return new HealingReceipt("heal-" + heals.size(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(RuleEngine.Signal signal) { session.start(state().buffs().timeMicros(), signal); }
        void choose(String id) { send(new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("test:grenade", "test:" + id))).signal()); }
        void bind(String name) { send(SourceChange.bind(source("test:" + name))); }
        AbilityUse.Request request() { return new AbilityUse.Request("player", "test:grenade", "cast-" + ++cast, new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())); }
        AbilityUse.Receipt use() { var request = request(); var plan = p.useAbility(state(), request); send(request.signal()); return (AbilityUse.Receipt) plan.result(); }
        double energy(String id) { return state().resources().get(new ResourceState.Key("player", "test:" + id)).value(); }
    }
    @Test void conversionConsumesEachOriginalSelectionsDeclaredPoolAndAmountWithResolvedCredit() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("convert"); var a = h.use();
        assertEquals("test:a", a.base()); assertEquals("test:converted", a.resolved()); assertEquals(1, a.cost().orElseThrow().receipt().paid());
        assertEquals(1, h.energy("energy")); assertEquals(2, h.energy("converted_energy"));
        h.choose("b"); var b = h.use(); assertEquals(.5, b.cost().orElseThrow().receipt().paid());
        assertEquals(1.5, h.energy("other")); assertEquals(1, h.energy("energy")); assertEquals(2, h.energy("converted_energy"));
        assertTrue(h.heals.stream().allMatch(c -> c.amount() == 4 && c.source().ability().equals("test:converted") && c.source().tags().contains("test:converted")));
        assertEquals("test:b", h.state().abilities().get("player").slots().get("test:grenade"));
    }
    @Test void chainedReplacementReadsOriginalCostWhileDirectSelectionUsesItsOwnDeclaration() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("chain"); var r = h.use();
        assertEquals(1, r.cost().orElseThrow().receipt().paid()); assertEquals(2, h.energy("converted_energy"));
        h.choose("converted"); r = h.use(); assertEquals(1.5, r.cost().orElseThrow().receipt().paid()); assertEquals(.5, h.energy("converted_energy"));
    }
    @Test void inheritedCostProfileEvaluatesAgainstTheActualConvertedCast() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("convert");
        var discount = new EffectSource("discount", "test:discount", "player", new BuffInstance.Origin("player", "discount", "", ""), Set.of()); h.send(SourceChange.bind(discount));
        assertEquals(.5, h.use().cost().orElseThrow().receipt().paid()); assertEquals(1.5, h.energy("energy"));
        h.send(SourceChange.remove("perk")); assertEquals(1, h.use().cost().orElseThrow().receipt().paid()); assertEquals(.5, h.energy("energy"));
    }
    @Test void refundsKeepThePaidAccountAndBudgetAfterTheBaseSelectionAndOverrideChange() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("refund_override"); h.use(); assertEquals(1, h.energy("energy"));
        h.choose("b"); h.send(SourceChange.remove("perk")); h.session.observe(100_000, List.of());
        assertEquals(2, h.energy("energy")); assertEquals(2, h.energy("other")); assertEquals(2, h.energy("converted_energy"));
        assertTrue(h.session.state().idle());
    }
    @Test void zeroCostSelectionIsFreeAndRefundsCannotCreateEnergy() throws Exception {
        var h = new Harness(); h.choose("a"); h.use(); h.use(); assertEquals(0, h.energy("energy"));
        h.choose("zero"); h.bind("refund_override"); var r = h.use(); assertEquals(0, r.cost().orElseThrow().receipt().paid());
        h.session.observe(100_000, List.of()); assertEquals(0, h.energy("energy")); assertEquals(2, h.energy("converted_energy"));
    }
    @Test void missingDeclaredBaseCostRejectsBeforeParametersAndInsufficientEnergyNeverFallsBack() throws Exception {
        var h = new Harness(); h.choose("free"); h.bind("missing_override"); var before = h.state();
        assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use().outcome()); assertEquals(before, h.state()); assertTrue(h.heals.isEmpty());
        h.choose("a"); h.bind("convert"); h.use(); h.use(); before = h.state();
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use().outcome()); assertEquals(before, h.state()); assertEquals(2, h.energy("converted_energy"));
    }
    @Test void externalGainsStillRouteToBaseSelectionAndUnknownWorldResultsKeepItsPayment() throws Exception {
        var h = new Harness(); h.choose("a"); h.bind("convert"); h.use();
        h.send(SourceChange.bind(new EffectSource("gain", "test:gain", "player", new BuffInstance.Origin("player", "gain", "", ""), Set.of())));
        h.send(new RuleEngine.Signal("test:gain", event(source("test:gain")))); assertEquals(1.25, h.energy("energy")); assertEquals(2, h.energy("converted_energy"));
        h.failWorld = true; assertThrows(IllegalStateException.class, h::use); assertEquals(.25, h.energy("energy")); assertEquals(2, h.energy("converted_energy"));
        assertThrows(IllegalStateException.class, () -> h.session.observe(100_000, List.of())); assertEquals(2, h.heals.size());
    }
    @Test void costPolicyRoundTripsAndInheritedReceiptBindingDoesNotRequireAnOwnPool() throws Exception {
        var p = load("ability_cost_selection"); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        var data = json("ability_cost_selection"); data.getAsJsonArray("abilities").get(4).getAsJsonObject().addProperty("cost_from", "definition");
        assertThrows(RuntimeException.class, () -> compile(data));
        data.getAsJsonArray("abilities").get(4).getAsJsonObject().addProperty("cost_from", "previous_replacement"); assertThrows(RuntimeException.class, () -> compile(data));
    }
}
