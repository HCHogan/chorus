package com.imdomestic.chorus.rule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, resumable event interpreter. S, payloads, commands and results must be immutable data.
 * World work is described, never executed here. A host must finish a boundary (including Pump
 * inputs) before starting the next external command. Pumps do not advance logical time.
 */
public final class RuleEngine<S> {
    public interface Payload {}
    public interface ActionResult {}
    /** Latest frame-local receipt per identity, retained even when lexical bindings leave scope. */
    public interface RetainedResult extends ActionResult { String retentionKey(); }
    public interface WorldCommand {}
    public enum Empty implements Payload, ActionResult { INSTANCE }

    public record Signal(String type, Payload payload) {
        public Signal { Objects.requireNonNull(type); Objects.requireNonNull(payload); }
    }
    public record Event(long id, long root, Optional<Long> parent, long timeMicros, Signal signal) {}
    public record OperationId(long frame, int pc, int invocation) {}
    public record WorldRequest(OperationId id, WorldCommand command) {}

    public sealed interface Input permits Start, Completed, Pump {}
    public record Start(long timeMicros, Signal signal, List<Signal> following, Payload committed) implements Input {
        public Start { Objects.requireNonNull(signal); following = List.copyOf(following); Objects.requireNonNull(committed); }
        public Start(long timeMicros, Signal signal, List<Signal> following) { this(timeMicros, signal, following, Empty.INSTANCE); }
        public Start(long timeMicros, Signal signal) { this(timeMicros, signal, List.of()); }
        /** One external world boundary may contain several already-committed facts. */
        public Start(long timeMicros, List<Signal> facts) { this(timeMicros, new Signal("chorus:internal/observed", Empty.INSTANCE), facts); }
        public Start(long timeMicros, List<Signal> facts, Payload committed) { this(timeMicros, new Signal("chorus:internal/observed", Empty.INSTANCE), facts, committed); }
    }
    /** Actual action result plus native facts observed during that world operation, in commit order. */
    public record WorldReceipt(ActionResult value, List<Signal> observed, Payload committed) implements ActionResult {
        public WorldReceipt {
            Objects.requireNonNull(value); observed = List.copyOf(observed); Objects.requireNonNull(committed);
            if (value instanceof WorldReceipt) throw new IllegalArgumentException("Nested world receipt envelope");
        }
        public WorldReceipt(ActionResult value, List<Signal> observed) { this(value, observed, Empty.INSTANCE); }
    }
    /** Pure, validated application of world-confirmed domain writes, before any dependent action or reaction. */
    @FunctionalInterface public interface Reconciler<S> { S apply(S state, Payload committed); }
    public static <S> S noWrites(S state, Payload committed) {
        if (committed != Empty.INSTANCE) throw new IllegalArgumentException("No reconciler for committed world writes");
        return state;
    }
    public record Completed(OperationId operation, ActionResult result) implements Input {
        public Completed { Objects.requireNonNull(operation); Objects.requireNonNull(result); }
    }
    public enum Pump implements Input { INSTANCE }

    public record Context(Event event, String ruleInstance, Payload scope, Map<String, ActionResult> bindings,
            OperationId operation, Map<String, ActionResult> retainedResults, Optional<WorldCommand> pendingCommand) {
        public Context { Objects.requireNonNull(scope); bindings = Map.copyOf(bindings); Objects.requireNonNull(operation); retainedResults = Map.copyOf(retainedResults); Objects.requireNonNull(pendingCommand); }
        public Context(Event event, String ruleInstance, Payload scope, Map<String, ActionResult> bindings,
                OperationId operation, Map<String, ActionResult> retainedResults) {
            this(event, ruleInstance, scope, bindings, operation, retainedResults, Optional.empty());
        }
        public Context(Event event, String ruleInstance, Payload scope, Map<String, ActionResult> bindings) {
            this(event, ruleInstance, scope, bindings, new OperationId(0, 0, 0), bindings);
        }
        public <R extends ActionResult> R result(String name, Class<R> type) {
            ActionResult value = bindings.get(name);
            if (value == null) throw new IllegalArgumentException("Unbound action result: " + name);
            return type.cast(value);
        }
        /** Available during completion, including after receipt writes have been reconciled into the domain. */
        public <C extends WorldCommand> C command(Class<C> type) {
            return type.cast(pendingCommand.orElseThrow(() -> new IllegalStateException("No issued command in this action context")));
        }
    }

    public sealed interface Outcome<S> permits Local, Await {}
    public record Local<S>(S state, ActionResult result, List<Signal> emitted) implements Outcome<S> {
        public Local { Objects.requireNonNull(state); Objects.requireNonNull(result); emitted = List.copyOf(emitted); }
    }
    public record Await<S>(WorldCommand command) implements Outcome<S> {
        public Await { Objects.requireNonNull(command); }
    }

    public interface Action<S> {
        Outcome<S> step(S state, Context context);
        default Local<S> complete(S state, Context context, ActionResult receipt) {
            return new Local<>(state, receipt, List.of());
        }
    }
    @FunctionalInterface public interface Condition<S> { boolean test(S state, Context context); }
    @FunctionalInterface public interface Collection<S> { List<? extends ActionResult> read(S state, Context context); }
    /** Structured control markers. Only the interpreter executes them; bodySize excludes both markers. */
    public record ForEach<S>(Collection<S> collection, int bodySize) implements Action<S> {
        public ForEach { Objects.requireNonNull(collection); if (bodySize < 0) throw new IllegalArgumentException("Negative loop body size"); }
        @Override public Outcome<S> step(S state, Context context) { throw new IllegalStateException("Loop marker requires interpreter"); }
    }
    public record EndEach<S>(int bodySize) implements Action<S> {
        @Override public Outcome<S> step(S state, Context context) { throw new IllegalStateException("Loop marker requires interpreter"); }
    }
    public record Instruction<S>(Action<S> action, String bind, Condition<S> guard, int skipWhenFalse) {
        public Instruction {
            Objects.requireNonNull(action); Objects.requireNonNull(bind); Objects.requireNonNull(guard);
            if (skipWhenFalse < 1) throw new IllegalArgumentException("Instruction jumps must move forward");
        }
        public Instruction(Action<S> action, String bind) { this(action, bind, (_, _) -> true, 1); }
    }
    public record EventRule<S>(String definition, String eventType, Condition<S> condition, List<Instruction<S>> actions) {
        public EventRule {
            Objects.requireNonNull(definition); Objects.requireNonNull(eventType); Objects.requireNonNull(condition);
            actions = List.copyOf(actions);
            var names = new java.util.HashSet<String>();
            for (var instruction : actions) if (!instruction.bind().isEmpty() && !names.add(instruction.bind())) {
                throw new IllegalArgumentException("Duplicate result binding: " + instruction.bind());
            }
            var stack = new ArrayDeque<Integer>();
            var scopes = new ArrayList<List<Integer>>();
            for (int pc = 0; pc < actions.size(); pc++) {
                scopes.add(List.copyOf(stack));
                var instruction = actions.get(pc);
                if (instruction.action() instanceof ForEach<S> loop) {
                    long end = (long) pc + loop.bodySize() + 1;
                    if (instruction.bind().isEmpty() || end >= actions.size() || !(actions.get((int) end).action() instanceof EndEach<S> close)
                            || close.bodySize() != loop.bodySize()) throw new IllegalArgumentException("Unmatched loop start");
                    stack.push(pc);
                } else if (instruction.action() instanceof EndEach<S> close) {
                    if (stack.isEmpty() || pc - stack.pop() != (long) close.bodySize() + 1 || !instruction.bind().isEmpty()) {
                        throw new IllegalArgumentException("Unmatched loop end");
                    }
                }
            }
            if (!stack.isEmpty()) throw new IllegalArgumentException("Unclosed loop");
            scopes.add(List.of());
            for (int pc = 0; pc < actions.size(); pc++) {
                var instruction = actions.get(pc);
                if (instruction.action() instanceof ForEach<?> || instruction.action() instanceof EndEach<?>) continue;
                long destination = (long) pc + instruction.skipWhenFalse();
                if (destination > actions.size() || !scopes.get(pc).equals(scopes.get((int) destination))) {
                    throw new IllegalArgumentException("Instruction jump leaves its scope");
                }
            }
        }
    }

    public record RuleBinding(String instance, String definition, Payload scope) {
        public RuleBinding { Objects.requireNonNull(instance); Objects.requireNonNull(definition); Objects.requireNonNull(scope); }
    }
    /** Returns a stable ordered list of source-bound rules for one event; it never performs world reads. */
    @FunctionalInterface public interface RuleResolver<S> { List<RuleBinding> resolve(S state, Event event); }

    /** Frame references a pinned rule definition; it does not contain a closure or Java call stack. */
    public record Iteration(int start, int end, int index, List<ActionResult> values) {
        public Iteration { values = List.copyOf(values); }
    }
    public record Frame(long id, Event event, String rule, String definition, Payload scope, int pc, boolean started,
            Map<String, ActionResult> bindings, List<Iteration> iterations, Map<Integer, Integer> invocations,
            Map<String, ActionResult> retainedResults, Optional<WorldCommand> pendingCommand) {
        public Frame { Objects.requireNonNull(scope); bindings = Map.copyOf(bindings); iterations = List.copyOf(iterations);
            invocations = Map.copyOf(invocations); retainedResults = Map.copyOf(retainedResults); Objects.requireNonNull(pendingCommand); }
        public Frame(long id, Event event, String rule, String definition, Payload scope, int pc, boolean started,
                Map<String, ActionResult> bindings, List<Iteration> iterations, Map<Integer, Integer> invocations,
                Map<String, ActionResult> retainedResults) {
            this(id, event, rule, definition, scope, pc, started, bindings, iterations, invocations, retainedResults, Optional.empty());
        }
        public Frame(long id, Event event, String rule, String definition, Payload scope, int pc, boolean started, Map<String, ActionResult> bindings) {
            this(id, event, rule, definition, scope, pc, started, bindings, List.of(), Map.of(), Map.of());
        }
        Frame move(int destination, Map<String, ActionResult> values) {
            return new Frame(id, event, rule, definition, scope, destination, true, values, iterations, invocations, retainedResults);
        }
        Frame await(WorldCommand command) {
            return new Frame(id, event, rule, definition, scope, pc, started, bindings, iterations, invocations, retainedResults, Optional.of(command));
        }
    }
    public record Failure(long root, String rule, int pc, String message, int abandonedFacts, int abandonedFrames) {}
    public record State<S>(String version, S domain, long timeMicros, long nextEvent, long nextFrame,
            List<Event> facts, List<Frame> frames, Optional<OperationId> pending,
            Map<OperationId, ActionResult> receipts, Optional<Failure> failure) {
        public State {
            Objects.requireNonNull(version); Objects.requireNonNull(domain);
            facts = List.copyOf(facts); frames = List.copyOf(frames);
            pending = Objects.requireNonNull(pending); receipts = Map.copyOf(receipts); failure = Objects.requireNonNull(failure);
        }
        public boolean idle() { return pending.isEmpty() && frames.isEmpty() && facts.isEmpty(); }
    }
    public record Transition<S>(State<S> state, List<WorldRequest> actions) {
        public Transition { actions = List.copyOf(actions); }
        public boolean needsPump() { return !state.idle() && state.pending().isEmpty() && state.failure().isEmpty(); }
    }

    private final String version;
    private final Map<String, EventRule<S>> rules;
    private final RuleResolver<S> resolver;
    private final int stepsPerTransition;
    private final Reconciler<S> reconciler;

    public RuleEngine(String version, List<EventRule<S>> definitions, int stepsPerTransition) {
        this(version, definitions, stepsPerTransition, staticResolver(definitions));
    }

    public RuleEngine(String version, List<EventRule<S>> definitions, int stepsPerTransition, RuleResolver<S> resolver) {
        this(version, definitions, stepsPerTransition, resolver, RuleEngine::noWrites);
    }
    public RuleEngine(String version, List<EventRule<S>> definitions, int stepsPerTransition, RuleResolver<S> resolver, Reconciler<S> reconciler) {
        this.version = Objects.requireNonNull(version);
        this.resolver = Objects.requireNonNull(resolver);
        this.reconciler = Objects.requireNonNull(reconciler);
        if (stepsPerTransition < 1) throw new IllegalArgumentException("A transition must allow at least one step");
        this.stepsPerTransition = stepsPerTransition;
        var rules = new LinkedHashMap<String, EventRule<S>>();
        for (var rule : definitions) {
            if (rules.putIfAbsent(rule.definition(), rule) != null) throw new IllegalArgumentException("Duplicate rule definition: " + rule.definition());
        }
        this.rules = Map.copyOf(rules);
    }

    private static <S> RuleResolver<S> staticResolver(List<EventRule<S>> definitions) {
        var index = new HashMap<String, List<RuleBinding>>();
        for (var rule : definitions) index.computeIfAbsent(rule.eventType(), _ -> new ArrayList<>())
                .add(new RuleBinding(rule.definition(), rule.definition(), Empty.INSTANCE));
        var frozen = new HashMap<String, List<RuleBinding>>();
        index.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        var lookup = Map.copyOf(frozen);
        return (_, event) -> lookup.getOrDefault(event.signal().type(), List.of());
    }

    public State<S> initial(S domain) {
        return new State<>(version, domain, 0, 1, 1, List.of(), List.of(), Optional.empty(), Map.of(), Optional.empty());
    }

    public Transition<S> transition(State<S> state, Input input) {
        Objects.requireNonNull(input, "input");
        if (!version.equals(state.version())) throw new IllegalArgumentException("State belongs to another rule version");
        var cursor = new Cursor(state);
        if (input instanceof Completed completed && state.receipts().containsKey(completed.operation())) {
            if (!state.receipts().get(completed.operation()).equals(completed.result())) {
                throw new IllegalArgumentException("Conflicting duplicate receipt: " + completed.operation());
            }
            return new Transition<>(state, List.of());
        }
        if (input instanceof Start start) {
            if (!state.idle()) throw new IllegalStateException("An external command cannot interleave an unfinished boundary");
            if (start.timeMicros() < state.timeMicros()) throw new IllegalArgumentException("Logical time cannot go backwards");
            cursor.time = start.timeMicros();
            cursor.receipts.clear(); // v0 retains receipts for one complete settlement boundary.
            cursor.failure = Optional.empty();
            long id = cursor.nextEvent++;
            cursor.facts.add(new Event(id, id, Optional.empty(), cursor.time, start.signal()));
            for (var signal : start.following()) cursor.facts.add(new Event(cursor.nextEvent++, id, Optional.of(id), cursor.time, signal));
            try { cursor.domain = Objects.requireNonNull(reconciler.apply(cursor.domain, start.committed())); }
            catch (RuntimeException error) { cursor.distributing = cursor.facts.getFirst(); cursor.fail(error); return new Transition<>(cursor.snapshot(), List.of()); }
        } else if (input instanceof Completed completed) {
            if (!state.pending().equals(Optional.of(completed.operation()))) {
                throw new IllegalArgumentException("Receipt does not match the pending operation: " + completed.operation());
            }
            // Preserve the actual world receipt even if the completion handler fails afterward.
            cursor.receipts.put(completed.operation(), completed.result());
            cursor.pending = Optional.empty();
            try {
                Frame frame = cursor.frames.getFirst();
                var instruction = rules.get(frame.definition()).actions().get(frame.pc());
                ActionResult actual = completed.result(); List<Signal> observed = List.of();
                if (actual instanceof WorldReceipt receipt) {
                    cursor.domain = Objects.requireNonNull(reconciler.apply(cursor.domain, receipt.committed()));
                    actual = receipt.value(); observed = receipt.observed();
                }
                var result = instruction.action().complete(cursor.domain, context(frame), actual);
                var signals = new ArrayList<>(observed); signals.addAll(result.emitted());
                cursor.commit(frame, instruction, new Local<>(result.state(), result.result(), signals));
            } catch (RuntimeException exception) {
                cursor.fail(exception);
                return new Transition<>(cursor.snapshot(), List.of());
            }
        } else if (input == Pump.INSTANCE) {
            if (state.pending().isPresent()) throw new IllegalStateException("Cannot pump while awaiting a world receipt");
            if (state.failure().isPresent()) return new Transition<>(state, List.of());
        }

        for (int work = 0; work < stepsPerTransition; work++) {
            try {
                if (cursor.frames.isEmpty()) {
                    if (cursor.facts.isEmpty()) break;
                    Event event = cursor.facts.removeFirst();
                    cursor.distributing = event;
                    var unique = new LinkedHashMap<String, RuleBinding>();
                    for (RuleBinding binding : resolver.resolve(cursor.domain, event)) {
                        var rule = rules.get(binding.definition());
                        if (rule == null || !rule.eventType().equals(event.signal().type())) {
                            throw new IllegalArgumentException("Unregistered or wrong-event rule binding: " + binding.definition());
                        }
                        RuleBinding previous = unique.putIfAbsent(binding.instance(), binding);
                        if (previous != null && !previous.equals(binding)) throw new IllegalArgumentException("Conflicting rule binding: " + binding.instance());
                    }
                    for (RuleBinding binding : unique.values()) {
                        cursor.frames.addLast(new Frame(cursor.nextFrame++, event, binding.instance(), binding.definition(), binding.scope(), 0, false, Map.of()));
                    }
                    cursor.distributing = null;
                    continue;
                }
                Frame frame = cursor.frames.getFirst();
                EventRule<S> rule = rules.get(frame.definition());
                if (!frame.started()) {
                    if (!rule.condition().test(cursor.domain, context(frame))) { cursor.frames.removeFirst(); continue; }
                    frame = frame.move(frame.pc(), frame.bindings());
                    cursor.frames.removeFirst();
                    cursor.frames.addFirst(frame);
                }
                if (frame.pc() == rule.actions().size()) { cursor.frames.removeFirst(); continue; }
                Instruction<S> instruction = rule.actions().get(frame.pc());
                if (instruction.action() instanceof ForEach<S> loop) { cursor.enter(frame, rule, loop); continue; }
                if (instruction.action() instanceof EndEach<S>) { cursor.next(frame, rule); continue; }
                if (!instruction.guard().test(cursor.domain, context(frame))) {
                    cursor.frames.removeFirst();
                    cursor.frames.addFirst(frame.move(frame.pc() + instruction.skipWhenFalse(), frame.bindings()));
                    continue;
                }
                Outcome<S> outcome = instruction.action().step(cursor.domain, context(frame));
                switch (outcome) {
                    case Local<S> local -> cursor.commit(frame, instruction, local);
                    case Await<S> await -> {
                        var op = operation(frame);
                        cursor.pending = Optional.of(op);
                        cursor.frames.removeFirst(); cursor.frames.addFirst(frame.await(await.command()));
                        return new Transition<>(cursor.snapshot(), List.of(new WorldRequest(op, await.command())));
                    }
                }
            } catch (RuntimeException exception) {
                cursor.fail(exception);
                break;
            }
        }
        return new Transition<>(cursor.snapshot(), List.of());
    }

    private static OperationId operation(Frame frame) { return new OperationId(frame.id(), frame.pc(), frame.invocations().getOrDefault(frame.pc(), 0)); }
    private static Context context(Frame frame) {
        return new Context(frame.event(), frame.rule(), frame.scope(), frame.bindings(), operation(frame), frame.retainedResults(), frame.pendingCommand());
    }

    /** Mutable scratch space is local to a single pure transition and never escapes. */
    private final class Cursor {
        S domain;
        long time, nextEvent, nextFrame;
        final ArrayDeque<Event> facts;
        final ArrayDeque<Frame> frames;
        Optional<OperationId> pending;
        final Map<OperationId, ActionResult> receipts;
        Optional<Failure> failure;
        Event distributing;

        Cursor(State<S> state) {
            domain = state.domain(); time = state.timeMicros(); nextEvent = state.nextEvent(); nextFrame = state.nextFrame();
            facts = new ArrayDeque<>(state.facts()); frames = new ArrayDeque<>(state.frames());
            pending = state.pending(); receipts = new LinkedHashMap<>(state.receipts()); failure = state.failure();
        }

        void commit(Frame frame, Instruction<S> instruction, Local<S> result) {
            domain = result.state();
            var bindings = new HashMap<>(frame.bindings());
            if (!instruction.bind().isEmpty()) bindings.put(instruction.bind(), result.result());
            var retained = new HashMap<>(frame.retainedResults());
            if (result.result() instanceof RetainedResult receipt) retained.put(receipt.retentionKey(), receipt);
            var invocations = new HashMap<>(frame.invocations());
            invocations.put(frame.pc(), Math.incrementExact(invocations.getOrDefault(frame.pc(), 0)));
            frames.removeFirst();
            frames.addFirst(new Frame(frame.id(), frame.event(), frame.rule(), frame.definition(), frame.scope(), frame.pc() + 1, true,
                    bindings, frame.iterations(), invocations, retained));
            for (var signal : result.emitted()) {
                facts.addLast(new Event(nextEvent++, frame.event().root(), Optional.of(frame.event().id()), time, signal));
            }
        }

        void enter(Frame frame, EventRule<S> rule, ForEach<S> loop) {
            List<ActionResult> values = List.copyOf(loop.collection().read(domain, context(frame)));
            int end = frame.pc() + loop.bodySize() + 1;
            var iterations = new ArrayList<>(frame.iterations());
            var bindings = new HashMap<>(frame.bindings());
            clearScope(bindings, rule, frame.pc(), end);
            if (!values.isEmpty()) {
                iterations.add(new Iteration(frame.pc(), end, 0, values));
                bindings.put(rule.actions().get(frame.pc()).bind(), values.getFirst());
            }
            replaceLoop(frame, values.isEmpty() ? end + 1 : frame.pc() + 1, bindings, iterations);
        }

        void next(Frame frame, EventRule<S> rule) {
            var iterations = new ArrayList<>(frame.iterations());
            var loop = iterations.removeLast();
            if (loop.end() != frame.pc()) throw new IllegalStateException("Wrong active loop");
            var bindings = new HashMap<>(frame.bindings());
            clearScope(bindings, rule, loop.start(), loop.end());
            int index = loop.index() + 1;
            boolean more = index < loop.values().size();
            if (more) {
                iterations.add(new Iteration(loop.start(), loop.end(), index, loop.values()));
                bindings.put(rule.actions().get(loop.start()).bind(), loop.values().get(index));
            }
            replaceLoop(frame, more ? loop.start() + 1 : loop.end() + 1, bindings, iterations);
        }

        void clearScope(Map<String, ActionResult> bindings, EventRule<S> rule, int start, int end) {
            for (int pc = start; pc <= end; pc++) bindings.remove(rule.actions().get(pc).bind());
        }

        void replaceLoop(Frame frame, int pc, Map<String, ActionResult> bindings, List<Iteration> iterations) {
            frames.removeFirst();
            frames.addFirst(new Frame(frame.id(), frame.event(), frame.rule(), frame.definition(), frame.scope(), pc, true,
                    bindings, iterations, frame.invocations(), frame.retainedResults()));
        }

        void fail(RuntimeException exception) {
            Frame frame = frames.peekFirst();
            failure = Optional.of(new Failure(frame == null ? distributing == null ? 0 : distributing.root() : frame.event().root(), frame == null ? "" : frame.rule(),
                    frame == null ? -1 : frame.pc(), exception.getClass().getSimpleName() + ": " + exception.getMessage(), facts.size(), frames.size()));
            facts.clear(); frames.clear(); pending = Optional.empty();
        }

        State<S> snapshot() {
            return new State<>(version, domain, time, nextEvent, nextFrame, List.copyOf(facts), List.copyOf(frames), pending, receipts, failure);
        }
    }
}
