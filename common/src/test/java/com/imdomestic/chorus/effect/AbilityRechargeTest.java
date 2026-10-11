package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityRechargeTest {
    static final String SLOT = "chorus_d2:melee", ABILITY = "test:linked_ability", USES = "test:charges", METER = "test:progress";
    static CompiledEffects program() throws Exception { return link("linked_recharge", "ability_recharge_inputs", "pugilist", "weapon_stats", "surplus", "wellspring"); }
    static EffectSource source(String id, String bundle, String holder) {
        return new EffectSource(id, bundle, holder, new BuffInstance.Origin(holder, id, id, ""), Set.of());
    }
    static final class Harness {
        final CompiledEffects p = program();
        final EffectSession session;
        final List<HealingCommand> heals = new ArrayList<>();
        boolean failCompletion;
        int casts;
        Harness() throws Exception {
            session = new EffectSession(engine(p), EffectState.empty(), request -> {
                var c = (HealingCommand) request.command(); heals.add(c);
                if (failCompletion && c.tags().contains("test:completed")) throw new IllegalStateException("Unknown routed completion result");
                return new HealingReceipt("heal/" + heals.size(), c, HealingReceipt.Outcome.APPLIED, c.amount(), c.amount(), 0);
            });
            send(SourceChange.bind(source("input", "test:route_inputs", "player"))); select("player", ABILITY);
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal signal) { session.start(now(), signal); }
        void until(long at) { session.observe(at, List.of()); }
        void select(String holder, String ability) {
            send(new AbilityChange(holder, state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY), ability.isEmpty() ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of(SLOT, ability))).signal());
        }
        ResourceState account(String holder, String resource) { return state().resources().get(new ResourceState.Key(holder, resource)); }
        double value(String resource) { return account("player", resource).value(); }
        void grant(String basis, String holder, double amount) {
            send(new RuleEngine.Signal("test:route_" + basis, new EffectEvent("player", holder, source("input", "test:route_inputs", "player").origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.CHARGE)))));
        }
        AbilityUse.Receipt use() {
            var request = new AbilityUse.Request("player", SLOT, "cast/" + ++casts, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of()));
            var receipt = (AbilityUse.Receipt) p.useAbility(state(), request).result(); send(request.signal()); return receipt;
        }
        void kill(String weapon) {
            var origin = new BuffInstance.Origin("player", weapon, weapon, "");
            send(new RuleEngine.Signal("chorus:kill", new EffectEvent("player", "target", origin, Set.of("chorus:weapon_kill"), Map.of())));
        }
        Evaluation evaluation(EffectState state, String target) {
            var input = source("input", "test:route_inputs", "player");
            return new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state.buffs().timeMicros(),
                    new RuleEngine.Signal("test:query", new EffectEvent("player", target, input.origin(), Set.of(), Map.of()))), "query", input, Map.of()),
                    Map.of(), Map.of(), p.program().resources().stream().collect(java.util.stream.Collectors.toMap(ResourceDefinition::id, x -> x)), Map.of(), Optional.of(p));
        }
    }
    static EnergyActions.GrantAbility gain(EnergyGains.Basis basis, Value amount) {
        return new EnergyActions.GrantAbility(SLOT, Evaluation.Target.VICTIM, amount, basis,
                basis == EnergyGains.Basis.REFERENCE ? Map.of("test:previous", new Value.Constant(2, Unit.MULTIPLIER)) : Map.of(), Set.of(), Map.of());
    }
    static RuleEngine.Local<EffectState> execute(Harness h, String holder, EnergyActions.GrantAbility action) {
        return (RuleEngine.Local<EffectState>) action.execute(h.evaluation(h.state(), holder));
    }
    static EnergyActions.AbilityObservation observe(Harness h, String holder) {
        return (EnergyActions.AbilityObservation) ((RuleEngine.Local<EffectState>) new EnergyActions.ObserveAbility(SLOT, Evaluation.Target.VICTIM).execute(h.evaluation(h.state(), holder))).result();
    }

    @Test void selectingInitializesBothAccountsAndReselectionPreservesUsesAndProgress() throws Exception {
        var h = new Harness(); assertEquals(2, h.value(USES)); assertEquals(0, h.value(METER));
        assertEquals(AbilityUse.Outcome.ACCEPTED, h.use().outcome()); h.until(500_000); assertEquals(.25, h.value(METER));
        h.select("player", ""); h.until(2_000_000); assertEquals(.25, h.value(METER)); assertEquals(1, h.value(USES));
        h.select("player", ABILITY); assertEquals(.25, h.value(METER)); assertEquals(1, h.value(USES));
        h.until(3_500_000); assertEquals(2, h.value(USES)); assertEquals(0, h.value(METER));
        // The core selection transaction initializes the route even without processing the source's init rules.
        var selected = h.p.changeAbilities(EffectState.empty(), new AbilityChange("fresh", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(SLOT, ABILITY)))).state();
        assertTrue(selected.resources().containsKey(new ResourceState.Key("fresh", METER)));
    }
    @Test void baseReferenceAndFixedGrantsTargetTheMeterAndKeepItsScalarAndTrace() throws Exception {
        var h = new Harness(); h.use();
        for (var basis : EnergyGains.Basis.values()) {
            var out = execute(h, "player", gain(basis, new Value.Constant(.8, Unit.CHARGE)));
            var r = ((EnergyActions.AbilityResult) out.result()).granted();
            assertEquals(METER, r.grant().after().key().resource());
            assertEquals(basis == EnergyGains.Basis.FIXED ? .8 : basis == EnergyGains.Basis.BASE ? .4 : .2, r.grant().credited());
            assertEquals(1, out.state().resources().get(new ResourceState.Key("player", USES)).value());
            assertEquals(basis != EnergyGains.Basis.FIXED, r.recipient().isPresent());
            assertEquals(basis != EnergyGains.Basis.FIXED, r.calculation().isPresent());
        }
    }
    @Test void fullUsableAccountCannotBankRoutedEnergyAndReturnsAnExplicitNonNumericOutcome() throws Exception {
        var h = new Harness(); var action = gain(EnergyGains.Basis.BASE, new Value.EventNumber("not_evaluated", Unit.CHARGE));
        var shape = action.validate(new Validation(Map.of(), Map.of(), false)); var out = execute(h, "player", action);
        assertSame(h.state(), out.state()); assertTrue(out.emitted().isEmpty()); assertTrue(shape.flag("already_full", out.result()));
        assertFalse(shape.flag("granted", out.result())); assertThrows(IllegalArgumentException.class, () -> shape.read("credited", out.result()));
        assertEquals(0, h.value(METER));
        h.grant("fixed", "player", 1); assertEquals(0, h.value(METER)); assertTrue(h.heals.isEmpty());
    }
    @Test void legacyAccountsKeepTheirGrantReceiptsIncludingFullClippingAndAliasReads() throws Exception {
        var h = new Harness(); h.select("player", "test:legacy_route"); h.grant("fixed", "player", 2);
        var out = execute(h, "player", gain(EnergyGains.Basis.FIXED, new Value.Constant(1, Unit.CHARGE)));
        var result = (EnergyActions.AbilityResult) out.result(); assertEquals(EnergyActions.AbilityOutcome.GRANTED, result.outcome());
        assertEquals(0, result.granted().grant().credited()); assertEquals(1, result.granted().grant().overflow());
        var observation = observe(h, "player"); assertEquals(observation.observed(), observation.rechargeObserved()); assertFalse(observation.separateRecharge());
    }
    @Test void observationsSeparateReadyUsesFromProgressAndFreezeOnlyTheSourceSide() throws Exception {
        var h = new Harness(); h.use(); h.grant("fixed", "player", .4); h.select("ally", "test:other_route"); h.grant("fixed", "ally", .7);
        var r = observe(h, "player"); var shape = new EnergyActions.ObserveAbility(SLOT, Evaluation.Target.SELF).validate(new Validation(Map.of(), Map.of(), false));
        assertEquals(1, shape.read("full_charges", r).value()); assertEquals(2, shape.read("capacity", r).value()); assertEquals(.4, shape.read("recharge_value", r).value());
        assertEquals(.6, shape.read("recharge_missing", r).value()); assertTrue(shape.flag("separate_recharge", r)); assertFalse(shape.flag("recharge_full", r));
        var self = new Value.AbilityEnergy(SLOT, Evaluation.Target.SELF, "recharge_value");
        var victim = new Value.AbilityEnergy(SLOT, Evaluation.Target.VICTIM, "recharge_value"); var e = h.evaluation(h.state(), "ally");
        assertEquals(new Value.Constant(.4, Unit.CHARGE), self.snapshot(e)); assertSame(victim, victim.snapshot(e));
        h.grant("fixed", "ally", .1); assertEquals(.8, victim.snapshot(e).evaluate(h.evaluation(h.state(), "ally")).value()); assertEquals(.4, r.rechargeObserved().value());
        for (String selection : List.of("", "test:no_route")) {
            h.select("player", selection); var empty = observe(h, "player"); assertFalse(shape.flag("available", empty));
            assertFalse(shape.flag("recharge_full", empty)); assertFalse(shape.flag("separate_recharge", empty));
            assertThrows(IllegalArgumentException.class, () -> shape.read("recharge_value", empty));
        }
    }
    @Test void replacementsDoNotRedirectBaseSelectionGainsOrRefunds() throws Exception {
        var h = new Harness(); h.use();
        h.send(SourceChange.bind(source("override", "test:route_override_source", "player")));
        var receipt = h.use(); assertEquals(1, receipt.cost().orElseThrow().receipt().paid()); assertEquals(USES, receipt.cost().orElseThrow().receipt().account().resource());
        assertEquals(1, h.value(USES), "replacement refunds the actual payment"); assertEquals(0, h.value(METER));
        var grant = (EnergyActions.AbilityResult) execute(h, "player", gain(EnergyGains.Basis.BASE, new Value.Constant(.4, Unit.CHARGE))).result();
        assertEquals(Optional.of(ABILITY), grant.ability()); assertEquals(METER, grant.granted().grant().after().key().resource()); assertEquals(.2, grant.granted().grant().credited());
    }
    @Test void delayedRoutedGainsResolveTheActualRecipientAndCurrentSelectionAtExecution() throws Exception {
        var h = new Harness(); h.use(); h.use(); h.select("ally", "test:other_route");
        h.grant("base", "ally", .4); assertEquals(.1, h.account("ally", "test:other_progress").value()); assertEquals(0, h.value(METER));
        h.grant("later", "player", .2); h.until(50_000); h.select("player", "test:other_route"); h.until(100_000);
        assertEquals(.025, h.value(METER)); assertEquals(.2, h.value("test:other_progress")); assertEquals(0, h.value("test:other_uses"));
    }
    @Test void unchangedPugilistDefinitionFeedsTheCycleAndCompletesBothUsesOnTheFiftiethKill() throws Exception {
        var h = new Harness(); h.send(SourceChange.bind(PugilistTest.weapon("rifle", "player", false, "auto_rifle")));
        h.kill("rifle"); assertEquals(0, h.value(METER)); h.use(); h.use();
        for (int i = 0; i < 49; i++) h.kill("rifle");
        assertEquals(.98, h.value(METER)); assertEquals(0, h.value(USES)); assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use().outcome());
        h.kill("rifle"); assertEquals(0, h.value(METER)); assertEquals(2, h.value(USES)); assertEquals(3, h.heals.size());
    }
    @Test void wellspringSharesAndSurplusBonusesStillReadUsableChargesRatherThanTheMeter() throws Exception {
        var h = new Harness(); var surplus = source("surplus", "chorus_d2:surplus", "player");
        h.send(SourceChange.bind(surplus)); h.send(SourceChange.bind(source("spring", "chorus_d2:wellspring", "player")));
        java.util.function.DoubleSupplier handling = () -> h.p.calculate(h.state(), "player", new EffectEvent("player", "player", surplus.origin(), Set.of(), Map.of()),
                "chorus_d2:weapon_handling", new Measure(10, Unit.STAT_POINT), List.of()).output().value();
        assertEquals(35, handling.getAsDouble()); h.kill("spring"); assertEquals(0, h.value(METER));
        h.use(); assertEquals(15, handling.getAsDouble()); h.kill("spring"); assertEquals(.032 / 3 * .5, h.value(METER), 1e-12);
        h.use(); assertEquals(10, handling.getAsDouble()); h.kill("spring"); assertEquals(.032 / 3 * .5 + .032 * .5, h.value(METER), 1e-12);
        assertEquals(0, h.value(USES));
    }
    @Test void unknownCompletionKeepsBothAccountsAndBrokenRoutesDoNotMasqueradeAsFull() throws Exception {
        var h = new Harness(); h.use(); h.use(); h.failCompletion = true;
        assertThrows(IllegalStateException.class, () -> h.grant("fixed", "player", 1)); assertEquals(2, h.value(USES)); assertEquals(0, h.value(METER));
        assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(3, h.heals.size());
        var selected = EffectState.empty().withAbilities("player", new AbilityLoadout(Map.of(SLOT, ABILITY)))
                .withResource(new ResourceState(new ResourceState.Key("player", USES), 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> gain(EnergyGains.Basis.FIXED, new Value.Constant(1, Unit.CHARGE)).execute(h.evaluation(selected, "player")));
        assertThrows(IllegalArgumentException.class, () -> new EnergyActions.AbilityObservation("player", SLOT, Optional.of(ABILITY),
                Optional.of(new ResourceState(new ResourceState.Key("player", USES), 2, 2, 0)), Optional.of(new ResourceState(new ResourceState.Key("player", USES), 1, 2, 0))));
    }
    @Test void strictRoundTripValidatesExplicitReferencesAndRejectsRoutesWithoutDeclaredCosts() throws Exception {
        var p = program(); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        var d = json("linked_recharge"); d.getAsJsonArray("abilities").get(0).getAsJsonObject().addProperty("recharge_resource", "test:absent");
        assertThrows(RuntimeException.class, () -> compile(d));
        var noCost = json("linked_recharge"); noCost.getAsJsonArray("abilities").get(0).getAsJsonObject().remove("cost"); assertThrows(RuntimeException.class, () -> compile(noCost));
        var legacy = p.program().abilities().stream().filter(a -> a.id().equals("test:legacy_route")).findFirst().orElseThrow();
        assertTrue(legacy.rechargeResource().isEmpty()); assertEquals(Optional.of("test:legacy_energy"), legacy.energyResource());
    }
}
