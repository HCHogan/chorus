package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class LinkedRechargeTest {
    static final String CHARGES = "test:charges", PROGRESS = "test:progress";
    static EffectSource source(String instance, String holder) {
        return new EffectSource(instance, "test:linked", holder, new BuffInstance.Origin(holder, instance, "", ""), Set.of());
    }
    record Heal(long time, HealingCommand command) {}
    static final class Harness {
        final CompiledEffects program;
        final EffectSession session;
        final List<Heal> heals = new ArrayList<>();
        boolean fail;
        Harness() throws Exception {
            program = load("linked_recharge");
            session = new EffectSession(engine(program), EffectState.empty(), request -> {
                var command = (HealingCommand) request.command(); heals.add(new Heal(now(), command));
                if (fail) throw new IllegalStateException("Unknown linked recharge observation");
                return new HealingReceipt("heal/" + heals.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
            });
            send(SourceChange.bind(source("input", "player")));
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void until(long time) { session.observe(time, List.of()); }
        void send(RuleEngine.Signal signal) { session.start(now(), signal); }
        void send(String kind, double amount) {
            send(new RuleEngine.Signal("test:" + kind, new EffectEvent("player", "player", source("input", "player").origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.CHARGE)))));
        }
        ResourceState account(String holder, String resource) { return state().resources().get(new ResourceState.Key(holder, resource)); }
        double charges() { return account("player", CHARGES).value(); }
        double progress() { return account("player", PROGRESS).value(); }
        List<Heal> completions() { return heals.stream().filter(h -> h.command().tags().contains("test:completed")).toList(); }
    }

    static ResourceState account(String resource, double value, double capacity) {
        return new ResourceState(new ResourceState.Key("player", resource), value, capacity, 7);
    }
    static Evaluation evaluation(Harness h, EffectState state, String target) {
        var input = source("input", "player");
        return new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state.buffs().timeMicros(),
                new RuleEngine.Signal("test:query", new EffectEvent("player", target, input.origin(), Set.of(), Map.of()))), "query", input, Map.of()),
                Map.of(), Map.of(), h.program.program().resources().stream().collect(java.util.stream.Collectors.toMap(ResourceDefinition::id, x -> x)), Map.of(), Optional.of(h.program));
    }
    @Test void resourceCapacityAndMissingReadsUseLiveCeilingsAndRespectSnapshotTargets() throws Exception {
        var h = new Harness(); var key = new ResourceState.Key("player", CHARGES); var other = new ResourceState.Key("other", CHARGES);
        var state = h.state().withResource(new ResourceState(key, 1.7, 3, 0)).withResource(new ResourceState(other, .4, 4, 0));
        var e = evaluation(h, state, "other");
        var capacity = new Value.Resource(CHARGES, Evaluation.Target.SELF, Value.ResourceField.CAPACITY);
        var missing = new Value.Resource(CHARGES, Evaluation.Target.SELF, Value.ResourceField.MISSING);
        var victim = new Value.Resource(CHARGES, Evaluation.Target.VICTIM, Value.ResourceField.CAPACITY);
        assertEquals(1.7, new Value.Resource(CHARGES, Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(1.3, missing.evaluate(e).value()); assertEquals(new Value.Constant(3, Unit.CHARGE), capacity.snapshot(e));
        assertSame(victim, victim.snapshot(e));
        var later = evaluation(h, state.withResource(new ResourceState(other, .4, 5, 0)), "other");
        assertEquals(5, victim.snapshot(e).evaluate(later).value());
        assertThrows(IllegalArgumentException.class, () -> victim.evaluate(evaluation(h, state, "absent")));
    }
    @Test void completionPublishesBothWritesBeforeFactsAndDoesNotPublishARefundablePayment() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.send("spend", 0);
        var progress = h.account("player", PROGRESS);
        var state = h.state().withResource(new ResourceState(progress.key(), 1, 1, 0));
        var action = new LinkedRechargeActions.Complete(PROGRESS, CHARGES, Evaluation.Target.SELF);
        var done = (RuleEngine.Local<EffectState>) action.execute(evaluation(h, state, "player"));
        assertEquals(0, done.state().resources().get(progress.key()).value());
        assertEquals(2, done.state().resources().get(new ResourceState.Key("player", CHARGES)).value());
        assertEquals(List.of("chorus:resource_changed", "chorus:resource_granted", "chorus:resource_changed"), done.emitted().stream().map(RuleEngine.Signal::type).toList());
        var reset = (EffectEvent) done.emitted().getFirst().payload();
        assertEquals("recharge_completed", reset.references().get("reason")); assertFalse(reset.numbers().containsKey("paid"));
        var grant = (EffectEvent) done.emitted().get(1).payload();
        assertEquals("linked_recharge", grant.references().get("reason")); assertEquals(2, grant.numbers().get("credited").value());
        var repeat = (RuleEngine.Local<EffectState>) action.execute(evaluation(h, done.state(), "player"));
        assertTrue(repeat.emitted().isEmpty()); assertSame(done.state(), repeat.state());
    }
    @Test void primitiveSeparatesProgressFromUsesAndCompletesBothAccountsTogether() {
        var charges = account(CHARGES, 1, 3); var partial = account(PROGRESS, .9, 1);
        var waiting = LinkedRecharge.complete(partial, charges);
        assertFalse(waiting.completed()); assertSame(partial, waiting.progressAfter()); assertEquals(charges, waiting.charges().after());
        var done = LinkedRecharge.complete(account(PROGRESS, 1, 1), charges);
        assertTrue(done.completed()); assertEquals(0, done.progressAfter().value()); assertEquals(3, done.charges().after().value());
        assertEquals(2, done.charges().credited()); assertEquals(0, done.charges().overflow());
        var again = LinkedRecharge.complete(done.progressAfter(), done.charges().after());
        assertFalse(again.completed()); assertEquals(0, again.charges().credited());
        assertEquals(0, LinkedRecharge.complete(account(PROGRESS, 1, 1), done.charges().after()).charges().credited());
        assertFalse(LinkedRechargeActions.RESULT.carriesCost(), "cycle conversion must not manufacture refund rights");
    }
    @Test void malformedPairsAndForgedCompletionReceiptsAreRejectedBeforeMutation() {
        var p = account(PROGRESS, 1, 1); var c = account(CHARGES, 0, 2);
        assertThrows(IllegalArgumentException.class, () -> LinkedRecharge.complete(p, p));
        assertThrows(IllegalArgumentException.class, () -> LinkedRecharge.complete(account(PROGRESS, 1, 2), c));
        assertThrows(IllegalArgumentException.class, () -> LinkedRecharge.complete(p, new ResourceState(new ResourceState.Key("other", CHARGES), 0, 2, 7)));
        assertThrows(IllegalArgumentException.class, () -> LinkedRecharge.complete(p, new ResourceState(c.key(), 0, 2, 8)));
        var done = LinkedRecharge.complete(p, c);
        assertThrows(IllegalArgumentException.class, () -> new LinkedRecharge.Result(p, p, done.charges(), true));
        assertThrows(IllegalArgumentException.class, () -> new LinkedRecharge.Result(p, done.progressAfter(), done.charges(), false));
    }
    @Test void secondUseMidCycleDoesNotRestartItOrAllowPartialProgressToPayACast() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.until(1_000_000);
        assertEquals(1, h.charges()); assertEquals(.5, h.progress());
        h.send("spend", 0); h.send("spend", 0); assertEquals(2, h.heals.size());
        h.until(1_999_999); assertEquals(0, h.charges()); assertTrue(h.completions().isEmpty());
        h.until(2_000_000); assertEquals(2, h.charges()); assertEquals(0, h.progress());
        assertEquals(List.of(2_000_000L), h.completions().stream().map(Heal::time).toList());
        h.until(20_000_000); h.send("spend", 0); assertEquals(0, h.progress());
        h.until(21_000_000); assertEquals(1, h.charges()); assertEquals(.5, h.progress());
    }
    @Test void gainScalarAppliesToCycleEnergyOnceWhileFixedFullCycleRestoresAllMissingUses() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.send("spend", 0);
        h.send("base", 1); assertEquals(.5, h.progress()); assertEquals(0, h.charges());
        h.send("grant", .5); assertEquals(0, h.progress()); assertEquals(2, h.charges());
        assertEquals(2, h.completions().getFirst().command().amount());
        h.send("grant", .5); assertEquals(0, h.progress(), "full uses must not bank external cycle energy");
        h.send("spend", 0); h.send("grant", 2); assertEquals(1, h.completions().getLast().command().amount());
        assertEquals(2, h.charges()); assertEquals(0, h.progress(), "overflow must not create another cycle");
    }
    @Test void buffExpirySplitsTheSharedCycleAtTheExactBoundary() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.send("spend", 0); h.until(500_000); h.send("boost", 0);
        h.until(1_000_000); assertEquals(.75, h.progress()); assertEquals(0, h.charges());
        h.until(1_499_999); assertEquals(0, h.charges()); h.until(1_500_000);
        assertEquals(2, h.charges()); assertEquals(1_500_000, h.completions().getFirst().time());
    }
    @Test void sourceRemovalPausesAndRebindingResumesWithoutResettingOrRefillingAccounts() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.until(500_000);
        h.send(SourceChange.remove("input")); h.until(2_000_000); assertEquals(.25, h.progress()); assertEquals(1, h.charges());
        h.send(SourceChange.bind(source("input", "player"))); assertEquals(.25, h.progress());
        h.until(3_499_999); assertEquals(1, h.charges()); h.until(3_500_000);
        assertEquals(2, h.charges()); assertEquals(1, h.completions().getFirst().command().amount());
    }
    @Test void completionUsesCurrentCapacityAndDuplicateBindingsDoNotCompleteTheSameMeterTwice() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.send("spend", 0); h.until(500_000); h.send("resize", 3);
        h.send(SourceChange.bind(source("duplicate", "player"))); h.send(SourceChange.bind(source("other", "other")));
        h.until(2_000_000); assertEquals(3, h.charges()); assertEquals(0, h.progress()); assertEquals(1, h.completions().size());
        assertEquals(3, h.completions().getFirst().command().amount()); assertEquals(2, h.account("other", CHARGES).value());
        h.send("complete", 0); assertEquals(1, h.completions().size());
    }
    @Test void unknownWorldFollowupKeepsTheCompletedCycleAndRestoredUsesWithoutReplay() throws Exception {
        var h = new Harness(); h.send("spend", 0); h.send("spend", 0); h.fail = true;
        assertThrows(IllegalStateException.class, () -> h.send("grant", 1));
        assertEquals(0, h.progress()); assertEquals(2, h.charges()); assertEquals(1, h.completions().size());
        assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(1, h.completions().size());
    }
    @Test void codecRetainsLegacyReadsAndRejectsUnknownFieldsAliasingAndResizableProgress() throws Exception {
        var p = load("linked_recharge");
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        var old = com.google.gson.JsonParser.parseString("{\"type\":\"chorus:resource\",\"resource\":\"test:charges\"}");
        assertEquals(new Value.Resource(CHARGES, Evaluation.Target.SELF), EffectCodecs.VALUE.parse(JsonOps.INSTANCE, old).getOrThrow());
        old.getAsJsonObject().addProperty("field", "unknown"); assertTrue(EffectCodecs.VALUE.parse(JsonOps.INSTANCE, old).error().isPresent());
        var resizable = json("linked_recharge"); resizable.getAsJsonArray("resources").get(1).getAsJsonObject().addProperty("resizable", true);
        assertThrows(RuntimeException.class, () -> compile(resizable));
        var alias = json("linked_recharge");
        alias.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("resource", PROGRESS);
        assertThrows(RuntimeException.class, () -> compile(alias));
    }
}
