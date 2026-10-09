package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.stat.*;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The actual hit's resolved profiles. Native gates are preserved as a proportional admitted budget. */
public record DamageBasis(Optional<CalculationProfile.Result> outgoing, Optional<CalculationProfile.Result> defense) {
    public static final DamageBasis EMPTY = new DamageBasis(Optional.empty(), Optional.empty());
    public DamageBasis {
        Objects.requireNonNull(outgoing); Objects.requireNonNull(defense);
        outgoing.ifPresent(DamageBasis::damage); defense.ifPresent(DamageBasis::damage);
    }
    private static void damage(CalculationProfile.Result result) {
        if (!result.output().unit().equals(Unit.DAMAGE) || !result.inputs().base().unit().equals(Unit.DAMAGE)) throw new IllegalArgumentException("Damage basis requires damage profiles");
        Numbers.nonnegative(result.output().value(), "damage basis output");
        Numbers.nonnegative(result.inputs().base().value(), "damage basis input");
    }
    /** Full counterfactual traces, not finalDamage / guessedPrecisionMultiplier. */
    public record Suppression(Set<String> factors, double multiplier, Optional<CalculationProfile.Result> outgoing, Optional<CalculationProfile.Result> defense) {
        public Suppression {
            factors = Set.copyOf(factors); Numbers.nonnegative(multiplier, "factor suppression multiplier");
            Objects.requireNonNull(outgoing); Objects.requireNonNull(defense);
        }
    }
    public Suppression excluding(Set<String> factors) {
        if (outgoing.isEmpty() || outgoing.orElseThrow().trace().factors().stream().noneMatch(f -> !f.omitted() && factors.contains(f.factor()))) {
            return new Suppression(factors, 1, Optional.empty(), Optional.empty());
        }
        var original = outgoing.orElseThrow(); var projected = original.withoutFactors(factors); damage(projected);
        double ratio = ratio(projected.output().value(), original.output().value());
        Optional<CalculationProfile.Result> projectedDefense = defense.map(value -> value.withBase(new Measure(value.inputs().base().value() * ratio, Unit.DAMAGE)));
        projectedDefense.ifPresent(DamageBasis::damage);
        double layerRatio = projectedDefense.map(value -> ratio(value.output().value(), defense.orElseThrow().output().value())).orElse(ratio);
        return new Suppression(factors, layerRatio, Optional.of(projected), projectedDefense);
    }
    private static double ratio(double projected, double actual) {
        if (actual <= 0) throw new IllegalArgumentException("Cannot project a positive shield budget from a zero damage basis");
        return Numbers.nonnegative(projected / actual, "projected damage ratio");
    }
}
