package com.imdomestic.chorus.stat;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class StatCodecsTest {
    @Test void jsonProfileLoadsCalculatesAndRoundTrips() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/profiles/reload.json")), StandardCharsets.UTF_8)) {
            var profile = StatCodecs.PROFILE.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var result = profile.calculate(new Measure(160, Unit.STAT_POINT), List.of(CalculationProfileTest.c(
                    "slow", "slow", "slow", NumericContribution.Operation.MULTIPLY, -.75, Unit.DELTA, "", "slow", "one", 0)));
            assertEquals(3.4, result.output().value(), 1e-10);
            var encoded = StatCodecs.PROFILE.encodeStart(JsonOps.INSTANCE, profile).getOrThrow();
            assertEquals(profile, StatCodecs.PROFILE.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        }
    }

    @Test void invalidDefinitionsReturnDataErrorsInsteadOfEscapingConstructorExceptions() {
        for (String steps : List.of(
                "[{\"type\":\"chorus:typo\"}]",
                "[{\"type\":\"chorus:clamp\",\"id\":\"cap\",\"minimum\":10,\"maximum\":0}]",
                "[{\"type\":\"chorus:apply\",\"id\":\"percent\",\"operation\":\"base_percent\",\"percent_of\":\"future\",\"group\":{\"name\":\"p\",\"reduction\":\"sum\"}}]",
                "[{\"type\":\"chorus:apply\",\"id\":\"flat\",\"operation\":\"add\",\"group\":{\"name\":\"p\",\"reduction\":\"product\"}}]")) {
            var json = JsonParser.parseString("{\"id\":\"test:bad\",\"version\":\"1\",\"input_unit\":\"damage\",\"steps\":" + steps + "}");
            assertTrue(StatCodecs.PROFILE.parse(JsonOps.INSTANCE, json).error().isPresent(), steps);
        }
        assertTrue(StatCodecs.FAMILY.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"kind\":\"count_table\",\"counts\":{\"bad\":1}}")).error().isPresent());
        assertTrue(StatCodecs.FAMILY.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"kind\":\"count_table\",\"counts\":{\"1\":0.1,\"01\":0.2}}")).error().isPresent());
    }

    private record CustomCurve() implements Curve {
        @Override public double evaluate(double input) { return input * 2; }
    }

    @Test void typesAreExtensibleButExistingCodecsHaveFrozenRegistrations() {
        var types = StatCodecs.curveTypes();
        var old = types.build();
        types.register("test:double", CustomCurve.class, MapCodec.unit(new CustomCurve()));
        var json = JsonParser.parseString("{\"type\":\"test:double\"}");
        assertTrue(old.parse(JsonOps.INSTANCE, json).error().isPresent());
        assertEquals(6, types.build().parse(JsonOps.INSTANCE, json).getOrThrow().evaluate(3));
        assertThrows(IllegalArgumentException.class, () -> types.register("test:double", CustomCurve.class, MapCodec.unit(new CustomCurve())));
    }
}
