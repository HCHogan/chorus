package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.ImpactData;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RatioValueTest {
    static final Validation V = new Validation(Map.of(), Map.of(), false);
    static Value c(double n, Unit u) { return new Value.Constant(n, u); }
    static Evaluation evaluation(double range, double distance) {
        var origin = new BuffInstance.Origin("player", "perk", "weapon", "");
        var source = new EffectSource("perk", "test:source", "player", origin, Set.of());
        var event = new EffectEvent("player", "target", origin, Set.of(), Map.of("range", new Measure(range, Unit.METER)),
                Map.of(), Map.of(), new ImpactData(Map.of("distance", new Measure(distance, Unit.METER))));
        return new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(0, 0, Optional.empty(), 0,
                new RuleEngine.Signal("test:event", event)), "test:rule", source, Map.of()), Map.of(), Map.of());
    }
    @Test void equalUnitsProduceDimensionlessExactDecimalAndSignedRatios() {
        for (var u : List.of(Unit.METER, Unit.DAMAGE, Unit.CHARGE, Unit.STAT_POINT)) {
            var ratio = new Value.Ratio(c(.3, u), c(.1, u));
            assertEquals(Unit.MULTIPLIER, ratio.unit(V)); assertEquals(3, ratio.evaluate(null).value());
            assertEquals(-.5, new Value.Ratio(c(1, u), c(-2, u)).evaluate(null).value());
            assertEquals(0, new Value.Ratio(c(0, u), c(2, u)).evaluate(null).value());
        }
        assertEquals(1.0 / 3, new Value.Ratio(c(1, Unit.METER), c(3, Unit.METER)).evaluate(null).value());
    }
    @Test void mismatchedUnitsZeroAndOverflowAreExplicitErrors() {
        var wrong = new Value.Ratio(c(1, Unit.METER), c(2, Unit.SECOND));
        assertThrows(IllegalArgumentException.class, () -> wrong.unit(V));
        assertThrows(IllegalArgumentException.class, () -> wrong.evaluate(null));
        for (double zero : new double[]{0, -0.0}) assertThrows(IllegalArgumentException.class,
                () -> new Value.Ratio(c(1, Unit.METER), c(zero, Unit.METER)).evaluate(null));
        assertThrows(IllegalArgumentException.class, () -> new Value.Ratio(c(Double.MAX_VALUE, Unit.METER), c(Double.MIN_VALUE, Unit.METER)).evaluate(null));
    }
    @Test void snapshotFreezesSourceRangeAndRetainsEachVictimsImpactDistance() {
        var ratio = new Value.Ratio(new Value.ImpactNumber("distance", Unit.METER), new Value.EventNumber("range", Unit.METER));
        var captured = ratio.snapshot(evaluation(6, 0));
        assertEquals(.5, captured.evaluate(evaluation(100, 3)).value());
        assertEquals(1, captured.evaluate(evaluation(100, 6)).value());
        assertEquals(.03, ratio.evaluate(evaluation(100, 3)).value());
    }
    @Test void jsonAndCheckpointRetainBothOperands() {
        var ratio = new Value.Ratio(new Value.ImpactNumber("distance", Unit.METER), c(6, Unit.METER));
        var json = EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE, ratio).getOrThrow();
        assertEquals(ratio, EffectCodecs.VALUE.parse(JsonOps.INSTANCE, json).getOrThrow());
        var codec = EffectCheckpointCodec.DATA;
        var restored = codec.decode(codec.encode(ratio), Value.class);
        assertEquals(ratio, restored); assertEquals(.5, restored.evaluate(evaluation(99, 3)).value());
    }
}
