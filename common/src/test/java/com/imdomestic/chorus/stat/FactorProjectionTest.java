package com.imdomestic.chorus.stat;

import static com.imdomestic.chorus.stat.CalculationProfileTest.c;
import static com.imdomestic.chorus.stat.NumericContribution.Operation.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.DamageBasis;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FactorProjectionTest {
    static CalculationStep.Apply factor(String stage, String factor) { return new CalculationStep.Apply(stage, MULTIPLY, NumericGroup.leaf(stage, Reduction.MAX), "", factor); }
    static NumericContribution value(String stage, NumericContribution.Operation op, double value, Unit unit) { return c(stage, stage, stage, op, value, unit, "", stage, "source", 0); }
    static CalculationProfile profile(List<CalculationStep> steps) { return new CalculationProfile("test:factor", "1", Unit.DAMAGE, steps); }
    static CalculationProfile.Result hit(double base) {
        return profile(List.of(factor("precision", "chorus:precision"), new CalculationStep.Apply("flat", ADD, NumericGroup.leaf("flat", Reduction.SUM)), new CalculationStep.Clamp("cap", 0, 22)))
                .calculate(new Measure(base, Unit.DAMAGE), List.of(value("precision", MULTIPLY, 1, Unit.DELTA), value("flat", ADD, 5, Unit.DAMAGE)));
    }
    @Test void omissionReplaysLaterAddClampAndRoundingInsteadOfDividingFinalDamage() {
        var result = hit(10); assertEquals(22, result.output().value());
        var body = result.withoutFactors(Set.of("chorus:precision")); assertEquals(15, body.output().value());
        assertEquals(10, body.trace().stages().get("precision").value());
        assertEquals(new CalculationTrace.FactorTrace("chorus:precision", "precision", 2, true), body.trace().factors().getFirst());
        assertFalse(body.trace().contributions().stream().filter(c -> c.contribution().stage().equals("precision")).findFirst().orElseThrow().selected());
        var rounded = profile(List.of(factor("precision", "chorus:precision"), new CalculationStep.Round("round", CalculationStep.Rounding.FLOOR)))
                .calculate(new Measure(1.8, Unit.DAMAGE), List.of(value("precision", MULTIPLY, 1, Unit.DELTA)));
        assertEquals(3, rounded.output().value()); assertEquals(1, rounded.withoutFactors(Set.of("chorus:precision")).output().value());
    }
    @Test void percentBasisAndCurvesUseTheReplayedEarlierStages() {
        var result = profile(List.of(factor("precision", "chorus:precision"), new CalculationStep.Apply("extra", BASE_PERCENT, NumericGroup.leaf("extra", Reduction.SUM), "precision"),
                new CalculationStep.Transform("curve", new Curve.Polynomial(List.of(0.0, 0.0, 1.0), 0, 100, Curve.Boundary.ERROR), Unit.DAMAGE)))
                .calculate(new Measure(10, Unit.DAMAGE), List.of(value("precision", MULTIPLY, 1, Unit.DELTA), c("extra", "extra", "extra", BASE_PERCENT, .5, Unit.DELTA, "precision", "extra", "source", 0)));
        assertEquals(900, result.output().value()); assertEquals(225, result.withoutFactors(Set.of("chorus:precision")).output().value());
    }
    @Test void multipleStagesCanShareAFactorAndOmissionsComposeWithoutChangingOriginal() {
        var result = profile(List.of(factor("base_precision", "chorus:precision"), factor("bonus_precision", "chorus:precision"), factor("activity", "test:activity")))
                .calculate(new Measure(10, Unit.DAMAGE), List.of(value("base_precision", MULTIPLY, 1, Unit.DELTA), value("bonus_precision", MULTIPLY, .5, Unit.DELTA), value("activity", MULTIPLY, 2, Unit.DELTA)));
        var body = result.withoutFactors(Set.of("chorus:precision")); assertEquals(30, body.output().value());
        assertEquals(10, body.withoutFactors(Set.of("test:activity")).output().value()); assertEquals(90, result.output().value());
        assertEquals(90, result.withoutFactors(Set.of("test:unknown")).output().value());
        assertThrows(UnsupportedOperationException.class, () -> result.inputs().contributions().clear());
        assertThrows(UnsupportedOperationException.class, () -> body.inputs().omittedFactors().clear());
    }
    @Test void factorTraceDoesNotInferAMultiplierFromStageOutputOrZeroInput() {
        var result = hit(0); assertEquals(5, result.output().value()); assertEquals(2, result.trace().factors().getFirst().multiplier());
        assertEquals(5, result.withoutFactors(Set.of("chorus:precision")).output().value());
        var empty = profile(List.of(factor("precision", "chorus:precision"))).calculate(new Measure(10, Unit.DAMAGE), List.of());
        assertEquals(1, empty.trace().factors().getFirst().multiplier());
    }
    @Test void damageBasisReplaysDefenseWithTheAlreadyAdmittedNativeBudget() {
        var outgoing = hit(10); // Outgoing 22, without precision 15; native gates admitted 11.
        var defense = profile(List.of(new CalculationStep.Apply("flat", ADD, NumericGroup.leaf("flat", Reduction.SUM))))
                .calculate(new Measure(11, Unit.DAMAGE), List.of(value("flat", ADD, 4, Unit.DAMAGE)));
        var projection = new DamageBasis(Optional.of(outgoing), Optional.of(defense)).excluding(Set.of("chorus:precision"));
        assertEquals(15, projection.outgoing().orElseThrow().output().value());
        assertEquals(7.5, projection.defense().orElseThrow().inputs().base().value(), 1e-12);
        assertEquals(11.5 / 15, projection.multiplier(), 1e-12);
        assertEquals(15 / 22.0, new DamageBasis(Optional.of(outgoing), Optional.empty()).excluding(Set.of("chorus:precision")).multiplier());
        assertEquals(1, DamageBasis.EMPTY.excluding(Set.of("chorus:precision")).multiplier());
    }
    @Test void validationRejectsAmbiguousFactorStagesBadUnitsAndZeroBasisProjection() {
        assertThrows(IllegalArgumentException.class, () -> new CalculationStep.Apply("flat", ADD, NumericGroup.leaf("flat", Reduction.SUM), "", "chorus:precision"));
        assertThrows(IllegalArgumentException.class, () -> factor("precision", "typo"));
        assertThrows(IllegalArgumentException.class, () -> hit(10).withoutFactors(Set.of("typo")));
        assertThrows(IllegalArgumentException.class, () -> hit(10).withBase(new Measure(10, Unit.SECOND)));
        var zero = profile(List.of(factor("precision", "chorus:precision"))).calculate(new Measure(0, Unit.DAMAGE), List.of(value("precision", MULTIPLY, 1, Unit.DELTA)));
        assertThrows(IllegalArgumentException.class, () -> new DamageBasis(Optional.of(zero), Optional.empty()).excluding(Set.of("chorus:precision")));
    }
    @Test void factorDeclarationCodecRoundTripsAndLegacyProfilesHaveNoNamedFactors() {
        var definition = hit(10).inputs().profile();
        assertEquals(definition, StatCodecs.PROFILE.parse(JsonOps.INSTANCE, StatCodecs.PROFILE.encodeStart(JsonOps.INSTANCE, definition).getOrThrow()).getOrThrow());
        var legacy = profile(List.of(new CalculationStep.Apply("precision", MULTIPLY, NumericGroup.leaf("precision", Reduction.SUM))));
        assertTrue(legacy.calculate(new Measure(10, Unit.DAMAGE), List.of(value("precision", MULTIPLY, 1, Unit.DELTA))).trace().factors().isEmpty());
    }
}
