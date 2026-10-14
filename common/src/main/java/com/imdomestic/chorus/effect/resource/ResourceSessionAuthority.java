package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectSession;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Supplier;

/** Owner-thread bridge between the pure authority and live settled sessions. Does not intercept DSL resource actions. */
public final class ResourceSessionAuthority {
    public record Binding(CompiledEffects program, EffectSession session) {
        public Binding { Objects.requireNonNull(program); Objects.requireNonNull(session); }
    }
    private record Caller(String domain, EffectSession.WorldOperation operation) {}
    private record Snapshot(ResourceAuthority.Worlds worlds, Map<String, TimelineEngine.State<EffectState>> timelines, Optional<Caller> caller) {}
    /** Prepared handoff is tied to this host and every participating timeline; callers cannot construct its writes. */
    public static final class Handoff {
        private final ResourceSessionAuthority host;
        private final Snapshot before;
        private final ResourceAuthority.Worlds after;
        private final List<EffectSession.DomainUpdate> updates;
        private final ResourceAuthority.Transfer transfer;
        private Handoff(ResourceSessionAuthority host, Snapshot before, ResourceAuthority.Worlds after,
                List<EffectSession.DomainUpdate> updates, ResourceAuthority.Transfer transfer) {
            this.host = host; this.before = before; this.after = after; this.updates = updates; this.transfer = transfer;
        }
        public ResourceAuthority.Transfer transfer() { return transfer; }
    }
    private final Thread ownerThread = Thread.currentThread();
    private final Map<String, Binding> bindings;
    private ResourceAuthority.Directory directory;
    private boolean working;
    private boolean draining;
    private String lastDrained = "";

    /** Restored directories must already match every live session; construction never silently republishes a mismatch. */
    public ResourceSessionAuthority(ResourceAuthority.Directory directory, Map<String, Binding> bindings) {
        this.directory = Objects.requireNonNull(directory);
        this.bindings = Collections.unmodifiableMap(new TreeMap<>(bindings));
        var sessions = Collections.newSetFromMap(new IdentityHashMap<EffectSession, Boolean>());
        for (var entry : this.bindings.entrySet()) {
            new ResourceAuthority.Lease(entry.getKey(), 1);
            if (!sessions.add(entry.getValue().session())) throw new IllegalArgumentException("Session bound to more than one resource domain");
        }
        boundary(() -> { capture(false); return null; });
    }
    private <T> T boundary(Supplier<T> action) {
        if (Thread.currentThread() != ownerThread || working) throw new IllegalStateException("Resource authority requires its nonreentrant owner thread");
        working = true;
        try { return action.get(); } finally { working = false; }
    }
    private Binding binding(String domain) {
        var value = bindings.get(domain);
        if (value == null) throw new IllegalArgumentException("Missing live resource domain: " + domain);
        return value;
    }
    private Snapshot capture(boolean publish) {
        return capture(publish, Optional.empty());
    }
    private Snapshot capture(boolean publish, Optional<Caller> caller) {
        var states = new TreeMap<String, EffectState>();
        var timelines = new TreeMap<String, TimelineEngine.State<EffectState>>();
        for (var entry : bindings.entrySet()) {
            var binding = entry.getValue();
            var current = caller.filter(value -> value.domain().equals(entry.getKey()));
            var catalogue = current.isPresent() || publish ? binding.session().catalogue() : binding.session().checkpoint().program();
            if (!binding.program().program().equals(catalogue)) throw new IllegalArgumentException("Live resource catalogue differs from its session");
            var timeline = current.isPresent() ? binding.session().operationState(current.orElseThrow().operation())
                    : publish ? binding.session().coordinationState() : binding.session().state();
            timelines.put(entry.getKey(), timeline); states.put(entry.getKey(), timeline.engine().domain());
        }
        var next = directory;
        for (var partition : directory.holders().values()) {
            var domain = partition.lease().domain();
            if (!partition.program().equals(binding(domain).program().program())) throw new IllegalArgumentException("Resource owner catalogue differs from its live session");
            if (publish) next = ResourceAuthority.publish(next, partition, states.get(domain));
        }
        var result = new Snapshot(new ResourceAuthority.Worlds(next, states), Map.copyOf(timelines), caller);
        unchanged(timelines, caller); directory = next; return result;
    }
    private void unchanged(Map<String, TimelineEngine.State<EffectState>> timelines, Optional<Caller> caller) {
        timelines.forEach((domain, expected) -> {
            var session = binding(domain).session();
            var current = caller.filter(value -> value.domain().equals(domain));
            var actual = current.isPresent() ? session.operationState(current.orElseThrow().operation()) : session.state();
            if (current.isEmpty() && session.running() || actual != expected) throw new IllegalStateException("Live resource domain changed during publication");
        });
    }
    private void current(Snapshot expected) {
        if (directory != expected.worlds().directory()) throw new IllegalStateException("Resource authority changed since preparation");
        unchanged(expected.timelines(), expected.caller());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after) {
        return prepare(before, after, Optional.empty());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after, Optional<RuleEngine.ActionResult> result) {
        return prepare(before, after, result, Map.of());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after, Optional<RuleEngine.ActionResult> result,
            Map<String, List<RuleEngine.Signal>> facts) {
        current(before);
        var updates = new ArrayList<EffectSession.DomainUpdate>();
        for (var entry : bindings.entrySet()) {
            var domain = entry.getKey(); var next = after.domain(domain);
            var emitted = facts.getOrDefault(domain, List.of());
            var caller = before.caller().filter(value -> value.domain().equals(domain));
            if (caller.isPresent())
                updates.add(entry.getValue().session().prepareOperationDomain(caller.orElseThrow().operation(), before.timelines().get(domain), next, result.orElseThrow(), emitted));
            else if (next != before.worlds().domain(domain) || !emitted.isEmpty())
                updates.add(entry.getValue().session().prepareInboxDomain(before.timelines().get(domain), next, emitted));
        }
        current(before); return List.copyOf(updates);
    }
    private void commit(Snapshot before, ResourceAuthority.Worlds after, List<EffectSession.DomainUpdate> updates) {
        current(before);
        EffectSession.commitDomains(updates);
        directory = after.directory(); // Reference assignment only; no rule or host callback can observe half a publication.
    }
    /** Capture committed balances, including sessions with queued facts. This is not a settled checkpoint. */
    public ResourceAuthority.Worlds snapshot() { return boundary(() -> capture(true).worlds()); }
    private void settled() { bindings.values().forEach(binding -> binding.session().checkpoint()); }
    public ResourceAuthority.Partition claim(String holder, String domain) {
        return boundary(() -> {
            settled();
            var before = capture(true);
            var next = ResourceAuthority.claim(directory, holder, domain, binding(domain).program(), before.worlds().domain(domain));
            var checked = new ResourceAuthority.Worlds(next, before.worlds().domains());
            current(before); directory = checked.directory(); return directory.owner(holder);
        });
    }
    public Handoff prepareTransfer(String holder, String destination) {
        return boundary(() -> {
            settled();
            var before = capture(true); var owner = directory.owner(holder); var program = binding(destination).program();
            var transfer = ResourceAuthority.transfer(directory, owner, before.worlds().domain(owner.lease().domain()),
                    destination, program, before.worlds().domain(destination));
            var after = ResourceAuthority.commit(before.worlds(), transfer, program);
            return new Handoff(this, before, after, prepare(before, after), transfer);
        });
    }
    public ResourceAuthority.Lease commit(Handoff handoff) {
        return boundary(() -> {
            Objects.requireNonNull(handoff);
            if (handoff.host != this) throw new IllegalArgumentException("Resource handoff belongs to another host");
            commit(handoff.before, handoff.after, handoff.updates);
            return handoff.transfer.after().lease();
        });
    }
    /** Return the committed credit. The native host remains responsible for scaling and result-fact delivery. */
    public ResourceAuthority.Granted grant(ResourceState.Key address, double requested, double scaled) {
        return boundary(() -> {
            var before = capture(true); var result = ResourceAuthority.grant(before.worlds(), address, requested, scaled);
            commit(before, result.worlds(), prepare(before, result.worlds())); return result;
        });
    }
    /** Publish the issuer's claim and recipient's balance before exposing the refund result to its caller. */
    public ResourceAuthority.Refunded refund(String issuer, RetainedCosts.Handle handle, double fraction) {
        return boundary(() -> {
            var before = capture(true); var result = ResourceAuthority.refund(before.worlds(), issuer, handle, fraction);
            commit(before, result.worlds(), prepare(before, result.worlds())); return result;
        });
    }
    /** Execute exactly the caller's active resource request. A repeated call inside that callback returns its committed result. */
    public ResourceRequests.Receipt execute(String caller, RuleEngine.WorldRequest request) {
        return boundary(() -> {
            var operation = binding(caller).session().operation(request);
            if (!(request.command() instanceof ResourceRequests.Request command)) throw new IllegalArgumentException("Not a resource request");
            if (operation.committed().isPresent()) {
                if (!(operation.committed().orElseThrow() instanceof ResourceRequests.Receipt receipt) || !receipt.request().equals(command))
                    throw new IllegalStateException("Active resource request has a conflicting committed result");
                return receipt;
            }
            var before = capture(true, Optional.of(new Caller(caller, operation)));
            ResourceAuthority.Worlds after; ResourceRequests.Receipt receipt;
            switch (command) {
                case ResourceRequests.Read read -> {
                    var owner = before.worlds().directory().owner(read.account()); after = before.worlds();
                    receipt = new ResourceRequests.ReadResult(read, owner.account(read.account().resource()), owner.lease());
                }
                case ResourceRequests.Grant grant -> {
                    var result = ResourceAuthority.grant(before.worlds(), grant.account(), grant.requested(), grant.scaled()); after = result.worlds();
                    receipt = new ResourceRequests.Granted(grant, result.result(), result.recipient());
                }
                case ResourceRequests.Refund refund -> {
                    var result = ResourceAuthority.refund(before.worlds(), refund.issuer(), refund.handle(), refund.fraction()); after = result.worlds();
                    receipt = new ResourceRequests.Refunded(refund, result.result(), result.recipient());
                }
            }
            var emitted = ResourceRequests.facts(caller, request.id(), receipt);
            var facts = emitted.isEmpty() ? Map.<String, List<RuleEngine.Signal>>of()
                    : Map.of(receipt.recipient().orElseThrow().domain(), emitted);
            commit(before, after, prepare(before, after, Optional.of(receipt), facts)); return receipt;
        });
    }
    public record Drained(int boundaries, boolean pending) {}
    /** Cooperative round-robin scheduling: a finite batch yields with every remaining fact intact, including feedback chains. */
    public Drained drain(int maxBoundaries) {
        if (Thread.currentThread() != ownerThread || working || draining) throw new IllegalStateException("Resource fact drain requires its nonreentrant owner thread");
        if (maxBoundaries < 1) throw new IllegalArgumentException("Positive resource fact drain batch required");
        draining = true;
        try {
            int count = 0;
            while (count < maxBoundaries) {
                var candidates = boundary(() -> {
                    capture(true);
                    return bindings.entrySet().stream().filter(entry -> entry.getValue().session().inboxPending()).map(Map.Entry::getKey).toList();
                });
                if (candidates.isEmpty()) return new Drained(count, false);
                String domain = candidates.stream().filter(value -> value.compareTo(lastDrained) > 0).findFirst().orElse(candidates.getFirst());
                binding(domain).session().drain(); lastDrained = domain; count++;
            }
            boolean pending = boundary(() -> { capture(true); return bindings.values().stream().anyMatch(binding -> binding.session().inboxPending()); });
            return new Drained(count, pending);
        } finally { draining = false; }
    }
}
