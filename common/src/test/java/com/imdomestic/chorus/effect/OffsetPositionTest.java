package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class OffsetPositionTest {
    static JsonObject fixture() throws Exception {
        var data = json("fixed_position");
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().add("do", JsonParser.parseString("""
                [
                  {"action":{"type":"chorus:capture_position","target":"self"},"as":"original"},
                  {"action":{"type":"chorus:offset_position","position":"original",
                    "x":{"type":"chorus:constant","value":-0.26,"unit":"meter"},
                    "y":{"type":"chorus:constant","value":0.01,"unit":"meter"},
                    "z":{"type":"chorus:constant","value":2,"unit":"meter"}},"as":"shifted"},
                  {"action":{"type":"chorus:select_targets","center":{"position":"shifted"},
                    "radius":{"type":"chorus:constant","value":1,"unit":"meter"}},"as":"nearby"}
                ]
                """)); return data;
    }
    static JsonArray body(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    @Test void displacementRetainsDimensionAndMissingPositionWithoutInventingWorldReadsOrAnOrigin() throws Exception {
        var program = compile(fixture()); var source = source("test:fixed_burst");
        for (var point : List.of(Optional.of(new WorldPosition("other-dimension", -12.5, 40, 3)), Optional.<WorldPosition>empty())) {
            var engine = engine(program); var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:burst", event(source));
            assertEquals(1, waiting.actions().size()); var query = (PositionQuery) waiting.actions().getFirst().command();
            var shifted = complete(engine, waiting, new PositionQuery.Result(query, point));
            assertEquals(1, shifted.actions().size()); var selected = (TargetQuery) shifted.actions().getFirst().command();
            assertEquals(new TargetQuery.PositionCenter(point.map(p -> new WorldPosition(p.dimension(), -12.76, 40.01, 5))), selected.center());
        }
        assertEquals(program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow()).getOrThrow());
    }
    @Test void displacementRequiresALexicallyBoundPositionAndLengthUnitsOnEveryAxis() throws Exception {
        for (String axis : List.of("x", "y", "z")) {
            var data = fixture(); body(data).get(1).getAsJsonObject().getAsJsonObject("action").getAsJsonObject(axis).addProperty("unit", "second");
            assertThrows(RuntimeException.class, () -> compile(data));
        }
        var missing = fixture(); body(missing).get(1).getAsJsonObject().getAsJsonObject("action").addProperty("position", "unbound");
        assertThrows(RuntimeException.class, () -> compile(missing));
        var number = fixture(); body(number).get(0).getAsJsonObject().add("action", JsonParser.parseString("""
                {"type":"chorus:capture_value","value":{"type":"chorus:constant","value":1,"unit":"meter"}}
                """)); assertThrows(RuntimeException.class, () -> compile(number));
    }
}
