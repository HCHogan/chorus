package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.ImpactData;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Facts captured by an adapter. Query context also uses this shape; missing measurements are not zero. */
public record EffectEvent(String actor, String victim, BuffInstance.Origin source, Set<String> tags, Map<String, Measure> numbers,
        Map<String, Boolean> flags, Map<String, String> references, ImpactData impact,
        java.util.Optional<com.imdomestic.chorus.effect.data.ReactionSnapshot> reactions,
        com.imdomestic.chorus.effect.combat.ProcPolicy proc,
        java.util.Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observedBuffs)
        implements RuleEngine.Payload {
    /** Typed payloads may expose the common DSL event context without discarding their richer receipt. */
    public interface Carrier extends RuleEngine.Payload { EffectEvent event(); }
    public EffectEvent {
        Objects.requireNonNull(actor); Objects.requireNonNull(victim); Objects.requireNonNull(source);
        tags = Set.copyOf(tags); numbers = Map.copyOf(numbers); flags = Map.copyOf(flags); references = Map.copyOf(references);
        Objects.requireNonNull(impact);
        Objects.requireNonNull(reactions);
        Objects.requireNonNull(proc); Objects.requireNonNull(observedBuffs);
        if (reactions.isPresent() && !reactions.orElseThrow().owner().equals(source.owner())) throw new IllegalArgumentException("Foreign reaction owner");
    }
    public EffectEvent withObservedBuffs(java.util.Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observation) {
        return new EffectEvent(actor, victim, source, tags, numbers, flags, references, impact, reactions, proc, observation);
    }
    public EffectEvent(String actor, String victim, BuffInstance.Origin source, Set<String> tags, Map<String, Measure> numbers,
            Map<String, Boolean> flags, Map<String, String> references, ImpactData impact,
            java.util.Optional<com.imdomestic.chorus.effect.data.ReactionSnapshot> reactions,
            com.imdomestic.chorus.effect.combat.ProcPolicy proc) {
        this(actor, victim, source, tags, numbers, flags, references, impact, reactions, proc, java.util.Optional.empty());
    }
    public EffectEvent(String actor, String victim, BuffInstance.Origin source, Set<String> tags, Map<String, Measure> numbers,
            Map<String, Boolean> flags, Map<String, String> references, ImpactData impact,
            java.util.Optional<com.imdomestic.chorus.effect.data.ReactionSnapshot> reactions) {
        this(actor, victim, source, tags, numbers, flags, references, impact, reactions, com.imdomestic.chorus.effect.combat.ProcPolicy.ALLOW);
    }
    public EffectEvent(String actor, String victim, BuffInstance.Origin source, Set<String> tags, Map<String, Measure> numbers,
            Map<String, Boolean> flags, Map<String, String> references, ImpactData impact) {
        this(actor, victim, source, tags, numbers, flags, references, impact, java.util.Optional.empty());
    }
    public EffectEvent(String actor, String victim, BuffInstance.Origin source, Set<String> tags, Map<String, Measure> numbers,
            Map<String, Boolean> flags, Map<String, String> references) {
        this(actor, victim, source, tags, numbers, flags, references, ImpactData.EMPTY);
    }
    public EffectEvent(String actor, String victim, BuffInstance.Origin source, Set<String> tags, Map<String, Measure> numbers) {
        this(actor, victim, source, tags, numbers, Map.of(), Map.of());
    }
}
