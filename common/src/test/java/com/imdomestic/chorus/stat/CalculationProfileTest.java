package com.imdomestic.chorus.stat;

import static com.imdomestic.chorus.stat.NumericContribution.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CalculationProfileTest {
    private static final double EPS = 1e-10;

    static NumericContribution c(String id, String stage, String group, NumericContribution.Operation op,
            double value, Unit unit, String basis, String family, String source, int priority) {
        return new NumericContribution(id, stage, group, op, new Measure(value, unit), basis, family,
                new NumericContribution.Source("test:" + family, source, "test fixture", NumericContribution.Confidence.MEASURED), priority);
    }

    static NumericContribution delta(String id, String group, double value) {
        return c(id, "damage", group, MULTIPLY, value, Unit.DELTA, "", id, id, 0);
    }

    static CalculationProfile damage(NumericGroup group) {
        return new CalculationProfile("test:damage", "1", Unit.DAMAGE, List.of(new CalculationStep.Apply("damage", MULTIPLY, group)));
    }

    @Test void reductionsKeepDeltaConventionAndNegativeMaximum() {
        assertEquals(.375, Reduction.PRODUCT.reduce(List.of(.10, .25)).orElseThrow(), EPS);
        assertEquals(-.40, Reduction.MAX.reduce(List.of(-.40)).orElseThrow(), EPS);
        assertTrue(Reduction.MAX.reduce(List.of()).isEmpty());
        assertEquals(.3625, Reduction.RESIST.reduce(List.of(.25, .15)).orElseThrow(), EPS);
    }

    @Test void percentBasisIsAnEarlierNamedStage() {
        for (String basis : List.of("base", "after_flat")) {
            var profile = new CalculationProfile("test:stat", "1", Unit.STAT_POINT, List.of(
                    new CalculationStep.Apply("after_flat", ADD, NumericGroup.leaf("flat", Reduction.SUM)),
                    new CalculationStep.Apply("percent", BASE_PERCENT, NumericGroup.leaf("percent", Reduction.SUM), basis)));
            var result = profile.calculate(new Measure(100, Unit.STAT_POINT), List.of(
                    c("flat", "after_flat", "flat", ADD, 50, Unit.STAT_POINT, "", "flat", "one", 0),
                    c("percent", "percent", "percent", BASE_PERCENT, .20, Unit.DELTA, basis, "percent", "one", 0)));
            assertEquals(basis.equals("base") ? 170 : 180, result.output().value(), EPS);
            assertEquals(150, result.trace().stages().get("after_flat").value());
        }
    }

    @Test void slowAppliesBeforeStatCapAndCurveChangesUnits() {
        var curve = new Curve.Table(new TreeMap<>(Map.of(0.0, 5.0, 100.0, 1.0)), Curve.Interpolation.LINEAR, Curve.Boundary.ERROR);
        var profile = new CalculationProfile("test:reload", "1", Unit.STAT_POINT, List.of(
                new CalculationStep.Apply("slow", MULTIPLY, NumericGroup.leaf("slow", Reduction.PRODUCT)),
                new CalculationStep.Clamp("cap", 0, 100), new CalculationStep.Transform("seconds", curve, Unit.SECOND)));
        var result = profile.calculate(new Measure(160, Unit.STAT_POINT), List.of(
                c("slow", "slow", "slow", MULTIPLY, -.75, Unit.DELTA, "", "slow", "one", 0)));
        assertEquals(40, result.trace().stages().get("cap").value());
        assertEquals(new Measure(3.4, Unit.SECOND), result.output());
    }

    @Test void nestedExclusiveMeleeGroupIsInsideAdditiveGroup() {
        var group = new NumericGroup("melee", Reduction.SUM, Map.of(), List.of(NumericGroup.leaf("exclusive", Reduction.MAX)));
        var result = damage(group).calculate(new Measure(100, Unit.DAMAGE), List.of(
                delta("exotic", "melee", 3), delta("shotgun", "melee/exclusive", 1.5), delta("frozen", "melee/exclusive", 2.2)));
        assertEquals(620, result.output().value(), EPS);
        assertFalse(result.trace().contributions().stream().filter(c -> c.contribution().id().equals("shotgun")).findFirst().orElseThrow().selected());
    }

    @Test void snapshotAndLiveContributionsMeetBeforeMaxReduction() {
        var frozen = List.of(delta("cast", "empowering", .20));
        var all = new ArrayList<>(frozen);
        all.add(delta("hit", "empowering", .30));
        var result = damage(NumericGroup.leaf("empowering", Reduction.MAX)).calculate(new Measure(100, Unit.DAMAGE), all);
        assertEquals(130, result.output().value(), EPS);
        assertEquals(.20, frozen.getFirst().amount().value());
    }

    @Test void familyPoliciesDistinguishPriorityCopiesAndIndependentInstances() {
        var group = new NumericGroup("buffs", Reduction.PRODUCT,
                Map.of("empowering", FamilyPolicy.of(FamilyPolicy.Kind.PRIORITY),
                        "copies", FamilyPolicy.of(FamilyPolicy.Kind.MAX_PER_SOURCE)), List.of());
        var values = List.of(
                c("radiant", "damage", "buffs", MULTIPLY, .30, Unit.DELTA, "", "empowering", "a", 0),
                c("well", "damage", "buffs", MULTIPLY, .25, Unit.DELTA, "", "empowering", "b", 1),
                c("copy1", "damage", "buffs", MULTIPLY, .10, Unit.DELTA, "", "copies", "one", 0),
                c("copy2", "damage", "buffs", MULTIPLY, .20, Unit.DELTA, "", "copies", "one", 0),
                c("independent1", "damage", "buffs", MULTIPLY, .50, Unit.DELTA, "", "applications", "same", 0),
                c("independent2", "damage", "buffs", MULTIPLY, .50, Unit.DELTA, "", "applications", "same", 0));
        assertEquals(100 * 1.25 * 1.2 * 1.5 * 1.5, damage(group).calculate(new Measure(100, Unit.DAMAGE), values).output().value(), EPS);
    }

    @Test void resistanceCopiesUseCountTableBeforeIndependentMultiplication() {
        var group = new NumericGroup("resist", Reduction.RESIST,
                Map.of("mod", new FamilyPolicy(FamilyPolicy.Kind.COUNT_TABLE, Map.of(1, .15, 2, .25, 3, .30))), List.of());
        var profile = new CalculationProfile("test:resistance", "1", Unit.DAMAGE,
                List.of(new CalculationStep.Apply("defense", RESIST, group)));
        var values = List.of(
                c("a", "defense", "resist", RESIST, .15, Unit.RESISTANCE, "", "mod", "a", 0),
                c("b", "defense", "resist", RESIST, .15, Unit.RESISTANCE, "", "mod", "b", 0),
                c("c", "defense", "resist", RESIST, .15, Unit.RESISTANCE, "", "other", "c", 0));
        assertEquals(63.75, profile.calculate(new Measure(100, Unit.DAMAGE), values).output().value(), EPS);
    }

    @Test void inputPermutationProducesIdenticalTraceAndOutput() {
        var values = new ArrayList<>(List.of(delta("b", "all", .10), delta("a", "all", .25), delta("c", "all", -.30)));
        var profile = damage(NumericGroup.leaf("all", Reduction.PRODUCT));
        var expected = profile.calculate(new Measure(100, Unit.DAMAGE), values);
        Collections.reverse(values);
        assertEquals(expected, profile.calculate(new Measure(100, Unit.DAMAGE), values));
    }

    @Test void badUnitsStagesGroupsBasisAndDuplicateSnapshotInputsFail() {
        var profile = damage(NumericGroup.leaf("all", Reduction.PRODUCT));
        assertThrows(IllegalArgumentException.class, () -> profile.calculate(new Measure(100, Unit.SECOND), List.of()));
        assertThrows(IllegalArgumentException.class, () -> profile.calculate(new Measure(100, Unit.DAMAGE), List.of(delta("a", "typo", .1))));
        var wrongStage = c("a", "typo", "all", MULTIPLY, .1, Unit.DELTA, "", "a", "a", 0);
        assertThrows(IllegalArgumentException.class, () -> profile.calculate(new Measure(100, Unit.DAMAGE), List.of(wrongStage)));
        var wrongUnit = c("a", "damage", "all", MULTIPLY, 1.1, Unit.MULTIPLIER, "", "a", "a", 0);
        assertThrows(IllegalArgumentException.class, () -> profile.calculate(new Measure(100, Unit.DAMAGE), List.of(wrongUnit)));
        var one = delta("same", "all", .1);
        assertThrows(IllegalArgumentException.class, () -> profile.calculate(new Measure(100, Unit.DAMAGE), List.of(one, one)));
        assertThrows(IllegalArgumentException.class, () -> new CalculationProfile("test:bad", "1", Unit.DAMAGE,
                List.of(new CalculationStep.Apply("percent", BASE_PERCENT, NumericGroup.leaf("p", Reduction.SUM), "future"))));
    }

    @Test void exactCurveDoesNotSilentlyInterpolateOrReturnZero() {
        var points = new TreeMap<>(Map.of(0.0, 1.0, 100.0, 2.75));
        var exact = new Curve.Table(points, Curve.Interpolation.EXACT, Curve.Boundary.ERROR);
        assertThrows(IllegalArgumentException.class, () -> exact.evaluate(50));
        assertThrows(IllegalArgumentException.class, () -> exact.evaluate(101));
        points.put(50.0, 2.0);
        assertThrows(IllegalArgumentException.class, () -> exact.evaluate(50)); // Defensive copy.
        assertEquals(2.75, exact.evaluate(100));
    }

    @Test void nonfiniteValuesAndWrongReducerUnitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Measure(Double.NaN, Unit.DAMAGE));
        assertThrows(IllegalArgumentException.class, () -> new Measure(Double.POSITIVE_INFINITY, Unit.DAMAGE));
        assertThrows(IllegalArgumentException.class, () -> new CalculationProfile("test:bad", "1", Unit.DAMAGE,
                List.of(new CalculationStep.Apply("flat", ADD, NumericGroup.leaf("all", Reduction.PRODUCT)))));
    }
}
