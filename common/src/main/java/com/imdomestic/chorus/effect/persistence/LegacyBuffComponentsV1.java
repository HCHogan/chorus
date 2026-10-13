package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.buff.BuffComponents;
import com.imdomestic.chorus.effect.data.DamageSnapshot;
import com.imdomestic.chorus.effect.target.*;
import java.util.*;

/** Exact six-field component state, retaining all old values and adding no invented observation. */
public record LegacyBuffComponentsV1(Map<String, Double> numbers, Map<String, Set<String>> sets, Map<String, String> references,
        Map<String, Targets.Identities> targetSets, Map<String, Optional<WorldPosition>> positions,
        Map<String, Optional<DamageSnapshot>> damageSnapshots) {
    public LegacyBuffComponentsV1 { new BuffComponents(numbers, sets, references, targetSets, positions, damageSnapshots); }
    public BuffComponents current() { return new BuffComponents(numbers, sets, references, targetSets, positions, damageSnapshots); }
}
