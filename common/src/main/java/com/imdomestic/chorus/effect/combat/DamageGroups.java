package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Explicit attack membership and receipt-confirmed, one-use buff eligibility. Never a batch or root. */
public final class DamageGroups {
    public static final String DUE = "chorus:internal/damage_group_due";
    private DamageGroups() {}
    public enum ReferenceFormat { LEGACY, ISSUED_V1 }
    public record Handle(String id, String owner, long startedAt, long dueAt, Optional<String> issuer,
            ReferenceFormat referenceFormat) implements RuleEngine.ActionResult, RuleEngine.Payload {
        public Handle {
            Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(issuer); issuer.ifPresent(value -> new ResourceAuthority.Lease(value, 1));
            Objects.requireNonNull(referenceFormat);
            if (id.isBlank() || owner.isBlank() || startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid damage group identity or lifetime");
            if (referenceFormat == ReferenceFormat.ISSUED_V1 && issuer.isEmpty())
                throw new IllegalArgumentException("Issued damage group references require an issuer");
        }
        public Handle(String id, String owner, long startedAt, long dueAt, Optional<String> issuer) {
            this(id, owner, startedAt, dueAt, issuer, ReferenceFormat.LEGACY);
        }
        public Handle(String id, String owner, long startedAt, long dueAt) { this(id, owner, startedAt, dueAt, Optional.empty()); }
        public static Handle issued(String id, String owner, long startedAt, long dueAt, String issuer) {
            return new Handle(id, owner, startedAt, dueAt, Optional.of(issuer), ReferenceFormat.ISSUED_V1);
        }
        public Handle issuedBy(String domain) {
            return issuer.isPresent() ? this : new Handle(id, owner, startedAt, dueAt, Optional.of(domain), referenceFormat);
        }
        public void requireIssuer(String domain) {
            if (issuer.isPresent() && !issuer.orElseThrow().equals(domain)) throw new IllegalArgumentException("Damage group belongs to another issuing domain");
        }
        /** Legacy aliases are compatible; choosing the issuing domain remains the coordinator's responsibility. */
        public boolean matches(Handle other) {
            return other != null && id.equals(other.id) && owner.equals(other.owner) && startedAt == other.startedAt && dueAt == other.dueAt
                    && referenceFormat == other.referenceFormat
                    && (issuer.isEmpty() || other.issuer.isEmpty() || issuer.equals(other.issuer));
        }
        public String timerId() { return id + "/expiry"; }
        /** The stored format keeps facts continuous with existing deduplication sets, including old qualified handles. */
        public String reference() {
            if (referenceFormat == ReferenceFormat.LEGACY) return "group/" + owner.length() + ":" + owner + "/" + id;
            String domain = issuer.orElseThrow();
            return "group-issued/" + domain.length() + ":" + domain + "/" + owner.length() + ":" + owner
                    + "/" + id.length() + ":" + id + "/" + startedAt + "/" + dueAt;
        }
    }
    public record Group(Handle handle, Map<BuffInstance.Key, BuffInstance> grants) {
        public Group {
            Objects.requireNonNull(handle); grants = Collections.unmodifiableMap(new TreeMap<>(grants));
            for (var entry : grants.entrySet()) if (!entry.getKey().equals(entry.getValue().key()) || !entry.getKey().holder().equals(handle.owner()))
                throw new IllegalArgumentException("Foreign damage group grant");
        }
        public Group retain(BuffInstance buff) {
            var copy = new TreeMap<>(grants); copy.putIfAbsent(buff.key(), buff); return new Group(handle, copy);
        }
    }
    /** Modifier-only view; the fact event has no authority to create or inherit an attack group. */
    public record Query(EffectEvent event, Optional<Handle> group) implements EffectEvent.Carrier {
        public Query { Objects.requireNonNull(event); Objects.requireNonNull(group); }
    }
    public static Group require(EffectState state, Handle handle) {
        var group = state.damageGroups().get(handle.id());
        if (group == null || !group.handle().matches(handle) || state.buffs().timeMicros() >= handle.dueAt())
            throw new IllegalArgumentException("Missing, closed or expired damage group");
        return group;
    }
    public static Optional<Group> forCommand(EffectState state, DamageCommand command) { return command.group().map(h -> require(state, h)); }
    public static RuleEngine.Local<EffectState> begin(EffectState state, Handle handle) {
        if (handle.startedAt() != state.buffs().timeMicros() || state.damageGroups().containsKey(handle.id()))
            throw new IllegalArgumentException("Duplicate damage group or wrong start boundary");
        state = state.withDamageGroup(new Group(handle, Map.of()));
        state = state.schedule(new EffectState.Timer(handle.timerId(), handle.dueAt(), 0, 1, new RuleEngine.Signal(DUE, handle), Optional.empty()));
        return new RuleEngine.Local<>(state, handle, List.of());
    }
    public static RuleEngine.Local<EffectState> close(EffectState state, Handle handle) {
        var group = state.damageGroups().get(handle.id());
        if (group != null && !group.handle().matches(handle)) throw new IllegalArgumentException("Damage group identity differs");
        return new RuleEngine.Local<>(state.withoutDamageGroup(handle.id()).cancel(handle.timerId()), RuleEngine.Empty.INSTANCE, List.of());
    }
}
