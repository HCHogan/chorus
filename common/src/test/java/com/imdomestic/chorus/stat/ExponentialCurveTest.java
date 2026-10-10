package com.imdomestic.chorus.stat;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class ExponentialCurveTest {
    @Test void repeatedDecayContinuesPastFiniteLookupTables() {
        var curve = new Curve.Exponential(.575, 0, Integer.MAX_VALUE, Curve.Boundary.ERROR);
        assertEquals(1, curve.evaluate(0));
        assertEquals(.575, curve.evaluate(1));
        assertEquals(.82 * .575 * .575, .82 * curve.evaluate(2), 1e-14);
        assertEquals(StrictMath.pow(.575, 20), curve.evaluate(20));
        assertTrue(curve.evaluate(20) < curve.evaluate(9));
        assertEquals(0, curve.evaluate(Integer.MAX_VALUE)); // Finite underflow, not a clamp to a last nonzero sample.
    }
    @Test void domainsPositiveBasesAndFiniteOutputsAreEnforced() {
        var strict = new Curve.Exponential(4, -1, 1, Curve.Boundary.ERROR);
        assertEquals(.25, strict.evaluate(-1)); assertEquals(2, strict.evaluate(.5));
        assertThrows(IllegalArgumentException.class, () -> strict.evaluate(2));
        assertEquals(4, new Curve.Exponential(4, -1, 1, Curve.Boundary.CLAMP).evaluate(2));
        assertThrows(IllegalArgumentException.class, () -> strict.evaluate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Curve.Exponential(0, 0, 2, Curve.Boundary.ERROR));
        assertThrows(IllegalArgumentException.class, () -> new Curve.Exponential(-1, 0, 2, Curve.Boundary.ERROR));
        assertThrows(IllegalArgumentException.class, () -> new Curve.Exponential(2, 2, 1, Curve.Boundary.ERROR));
        assertThrows(IllegalArgumentException.class, () -> new Curve.Exponential(2, 0, 2000, Curve.Boundary.ERROR).evaluate(2000));
    }
    @Test void codecRoundTripsAndReportsInvalidOrUnknownFields() {
        var json = JsonParser.parseString("""
            {"type":"chorus:exponential","base":0.575,"minimum":0,"maximum":2147483647,"boundary":"error"}
            """).getAsJsonObject();
        var curve = StatCodecs.CURVE.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(curve, StatCodecs.CURVE.parse(JsonOps.INSTANCE, StatCodecs.CURVE.encodeStart(JsonOps.INSTANCE, curve).getOrThrow()).getOrThrow());
        json.addProperty("base", 0); assertTrue(StatCodecs.CURVE.parse(JsonOps.INSTANCE, json).error().isPresent());
        json.addProperty("base", .575); json.addProperty("typo", 1);
        assertTrue(StatCodecs.CURVE.parse(JsonOps.INSTANCE, json).error().isPresent());
    }
}
