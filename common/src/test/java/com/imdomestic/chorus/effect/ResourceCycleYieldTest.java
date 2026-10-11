package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceCycleYieldTest {
    static final String USES = "test:yield_uses", METER = "test:yield_progress", SLOT = "chorus_d2:melee";
    static EffectSource source(String id, String bundle, String holder) {
        return new EffectSource(id, bundle, holder, new BuffInstance.Origin(holder, id, "", ""), Set.of());
    }
    record Heal(long time, HealingCommand command) {}
    static final class Harness {
        final CompiledEffects p = load("resource_cycle_yield");
        final EffectSession session;
        final List<Heal> heals = new ArrayList<>();
        int casts;
        Harness() throws Exception {
            session = new EffectSession(engine(p), EffectState.empty(), request -> {
                var c = (HealingCommand) request.command(); heals.add(new Heal(now(), c));
                return new HealingReceipt("heal/" + heals.size(), c, HealingReceipt.Outcome.APPLIED, c.amount(), c.amount(), 0);
            });
            send(SourceChange.bind(source("input", "test:yield_inputs", "player"))); select("player");
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal signal) { session.start(now(), signal); }
        void send(String event, double amount) {
            send(new RuleEngine.Signal("test:" + event, new EffectEvent("player", "player", source("input", "test:yield_inputs", "player").origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.CHARGE)))));
        }
        void until(long at) { session.observe(at, List.of()); }
        void select(String holder) { send(new AbilityChange(holder, AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(SLOT, "test:yield_ability"))).signal()); }
        void equip(String holder, String instance) {
            var after = instance.isEmpty() ? Loadout.EMPTY : new Loadout(Map.of("test:armor", new Loadout.Gear(instance, "test:linked_armor", Map.of())), Optional.empty());
            send(new EquipmentChange(holder, state().equipment().getOrDefault(holder, Loadout.EMPTY), after).signal());
        }
        ResourceState account(String holder, String resource) { return state().resources().get(new ResourceState.Key(holder, resource)); }
        double value(String resource) { return account("player", resource).value(); }
        AbilityUse.Receipt use() {
            var r = new AbilityUse.Request("player", SLOT, "cast/" + ++casts, new EffectEvent("player", "player", source("input", "test:yield_inputs", "player").origin(), Set.of(), Map.of()));
            var receipt = (AbilityUse.Receipt) p.useAbility(state(), r).result(); send(r.signal()); return receipt;
        }
        List<Heal> completions() { return heals.stream().filter(x -> x.command().tags().contains("test:completed")).toList(); }
    }
    @Test void explicitYieldClipsAtTheCurrentCeilingAndDoesNotApplyEnergyScalingAgain() {
        var p = LinkedRechargeTest.account(METER, 1, 1); var c = LinkedRechargeTest.account(USES, .5, 3);
        var single = LinkedRecharge.complete(p, c, 1);
        assertEquals(1.5, single.charges().after().value()); assertEquals(0, single.progressAfter().value());
        var fractional = LinkedRecharge.complete(p, c, .25); assertEquals(.75, fractional.charges().after().value());
        var overflow = LinkedRecharge.complete(p, c, 4);
        assertEquals(4, LinkedRechargeActions.RESULT.read("requested", overflow).value());
        assertEquals(1.5, LinkedRechargeActions.RESULT.read("overflow", overflow).value()); assertEquals(2.5, overflow.charges().credited());
        var zero = LinkedRecharge.complete(p, c, 0); assertTrue(zero.completed()); assertEquals(c, zero.charges().after()); assertEquals(0, zero.progressAfter().value());
        assertEquals(3, LinkedRecharge.complete(p, c).charges().after().value(), "omitted yield still fills the missing uses");
        assertFalse(LinkedRechargeActions.RESULT.carriesCost());
        for (double amount : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) assertThrows(IllegalArgumentException.class, () -> LinkedRecharge.complete(p, c, amount));
        var grant = single.charges();
        assertThrows(IllegalArgumentException.class, () -> new LinkedRecharge.Result(p, single.progressAfter(), new ResourceResult(c, grant.after(), 1, .5, 1, 0), true));
    }
    @Test void contextualYieldIsNotReadForAnUnfinishedCycleAndInvalidYieldCannotPartiallyCommit() throws Exception {
        var h = new LinkedRechargeTest.Harness(); var state = h.state();
        var action = new LinkedRechargeActions.Complete(LinkedRechargeTest.PROGRESS, LinkedRechargeTest.CHARGES, Evaluation.Target.SELF,
                Optional.of(new Value.EventNumber("missing", Unit.CHARGE)));
        var waiting = (RuleEngine.Local<EffectState>) action.execute(LinkedRechargeTest.evaluation(h, state, "player"));
        assertSame(state, waiting.state()); assertTrue(waiting.emitted().isEmpty());
        var key = new ResourceState.Key("player", LinkedRechargeTest.PROGRESS);
        var ready = state.withResource(new ResourceState(key, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> action.execute(LinkedRechargeTest.evaluation(h, ready, "player")));
        var negative = new LinkedRechargeActions.Complete(LinkedRechargeTest.PROGRESS, LinkedRechargeTest.CHARGES, Evaluation.Target.SELF, Optional.of(new Value.Constant(-1, Unit.CHARGE)));
        assertThrows(IllegalArgumentException.class, () -> negative.execute(LinkedRechargeTest.evaluation(h, ready, "player")));
        assertEquals(1, ready.resources().get(key).value()); assertEquals(2, ready.resources().get(new ResourceState.Key("player", LinkedRechargeTest.CHARGES)).value());
    }
    @Test void normalModeRestoresOneUsePerWholeCycleAndPartialProgressCannotPay() throws Exception {
        var h = new Harness(); h.use(); h.use(); h.until(999_999);
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use().outcome()); assertEquals(0, h.value(USES));
        h.until(1_000_000); assertEquals(1, h.value(USES)); assertEquals(0, h.value(METER));
        h.until(1_999_999); assertEquals(1, h.value(USES)); h.until(2_000_000);
        assertEquals(2, h.value(USES)); assertEquals(0, h.value(METER));
        assertEquals(List.of(1_000_000L, 2_000_000L), h.completions().stream().map(Heal::time).toList());
        h.until(10_000_000); assertEquals(0, h.value(METER)); assertEquals(2, h.completions().size());
    }
    @Test void equippingAndReplacingMidCyclePreserveAccountsAndRestoreTheCurrentMissingUses() throws Exception {
        var h = new Harness(); h.use(); h.use(); h.until(400_000);
        var before = h.state().resources(); h.equip("player", "first"); assertEquals(before, h.state().resources());
        h.equip("player", "replacement"); assertEquals(before, h.state().resources());
        h.until(999_999); assertEquals(0, h.value(USES)); h.until(1_000_000);
        assertEquals(2, h.value(USES)); assertEquals(0, h.value(METER)); assertEquals(2, h.completions().getFirst().command().amount());
        assertEquals(2, h.state().resources().size(), "mode changes must not create an idle secondary ability account");
    }
    @Test void removingEquipmentBeforeCompletionRestoresOneAndContinuesTheNextCycle() throws Exception {
        var h = new Harness(); h.equip("player", "first"); h.use(); h.use(); h.until(500_000);
        h.equip("player", ""); assertEquals(.5, h.value(METER)); assertEquals(0, h.value(USES));
        h.until(1_000_000); assertEquals(1, h.value(USES)); h.until(1_500_000); assertEquals(.5, h.value(METER));
        h.until(2_000_000); assertEquals(2, h.value(USES)); assertEquals(2, h.completions().size());
    }
    @Test void buffPriorityIsQueriedAtCompletionAndExpirationReturnsToTheEquippedPolicy() throws Exception {
        var h = new Harness(); h.equip("player", "first"); h.use(); h.use(); h.until(500_000); h.send("single", 0);
        h.until(1_000_000); assertEquals(1, h.value(USES)); assertEquals(1, h.completions().getFirst().command().amount());
        h.until(1_500_000); assertTrue(h.state().buffs().instances().isEmpty()); assertEquals(.5, h.value(METER));
        h.send("resize", 3); h.until(2_000_000); assertEquals(3, h.value(USES)); assertEquals(2, h.completions().getLast().command().amount());
    }
    @Test void buffExpiringExactlyAtCompletionDoesNotContributeItsOldYield() throws Exception {
        var h = new Harness(); h.equip("player", "first"); h.use(); h.use(); h.send("single", 0);
        h.until(1_000_000); assertTrue(h.state().buffs().instances().isEmpty()); assertEquals(2, h.value(USES));
        assertEquals(2, h.completions().getFirst().command().amount());
    }
    @Test void duplicateCycleSourcesAndOtherHoldersCannotMultiplyTheYield() throws Exception {
        var h = new Harness(); h.select("other"); h.equip("other", "other-armor"); h.use(); h.use(); h.send("resize", 3);
        h.send(SourceChange.bind(source("duplicate", "test:yield_cycle", "player")));
        h.until(1_000_000); assertEquals(1, h.value(USES)); assertEquals(1, h.completions().size());
        assertEquals(2, h.account("other", USES).value());
        h.equip("player", "first"); h.until(2_000_000); assertEquals(3, h.value(USES)); assertEquals(2, h.completions().size());
    }
    @Test void incomingEnergyScalingAffectsProgressWhileYieldFollowsTheCurrentEquipment() throws Exception {
        var h = new Harness(); h.use(); h.use(); h.send("base", 1); assertEquals(.5, h.value(METER));
        h.send("grant", .5); assertEquals(1, h.value(USES)); assertEquals(0, h.value(METER));
        h.use(); h.send("base", 1); h.equip("player", "first"); h.send("grant", .5);
        assertEquals(2, h.value(USES)); assertEquals(0, h.value(METER));
        assertEquals(List.of(1.0, 2.0), h.completions().stream().map(x -> x.command().amount()).toList());
    }
    @Test void codecRoundTripsExplicitYieldAndRejectsInvalidUnitsAndNegativeConstants() throws Exception {
        var p = load("resource_cycle_yield");
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        for (var value : List.of(new Value.Constant(1, Unit.DAMAGE), new Value.Constant(-1, Unit.CHARGE))) {
            var input = json("resource_cycle_yield");
            var action = input.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonObject("action");
            action.add("amount", EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE, value).getOrThrow());
            assertThrows(RuntimeException.class, () -> compile(input));
        }
    }
}
