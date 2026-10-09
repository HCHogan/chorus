package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IterationTest {
    record Item(String id) implements ActionResult, WorldCommand {}
    private static Transition<List<String>> pump(RuleEngine<List<String>> engine, Transition<List<String>> t) {
        for (int i = 0; i < 20000 && t.needsPump(); i++) t = engine.transition(t.state(), Pump.INSTANCE);
        assertFalse(t.needsPump()); assertTrue(t.state().failure().isEmpty(), t.state().failure().toString()); return t;
    }
    private static Local<List<String>> append(List<String> state, String value, List<Signal> facts) {
        var values = new ArrayList<>(state); values.add(value); return new Local<>(List.copyOf(values), Empty.INSTANCE, facts);
    }
    @Test void nestedLoopsResumeWithUniqueOperationsAndDerivedFactsStayBreadthFirst() {
        var reads = new AtomicInteger(); var outer = new ArrayList<ActionResult>(List.of(new Item("a"), new Item("b")));
        List<Instruction<List<String>>> actions = List.of(
                new Instruction<>(new ForEach<>((_, _) -> { reads.incrementAndGet(); return outer; }, 3), "outer"),
                new Instruction<>(new ForEach<>((_, _) -> List.of(new Item("1"), new Item("2")), 1), "inner"),
                new Instruction<>(new Action<>() {
                    public Outcome<List<String>> step(List<String> s, Context c) { return new Await<>(new Item(c.result("outer", Item.class).id() + c.result("inner", Item.class).id())); }
                    public Local<List<String>> complete(List<String> s, Context c, ActionResult r) { return append(s, ((Item) r).id(), List.of(new Signal("child", Empty.INSTANCE))); }
                }, "receipt"),
                new Instruction<>(new EndEach<>(1), ""), new Instruction<>(new EndEach<>(3), ""),
                new Instruction<>((s, c) -> { assertTrue(c.bindings().isEmpty()); return append(s, "end", List.of()); }, ""));
        var root = new EventRule<>("root", "root", (_, _) -> true, actions);
        var child = new EventRule<List<String>>("child", "child", (_, _) -> true, List.of(new Instruction<>((s, _) -> append(s, "child", List.of()), "")));
        var engine = new RuleEngine<>("1", List.of(root, child), 1);
        var waiting = pump(engine, engine.transition(engine.initial(List.of()), new Start(17, new Signal("root", Empty.INSTANCE))));
        var firstSnapshot = waiting.state(); outer.clear(); // The iteration copied the selection at entry.
        var firstId = waiting.actions().getFirst().id(); var firstResult = new Item("a1");
        var operations = new ArrayList<OperationId>();
        for (String expected : List.of("a1", "a2", "b1", "b2")) {
            var request = waiting.actions().getFirst(); assertEquals(new Item(expected), request.command());
            assertEquals(operations.size(), request.id().invocation()); operations.add(request.id());
            assertEquals(17, waiting.state().timeMicros());
            if (operations.size() > 1) {
                var duplicate = engine.transition(waiting.state(), new Completed(firstId, firstResult));
                assertEquals(waiting.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
            }
            waiting = pump(engine, engine.transition(waiting.state(), new Completed(request.id(), new Item(expected))));
        }
        assertTrue(waiting.state().idle()); assertEquals(1, reads.get());
        assertEquals(List.of("a1", "a2", "b1", "b2", "end", "child", "child", "child", "child"), waiting.state().domain());
        assertEquals(2, firstSnapshot.frames().getFirst().iterations().size()); assertEquals(List.of(), firstSnapshot.domain());
        assertEquals(4, operations.stream().distinct().count());
    }
    @Test void emptySelectionsSkipBodiesAndLargeLocalIterationsYieldWithoutTruncating() {
        var items = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> new Item("" + i)).toList();
        List<Instruction<List<String>>> actions = List.of(
                new Instruction<>(new ForEach<>((_, _) -> List.of(), 1), "empty"),
                new Instruction<>((s, c) -> { fail("Empty loop body ran"); return null; }, ""), new Instruction<>(new EndEach<>(1), ""),
                new Instruction<>(new ForEach<>((_, _) -> items, 1), "item"),
                new Instruction<>((s, c) -> append(s, c.result("item", Item.class).id(), List.of()), ""), new Instruction<>(new EndEach<>(1), ""));
        var engine = new RuleEngine<>("1", List.of(new EventRule<>("r", "r", (_, _) -> true, actions)), 1);
        var done = pump(engine, engine.transition(engine.initial(List.of()), new Start(0, new Signal("r", Empty.INSTANCE))));
        assertEquals(1000, done.state().domain().size()); assertEquals("999", done.state().domain().getLast());
    }
    @Test void invalidLoopPairsAndJumpsAcrossScopesAreRejectedBeforeExecution() {
        Instruction<Integer> noop = new Instruction<>((s, _) -> new Local<>(s, Empty.INSTANCE, List.of()), "");
        assertThrows(IllegalArgumentException.class, () -> new EventRule<>("r", "r", (_, _) -> true,
                List.of(new Instruction<Integer>(new ForEach<>((_, _) -> List.of(), 1), "x"), noop)));
        assertThrows(IllegalArgumentException.class, () -> new EventRule<>("r", "r", (_, _) -> true,
                List.of(new Instruction<Integer>(new EndEach<>(0), ""))));
        assertThrows(IllegalArgumentException.class, () -> new EventRule<>("r", "r", (_, _) -> true,
                List.of(new Instruction<Integer>(new ForEach<>((_, _) -> List.of(), 1), "x"),
                        new Instruction<>(noop.action(), "", (_, _) -> false, 2), new Instruction<>(new EndEach<>(1), ""), noop)));
    }
}
