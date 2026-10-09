package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** In-memory world-side idempotency for one live interpreter session; unknown operations never retry. */
public final class OperationLedger {
    private record Entry(WorldCommand command, Optional<ActionResult> result) {}
    private final Map<OperationId, Entry> entries = new HashMap<>();
    private long retiredFrame, greatestFrame;

    public ActionResult execute(WorldRequest request, Function<WorldCommand, ActionResult> executor) {
        var previous = entries.get(request.id());
        if (previous != null) {
            if (!previous.command().equals(request.command())) throw new IllegalArgumentException("Conflicting command for the same operation ID");
            return previous.result().orElseThrow(() -> new IllegalStateException("World operation is in progress or has an unknown outcome; automatic retry is forbidden"));
        }
        if (request.id().frame() <= retiredFrame) throw new IllegalArgumentException("Stale operation from a retired boundary");
        greatestFrame = Math.max(greatestFrame, request.id().frame());
        entries.put(request.id(), new Entry(request.command(), Optional.empty()));
        ActionResult result = Objects.requireNonNull(executor.apply(request.command()));
        entries.put(request.id(), new Entry(request.command(), Optional.of(result)));
        return result;
    }
    /** Called only when starting the next external boundary after the old boundary has settled. */
    public void nextBoundary() {
        if (entries.values().stream().anyMatch(entry -> entry.result().isEmpty())) throw new IllegalStateException("Cannot retire an unknown world operation");
        retiredFrame = greatestFrame; entries.clear();
    }
}
