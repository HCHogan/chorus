package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectSession;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.util.*;
import java.util.function.Supplier;

/** Owner-thread bridge between the pure authority and live settled sessions. Does not intercept DSL resource actions. */
public final class ResourceSessionAuthority {
    public record Binding(CompiledEffects program, EffectSession session) {
        public Binding { Objects.requireNonNull(program); Objects.requireNonNull(session); }
    }
    private record Snapshot(ResourceAuthority.Worlds worlds, Map<String, TimelineEngine.State<EffectState>> timelines) {}
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
        var states = new TreeMap<String, EffectState>();
        var timelines = new TreeMap<String, TimelineEngine.State<EffectState>>();
        for (var entry : bindings.entrySet()) {
            var binding = entry.getValue(); var checkpoint = binding.session().checkpoint();
            if (!binding.program().program().equals(checkpoint.program())) throw new IllegalArgumentException("Live resource catalogue differs from its session");
            var timeline = binding.session().state();
            timelines.put(entry.getKey(), timeline); states.put(entry.getKey(), timeline.engine().domain());
        }
        var next = directory;
        for (var partition : directory.holders().values()) {
            var domain = partition.lease().domain();
            if (!partition.program().equals(binding(domain).program().program())) throw new IllegalArgumentException("Resource owner catalogue differs from its live session");
            if (publish) next = ResourceAuthority.publish(next, partition, states.get(domain));
        }
        var result = new Snapshot(new ResourceAuthority.Worlds(next, states), Map.copyOf(timelines));
        unchanged(timelines); directory = next; return result;
    }
    private void unchanged(Map<String, TimelineEngine.State<EffectState>> timelines) {
        timelines.forEach((domain, expected) -> {
            var session = binding(domain).session();
            if (session.running() || session.state() != expected) throw new IllegalStateException("Live resource domain changed during publication");
        });
    }
    private void current(Snapshot expected) {
        if (directory != expected.worlds().directory()) throw new IllegalStateException("Resource authority changed since preparation");
        unchanged(expected.timelines());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after) {
        current(before);
        var updates = new ArrayList<EffectSession.DomainUpdate>();
        for (var entry : bindings.entrySet()) {
            var domain = entry.getKey(); var next = after.domain(domain);
            if (next != before.worlds().domain(domain))
                updates.add(entry.getValue().session().prepareDomain(before.timelines().get(domain), next));
        }
        current(before); return List.copyOf(updates);
    }
    private void commit(Snapshot before, ResourceAuthority.Worlds after, List<EffectSession.DomainUpdate> updates) {
        current(before);
        EffectSession.commitDomains(updates);
        directory = after.directory(); // Reference assignment only; no rule or host callback can observe half a publication.
    }
    /** Publish already-settled local progress; this method neither advances time nor executes a rule. */
    public ResourceAuthority.Worlds snapshot() { return boundary(() -> capture(true).worlds()); }
    public ResourceAuthority.Partition claim(String holder, String domain) {
        return boundary(() -> {
            var before = capture(true);
            var next = ResourceAuthority.claim(directory, holder, domain, binding(domain).program(), before.worlds().domain(domain));
            var checked = new ResourceAuthority.Worlds(next, before.worlds().domains());
            current(before); directory = checked.directory(); return directory.owner(holder);
        });
    }
    public Handoff prepareTransfer(String holder, String destination) {
        return boundary(() -> {
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
}
