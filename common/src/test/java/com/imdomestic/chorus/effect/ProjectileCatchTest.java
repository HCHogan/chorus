package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileCatchTest {
    @Test void windowIsHalfOpenAndRadiusClosedWithFiniteParameters() {
        var p = new ProjectileCatch(2, 50_000, 500_000, true);
        assertFalse(p.allows(49_999, 1)); assertTrue(p.allows(50_000, 2));
        assertTrue(p.allows(499_999, 2)); assertFalse(p.allows(500_000, 0)); assertFalse(p.allows(60_000, 2.00001));
        assertTrue(new ProjectileCatch(0, 0, 1, false).allows(0, 0));
        for (double n : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) assertThrows(IllegalArgumentException.class, () -> new ProjectileCatch(n, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileCatch(1, -1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileCatch(1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileCatch(1, 2, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileCatch(1, 0, Long.MAX_VALUE, true));
    }
    @Test void catchIsTerminalWithReceiverButNoCollisionDamageCounters() {
        var p = ProjectileFlight.Progress.EMPTY.contact(ProjectileFlight.Collision.STOP, ProjectileFlight.End.CAUGHT, Optional.of("owner"), false);
        assertTrue(p.terminal()); assertEquals(1, p.sequence()); assertEquals(0, p.entityContacts()); assertTrue(p.hits().isEmpty());
        var impact = new ProjectileFlight.Impact(ProjectileFlight.End.CAUGHT, ProjectileDestinationTest.point(1, 2, 3), Optional.of("owner"), 0, 0, 0, 50_000);
        assertTrue(ResultShape.PROJECTILE_IMPACT.flag("caught", impact)); assertFalse(ResultShape.PROJECTILE_IMPACT.flag("arrived", impact));
        assertFalse(ResultShape.PROJECTILE_IMPACT.flag("entity", impact)); assertEquals(0, impact.targetContacts()); assertEquals(1, impact.targets().size());
        assertThrows(IllegalStateException.class, () -> p.contact(ProjectileFlight.Collision.STOP, ProjectileFlight.End.CAUGHT, Optional.of("owner"), false));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.CAUGHT, impact.point(), Optional.empty(), 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProjectileFlight.Impact(ProjectileFlight.End.CAUGHT, impact.point(), Optional.of("owner"), 0, 0, 0, 0, 1, 0, 0, 0, false));
    }
    @Test void codecRejectsTyposWrongUnitsSubMicrosecondAndReversedWindows() throws Exception {
        var data = json("projectile_catch"); var program = compile(data).program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("radius", "unit", "negative", "empty", "reverse", "fraction", "typo")) {
            var d = data.deepCopy(); var p = ProjectileDestinationTest.returnSpec(d).getAsJsonObject("destination").getAsJsonObject("catch");
            switch (fault) {
                case "radius" -> p.getAsJsonObject("radius").addProperty("value", -1);
                case "unit" -> p.getAsJsonObject("opens_after").addProperty("unit", "meter");
                case "negative" -> p.getAsJsonObject("opens_after").addProperty("value", -1);
                case "empty" -> p.getAsJsonObject("closes_after").addProperty("value", .05);
                case "reverse" -> p.getAsJsonObject("closes_after").addProperty("value", .01);
                case "fraction" -> p.getAsJsonObject("opens_after").addProperty("value", .0000001);
                case "typo" -> p.addProperty("close_after", 1);
            }
            assertThrows(RuntimeException.class, () -> compile(d), fault);
        }
        var p = ProjectileDestinationTest.returnSpec(data).getAsJsonObject("destination").getAsJsonObject("catch");
        p.remove("opens_after"); p.remove("line_of_sight"); assertDoesNotThrow(() -> compile(data));
    }
    @Test void caughtAndAutomaticArrivalChooseSeparateDataDefinedRefunds() throws Exception {
        for (var end : List.of(ProjectileFlight.End.CAUGHT, ProjectileFlight.End.ARRIVED)) {
            var h = new ProjectileDestinationTest.Harness("projectile_catch");
            h.complete(0, 100_000, ProjectileFlight.End.ENTITY, Optional.of("enemy"));
            var policy = h.launches.getLast().parameters().destination().orElseThrow().catching().orElseThrow();
            assertEquals(new ProjectileCatch(2, 50_000, 500_000, true), policy);
            h.complete(1, 200_000, end, Optional.of("player"));
            assertEquals(end == ProjectileFlight.End.CAUGHT ? 2 : 1.25, h.energy());
            assertEquals(List.of(end == ProjectileFlight.End.CAUGHT ? 10.0 : 2.5), h.heals);
            assertTrue(h.state().retainedCosts().isEmpty());
        }
    }
}
