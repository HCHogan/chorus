package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TargetSelectionTest {
    private static final EffectSource SOURCE = source("test:radial");
    private static EffectEvent input(double targets) { return new EffectEvent("player", "target", SOURCE.origin(), Set.of(), Map.of("targets", new Measure(targets, Unit.COUNT))); }
    private static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    private static TargetQuery query(TargetQuery.Order order, int limit) {
        return new TargetQuery("center", 7, TargetQuery.Relation.ANY, "owner", false, Set.of("owner"), order, OptionalInt.of(limit));
    }
    private static TargetQuery.Result result(TargetQuery query, TargetQuery.Target... values) { return new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(values)); }
    @Test void nearestOrderUsesDistanceThenIdentityAndReceiptValidationEnforcesEveryFilter() {
        var query = query(TargetQuery.Order.NEAREST, 3);
        var far = new TargetQuery.Target("a", 7); var nearB = new TargetQuery.Target("b", 2); var nearC = new TargetQuery.Target("c", 2);
        assertEquals(List.of(nearB, nearC, far), List.of(far, nearC, nearB).stream().sorted(query.comparator()).toList());
        assertDoesNotThrow(() -> result(query, nearB, nearC, far));
        assertThrows(IllegalArgumentException.class, () -> result(query, nearC, nearB));
        assertThrows(IllegalArgumentException.class, () -> result(query, far, nearB));
        assertThrows(IllegalArgumentException.class, () -> result(query, nearB, new TargetQuery.Target("b", 3)));
        assertThrows(IllegalArgumentException.class, () -> result(query, new TargetQuery.Target("center", 0)));
        assertThrows(IllegalArgumentException.class, () -> result(query, new TargetQuery.Target("owner", 1)));
        assertThrows(IllegalArgumentException.class, () -> result(query, new TargetQuery.Target("outside", 7.0001)));
        assertThrows(IllegalArgumentException.class, () -> result(query(TargetQuery.Order.NEAREST, 1), nearB, far));
        assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Target("bad", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Target("bad", -1));
        var byId = query(TargetQuery.Order.IDENTITY, 3); assertDoesNotThrow(() -> result(byId, far, nearB));
        assertThrows(IllegalArgumentException.class, () -> result(byId, nearB, far));
    }
    @Test void capturedDistanceDrivesPlateauFalloffAndBoundaryDamageAcrossWorldWaits() throws Exception {
        var compiled = load("radial_falloff");
        var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, compiled.program()).getOrThrow();
        assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var engine = engine(compiled); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:blast", input(4));
        var query = (TargetQuery) waiting.actions().getFirst().command(); assertEquals(Set.of("player"), query.exclude());
        assertEquals(new TargetQuery.EntityCenter("target"), query.center()); assertEquals(TargetQuery.Order.NEAREST, query.order()); assertEquals(4, query.limit().orElseThrow());
        var snapshot = result(query, new TargetQuery.Target("d", 0), new TargetQuery.Target("c", 3), new TargetQuery.Target("b", 5), new TargetQuery.Target("a", 7));
        var next = complete(engine, waiting, snapshot); var oldSnapshot = next.state();
        double[] expected = {10, 10, 5, 0}; String[] ids = {"d", "c", "b", "a"};
        for (int i = 0; i < expected.length; i++) {
            var command = (DamageCommand) next.actions().getFirst().command(); assertEquals(ids[i], command.target()); assertEquals(expected[i], command.amount());
            var receipt = new DamageReceipt("radial/" + i, expected[i] == 0 ? DamageReceipt.Outcome.BLOCKED : DamageReceipt.Outcome.APPLIED,
                    0, 0, expected[i], Optional.empty(), false);
            next = complete(engine, next, receipt);
        }
        assertTrue(next.state().idle()); assertEquals(4, snapshot.targets().size());
        assertEquals(0, ((TargetQuery.Target) oldSnapshot.engine().frames().getFirst().iterations().getFirst().values().getFirst()).distance());
        assertEquals(Unit.METER, ResultShape.TARGET.read("distance", snapshot.targets().get(2)).unit());
    }
    @Test void zeroLimitIsEmptyWhileMissingLimitIsUnboundedAndDynamicInvalidCountsFailBeforeWorldReads() throws Exception {
        var engine = engine(load("radial_falloff")); var initial = engine.initial(EffectState.empty().withSource(SOURCE));
        var zero = send(engine, initial, 0, "test:blast", input(0)); var query = (TargetQuery) zero.actions().getFirst().command();
        assertEquals(OptionalInt.of(0), query.limit()); assertTrue(complete(engine, zero, result(query)).state().idle());
        for (double bad : List.of(-1.0, .5, (double) Integer.MAX_VALUE + 1)) {
            var failed = engine.transition(initial, new RuleEngine.Start(0, new RuleEngine.Signal("test:blast", input(bad))));
            while (failed.needsPump()) failed = engine.transition(failed.state(), RuleEngine.Pump.INSTANCE);
            assertTrue(failed.state().engine().failure().isPresent()); assertTrue(failed.actions().isEmpty());
        }
        var data = json("radial_falloff"); actions(data).get(0).getAsJsonObject().getAsJsonObject("action").remove("limit");
        var unbounded = engine(compile(data)); var wait = send(unbounded, unbounded.initial(EffectState.empty().withSource(SOURCE)), 0, "test:blast", input(0));
        assertTrue(((TargetQuery) wait.actions().getFirst().command()).limit().isEmpty());
    }
    @Test void curveInputOutputUnitsUnknownFieldsAndExcludedBindingScopeAreCheckedAtLoad() throws Exception {
        var baseline = json("radial_falloff"); assertDoesNotThrow(() -> compile(baseline));
        for (String field : List.of("from", "to")) {
            var data = baseline.deepCopy(); var curve = actions(data).get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject()
                    .getAsJsonObject("amount").getAsJsonArray("of").get(1).getAsJsonObject();
            curve.addProperty(field, "count"); assertThrows(RuntimeException.class, () -> compile(data));
        }
        for (String option : List.of("exclude", "order", "limit")) {
            var data = baseline.deepCopy(); var query = actions(data).get(0).getAsJsonObject().getAsJsonObject("action");
            if (option.equals("exclude")) query.add("exclude", JsonParser.parseString("[{\"binding\":\"target\"}]"));
            if (option.equals("order")) query.addProperty("order", "random");
            if (option.equals("limit")) query.add("limit", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":-1,\"unit\":\"count\"}"));
            assertThrows(RuntimeException.class, () -> compile(data));
        }
        var typo = baseline.deepCopy(); actions(typo).get(0).getAsJsonObject().getAsJsonObject("action").addProperty("limits", 2);
        assertThrows(RuntimeException.class, () -> compile(typo));
        var badPoint = baseline.deepCopy(); actions(badPoint).get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject()
                .getAsJsonObject("amount").getAsJsonArray("of").get(1).getAsJsonObject().getAsJsonObject("curve")
                .getAsJsonArray("points").get(0).getAsJsonObject().addProperty("outpt", 123);
        assertThrows(RuntimeException.class, () -> compile(badPoint));
    }
    @Test void typedCurvesReuseExplicitExactFloorLinearPolynomialAndDomainPolicies() {
        var values = new TreeMap<Double, Double>(); values.put(0.0, 1.0); values.put(2.0, .5); values.put(4.0, 0.0);
        var validation = new Validation(Map.of(), Map.of(), false);
        for (var interpolation : Curve.Interpolation.values()) {
            var value = new Value.CurveValue(new Value.Constant(1, Unit.METER), new Curve.Table(values, interpolation, Curve.Boundary.ERROR), Unit.METER, Unit.MULTIPLIER);
            assertEquals(Unit.MULTIPLIER, value.unit(validation));
            if (interpolation == Curve.Interpolation.EXACT) assertThrows(IllegalArgumentException.class, () -> value.evaluate(null));
            else assertEquals(interpolation == Curve.Interpolation.FLOOR ? 1 : .75, value.evaluate(null).value());
        }
        var polynomial = new Value.CurveValue(new Value.Constant(3, Unit.COUNT), new Curve.Polynomial(List.of(2.0, 3.0), 0, 5, Curve.Boundary.ERROR), Unit.COUNT, Unit.DAMAGE);
        assertEquals(11, polynomial.evaluate(null).value());
        var error = new Value.CurveValue(new Value.Constant(6, Unit.COUNT), polynomial.curve(), Unit.COUNT, Unit.DAMAGE);
        assertThrows(IllegalArgumentException.class, () -> error.evaluate(null));
        var clamp = new Value.CurveValue(error.input(), new Curve.Polynomial(List.of(2.0, 3.0), 0, 5, Curve.Boundary.CLAMP), Unit.COUNT, Unit.DAMAGE);
        assertEquals(17, clamp.evaluate(null).value());
    }
}
