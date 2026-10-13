package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AfterReactionsTest {
    static Signal signal(String type) { return new Signal(type, Empty.INSTANCE); }
    static EventRule<List<Event>> rule(String type, Signal... children) {
        return new EventRule<>(type, type, (_, _) -> true, List.of(new Instruction<>((state, context) -> {
            var events = new ArrayList<>(state); events.add(context.event());
            return new Local<>(List.copyOf(events), Empty.INSTANCE, List.of(children));
        }, "")));
    }
    static Transition<List<Event>> settle(RuleEngine<List<Event>> engine, Transition<List<Event>> result) {
        for (int i = 0; i < 1000 && result.needsPump(); i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertTrue(result.state().idle()); assertTrue(result.state().failure().isEmpty()); return result;
    }
    @Test void ordinaryDescendantsPrecedeDeferredFifoAndKeepOriginalCausalityAtEveryWorkBudget() {
        for (int budget : List.of(1, 2, 256)) {
            var engine = new RuleEngine<List<Event>>("test", List.of(
                    rule("root", afterReactions(signal("tail1")), signal("child"), afterReactions(signal("tail2"))),
                    rule("child", signal("grandchild")), rule("grandchild"),
                    rule("tail1", signal("tail1-child")), rule("tail1-child"), rule("tail2")), budget);
            var initial = engine.initial(List.of());
            var result = settle(engine, engine.transition(initial, new Start(123, signal("root"))));
            var events = result.state().domain();
            assertEquals(List.of("root", "child", "grandchild", "tail1", "tail1-child", "tail2"), events.stream().map(e -> e.signal().type()).toList());
            assertEquals(List.of(1L, 3L, 5L, 2L, 6L, 4L), events.stream().map(Event::id).toList());
            assertEquals(Optional.of(1L), events.get(3).parent()); assertEquals(Optional.of(2L), events.get(4).parent());
            assertTrue(events.stream().allMatch(e -> e.root() == 1 && e.timeMicros() == 123));
            assertTrue(initial.domain().isEmpty()); assertEquals(7, result.state().nextEvent());
        }
    }
    @Test void observedWorldFactsAndTheirDescendantsSettleBeforeAnAlreadyQueuedTail() {
        var engine = new RuleEngine<List<Event>>("test", List.of(
                new EventRule<>("root", "root", (_, _) -> true, List.of(
                        new Instruction<>((state, _) -> new Local<>(state, Empty.INSTANCE, List.of(afterReactions(signal("tail")))), ""),
                        new Instruction<>((_, _) -> new Await<>(new TestCommand()), ""))),
                rule("observed", signal("child")), rule("child"), rule("tail")), 256);
        var pending = engine.transition(engine.initial(List.of()), new Start(22, signal("root")));
        assertEquals(1, pending.actions().size());
        var result = settle(engine, engine.transition(pending.state(), new Completed(pending.actions().getFirst().id(), new WorldReceipt(Empty.INSTANCE, List.of(signal("observed"))))));
        assertEquals(List.of("observed", "child", "tail"), result.state().domain().stream().map(e -> e.signal().type()).toList());
    }
    record TestCommand() implements WorldCommand {}
    @Test void deferredRootDispatchesItsActualSignalAndNoExtraGeneration() {
        var engine = new RuleEngine<List<Event>>("test", List.of(rule("tail")), 1);
        var result = settle(engine, engine.transition(engine.initial(List.of()), new Start(7, afterReactions(signal("tail")))));
        assertEquals(new Event(1, 1, Optional.empty(), 7, signal("tail")), result.state().domain().getFirst());
        assertEquals(2, result.state().nextEvent());
    }
    @Test void ordinaryCyclesKeepRunningAcrossPumpsWithoutAdvancingTimeOrReleasingTheTail() {
        var engine = new RuleEngine<List<Event>>("test", List.of(rule("loop", signal("loop")), rule("tail")), 1);
        var result = engine.transition(engine.initial(List.of()), new Start(50, signal("loop"), List.of(afterReactions(signal("tail")))));
        for (int i = 0; i < 200; i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertTrue(result.needsPump()); assertTrue(result.state().failure().isEmpty());
        assertTrue(result.state().domain().size() > 40);
        assertTrue(result.state().domain().stream().allMatch(e -> e.signal().type().equals("loop") && e.root() == 1 && e.timeMicros() == 50));
        assertEquals(1, result.state().facts().stream().filter(e -> e.signal().payload() instanceof AfterReactions).count());
    }
    @Test void failedReactionNeverDispatchesDeferredWork() {
        var engine = new RuleEngine<List<Event>>("test", List.of(
                rule("root", afterReactions(signal("tail")), signal("broken")),
                new EventRule<>("broken", "broken", (_, _) -> true, List.of(new Instruction<>((_, _) -> { throw new IllegalStateException("bad reaction"); }, ""))),
                rule("tail")), 256);
        var result = engine.transition(engine.initial(List.of()), new Start(0, signal("root")));
        assertTrue(result.state().failure().isPresent()); assertEquals(1, result.state().domain().size()); assertTrue(result.state().facts().isEmpty());
    }
    @Test void invalidOrNestedInternalEnvelopesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> afterReactions(afterReactions(signal("tail"))));
        assertThrows(IllegalArgumentException.class, () -> new Signal("wrong", new AfterReactions(signal("tail"))));
        assertThrows(IllegalArgumentException.class, () -> signal("chorus:internal/after_reactions"));
    }
}
