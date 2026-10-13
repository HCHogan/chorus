package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.ability.AbilityDefinition;

/** Preserve the two original enum values under their original storage ID. */
public enum LegacyAbilityCostFromV1 {
    DEFINITION, BASE_SELECTION;
    public AbilityDefinition.CostFrom current() { return AbilityDefinition.CostFrom.valueOf(name()); }
}
