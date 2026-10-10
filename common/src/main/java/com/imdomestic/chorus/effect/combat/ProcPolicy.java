package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectTimers;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Content-declared reaction exclusions. No implicit depth, ancestor or once-per-root restriction. */
public record ProcPolicy(Set<String> deny) {
    public static final ProcPolicy ALLOW = new ProcPolicy(Set.of());
    public ProcPolicy {
        deny = Set.copyOf(deny);
        for (String key : deny) if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid proc key: " + key);
    }
    public boolean allows(String key) { return !deny.contains(key); }
    public static ProcPolicy from(RuleEngine.Payload payload) {
        return EffectTimers.event(payload).map(EffectEvent::proc).orElse(ALLOW);
    }
    public enum Inherit { FRESH, EVENT }
    /** Each derived attack declares whether to start fresh or retain the triggering event's exclusions. */
    public record Spec(Set<String> deny, Inherit inherit) {
        public static final Spec DEFAULT = new Spec(Set.of(), Inherit.FRESH);
        public Spec { deny = new ProcPolicy(deny).deny(); Objects.requireNonNull(inherit); }
        public ProcPolicy resolve(RuleEngine.Payload event) {
            if (inherit == Inherit.FRESH) return new ProcPolicy(deny);
            var result = new HashSet<>(ProcPolicy.from(event).deny()); result.addAll(deny); return new ProcPolicy(result);
        }
    }
}
