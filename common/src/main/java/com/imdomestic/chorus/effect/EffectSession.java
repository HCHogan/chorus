package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import com.imdomestic.chorus.rule.OperationLedger;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.EffectProgram;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Imperative host for the pure timeline. One session and its world executor belong to one server thread. */
public final class EffectSession {
    private final TimelineEngine<EffectState> engine;
    private final Function<WorldRequest, ActionResult> world;
    private final OperationLedger ledger;
    private final Optional<EffectProgram> program;
    private final Thread ownerThread = Thread.currentThread();
    private TimelineEngine.State<EffectState> state;
    private boolean running;
    private WorldOperation activeOperation;

    public EffectSession(TimelineEngine<EffectState> engine, EffectState initial, Function<WorldRequest, ActionResult> world) {
        this(engine, engine.initial(initial), world, Optional.empty(), new OperationLedger());
    }
    /** A data-defined session pins its complete catalogue, not only its human-readable version label. */
    public EffectSession(CompiledEffects program, EffectClock clock, int budget, EffectState initial, Function<WorldRequest, ActionResult> world) {
        this(program.engine(clock, budget), initial, world, program.program());
    }
    private EffectSession(TimelineEngine<EffectState> engine, EffectState initial, Function<WorldRequest, ActionResult> world, EffectProgram program) {
        this(engine, engine.initial(initial), world, Optional.of(program), new OperationLedger());
    }
    private EffectSession(TimelineEngine<EffectState> engine, TimelineEngine.State<EffectState> initial,
            Function<WorldRequest, ActionResult> world, Optional<EffectProgram> program, OperationLedger ledger) {
        this.engine = Objects.requireNonNull(engine); this.world = Objects.requireNonNull(world);
        this.state = Objects.requireNonNull(initial); this.program = program; this.ledger = ledger;
    }
    /** Full immutable domain state; host/world state and storage encoding are separate responsibilities. */
    public record Checkpoint(EffectProgram program, TimelineEngine.Checkpoint<EffectState> timeline) {
        public Checkpoint {
            Objects.requireNonNull(program); Objects.requireNonNull(timeline);
            if (!program.version().equals(timeline.version())) throw new IllegalArgumentException("Checkpoint catalogue version differs");
        }
    }
    public Checkpoint checkpoint() {
        thread();
        if (running) throw new IllegalStateException("Cannot checkpoint during a world boundary");
        var before = state;
        var saved = new Checkpoint(program.orElseThrow(() -> new IllegalStateException("Checkpoint requires a pinned data-defined catalogue")),
                engine.checkpoint(before));
        current(before);
        return saved;
    }
    /** Reconnect to the corresponding world state; this method does not replay or reconcile world writes. */
    public static EffectSession restore(CompiledEffects program, EffectClock clock, int budget, Checkpoint checkpoint,
            Function<WorldRequest, ActionResult> world) {
        if (!program.program().equals(checkpoint.program())) throw new IllegalArgumentException("Checkpoint catalogue content differs");
        var engine = program.engine(clock, budget);
        return new EffectSession(engine, engine.restore(checkpoint.timeline()), world, Optional.of(program.program()),
                OperationLedger.restore(checkpoint.timeline().nextFrame()));
    }
    public TimelineEngine.State<EffectState> state() { return state; }
    public boolean running() { return running; }
    public EffectProgram catalogue() {
        thread(); return program.orElseThrow(() -> new IllegalStateException("Operation requires a pinned data-defined catalogue"));
    }
    private void thread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Effect session belongs to its owner thread");
    }
    private void current(TimelineEngine.State<EffectState> expected) {
        thread();
        if (running || state != expected || !state.idle() || state.engine().failure().isPresent() || state.abandoned().isPresent())
            throw new IllegalStateException("Domain publication requires the original healthy settled session");
    }
    /** Opaque, single-use publication prepared against the exact live timeline, not merely equal account values. */
    public static final class DomainUpdate {
        private final EffectSession session;
        private final TimelineEngine.State<EffectState> before, after;
        private final Optional<OperationCommit> operation;
        private DomainUpdate(EffectSession session, TimelineEngine.State<EffectState> before, TimelineEngine.State<EffectState> after) {
            this(session, before, after, Optional.empty());
        }
        private DomainUpdate(EffectSession session, TimelineEngine.State<EffectState> before, TimelineEngine.State<EffectState> after, Optional<OperationCommit> operation) {
            this.session = session; this.before = before; this.after = after; this.operation = operation;
        }
    }
    /** Only the currently executing world callback can obtain this capability; it cannot survive a callback or authorize another request. */
    public static final class WorldOperation {
        private final EffectSession session;
        private final WorldRequest request;
        private Optional<ActionResult> committed = Optional.empty();
        private WorldOperation(EffectSession session, WorldRequest request) { this.session = session; this.request = request; }
        public WorldRequest request() { return request; }
        public Optional<ActionResult> committed() { return committed; }
    }
    private record OperationCommit(WorldOperation operation, Optional<ActionResult> result) {}
    private void currentOperation(WorldOperation operation, TimelineEngine.State<EffectState> expected) {
        thread();
        if (operation.session != this || activeOperation != operation || !running || state != expected
                || !state.engine().pending().equals(Optional.of(operation.request.id())) || state.engine().failure().isPresent() || state.abandoned().isPresent())
            throw new IllegalStateException("Publication requires this session's active world operation");
    }
    public WorldOperation operation(WorldRequest request) {
        thread();
        if (activeOperation == null || !activeOperation.request.equals(request)) throw new IllegalStateException("Request is not the active world operation");
        currentOperation(activeOperation, state); return activeOperation;
    }
    public TimelineEngine.State<EffectState> operationState(WorldOperation operation) {
        currentOperation(operation, state); return state;
    }
    /** Prepare a single world result and its committed domain. Completion still resumes through the normal receipt protocol. */
    public DomainUpdate prepareOperationDomain(WorldOperation operation, TimelineEngine.State<EffectState> expected, EffectState domain, ActionResult result) {
        currentOperation(operation, expected);
        if (operation.committed.isPresent()) throw new IllegalStateException("World operation already committed its result");
        Objects.requireNonNull(result);
        if (result instanceof WorldReceipt) throw new IllegalArgumentException("Commit the action result, not a world receipt envelope");
        var next = engine.replacePendingDomain(expected, operation.request.id(), domain);
        currentOperation(operation, expected);
        return new DomainUpdate(this, expected, next, Optional.of(new OperationCommit(operation, Optional.of(result))));
    }
    /** The trusted host validates domain semantics. Preparation may inspect clock policy but executes no rules. */
    public DomainUpdate prepareDomain(TimelineEngine.State<EffectState> expected, EffectState domain) {
        current(expected);
        var next = engine.replaceDomain(expected, Objects.requireNonNull(domain));
        current(expected); // A custom clock policy must not have reentered this session while validating.
        return new DomainUpdate(this, expected, next);
    }
    /** Validate every participant first, then publish with no callbacks between writes. All sessions share this thread. */
    public static void commitDomains(List<DomainUpdate> updates) {
        var batch = List.copyOf(updates);
        var sessions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<EffectSession, Boolean>());
        for (var update : batch) {
            if (!sessions.add(update.session)) throw new IllegalArgumentException("Duplicate session in domain publication");
            if (update.operation.isEmpty()) update.session.current(update.before);
            else {
                var operation = update.operation.orElseThrow().operation();
                update.session.currentOperation(operation, update.before);
                if (operation.committed.isPresent()) throw new IllegalStateException("World operation already committed its result");
            }
        }
        for (var update : batch) update.session.state = update.after;
        for (var update : batch) if (update.operation.isPresent()) {
            var commit = update.operation.orElseThrow(); commit.operation().committed = commit.result();
        }
    }
    public void start(long time, Signal signal) { start(new Start(time, signal)); }
    public void observe(long time, List<Signal> facts) { start(new Start(time, facts)); }
    public void observe(long time, List<Signal> facts, Payload committed) { start(new Start(time, facts, committed)); }
    private void start(Start start) {
        thread();
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
                    var receipt = ledger.execute(request, _ -> executeWorld(request));
                    transition = engine.transition(state, new Completed(request.id(), receipt));
                } else if (transition.needsPump()) transition = engine.transition(state, Pump.INSTANCE);
                else return;
            }
        } finally { running = false; }
    }
    private ActionResult executeWorld(WorldRequest request) {
        if (activeOperation != null) throw new IllegalStateException("Reentrant world operation");
        var operation = new WorldOperation(this, request); activeOperation = operation;
        try {
            var receipt = Objects.requireNonNull(world.apply(request));
            var value = receipt instanceof WorldReceipt envelope ? envelope.value() : receipt;
            if (operation.committed.isPresent() && !operation.committed.orElseThrow().equals(value))
                throw new IllegalStateException("World callback returned a different result after committing its operation");
            return receipt;
        } finally { activeOperation = null; }
    }
}
