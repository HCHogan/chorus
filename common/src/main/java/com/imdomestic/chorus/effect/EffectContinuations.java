package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.BuffRules;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Deferred DSL bodies are pinned definition references plus immutable lexical data, never suspended Java stacks. */
public final class EffectContinuations {
    public static final String EVENT = "chorus:internal/continuation";
    private EffectContinuations() {}
    public enum Lifetime { SOURCE, DETACHED }
    public record Pending(String id, String definition, String version, RuleEngine.Payload scope, RuleEngine.Event cause,
            Map<String, RuleEngine.ActionResult> bindings, Optional<EffectTimers.Owner> owner) implements RuleEngine.Payload {
        public Pending {
            Objects.requireNonNull(id); Objects.requireNonNull(definition); Objects.requireNonNull(version);
            Objects.requireNonNull(scope); Objects.requireNonNull(cause); bindings = Map.copyOf(bindings); Objects.requireNonNull(owner);
            if (id.isBlank() || definition.isBlank() || version.isBlank() || !(scope instanceof EffectSource || scope instanceof BuffRules.Scope || scope instanceof com.imdomestic.chorus.effect.ability.AbilityUse.Scope || scope instanceof com.imdomestic.chorus.effect.weapon.WeaponFire.Scope)) {
                throw new IllegalArgumentException("Invalid continuation identity or source");
            }
            owner.ifPresent(value -> {
                if (!value.equals(EffectTimers.Owner.of(scope))) throw new IllegalArgumentException("Continuation owner differs from captured scope");
            });
            if (bindings.values().stream().anyMatch(value -> value instanceof RuleEngine.RetainedResult)) {
                throw new IllegalArgumentException("Retained accounting receipts cannot cross continuation frames");
            }
        }
        public boolean active(EffectState state) { return owner.map(value -> value.active(state.sources(), state.buffs())).orElse(true); }
    }
    public static RuleEngine.Context context(RuleEngine.Context context) {
        if (!(context.scope() instanceof Pending pending)) return context;
        var now = context.event();
        var event = new RuleEngine.Event(now.id(), now.root(), now.parent(), now.timeMicros(), pending.cause().signal());
        return new RuleEngine.Context(event, context.ruleInstance(), pending.scope(), context.bindings(), context.operation(), context.retainedResults(), context.pendingCommand());
    }
}
