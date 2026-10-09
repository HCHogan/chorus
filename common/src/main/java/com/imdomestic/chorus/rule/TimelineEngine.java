package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure host protocol: settle every time boundary before delivering a requested external event. */
public final class TimelineEngine<S> {
    private static final String ADVANCE = "chorus:internal/advance_time";
    public static final long NEVER = Long.MAX_VALUE;

    public interface Clock<S> {
        long time(S state);
        long nextDeadline(S state);
        Local<S> advance(S state, long until);
    }
    public record State<S>(RuleEngine.State<S> engine, Optional<Start> requested, Map<OperationId, ActionResult> receipts, Optional<Start> abandoned) {
        public State { Objects.requireNonNull(engine); Objects.requireNonNull(requested); receipts = Map.copyOf(receipts); Objects.requireNonNull(abandoned); }
        public boolean idle() { return engine.idle() && requested.isEmpty(); }
    }
    public record Transition<S>(State<S> state, List<WorldRequest> actions) {
        public Transition { actions = List.copyOf(actions); }
        public boolean needsPump() {
            return !state.idle() && state.engine().pending().isEmpty() && state.engine().failure().isEmpty();
        }
    }

    private final RuleEngine<S> engine;
    private final Clock<S> clock;

    public TimelineEngine(String version, List<EventRule<S>> definitions, RuleResolver<S> resolver, Clock<S> clock, int stepsPerTransition) {
        this(version, definitions, resolver, clock, stepsPerTransition, RuleEngine::noWrites);
    }
    public TimelineEngine(String version, List<EventRule<S>> definitions, RuleResolver<S> resolver, Clock<S> clock, int stepsPerTransition, Reconciler<S> reconciler) {
        this.clock = Objects.requireNonNull(clock);
        Objects.requireNonNull(resolver);
        var rules = new ArrayList<>(definitions);
        if (rules.stream().anyMatch(rule -> rule.definition().equals(ADVANCE) || rule.eventType().equals(ADVANCE))) {
            throw new IllegalArgumentException("Reserved timeline rule/event");
        }
        rules.add(new EventRule<>(ADVANCE, ADVANCE, (_, _) -> true, List.of(new Instruction<>((state, context) -> {
            Local<S> advanced = clock.advance(state, context.event().timeMicros());
            if (clock.time(advanced.state()) != context.event().timeMicros()) throw new IllegalStateException("Clock did not reach the boundary");
            return advanced;
        }, ""))));
        this.engine = new RuleEngine<>(version, rules, stepsPerTransition, (state, event) ->
                event.signal().type().equals(ADVANCE)
                        ? List.of(new RuleBinding(ADVANCE, ADVANCE, Empty.INSTANCE)) : resolver.resolve(state, event), reconciler);
    }

    public State<S> initial(S domain) {
        long time = clock.time(domain);
        if (time < 0 || time == NEVER || clock.nextDeadline(domain) <= time) throw new IllegalArgumentException("Invalid or unsettled initial clock");
        var base = engine.initial(domain);
        return new State<>(new RuleEngine.State<>(base.version(), domain, time, base.nextEvent(), base.nextFrame(), base.facts(),
                base.frames(), base.pending(), base.receipts(), base.failure()), Optional.empty(), Map.of(), Optional.empty());
    }

    public Transition<S> transition(State<S> state, Input input) {
        Objects.requireNonNull(input);
        if (input instanceof Start start) {
            if (state.engine().failure().isPresent()) throw new IllegalStateException("Failed timeline requires explicit recovery");
            if (!state.idle()) throw new IllegalStateException("Cannot interleave a requested timeline event");
            if (start.timeMicros() < clock.time(state.engine().domain()) || start.timeMicros() == NEVER) throw new IllegalArgumentException("Invalid event time");
            if (start.signal().type().equals(ADVANCE) || start.following().stream().anyMatch(signal -> signal.type().equals(ADVANCE))) {
                throw new IllegalArgumentException("Reserved timeline event");
            }
            return dispatch(new State<>(state.engine(), Optional.of(start), Map.of(), Optional.empty()));
        }
        if (input instanceof Completed completed) {
            if (state.receipts().containsKey(completed.operation())) {
                if (!state.receipts().get(completed.operation()).equals(completed.result())) throw new IllegalArgumentException("Conflicting duplicate timeline receipt");
                return new Transition<>(state, List.of());
            }
            var result = engine.transition(state.engine(), input);
            var receipts = new HashMap<>(state.receipts()); receipts.put(completed.operation(), completed.result());
            return wrap(new State<>(state.engine(), state.requested(), receipts, state.abandoned()), state.requested(), result);
        }
        if (state.engine().failure().isPresent()) return new Transition<>(state, List.of());
        if (!state.engine().idle()) return wrap(state, state.requested(), engine.transition(state.engine(), Pump.INSTANCE));
        return dispatch(state);
    }

    private Transition<S> dispatch(State<S> state) {
        if (state.requested().isEmpty()) return new Transition<>(state, List.of());
        Start requested = state.requested().orElseThrow();
        long time = clock.time(state.engine().domain());
        long next = clock.nextDeadline(state.engine().domain());
        if (next <= time) throw new IllegalStateException("Clock left an unsettled deadline");
        if (time < requested.timeMicros()) {
            long until = Math.min(requested.timeMicros(), next);
            return wrap(state, state.requested(), engine.transition(state.engine(), new Start(until, new Signal(ADVANCE, Empty.INSTANCE))));
        }
        return wrap(state, Optional.empty(), engine.transition(state.engine(), requested));
    }

    private Transition<S> wrap(State<S> before, Optional<Start> requested, RuleEngine.Transition<S> result) {
        // A failed expiry reaction must not be followed by the external command that depended on it.
        boolean failed = result.state().failure().isPresent();
        return new Transition<>(new State<>(result.state(), failed ? Optional.empty() : requested, before.receipts(),
                failed && requested.isPresent() ? requested : before.abandoned()), result.actions());
    }
}
