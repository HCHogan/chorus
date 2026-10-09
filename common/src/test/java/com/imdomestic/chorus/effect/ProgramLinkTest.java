package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramLinkTest {
    private static EffectProgram decode(String text) { return EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).getOrThrow(); }
    private static EffectProgram fragment(String fixture) throws Exception { return EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(fixture)).getOrThrow(); }
    @Test void crossFragmentReferencesValidateAfterLinkingAndFlattenIntoTheExistingCodec() throws Exception {
        var voltshot = fragment("voltshot"); var jolt = fragment("jolt");
        assertThrows(IllegalArgumentException.class, () -> new CompiledEffects(voltshot));
        var compiled = CompiledEffects.link(List.of(voltshot, jolt));
        assertEquals(4, compiled.program().buffs().size()); assertEquals(3, compiled.program().bundles().size());
        assertEquals("chorus_d2:jolt", compiled.buff("chorus_d2:jolt").id());
        assertEquals(2, voltshot.buffs().size()); assertEquals(2, jolt.buffs().size());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow();
        assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var input = new ArrayList<>(List.of(voltshot, jolt)); var immutable = CompiledEffects.link(input); input.clear();
        assertEquals(compiled.program(), immutable.program());
    }
    @Test void duplicatesAreRejectedInEveryCatalogueEvenWhenDefinitionsAreIdentical() {
        for (String member : List.of(
                "\"buffs\":[{\"definition\":{\"id\":\"test:a\",\"version\":\"v1\",\"duration\":1}}]",
                "\"bundles\":[{\"id\":\"test:a\"}]",
                "\"profiles\":[{\"id\":\"test:a\",\"version\":\"v1\",\"input_unit\":\"damage\",\"steps\":[]}]",
                "\"resources\":[{\"id\":\"test:a\",\"capacity\":1,\"initial\":0,\"base_rate\":0}]")) {
            var fragment = decode("{\"version\":\"v1\"," + member + "}");
            var failure = assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(fragment, fragment)));
            assertTrue(failure.getMessage().contains("Duplicate definition"), failure.getMessage());
        }
    }
    @Test void emptyMixedVersionsAndMultipleDefenseSelectionsDoNotSilentlyPickAWinner() {
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of()));
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(decode("{\"version\":\"v1\"}"), decode("{\"version\":\"v2\"}"))));
        var one = decode("{\"version\":\"v1\",\"defense_profile\":\"test:defense\"}");
        for (String other : List.of("test:defense", "test:other")) {
            var two = decode("{\"version\":\"v1\",\"defense_profile\":\"" + other + "\"}");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(one, two))).getMessage().contains("Multiple fragments"));
        }
    }
    @Test void linkValidatesCrossFragmentUnitsAndInternalVersionsWithoutRewritingDefinitions() {
        var effect = decode("""
                {"version":"v1","bundles":[{"id":"test:effect","rules":[{"id":"apply","on":"test:go","do":[
                  {"type":"chorus:grant_buff","buff":"test:status","duration":{"type":"chorus:constant","value":2,"unit":"damage"}}
                ]}]}]}
                """);
        var definition = decode("{\"version\":\"v1\",\"buffs\":[{\"definition\":{\"id\":\"test:status\",\"version\":\"v1\",\"duration\":1}}]}");
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(definition, effect)));
        var wrongVersion = decode("{\"version\":\"v1\",\"buffs\":[{\"definition\":{\"id\":\"test:status\",\"version\":\"old\",\"duration\":1}}]}");
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(wrongVersion)));
        assertEquals("old", wrongVersion.buffs().getFirst().definition().version());
    }
    @Test void oneFragmentMaySelectADefenseProfileDefinedByAnother() {
        var settings = decode("{\"version\":\"v1\",\"defense_profile\":\"test:defense\"}");
        var profile = decode("{\"version\":\"v1\",\"profiles\":[{\"id\":\"test:defense\",\"version\":\"v1\",\"input_unit\":\"damage\",\"steps\":[]}]}");
        var compiled = CompiledEffects.link(List.of(settings, profile)); assertEquals(Optional.of("test:defense"), compiled.program().defenseProfile());
        assertEquals(compiled.program(), CompiledEffects.link(List.of(profile, settings)).program());
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(settings)));
    }
}
