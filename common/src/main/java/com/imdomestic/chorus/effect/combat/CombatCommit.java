package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;

/** Confirmed native combat writes, reconciled once before dependent actions or queued facts. */
public record CombatCommit(EffectState before, EffectState after) implements RuleEngine.Payload {
    public CombatCommit {
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        if (before.buffs().timeMicros() != after.buffs().timeMicros() || !before.damageGroups().keySet().equals(after.damageGroups().keySet()))
            throw new IllegalArgumentException("Combat writes cannot advance time or create/close attack groups");
        var allowed = before.withBuffs(after.buffs());
        for (var group : after.damageGroups().values()) {
            if (!before.damageGroups().get(group.handle().id()).handle().equals(group.handle())) throw new IllegalArgumentException("Combat writes changed group identity");
            allowed = allowed.withDamageGroup(group);
        }
        if (!allowed.equals(after)) throw new IllegalArgumentException("Combat writes changed unrelated effect state");
    }
    public boolean changed() { return !before.equals(after); }
    public EffectState apply(EffectState current) {
        if (!current.equals(before)) throw new IllegalStateException("Stale native combat boundary");
        return after;
    }
}
