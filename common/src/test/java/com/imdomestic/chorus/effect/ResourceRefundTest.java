package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceRefundTest {
    private static final ResourceState.Key ENERGY = new ResourceState.Key("player", "test:energy");
    private static final EffectSource SOURCE = source("test:refund");
    private static EffectState initial(double energy) {
        return EffectState.empty().withSource(SOURCE).withResource(new ResourceState(ENERGY, energy, 2, 0));
    }
    private static EffectEvent cast(double cost, double drain, boolean topUp, boolean early) {
        return new EffectEvent("player", "target", SOURCE.origin(), Set.of(),
                Map.of("cost", new Measure(cost, Unit.CHARGE), "drain", new Measure(drain, Unit.CHARGE)),
                Map.of("top_up", topUp, "early_refund", early), Map.of());
    }
    private static double energy(TimelineEngine.State<EffectState> state) { return state.engine().domain().resources().get(ENERGY).value(); }
    private static HealingReceipt receipt(TimelineEngine.Transition<EffectState> waiting) {
        var command = (HealingCommand) waiting.actions().getFirst().command();
        return new HealingReceipt(waiting.actions().getFirst().id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
    }
    private static Resources.RefundResult refund(TimelineEngine.Transition<EffectState> waiting) {
        return waiting.state().engine().frames().getFirst().bindings().values().stream().filter(Resources.RefundResult.class::isInstance)
                .map(Resources.RefundResult.class::cast).findFirst().orElseThrow();
    }
    private static JsonObject step(JsonObject data, int index) {
        return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do").get(index).getAsJsonObject();
    }
    @Test void unboundBranchClaimsSurviveWorldWaitAndDuplicateReceiptsWithoutDoubleRefund() throws Exception {
        var engine = engine(load("resource_refund")); var first = send(engine, engine.initial(initial(1)), 0, "test:cast", cast(1, 0, false, true));
        assertEquals(.7, energy(first.state())); assertEquals(.7, refund(first).receipt().refundClaimed());
        var firstReceipt = receipt(first); var second = complete(engine, first, firstReceipt);
        assertEquals(1, energy(second.state())); assertEquals(.3, ((HealingCommand) second.actions().getFirst().command()).amount());
        var duplicate = engine.transition(second.state(), new RuleEngine.Completed(first.actions().getFirst().id(), firstReceipt));
        assertEquals(second.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
        var done = complete(engine, second, receipt(second)); assertEquals(1, energy(done.state())); assertTrue(done.state().idle());
        assertTrue(done.state().engine().frames().isEmpty(), "Completed frames must not retain cost accounting");
        assertEquals(.7, energy(first.state()), "Earlier immutable snapshot was mutated");
        var again = send(engine, done.state(), 0, "test:cast", cast(1, 0, false, true));
        assertEquals(.7, energy(again.state())); assertNotEquals(refund(first).receipt().operation(), refund(again).receipt().operation());
    }
    @Test void overflowConsumesClaimBudgetAndLaterSpendingCannotRecoverTheLostRefund() throws Exception {
        var engine = engine(load("resource_refund")); var first = send(engine, engine.initial(initial(2)), 0, "test:cast", cast(1, 2, true, true));
        var early = refund(first); assertEquals(2, energy(first.state())); assertEquals(.7, early.grant().overflow());
        assertEquals(0, early.grant().credited()); assertEquals(.7, early.receipt().refundClaimed());
        var fact = ResourceFacts.refunded(early, SOURCE.origin());
        assertEquals("refund", fact.references().get("reason")); assertEquals(early.receipt().operation(), fact.references().get("payment"));
        assertEquals(.3, fact.numbers().get("remaining").value()); assertFalse(fact.flags().get("changed"));
        var second = complete(engine, first, receipt(first)); assertEquals(.3, energy(second.state()));
        assertEquals(.3, ((HealingCommand) second.actions().getFirst().command()).amount());
        var done = complete(engine, second, receipt(second)); assertEquals(.3, energy(done.state()));
    }
    @Test void skippedBranchesDoNotClaimAndARefundResultCanReferenceTheSamePayment() throws Exception {
        var data = json("resource_refund"); step(data, 7).addProperty("cost", "refund"); var engine = engine(compile(data));
        var first = send(engine, engine.initial(initial(1)), 0, "test:cast", cast(1, 0, false, false));
        assertEquals(0, energy(first.state()));
        var second = complete(engine, first, receipt(first)); assertEquals(.7, energy(second.state()));
        var done = complete(engine, second, receipt(second)); assertEquals(1, energy(done.state()));
    }
    @Test void freeAndFailedCostsNeverGenerateEnergyEvenWithRepeatedRefundActions() throws Exception {
        for (double cost : List.of(0.0, 1.0)) {
            var engine = engine(load("resource_refund")); var first = send(engine, engine.initial(initial(.2)), 0, "test:cast", cast(cost, 0, false, true));
            var payment = (Resources.SpendResult) first.state().engine().frames().getFirst().bindings().get("cost");
            assertEquals(cost == 0, payment.succeeded()); assertEquals(0, payment.receipt().paid());
            var second = complete(engine, first, receipt(first)); assertEquals(0, ((HealingCommand) second.actions().getFirst().command()).amount());
            var done = complete(engine, second, receipt(second)); assertEquals(.2, energy(done.state()));
        }
    }
    @Test void refundsReturnToThePaidAccountRatherThanTheRuleHolder() throws Exception {
        var data = json("resource_refund"); step(data, 0).getAsJsonObject("action").addProperty("target", "victim");
        var key = new ResourceState.Key("target", "test:energy"); var engine = engine(compile(data));
        var state = engine.initial(initial(.2).withResource(new ResourceState(key, 1, 2, 0)));
        var first = send(engine, state, 0, "test:cast", cast(1, 0, false, true));
        assertEquals(.7, first.state().engine().domain().resources().get(key).value()); assertEquals(.2, energy(first.state()));
        var second = complete(engine, first, receipt(first)); var done = complete(engine, second, receipt(second));
        assertEquals(1, done.state().engine().domain().resources().get(key).value()); assertEquals(.2, energy(done.state()));
    }
    @Test void fullChargeAddsWholeUnitsPreservesPartialProgressAndClipsOnlyAtCapacity() throws Exception {
        var engine = engine(load("resource_refund")); var state = engine.initial(initial(.4));
        for (int count : List.of(1, 2, 0)) {
            var event = new EffectEvent("player", "target", SOURCE.origin(), Set.of(), Map.of("charges", new Measure(count, Unit.COUNT)));
            var waiting = send(engine, state, 0, "test:full", event);
            var gain = (ResourceResult) waiting.state().engine().frames().getFirst().bindings().get("gain");
            assertEquals(count, gain.requested()); assertEquals(count, gain.scaled());
            if (count == 1) { assertEquals(1.4, gain.after().value()); assertEquals(1, gain.credited()); }
            if (count == 2) { assertEquals(2, gain.after().value()); assertEquals(.6, gain.credited()); assertEquals(1.4, gain.overflow()); }
            if (count == 0) assertEquals(0, gain.credited());
            var granted = waiting.state().engine().facts().stream().filter(fact -> fact.signal().type().equals("chorus:resource_granted")).findFirst().orElseThrow();
            assertEquals("full_charge", EffectTimers.event(granted.signal().payload()).orElseThrow().references().get("reason"));
            state = complete(engine, waiting, receipt(waiting)).state();
        }
    }
    @Test void invalidCostReferencesFractionsAndFullChargeCountsFailAtCompileTime() throws Exception {
        var unknown = json("resource_refund"); step(unknown, 5).getAsJsonObject("action").addProperty("cost", "future");
        assertThrows(IllegalStateException.class, () -> compile(unknown));
        var wrongResult = json("resource_refund"); step(wrongResult, 0).getAsJsonObject("action").addProperty("type", "chorus:grant_resource");
        var action = step(wrongResult, 0).getAsJsonObject("action"); action.add("requested", action.remove("amount")); action.remove("payment");
        assertThrows(IllegalStateException.class, () -> compile(wrongResult));
        for (double invalid : List.of(-.1, 1.1)) {
            var data = json("resource_refund"); step(data, 5).getAsJsonObject("action").getAsJsonObject("fraction").addProperty("value", invalid);
            assertThrows(IllegalStateException.class, () -> compile(data));
        }
        var unit = json("resource_refund"); step(unit, 5).getAsJsonObject("action").getAsJsonObject("fraction").addProperty("unit", "delta");
        assertThrows(IllegalStateException.class, () -> compile(unit));
        for (double invalid : List.of(-1.0, .5, 2147483648.0)) {
            var data = json("resource_refund"); var charge = step(data, 1).getAsJsonArray("then").get(0).getAsJsonObject();
            var count = new JsonObject(); count.addProperty("type", "chorus:constant"); count.addProperty("value", invalid); count.addProperty("unit", "count"); charge.add("charges", count);
            assertThrows(IllegalStateException.class, () -> compile(data));
        }
        var program = load("resource_refund"); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
    @Test void dynamicInvalidRefundFailsWithoutUndoingTheCostOrCreditingInvalidEnergy() throws Exception {
        var data = json("resource_refund"); var dynamic = new JsonObject();
        dynamic.addProperty("type", "chorus:event_number"); dynamic.addProperty("name", "fraction"); dynamic.addProperty("unit", "multiplier");
        step(data, 2).getAsJsonArray("then").get(0).getAsJsonObject().add("fraction", dynamic);
        var engine = engine(compile(data)); var base = cast(1, 0, false, true); var numbers = new HashMap<>(base.numbers()); numbers.put("fraction", new Measure(1.1, Unit.MULTIPLIER));
        var event = new EffectEvent(base.actor(), base.victim(), base.source(), base.tags(), numbers, base.flags(), base.references());
        var result = engine.transition(engine.initial(initial(1)), new RuleEngine.Start(0, new RuleEngine.Signal("test:cast", event)));
        for (int i = 0; i < 100 && result.needsPump(); i++) result = engine.transition(result.state(), RuleEngine.Pump.INSTANCE);
        assertTrue(result.state().engine().failure().isPresent()); assertEquals(0, energy(result.state())); assertTrue(result.actions().isEmpty());
    }
    @Test void conflictingCostIdentitiesAreRejectedInsteadOfSharingBudget() {
        var cost = new Resources.CostReceipt("payment", ENERGY, 1, .3);
        var other = new Resources.CostReceipt("payment", new ResourceState.Key("other", "test:energy"), 1, 0);
        assertThrows(IllegalArgumentException.class, () -> Resources.latestClaim(cost, List.of(new Resources.SpendResult(new ResourceState(other.account(), 0, 2, 0), true, other))));
        assertEquals(cost, Resources.latestClaim(cost, List.of()));
    }
}
