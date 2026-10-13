package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.ability.AbilityDefinition;
import com.imdomestic.chorus.effect.data.*;
import java.util.*;

/** Exact original declaration shape: old abilities never selected another slot's cost. */
public record LegacyAbilityDefinitionV1(String id, String slot, Optional<AbilityDefinition.Cost> cost, Condition condition,
        Set<String> tags, Map<String, AbilityDefinition.Parameter> parameters, List<EffectProgram.Step> onUse,
        Map<String, AbilityDefinition.Effect> effects, AbilityDefinition.CostFrom costFrom, Optional<String> rechargeResource,
        Optional<String> rechargeYieldProfile) {
    public AbilityDefinition current() {
        return new AbilityDefinition(id, slot, cost, condition, tags, parameters, onUse, effects, costFrom, rechargeResource, rechargeYieldProfile);
    }
}
