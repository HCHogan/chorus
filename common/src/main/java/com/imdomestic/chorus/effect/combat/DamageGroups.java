package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Explicit attack membership and receipt-confirmed, one-use buff eligibility. Never a batch or root. */
public final class DamageGroups {
    public static final String DUE = "chorus:internal/damage_group_due";
    private DamageGroups() {}
    public record Handle(String id, String owner, long startedAt, long dueAt) implements RuleEngine.ActionResult, RuleEngine.Payload {
        public Handle {
            Objects.requireNonNull(id); Objects.requireNonNull(owner);
            if (id.isBlank() || owner.isBlank() || startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid damage group identity or lifetime");
        }
        public String timerId() { return id + "/expiry"; }
        public String reference() { return "group/" + owner.length() + ":" + owner + "/" + id; }
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
        if (group == null || !group.handle().equals(handle) || state.buffs().timeMicros() >= handle.dueAt())
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
        if (group != null && !group.handle().equals(handle)) throw new IllegalArgumentException("Damage group identity differs");
        return new RuleEngine.Local<>(state.withoutDamageGroup(handle.id()).cancel(handle.timerId()), RuleEngine.Empty.INSTANCE, List.of());
    }
}
