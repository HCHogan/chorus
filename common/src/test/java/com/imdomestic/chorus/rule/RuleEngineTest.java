package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
    private record Amount(double value) implements ActionResult {}
    private record Damage(double amount) implements WorldCommand {}
    private record Heal(double amount) implements WorldCommand {}

    private static Signal signal(String type) { return new Signal(type, Empty.INSTANCE); }
    private static Instruction<Integer> action(Action<Integer> action) { return new Instruction<>(action, ""); }
    private static EventRule<Integer> rule(String id, String event, List<Instruction<Integer>> actions) {
        return new EventRule<>(id, event, (_, _) -> true, actions);
    }
    private static Transition<Integer> pump(RuleEngine<Integer> engine, Transition<Integer> transition) {
        for (int i = 0; i < 10000 && transition.needsPump(); i++) transition = engine.transition(transition.state(), Pump.INSTANCE);
        assertFalse(transition.needsPump(), "Test scenario did not settle");
        return transition;
    }

    @Test void worldResultControlsNextActionAndDuplicateReceiptDoesNotReissueIt() {
        var engine = new RuleEngine<Integer>("1", List.of(rule("leech", "hit", List.of(
                new Instruction<>((_, _) -> new Await<>(new Damage(10)), "damage"),
                action((_, context) -> new Await<>(new Heal(context.result("damage", Amount.class).value())))
        ))), 20);
        var initial = engine.initial(0);
        var damage = engine.transition(initial, new Start(0, signal("hit")));
        assertEquals(new Damage(10), damage.actions().getFirst().command());
        var op = damage.actions().getFirst().id();
        var heal = engine.transition(damage.state(), new Completed(op, new Amount(4)));
        assertEquals(new Heal(4), heal.actions().getFirst().command());
        var duplicate = engine.transition(heal.state(), new Completed(op, new Amount(4)));
        assertEquals(heal.state(), duplicate.state());
        assertTrue(duplicate.actions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> engine.transition(heal.state(), new Completed(op, new Amount(5))));
        var finished = engine.transition(heal.state(), new Completed(heal.actions().getFirst().id(), new Amount(4)));
        assertTrue(finished.state().idle());
        assertTrue(initial.idle());
        assertTrue(initial.receipts().isEmpty());
    }

    @Test void derivedEventMayTriggerSameRuleWithNewOperationInSameRoot() {
        Action<Integer> damageAndRepeat = new Action<>() {
            @Override public Outcome<Integer> step(Integer state, Context context) { return new Await<>(new Damage(1)); }
            @Override public Local<Integer> complete(Integer state, Context context, ActionResult receipt) {
                return new Local<>(state + 1, receipt, state < 39 ? List.of(signal("hit")) : List.of());
            }
        };
        var engine = new RuleEngine<Integer>("1", List.of(rule("loop", "hit", List.of(action(damageAndRepeat)))), 2);
        var transition = pump(engine, engine.transition(engine.initial(0), new Start(0, signal("hit"))));
        var operations = new ArrayList<OperationId>();
        long root = transition.state().frames().getFirst().event().root();
        while (!transition.state().idle()) {
            assertTrue(transition.state().failure().isEmpty());
            assertEquals(root, transition.state().frames().getFirst().event().root());
            var request = transition.actions().getFirst();
            operations.add(request.id());
            transition = pump(engine, engine.transition(transition.state(), new Completed(request.id(), new Amount(1))));
        }
        assertEquals(40, transition.state().domain());
        assertEquals(40, operations.stream().distinct().count());
        assertEquals(0, transition.state().timeMicros()); // Continuation budget never advances time or truncates the root.
    }

    @Test void localActionOrderAndBreadthFirstFactsArePreserved() {
        var engine = new RuleEngine<Integer>("1", List.of(
                rule("first", "root", List.of(
                        action((s, _) -> new Local<>(s + 1, Empty.INSTANCE, List.of(signal("child")))),
                        action((s, _) -> new Local<>(s + 10, Empty.INSTANCE, List.of())))),
                new EventRule<>("second", "root", (s, _) -> s == 11,
                        List.of(action((s, _) -> new Local<>(s + 100, Empty.INSTANCE, List.of())))),
                rule("child", "child", List.of(action((s, _) -> new Local<>(s * 2, Empty.INSTANCE, List.of()))))
        ), 1);
        var result = pump(engine, engine.transition(engine.initial(0), new Start(123, signal("root"))));
        assertEquals(222, result.state().domain());
        assertEquals(123, result.state().timeMicros());
        assertTrue(result.state().failure().isEmpty());
    }

    @Test void commandsCannotInterleavePendingWorldOperationAndUnknownReceiptsFail() {
        var engine = new RuleEngine<Integer>("1", List.of(rule("damage", "hit", List.of(action((_, _) -> new Await<>(new Damage(1)))))), 20);
        var pending = engine.transition(engine.initial(0), new Start(10, signal("hit")));
        assertThrows(IllegalStateException.class, () -> engine.transition(pending.state(), new Start(11, signal("hit"))));
        assertThrows(IllegalStateException.class, () -> engine.transition(pending.state(), Pump.INSTANCE));
        assertThrows(IllegalArgumentException.class, () -> engine.transition(pending.state(), new Completed(new OperationId(999, 0, 0), Empty.INSTANCE)));
        assertThrows(IllegalArgumentException.class, () -> new RuleEngine<Integer>("other", List.of(), 1).transition(pending.state(), Pump.INSTANCE));
    }

    @Test void failureAfterWorldCommitRetainsReceiptAndCommittedLocalChanges() {
        var engine = new RuleEngine<Integer>("1", List.of(rule("failure", "hit", List.of(
                action((s, _) -> new Local<>(s - 1, Empty.INSTANCE, List.of())),
                new Instruction<>((_, _) -> new Await<>(new Damage(10)), "damage"),
                action((s, context) -> { throw new IllegalArgumentException("bad content after actual damage"); })
        ))), 20);
        var pending = engine.transition(engine.initial(5), new Start(0, signal("hit")));
        var operation = pending.actions().getFirst().id();
        var failed = engine.transition(pending.state(), new Completed(operation, new Amount(4)));
        assertEquals(4, failed.state().domain());
        assertEquals(new Amount(4), failed.state().receipts().get(operation));
        assertTrue(failed.state().failure().isPresent());
        assertTrue(failed.state().idle());
        assertTrue(failed.actions().isEmpty());
        assertTrue(engine.transition(failed.state(), new Completed(operation, new Amount(4))).actions().isEmpty());
    }

    @Test void failureInsideCompletionAlsoRetainsTheActualWorldReceipt() {
        Action<Integer> broken = new Action<>() {
            @Override public Outcome<Integer> step(Integer state, Context context) { return new Await<>(new Damage(1)); }
            @Override public Local<Integer> complete(Integer state, Context context, ActionResult receipt) {
                throw new IllegalArgumentException("incompatible result type");
            }
        };
        var engine = new RuleEngine<Integer>("1", List.of(rule("broken", "hit", List.of(action(broken)))), 10);
        var pending = engine.transition(engine.initial(0), new Start(0, signal("hit")));
        var op = pending.actions().getFirst().id();
        var failed = engine.transition(pending.state(), new Completed(op, new Amount(1)));
        assertTrue(failed.state().failure().isPresent());
        assertEquals(new Amount(1), failed.state().receipts().get(op));
    }

    @Test void dynamicBindingsDeduplicateOnlyWithinOneEventAndValidateBeforeRunning() {
        var definition = rule("template", "hit", List.of(action((state, _) -> new Local<>(state + 1, Empty.INSTANCE, List.of()))));
        var binding = new RuleBinding("instance", "template", Empty.INSTANCE);
        var engine = new RuleEngine<Integer>("1", List.of(definition), 20, (_, _) -> List.of(binding, binding));
        var first = engine.transition(engine.initial(0), new Start(0, signal("hit")));
        assertEquals(1, first.state().domain());
        var second = engine.transition(first.state(), new Start(0, signal("hit")));
        assertEquals(2, second.state().domain());
        var invalid = new RuleEngine<Integer>("1", List.of(definition), 20,
                (_, _) -> List.of(binding, new RuleBinding("broken", "absent", Empty.INSTANCE)));
        var result = invalid.transition(invalid.initial(0), new Start(0, signal("hit")));
        assertEquals(0, result.state().domain());
        assertTrue(result.state().failure().isPresent());
        assertEquals(1, result.state().failure().orElseThrow().root());
    }
}
