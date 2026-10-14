package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.resource.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorldCostRetentionTest {
    private enum Request implements WorldCommand { INSTANCE }
    private static final ResourceState ACCOUNT = new ResourceState(new ResourceState.Key("player", "test:energy"), 1, 1, 0);
    private static final Resources.SpendResult PAID = Resources.spend(ACCOUNT, "payment", .4);
    private static RuleEngine<Integer> engine(List<Instruction<Integer>> instructions) {
        return new RuleEngine<>("test", List.of(new EventRule<>("rule", "test:event", (_, _) -> true, instructions)), 50);
    }
    private static Transition<Integer> start(RuleEngine<Integer> engine) {
        return engine.transition(engine.initial(0), new Start(0, new Signal("test:event", Empty.INSTANCE)));
    }
    @Test void actualWorldCostIsVisibleToCompletionAndSurvivesAnEmptyResultOrEnvelope() {
        for (boolean envelope : List.of(false, true)) {
            var action = new Action<Integer>() {
                public Outcome<Integer> step(Integer state, Context context) { return new Await<>(Request.INSTANCE); }
                public Local<Integer> complete(Integer state, Context context, ActionResult actual) {
                    assertEquals(PAID, actual); assertEquals(PAID, context.retainedResults().get("payment"));
                    return new Local<>(state, Empty.INSTANCE, List.of());
                }
            };
            var engine = engine(List.of(new Instruction<>(action, "ignored"), new Instruction<>((state, context) -> {
                assertEquals(PAID, context.retainedResults().get("payment")); assertEquals(Empty.INSTANCE, context.bindings().get("ignored"));
                return new Local<>(1, Empty.INSTANCE, List.of());
            }, "")));
            var pending = start(engine); ActionResult actual = envelope ? new WorldReceipt(PAID, List.of()) : PAID;
            var finished = engine.transition(pending.state(), new Completed(pending.actions().getFirst().id(), actual));
            assertTrue(finished.state().idle()); assertTrue(finished.state().failure().isEmpty()); assertEquals(1, finished.state().domain());
        }
    }
    @Test void returningAnOlderCostCannotReopenARefundOrUnsealARetainedClaim() {
        var refunded = Resources.refund(PAID.after(), PAID.receipt(), .5);
        var sealed = new RetainedCosts.Handle("handle", new Resources.CostReceipt("payment", ACCOUNT.key(), .4, .4), 0, 10);
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer state, Context context) { return new Await<>(Request.INSTANCE); }
            public Local<Integer> complete(Integer state, Context context, ActionResult actual) { return new Local<>(state, PAID, List.of()); }
        };
        for (Resources.CostResult actual : List.of(refunded, sealed)) {
            var engine = engine(List.of(new Instruction<>((state, _) -> new Local<>(state, PAID, List.of()), "paid"),
                    new Instruction<>(action, "stale"), new Instruction<>((state, context) -> {
                        assertEquals(actual.receipt(), Resources.latestClaim(PAID.receipt(), context.retainedResults().values()));
                        assertEquals(PAID, context.bindings().get("stale")); return new Local<>(1, Empty.INSTANCE, List.of());
                    }, "")));
            var pending = start(engine); var finished = engine.transition(pending.state(), new Completed(pending.actions().getFirst().id(), actual));
            assertTrue(finished.state().failure().isEmpty()); assertEquals(1, finished.state().domain());
        }
    }
    @Test void conflictingWorldPaymentFailsBeforeCompletionButPreservesTheActualReceipt() {
        var called = new AtomicBoolean(); var conflict = Resources.spend(ACCOUNT, "payment", .2);
        var action = new Action<Integer>() {
            public Outcome<Integer> step(Integer state, Context context) { return new Await<>(Request.INSTANCE); }
            public Local<Integer> complete(Integer state, Context context, ActionResult actual) { called.set(true); return new Local<>(99, actual, List.of()); }
        };
        var engine = engine(List.of(new Instruction<>((_, _) -> new Local<>(1, PAID, List.of()), ""), new Instruction<>(action, "")));
        var pending = start(engine); var op = pending.actions().getFirst().id(); var failed = engine.transition(pending.state(), new Completed(op, conflict));
        assertFalse(called.get()); assertTrue(failed.state().failure().isPresent()); assertEquals(1, failed.state().domain());
        assertEquals(conflict, failed.state().receipts().get(op));
    }
    @Test void conflictingLocalPaymentDoesNotPublishItsProposedDomainChange() {
        var conflict = Resources.spend(ACCOUNT, "payment", .2);
        var engine = engine(List.of(new Instruction<>((_, _) -> new Local<>(1, PAID, List.of()), ""),
                new Instruction<>((_, _) -> new Local<>(99, conflict, List.of()), "")));
        var failed = start(engine); assertTrue(failed.state().failure().isPresent()); assertEquals(1, failed.state().domain());
    }
}
