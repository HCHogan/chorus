package com.imdomestic.chorus.effect.ability;

import com.imdomestic.chorus.effect.data.*;
import java.util.*;

/** Instant action kind. The chosen definition and numeric parameters are pinned at acceptance. */
public record AbilityDefinition(String id, String slot, Optional<Cost> cost, Condition condition,
        Set<String> tags, Map<String, Parameter> parameters, List<EffectProgram.Step> onUse) {
    public AbilityDefinition {
        id(id); id(slot); Objects.requireNonNull(cost); Objects.requireNonNull(condition);
        tags = Set.copyOf(tags); tags.forEach(AbilityDefinition::id);
        parameters = Collections.unmodifiableMap(new TreeMap<>(parameters)); parameters.keySet().forEach(com.imdomestic.chorus.effect.EffectTimers::localName);
        onUse = List.copyOf(onUse);
    }
    public record Cost(String resource, Value amount, Optional<String> profile) {
        public Cost { id(resource); Objects.requireNonNull(amount); Objects.requireNonNull(profile); profile.ifPresent(AbilityDefinition::id); }
    }
    public record Parameter(Value value, Optional<String> profile) {
        public Parameter { Objects.requireNonNull(value); Objects.requireNonNull(profile); profile.ifPresent(AbilityDefinition::id); }
    }
    public record Replacement(String id, String slot, Optional<String> ability, String replaceWith, int priority, Condition condition) {
        public Replacement { com.imdomestic.chorus.effect.EffectTimers.localName(id); AbilityDefinition.id(slot); Objects.requireNonNull(ability); ability.ifPresent(AbilityDefinition::id); AbilityDefinition.id(replaceWith); Objects.requireNonNull(condition); }
    }
    public static void id(String id) { if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid ability id: " + id); }
}
