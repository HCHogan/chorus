package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpatialQueryTest {
    private static TargetShape.Offset at(double x, double y, double z) { return new TargetShape.Offset(x, y, z); }
    private static TargetQuery query(TargetShape shape) { return new TargetQuery(new TargetQuery.EntityCenter("center"), shape, TargetQuery.Relation.ANY, "owner", false, Set.of(), TargetQuery.Order.IDENTITY, OptionalInt.empty(), TargetQuery.Anchor.FEET, false); }
    private static TargetQuery.Result result(TargetShape shape, TargetQuery.Target... targets) { return new TargetQuery.Result(query(shape), TargetQuery.Outcome.AVAILABLE, List.of(targets)); }
    private static JsonObject selection(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(2).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action"); }
    @Test void finiteShapesIncludeDeclaredEdgesAndExcludeBehindAboveAndBeyond() {
        var sphere = new TargetShape.Sphere(5); assertTrue(sphere.contains(at(3, 4, 0))); assertFalse(sphere.contains(at(3, 4.00001, 0)));
        var cylinder = new TargetShape.Cylinder(3, 4); assertTrue(cylinder.contains(at(3, 2, 0))); assertTrue(cylinder.contains(at(0, -2, 3)));
        assertFalse(cylinder.contains(at(0, 2.00001, 0))); assertFalse(cylinder.contains(at(3.00001, 0, 0)));
        var cone = new TargetShape.Cone(4, 2, Optional.of(new WorldDirection("world", 1, 0, 0)));
        assertTrue(cone.contains(at(0, 0, 0))); assertTrue(cone.contains(at(4, 2, 0))); assertTrue(cone.contains(at(2, 0, 1)));
        assertFalse(cone.contains(at(-.0001, 0, 0))); assertFalse(cone.contains(at(4.0001, 0, 0))); assertFalse(cone.contains(at(2, 1.0001, 0)));
        assertEquals(Math.hypot(4, 2), cone.boundingRadius());
    }
    @Test void normalizedAxesSupportVerticalAndObliqueConesAndRejectInvalidGeometry() {
        var vertical = new TargetShape.Cone(4, 2, Optional.of(new WorldDirection("world", 0, 10, 0)));
        assertTrue(vertical.contains(at(2, 4, 0))); assertFalse(vertical.contains(at(0, -1, 0)));
        var oblique = new TargetShape.Cone(10, 1, Optional.of(new WorldDirection("world", 3, 0, 4)));
        assertTrue(oblique.contains(at(3, 0, 4))); assertFalse(oblique.contains(at(3, 2, 4)));
        assertThrows(IllegalArgumentException.class, () -> new WorldDirection("world", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldDirection("world", Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetShape.Cone(0, 2, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TargetShape.Cylinder(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new TargetShape.Cylinder(Double.MAX_VALUE, Double.MAX_VALUE));
    }
    @Test void shapedReceiptsRequireOffsetsThatMatchBothGeometryAndDistance() {
        var cone = new TargetShape.Cone(4, 2, Optional.of(new WorldDirection("world", 1, 0, 0)));
        assertDoesNotThrow(() -> result(cone, new TargetQuery.Target("hit", at(4, 2, 0))));
        assertThrows(IllegalArgumentException.class, () -> result(cone, new TargetQuery.Target("no_offset", 1)));
        assertThrows(IllegalArgumentException.class, () -> result(cone, new TargetQuery.Target("behind", at(-1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Target("wrong_distance", 1, Optional.of(at(2, 0, 0))));
        var missing = new TargetShape.Cone(4, 2, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> result(missing));
        assertTrue(new TargetQuery.Result(query(missing), TargetQuery.Outcome.MISSING_DIRECTION, List.of()).targets().isEmpty());
    }
    @Test void directionAndOriginBindingsRemainFrozenAcrossDetachedDelayAndSourceRemoval() throws Exception {
        var p = load("spatial_query"); var source = source("test:spatial"); var commands = new ArrayList<RuleEngine.WorldCommand>();
        var original = new WorldDirection("world", 1, 0, 0); var position = new WorldPosition("world", 1, 2, 3);
        var session = new EffectSession(engine(p), EffectState.empty().withSource(source), request -> {
            commands.add(request.command());
            return switch (request.command()) {
                case DirectionQuery direction -> new DirectionQuery.Result(direction, Optional.of(original));
                case PositionQuery capture -> new PositionQuery.Result(capture, Optional.of(position));
                case TargetQuery target -> new TargetQuery.Result(target, TargetQuery.Outcome.AVAILABLE, List.of());
                default -> throw new AssertionError("Unexpected world operation");
            };
        });
        session.start(0, new RuleEngine.Signal("test:scan", event(source))); session.start(0, SourceChange.remove(source.instance()));
        assertEquals(2, commands.size()); session.observe(100_000, List.of()); assertEquals(3, commands.size());
        var target = (TargetQuery) commands.getLast(); assertEquals(Optional.of(original), ((TargetShape.Cone) target.shape()).direction());
        assertEquals(new TargetQuery.PositionCenter(position), target.center()); assertTrue(target.lineOfSight()); assertEquals(TargetQuery.Anchor.BODY, target.targetAnchor());
    }
    @Test void codecPreservesShapesAndRejectsAmbiguousFieldsWrongUnitsAndMissingDirectionBindings() throws Exception {
        var program = load("spatial_query").program(); assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("both", "missing", "unit", "unbound", "anchor")) {
            var data = json("spatial_query"); var query = selection(data);
            switch (fault) {
                case "both" -> query.add("radius", query.getAsJsonObject("area").get("radius"));
                case "missing" -> query.remove("area");
                case "unit" -> query.getAsJsonObject("area").getAsJsonObject("length").addProperty("unit", "second");
                case "unbound" -> query.getAsJsonObject("area").addProperty("direction", "origin");
                case "anchor" -> query.addProperty("center_anchor", "eyes");
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
        var old = load("radial_falloff").program(); assertEquals(old, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, old).getOrThrow()).getOrThrow());
    }
    @Test void wrongDirectionReceiptCannotResumeAnAwaitingQuery() throws Exception {
        var p = load("spatial_query"); var source = source("test:spatial"); var engine = engine(p);
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:scan", event(source));
        var wrong = new DirectionQuery.Result(new DirectionQuery("someone_else"), Optional.empty());
        var result = engine.transition(waiting.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), wrong));
        while (result.needsPump()) result = engine.transition(result.state(), RuleEngine.Pump.INSTANCE);
        assertTrue(result.state().engine().failure().isPresent()); assertTrue(result.actions().isEmpty());
    }
}
