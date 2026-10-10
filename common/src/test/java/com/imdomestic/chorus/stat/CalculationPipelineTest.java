package com.imdomestic.chorus.stat;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.stat.NumericContribution.Operation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CalculationPipelineTest {
    static CalculationProfile multiplier(String id, String factor) {
        return new CalculationProfile(id, "v1", Unit.CHARGE, List.of(new CalculationStep.Apply("scale", MULTIPLY,
                NumericGroup.leaf("boost", Reduction.SUM), "", factor)));
    }
    static NumericContribution boost(double delta) { return CalculationProfileTest.c("boost", "scale", "boost", MULTIPLY, delta, Unit.DELTA, "", "boost", "source", 0); }
    @Test void typeChangingStagesKeepTheirOwnVersionsGroupsAndSources() {
        var stat = new CalculationProfile("test:stat", "stat-v2", Unit.STAT_POINT, List.of(
                new CalculationStep.Apply("perks", ADD, NumericGroup.leaf("reload", Reduction.SUM)), new CalculationStep.Clamp("cap", 0, 100)));
        var seconds = new CalculationProfile("test:seconds", "curve-v3", Unit.STAT_POINT, List.of(
                new CalculationStep.Transform("curve", new Curve.Polynomial(List.of(2.0, -.01), 0, 100, Curve.Boundary.ERROR), Unit.SECOND)));
        var pipeline = new CalculationPipeline(List.of(stat, seconds));
        var result = pipeline.calculate(new Measure(80, Unit.STAT_POINT), (p, base) -> p.calculate(base, p == stat ? List.of(
                CalculationProfileTest.c("perk", "perks", "reload", ADD, 60, Unit.STAT_POINT, "", "perk", "gun", 0)) : List.of()));
        assertEquals(new Measure(1, Unit.SECOND), result.output()); assertEquals(140, result.steps().getFirst().trace().stages().get("perks").value());
        assertEquals(new Measure(100, Unit.STAT_POINT), result.steps().getLast().inputs().base());
        assertEquals(List.of("stat-v2", "curve-v3"), result.steps().stream().map(s -> s.trace().version()).toList());
        assertEquals(Set.of(NumericContribution.Confidence.MEASURED), result.contributionConfidence());
        assertThrows(UnsupportedOperationException.class, () -> result.steps().clear());
    }
    @Test void replayRefeedsDownstreamClampAndNeverCallsTheOriginalCollector() {
        var gain = multiplier("test:gain", "test:gain_factor");
        var cap = new CalculationProfile("test:cap", "v1", Unit.CHARGE, List.of(new CalculationStep.Clamp("cap", 0, 1)));
        var calls = new AtomicInteger(); var pipeline = new CalculationPipeline(List.of(gain, cap));
        var result = pipeline.calculate(new Measure(.8, Unit.CHARGE), (p, base) -> { calls.incrementAndGet(); return p.calculate(base, p == gain ? List.of(boost(1)) : List.of()); });
        assertEquals(1, result.output().value()); assertEquals(.8, result.withoutFactors(Set.of("test:gain_factor")).output().value());
        assertEquals(.6, result.withBase(new Measure(.3, Unit.CHARGE)).output().value(), 1e-12);
        assertEquals(.3, result.withoutFactors(Set.of("test:gain_factor")).withBase(new Measure(.3, Unit.CHARGE)).output().value());
        assertEquals(2, calls.get()); assertEquals(1, result.output().value(), "original result is immutable");
    }
    @Test void repeatedProfileOccurrencesRemainDistinctAndZeroFactorsCanBeRemoved() {
        var profile = multiplier("test:gain", "test:factor"); var pipeline = new CalculationPipeline(List.of(profile, profile));
        var result = pipeline.calculate(new Measure(1, Unit.CHARGE), (p, base) -> p.calculate(base, List.of(boost(1))));
        assertEquals(4, result.output().value()); assertEquals(2, result.steps().size());
        assertEquals(1, result.withoutFactors(Set.of("test:factor")).output().value());
        var zero = pipeline.calculate(new Measure(1, Unit.CHARGE), (p, base) -> p.calculate(base, List.of(boost(-1))));
        assertEquals(0, zero.output().value()); assertEquals(1, zero.withoutFactors(Set.of("test:factor")).output().value(), "replay does not divide by a zero factor");
    }
    @Test void definitionAndResultUnitLinksAreValidatedBeforeCollection() {
        var a = multiplier("test:a", ""); var b = new CalculationProfile("test:b", "v1", Unit.SECOND, List.of());
        assertThrows(IllegalArgumentException.class, () -> new CalculationPipeline(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CalculationPipeline(List.of(a, b)));
        assertThrows(IllegalArgumentException.class, () -> CalculationPipeline.resolve(List.of("test:missing"), Map.of(a.id(), a)));
        var pipeline = new CalculationPipeline(List.of(a)); var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> pipeline.calculate(new Measure(1, Unit.SECOND), (p, base) -> { calls.incrementAndGet(); return p.calculate(base, List.of()); }));
        assertEquals(0, calls.get());
        var first = a.calculate(new Measure(1, Unit.CHARGE), List.of()); var wrong = a.calculate(new Measure(2, Unit.CHARGE), List.of());
        assertThrows(IllegalArgumentException.class, () -> new CalculationPipeline.Result(first.inputs().base(), List.of(first, wrong)));
    }
    @Test void collectorCannotSubstituteAnotherProfileOrDifferentInput() {
        var a = multiplier("test:a", ""); var b = multiplier("test:b", ""); var pipeline = new CalculationPipeline(List.of(a));
        assertThrows(IllegalArgumentException.class, () -> pipeline.calculate(new Measure(1, Unit.CHARGE), (p, base) -> b.calculate(base, List.of())));
        assertThrows(IllegalArgumentException.class, () -> pipeline.calculate(new Measure(1, Unit.CHARGE), (p, base) -> {
            var valid = p.calculate(base, List.of()); return new CalculationProfile.Result(new Measure(1, Unit.SECOND), valid.trace(), valid.inputs());
        }));
        assertThrows(IllegalArgumentException.class, () -> pipeline.calculate(new Measure(1, Unit.CHARGE), (p, base) -> p.calculate(new Measure(2, Unit.CHARGE), List.of())));
    }
}
