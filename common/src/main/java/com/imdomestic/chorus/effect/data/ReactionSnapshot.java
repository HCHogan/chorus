package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Immutable source-rule selection, independent of numeric contributions and attack credit. */
public record ReactionSnapshot(String owner, EffectProgram catalogue, List<EffectSource> sources) {
    public static final Set<String> EVENTS = Set.of("chorus:hit", "chorus:damage_taken", "chorus:shield_damaged",
            "chorus:shield_broken", "chorus:death_prevented", "chorus:death", "chorus:kill");
    public ReactionSnapshot {
        Objects.requireNonNull(owner); Objects.requireNonNull(catalogue); sources = List.copyOf(sources);
        if (sources.stream().anyMatch(s -> !s.holder().equals(owner))
                || sources.stream().map(EffectSource::instance).distinct().count() != sources.size())
            throw new IllegalArgumentException("Duplicate or foreign captured reaction source");
    }
    /** A version label alone cannot prove that two definitions have the same content. */
    public void requireCompatible(EffectProgram current) {
        if (!sources.isEmpty() && !catalogue.equals(current)) throw new IllegalArgumentException("Incompatible captured reaction catalogue");
    }
    public static Optional<ReactionSnapshot> from(RuleEngine.Event event) {
        if (!EVENTS.contains(event.signal().type())) return Optional.empty();
        return switch (event.signal().payload()) {
            case EffectEvent fact -> fact.reactions();
            case EffectEvent.Carrier carrier -> carrier.event().reactions();
            default -> Optional.empty();
        };
    }
}
