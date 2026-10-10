package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileTrackingTest {
    private static WorldDirection axis(double x, double y, double z) { return new WorldDirection("world", x, y, z); }
    private static double angle(WorldDirection a, WorldDirection b) { return Math.toDegrees(Math.acos(Math.clamp(a.x() * b.x() + a.y() * b.y() + a.z() * b.z(), -1, 1))); }
    @Test void boundedShortestArcPreservesNormalizedDirectionsAndCanSnapOrRemainBallistic() {
        var from = axis(0, 1, 0); var to = axis(1, 0, 0); var result = ProjectileTracking.turn(from, to, 9);
        assertEquals(9, angle(from, result), 1e-10); assertEquals(81, angle(result, to), 1e-10);
        assertEquals(Math.sin(Math.toRadians(9)), result.x(), 1e-12);
        assertEquals(to, ProjectileTracking.turn(from, to, 180)); assertEquals(from, ProjectileTracking.turn(from, to, 0));
        assertEquals(to, ProjectileTracking.turn(from, to, Double.MAX_VALUE));
    }
    @Test void antipodalAndNearlyAntipodalTargetsChooseFiniteDeterministicTurns() {
        for (var from : List.of(axis(0, 1, 0), axis(1, 0, 0), axis(1, 2, 3))) {
            var to = axis(-from.x(), -from.y(), -from.z()); var first = ProjectileTracking.turn(from, to, 10);
            assertEquals(10, angle(from, first), 1e-8); assertEquals(170, angle(first, to), 1e-8);
            assertEquals(first, ProjectileTracking.turn(from, to, 10));
            var near = axis(-from.x() + 1e-14, -from.y(), -from.z());
            assertEquals(10, angle(from, ProjectileTracking.turn(from, near, 10)), 1e-8);
        }
    }
    @Test void variedDirectionsNeverExceedAngularBudgetOrTurnAwayFromTheirTarget() {
        var random = new Random(139);
        for (int i = 0; i < 1000; i++) {
            var from = axis(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5);
            var to = axis(random.nextDouble() - .5, random.nextDouble() - .5, random.nextDouble() - .5);
            double budget = random.nextDouble() * 180; var result = ProjectileTracking.turn(from, to, budget);
            assertTrue(angle(from, result) <= budget + 1e-8); assertTrue(angle(result, to) <= angle(from, to) + 1e-8);
            assertEquals(1, Math.hypot(Math.hypot(result.x(), result.y()), result.z()), 1e-12);
        }
    }
    @Test void conesAndPoliciesRejectMissingNumericalMeaningRatherThanInventingDirections() {
        var forward = axis(0, 1, 0);
        assertTrue(ProjectileTracking.inCone(forward, axis(1, 1, 0), 45)); assertFalse(ProjectileTracking.inCone(forward, axis(1, 0, 0), 45));
        assertTrue(ProjectileTracking.inCone(forward, axis(0, -1, 0), 180));
        assertThrows(IllegalArgumentException.class, () -> ProjectileTracking.turn(forward, new WorldDirection("other", 0, 1, 0), 20));
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ProjectileTracking.Policy(invalid, 10, 90, TargetQuery.Anchor.BODY, TargetQuery.Relation.ANY, true, false));
            assertThrows(IllegalArgumentException.class, () -> ProjectileTracking.turn(forward, forward, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProjectileTracking.Policy(8, 10, 181, TargetQuery.Anchor.BODY, TargetQuery.Relation.ANY, true, false));
    }
    @Test void completeTrackingFixtureRoundTripsAndEachFlightRetainsResolvedPolicyAfterUnbinding() throws Exception {
        var compiled = load("projectile_tracking"); var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, compiled.program()).getOrThrow();
        assertEquals(compiled.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        var h = new ProjectileTest.Harness(compiled); h.fire(); h.fire();
        var policy = h.launches.getFirst().parameters().tracking().orElseThrow();
        assertEquals(8, policy.radius()); assertEquals(180, policy.turnRate()); assertEquals(90, policy.acquisitionAngle());
        assertTrue(policy.lineOfSight() && policy.redirectOnContact());
        h.session.start(0, SourceChange.remove("perk")); h.session.start(0, SourceChange.remove("boost"));
        assertEquals(policy, h.launches.getLast().parameters().tracking().orElseThrow());
        assertTrue(new ProjectileFlight.Parameters(1, 0, 1, 1).tracking().isEmpty());
    }
    @Test void malformedTrackingDefinitionsFailBeforeLaunching() throws Exception {
        var data = json("projectile_tracking");
        for (String fault : List.of("radius", "rate", "angle", "unit", "field", "anchor", "relation")) {
            var invalid = data.deepCopy(); var tracking = invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("tracking");
            switch (fault) {
                case "radius" -> tracking.getAsJsonObject("radius").addProperty("value", -1);
                case "rate" -> tracking.getAsJsonObject("turn_rate").addProperty("value", -1);
                case "angle" -> tracking.getAsJsonObject("acquisition_angle").addProperty("value", 181);
                case "unit" -> tracking.getAsJsonObject("turn_rate").addProperty("unit", "meter_per_second");
                case "field" -> tracking.addProperty("unknown", true);
                case "anchor" -> tracking.addProperty("target_anchor", "guessed");
                case "relation" -> tracking.addProperty("relation", "guessed");
            }
            assertThrows(RuntimeException.class, () -> compile(invalid), fault);
        }
    }
}
