package com.imdomestic.chorus.effect.ability;

import com.imdomestic.chorus.effect.data.*;
import java.util.*;

/** Instant action kind. The chosen definition and numeric parameters are pinned at acceptance. */
public record AbilityDefinition(String id, String slot, Optional<Cost> cost, Condition condition,
        Set<String> tags, Map<String, Parameter> parameters, List<EffectProgram.Step> onUse, Map<String, Effect> effects, CostFrom costFrom) {
    /** Only the final resolved definition chooses the payment policy; BASE_SELECTION reads its original selection's declared cost. */
    public enum CostFrom { DEFINITION, BASE_SELECTION }
    public AbilityDefinition {
        id(id); id(slot); Objects.requireNonNull(cost); Objects.requireNonNull(condition); Objects.requireNonNull(costFrom);
        tags = Set.copyOf(tags); tags.forEach(AbilityDefinition::id);
        parameters = Collections.unmodifiableMap(new TreeMap<>(parameters)); parameters.keySet().forEach(com.imdomestic.chorus.effect.EffectTimers::localName);
        onUse = List.copyOf(onUse);
        effects = com.imdomestic.chorus.effect.EffectParameters.copy(effects);
    }
    public AbilityDefinition(String id, String slot, Optional<Cost> cost, Condition condition,
            Set<String> tags, Map<String, Parameter> parameters, List<EffectProgram.Step> onUse) {
        this(id, slot, cost, condition, tags, parameters, onUse, Map.of(), CostFrom.DEFINITION);
    }
    public AbilityDefinition(String id, String slot, Optional<Cost> cost, Condition condition,
            Set<String> tags, Map<String, Parameter> parameters, List<EffectProgram.Step> onUse, Map<String, Effect> effects) {
        this(id, slot, cost, condition, tags, parameters, onUse, effects, CostFrom.DEFINITION);
    }
    public boolean hasPayment() { return cost.isPresent() || costFrom == CostFrom.BASE_SELECTION; }
    /** Static selection configuration, independent of the parameters evaluated for each accepted cast. */
    public record Effect(String bundle, Set<String> tags, Map<String, com.imdomestic.chorus.stat.Measure> parameters) {
        public Effect {
            id(bundle); tags = Set.copyOf(tags); tags.forEach(AbilityDefinition::id);
            parameters = com.imdomestic.chorus.effect.EffectParameters.copy(parameters);
        }
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
