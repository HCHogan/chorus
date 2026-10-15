package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Independent, persistent contact offsets and explicit policies for one entity life. */
public final class EntityAttachments {
    private EntityAttachments() {}
    public enum DeathPolicy { FOLLOW, FIX, END }
    public enum RemovalPolicy { WAIT, FIX, END }
    public enum Phase { FOLLOWING, FIXED, WAITING }
    public enum Outcome { RESOLVED, FIXED, WAITING, MISSING, REPLACED, WRONG_DIMENSION, CLOSED, EXPIRED }
    public record Policy(DeathPolicy death, RemovalPolicy destroyed, RemovalPolicy removed,
            RemovalPolicy unloaded, RemovalPolicy transferred) {
        public Policy { Objects.requireNonNull(death); Objects.requireNonNull(destroyed); Objects.requireNonNull(removed); Objects.requireNonNull(unloaded); Objects.requireNonNull(transferred); }
        RemovalPolicy removal(EntityLifecycle.Kind kind) {
            return switch (kind) { case DESTROYED -> destroyed; case REMOVED -> removed; case UNLOADED -> unloaded; case TRANSFERRED -> transferred; };
        }
    }
    public record Handle(String id, long startedAt, long expiresAt, EntityAnchor.Observation initial,
            WorldPosition contact, Policy policy, Optional<String> issuer) implements RuleEngine.ActionResult {
        public Handle {
            Objects.requireNonNull(initial); Objects.requireNonNull(contact); Objects.requireNonNull(policy); Objects.requireNonNull(issuer); issuer.ifPresent(value -> new ResourceAuthority.Lease(value, 1));
            if (id == null || id.isBlank() || startedAt < 0 || expiresAt <= startedAt)
                throw new IllegalArgumentException("Invalid attachment identity or lifetime");
            if (!initial.position().dimension().equals(contact.dimension())) throw new IllegalArgumentException("Attachment contact belongs to another world");
            contact.rebase(initial.position(), initial.position());
        }
        public Handle(String id, long startedAt, long expiresAt, EntityAnchor.Observation initial, WorldPosition contact, Policy policy) {
            this(id, startedAt, expiresAt, initial, contact, policy, Optional.empty());
        }
        public Handle issuedBy(String domain) { return issuer.isPresent() ? this : new Handle(id, startedAt, expiresAt, initial, contact, policy, Optional.of(domain)); }
        public void requireIssuer(String domain) {
            if (issuer.isPresent() && !issuer.orElseThrow().equals(domain)) throw new IllegalArgumentException("Attachment belongs to another issuing domain");
        }
        /** Legacy aliases are compatible; choosing the issuing domain remains the coordinator's responsibility. */
        public boolean matches(Handle other) {
            return other != null && id.equals(other.id) && startedAt == other.startedAt && expiresAt == other.expiresAt
                    && initial.equals(other.initial) && contact.equals(other.contact) && policy.equals(other.policy)
                    && (issuer.isEmpty() || other.issuer.isEmpty() || issuer.equals(other.issuer));
        }
        public EntityAnchor.Query query() { return new EntityAnchor.Query(initial.reference().target(), initial.anchor(), Optional.of(initial.reference())); }
        public WorldPosition position(EntityAnchor.Observation observation) {
            if (!initial.reference().equals(observation.reference()) || initial.anchor() != observation.anchor())
                throw new IllegalArgumentException("Attachment observed a different life or anchor");
            return contact.rebase(initial.position(), observation.position());
        }
    }
    public record Entry(Handle handle, Phase phase, EntityAnchor.Observation last, long revision, String reason) {
        public Entry {
            Objects.requireNonNull(handle); Objects.requireNonNull(phase); Objects.requireNonNull(last);
            if (revision < 0 || reason == null || reason.isBlank()) throw new IllegalArgumentException("Invalid attachment observation revision or reason");
            handle.position(last);
        }
        Entry observe(Phase next, EntityAnchor.Observation point, String reason) {
            return new Entry(handle, next, point, Math.incrementExact(revision), reason);
        }
    }
    /** Carry the exact issued entry so a receipt cannot revive an attachment changed during the query. */
    public record Query(Entry expected) implements RuleEngine.WorldCommand {
        public Query { Objects.requireNonNull(expected); if (expected.phase() == Phase.FIXED) throw new IllegalArgumentException("Fixed attachment requires no entity query"); }
    }
    public record Observed(Query query, EntityAnchor.Result entity) implements RuleEngine.ActionResult {
        public Observed {
            Objects.requireNonNull(query); Objects.requireNonNull(entity);
            if (!query.expected().handle().query().equals(entity.query())) throw new IllegalArgumentException("Attachment receipt observed a different query");
        }
    }
    public record Resolution(Handle handle, Outcome outcome, Optional<WorldPosition> position,
            EntityAnchor.Result entity) implements PositionResult {
        public Resolution {
            Objects.requireNonNull(handle); Objects.requireNonNull(outcome); Objects.requireNonNull(position); Objects.requireNonNull(entity);
            if (!entity.query().equals(handle.query())) throw new IllegalArgumentException("Attachment result refers to another life");
            if (position.isPresent() != (outcome == Outcome.RESOLVED || outcome == Outcome.FIXED))
                throw new IllegalArgumentException("Attachment position differs from its outcome");
            position.ifPresent(point -> { if (!point.dimension().equals(handle.contact().dimension())) throw new IllegalArgumentException("Attachment position changed world"); });
            if ((entity.outcome() == EntityAnchor.Outcome.AVAILABLE) != (outcome == Outcome.RESOLVED))
                throw new IllegalArgumentException("Attachment result claims an unavailable entity is current");
            if (outcome == Outcome.RESOLVED && !position.orElseThrow().equals(handle.position(entity.requireObservation())))
                throw new IllegalArgumentException("Attachment result lost its original contact offset");
        }
    }
    /** Reconcile only host-confirmed observations, before dependent actions; emitted notifications do not replay this write. */
    public record Commit(RuleEngine.Payload previous, List<RuleEngine.Signal> facts) implements RuleEngine.Payload {
        public Commit {
            Objects.requireNonNull(previous); facts = List.copyOf(facts);
            if (previous instanceof Commit || facts.isEmpty() || facts.stream().anyMatch(f -> !lifecycle(f)))
                throw new IllegalArgumentException("Invalid attachment lifecycle commit");
        }
        public EffectState apply(EffectState state) {
            var entries = new TreeMap<>(state.attachments());
            for (var fact : facts) {
                var reference = reference(fact); long at = time(fact);
                for (var id : List.copyOf(entries.keySet())) {
                    var entry = entries.get(id); var h = entry.handle();
                    if (entry.phase() == Phase.FIXED || h.startedAt() > at || !h.initial().reference().equals(reference)) continue;
                    var changed = change(entry, fact);
                    if (changed.isPresent()) entries.put(id, changed.orElseThrow()); else entries.remove(id);
                }
            }
            return state.withAttachments(entries);
        }
    }
    public static RuleEngine.Payload commit(RuleEngine.Payload previous, List<RuleEngine.Signal> facts) {
        var relevant = facts.stream().filter(EntityAttachments::lifecycle).toList();
        return relevant.isEmpty() ? previous : new Commit(previous, relevant);
    }
    public static boolean lifecycle(RuleEngine.Signal fact) {
        return fact.type().equals(EntityLifecycle.DIED) && fact.payload() instanceof EntityLifecycle.Died
                || fact.type().equals(EntityLifecycle.REMOVED) && fact.payload() instanceof EntityLifecycle.Removed;
    }
    public static long time(RuleEngine.Signal fact) {
        return switch (fact.payload()) { case EntityLifecycle.Died d -> d.observation().timeMicros(); case EntityLifecycle.Removed r -> r.observation().timeMicros(); default -> throw new IllegalArgumentException("Not a lifecycle observation"); };
    }
    public static EntityAnchor.Reference reference(RuleEngine.Signal fact) {
        if (!lifecycle(fact)) throw new IllegalArgumentException("Not a lifecycle observation");
        return switch (fact.payload()) { case EntityLifecycle.Died d -> d.reference(); case EntityLifecycle.Removed r -> r.reference(); default -> throw new IllegalArgumentException("Not a lifecycle observation"); };
    }
    public static Optional<Entry> change(Entry entry, RuleEngine.Signal fact) {
        if (!entry.handle().initial().reference().equals(reference(fact)))
            throw new IllegalArgumentException("Attachment lifecycle observed another entity life");
        if (entry.phase() == Phase.FIXED) return Optional.of(entry);
        if (fact.payload() instanceof EntityLifecycle.Died died) {
            var last = died.anchor(entry.handle().initial().anchor()).requireObservation();
            return switch (entry.handle().policy().death()) {
                case END -> Optional.empty();
                case FIX -> Optional.of(entry.observe(Phase.FIXED, last, "death"));
                case FOLLOW -> Optional.of(entry.observe(entry.phase(), last, "death"));
            };
        }
        var removed = (EntityLifecycle.Removed) fact.payload(); var last = removed.anchor(entry.handle().initial().anchor()).requireObservation();
        return switch (entry.handle().policy().removal(removed.kind())) {
            case END -> Optional.empty();
            case FIX -> Optional.of(entry.observe(Phase.FIXED, last, removed.reason()));
            case WAIT -> Optional.of(entry.observe(Phase.WAITING, last, removed.reason()));
        };
    }
    public static RuleEngine.Local<EffectState> begin(EffectState state, Handle handle) {
        if (handle.startedAt() != state.buffs().timeMicros() || state.attachments().containsKey(handle.id())) throw new IllegalArgumentException("Duplicate attachment or wrong start boundary");
        var entries = new TreeMap<>(state.attachments()); entries.put(handle.id(), new Entry(handle, Phase.FOLLOWING, handle.initial(), 0, "created"));
        return new RuleEngine.Local<>(state.withAttachments(entries), handle, List.of());
    }
    private static Optional<Entry> find(EffectState state, Handle handle) {
        var entry = Optional.ofNullable(state.attachments().get(handle.id()));
        if (entry.filter(e -> !e.handle().matches(handle)).isPresent()) throw new IllegalArgumentException("Attachment handle identity differs");
        return entry;
    }
    public static RuleEngine.Local<EffectState> close(EffectState state, Handle handle) {
        find(state, handle); var entries = new TreeMap<>(state.attachments()); entries.remove(handle.id());
        return new RuleEngine.Local<>(state.withAttachments(entries), RuleEngine.Empty.INSTANCE, List.of());
    }
    private static Resolution unavailable(Handle handle, Outcome outcome, Optional<WorldPosition> point) {
        return new Resolution(handle, outcome, point, new EntityAnchor.Result(handle.query(), EntityAnchor.Outcome.MISSING, Optional.empty()));
    }
    private static Resolution local(EffectState state, Handle handle, Optional<Entry> entry) {
        if (state.buffs().timeMicros() >= handle.expiresAt()) return unavailable(handle, Outcome.EXPIRED, Optional.empty());
        if (entry.isEmpty()) return unavailable(handle, Outcome.CLOSED, Optional.empty());
        var value = entry.orElseThrow();
        return value.phase() == Phase.FIXED ? unavailable(handle, Outcome.FIXED, Optional.of(handle.position(value.last())))
                : unavailable(handle, value.phase() == Phase.WAITING ? Outcome.WAITING : Outcome.MISSING, Optional.empty());
    }
    public static RuleEngine.Outcome<EffectState> resolve(EffectState state, Handle handle) {
        var entry = find(state, handle);
        if (entry.isEmpty() || entry.orElseThrow().phase() == Phase.FIXED || state.buffs().timeMicros() >= handle.expiresAt())
            return new RuleEngine.Local<>(state, local(state, handle, entry), List.of());
        return new RuleEngine.Await<>(new Query(entry.orElseThrow()));
    }
    /** Resolve a live issuer entry without rewriting a legacy ledger merely to qualify its query. */
    public static RuleEngine.Outcome<EffectState> resolveIssued(EffectState state, String issuer, Handle handle) {
        handle.requireIssuer(issuer); var canonical = handle.issuedBy(issuer);
        var entry = find(state, canonical).map(value -> issued(value, issuer));
        if (entry.isEmpty() || entry.orElseThrow().phase() == Phase.FIXED || state.buffs().timeMicros() >= canonical.expiresAt())
            return new RuleEngine.Local<>(state, local(state, canonical, entry), List.of());
        return new RuleEngine.Await<>(new Query(entry.orElseThrow()));
    }
    private static Entry issued(Entry entry, String issuer) {
        entry.handle().requireIssuer(issuer);
        return new Entry(entry.handle().issuedBy(issuer), entry.phase(), entry.last(), entry.revision(), entry.reason());
    }
    /** Only issuer qualification may differ from the exact revision which the world observed. */
    public static RuleEngine.Local<EffectState> resolvedIssued(EffectState state, String issuer, Query query, Observed receipt) {
        query.expected().handle().requireIssuer(issuer);
        if (!query.expected().handle().issuer().equals(Optional.of(issuer)))
            throw new IllegalArgumentException("Coordinated attachment query needs its explicit issuer");
        if (!query.equals(receipt.query())) throw new IllegalArgumentException("Attachment receipt differs from its issued query");
        var found = find(state, query.expected().handle());
        if (found.isEmpty()) return resolved(state, query, receipt);
        var canonical = issued(found.orElseThrow(), issuer);
        if (!canonical.equals(query.expected()))
            return new RuleEngine.Local<>(state, local(state, query.expected().handle(), Optional.of(canonical)), List.of());
        var entries = new TreeMap<>(state.attachments()); entries.put(canonical.handle().id(), canonical);
        var result = resolved(state.withAttachments(entries), query, receipt);
        // A missing/wrong-dimension observation must not itself rewrite a legacy entry.
        return result.state().attachments().equals(entries) ? new RuleEngine.Local<>(state, result.result(), result.emitted()) : result;
    }
    public static RuleEngine.Local<EffectState> resolved(EffectState state, Query issued, Observed receipt) {
        if (!issued.equals(receipt.query())) throw new IllegalArgumentException("Attachment receipt differs from its issued query");
        var h = issued.expected().handle(); var entry = find(state, h);
        if (entry.isEmpty() || !entry.orElseThrow().equals(issued.expected()) || state.buffs().timeMicros() >= h.expiresAt())
            return new RuleEngine.Local<>(state, local(state, h, entry), List.of());
        var entity = receipt.entity(); var entries = new TreeMap<>(state.attachments());
        Outcome outcome; Optional<WorldPosition> point = Optional.empty();
        switch (entity.outcome()) {
            case AVAILABLE -> {
                var observation = entity.requireObservation(); point = Optional.of(h.position(observation)); outcome = Outcome.RESOLVED;
                entries.put(h.id(), entry.orElseThrow().observe(Phase.FOLLOWING, observation, "resolved"));
            }
            case REPLACED -> { entries.remove(h.id()); outcome = Outcome.REPLACED; }
            case WRONG_DIMENSION -> outcome = Outcome.WRONG_DIMENSION;
            case MISSING -> outcome = entry.orElseThrow().phase() == Phase.WAITING ? Outcome.WAITING : Outcome.MISSING;
            default -> throw new AssertionError(entity.outcome());
        }
        return new RuleEngine.Local<>(state.withAttachments(entries), new Resolution(h, outcome, point, entity), List.of());
    }
}
