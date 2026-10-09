package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import com.imdomestic.chorus.rule.OperationLedger;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.util.Objects;
import java.util.List;
import java.util.function.Function;

/** Imperative host for the pure timeline. One session and its world executor belong to one server thread. */
public final class EffectSession {
    private final TimelineEngine<EffectState> engine;
    private final Function<WorldRequest, ActionResult> world;
    private final OperationLedger ledger = new OperationLedger();
    private TimelineEngine.State<EffectState> state;
    private boolean running;

    public EffectSession(TimelineEngine<EffectState> engine, EffectState initial, Function<WorldRequest, ActionResult> world) {
        this.engine = Objects.requireNonNull(engine); this.world = Objects.requireNonNull(world); this.state = engine.initial(initial);
    }
    public TimelineEngine.State<EffectState> state() { return state; }
    public boolean running() { return running; }
    public void start(long time, Signal signal) { start(new Start(time, signal)); }
    public void observe(long time, List<Signal> facts) { start(new Start(time, facts)); }
    public void observe(long time, List<Signal> facts, Payload committed) { start(new Start(time, facts, committed)); }
    private void start(Start start) {
        if (running || !state.idle() || state.engine().failure().isPresent()) throw new IllegalStateException("Cannot interleave or resume a failed world boundary");
        ledger.nextBoundary(); running = true;
        try {
            var transition = engine.transition(state, start);
            while (true) {
                state = transition.state(); // Save committed pure state before entering any world operation.
                if (state.engine().failure().isPresent()) throw new IllegalStateException("Rule execution failed: " + state.engine().failure().orElseThrow());
                if (!transition.actions().isEmpty()) {
                    if (transition.actions().size() != 1) throw new IllegalStateException("Unsupported multi-command transition");
                    var request = transition.actions().getFirst();
                    var receipt = ledger.execute(request, _ -> world.apply(request));
                    transition = engine.transition(state, new Completed(request.id(), receipt));
                } else if (transition.needsPump()) transition = engine.transition(state, Pump.INSTANCE);
                else return;
            }
        } finally { running = false; }
    }
}
