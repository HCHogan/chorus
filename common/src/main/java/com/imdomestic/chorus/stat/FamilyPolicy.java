package com.imdomestic.chorus.stat;

import java.util.Map;
import java.util.Objects;

/** An effect family is not inherently unique. Independent applications may all contribute. */
public record FamilyPolicy(Kind kind, Map<Integer, Double> countValues) {
    public enum Kind { INDEPENDENT, UNIQUE, MAX, MAX_PER_SOURCE, PRIORITY, COUNT_TABLE }
    public static final FamilyPolicy INDEPENDENT = new FamilyPolicy(Kind.INDEPENDENT, Map.of());

    public FamilyPolicy {
        Objects.requireNonNull(kind);
        countValues = Map.copyOf(countValues);
        if ((kind == Kind.COUNT_TABLE) != !countValues.isEmpty()) {
            throw new IllegalArgumentException("Only COUNT_TABLE requires count values");
        }
        countValues.forEach((count, value) -> {
            if (count < 1) throw new IllegalArgumentException("Family count must be positive");
            Numbers.finite(value, "count table value");
        });
    }

    public static FamilyPolicy of(Kind kind) { return new FamilyPolicy(kind, Map.of()); }
}
