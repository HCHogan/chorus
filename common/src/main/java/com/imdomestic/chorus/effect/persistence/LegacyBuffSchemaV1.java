package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.buff.BuffSchema;
import com.imdomestic.chorus.stat.Measure;
import java.util.*;

/** Exact six-field schema. Existing components do not acquire undeclared entity references. */
public record LegacyBuffSchemaV1(Map<String, Measure> numbers, Set<String> sets, Set<String> references,
        Set<String> targetSets, Set<String> positions, Set<String> damageSnapshots) {
    public LegacyBuffSchemaV1 { new BuffSchema(numbers, sets, references, targetSets, positions, damageSnapshots); }
    public BuffSchema current() { return new BuffSchema(numbers, sets, references, targetSets, positions, damageSnapshots); }
}
