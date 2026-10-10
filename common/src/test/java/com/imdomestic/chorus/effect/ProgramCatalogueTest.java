package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramCatalogueTest {
    static ProgramModule decode(String json) { return ProgramModule.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow(); }
    static ProgramModule module(String name, boolean fragment, String... imports) {
        var p = decode("{\"version\":\"v1\",\"bundles\":[{\"id\":\"test:" + name + "\"}]}").program();
        return new ProgramModule(p, List.of(imports), fragment);
    }
    @Test void flatProgramsRemainCompatibleAndModulesRoundTripWithoutFlatteningImports() {
        var original = decode("{\"version\":\"v1\",\"bundles\":[{\"id\":\"test:source\"}]}");
        assertFalse(original.fragment()); assertTrue(original.imports().isEmpty());
        assertEquals(new CompiledEffects(original.program()).program(), ProgramCatalogue.compile(Map.of("test:root", original)).get("test:root").program());
        var fragment = new ProgramModule(original.program(), List.of("test:shared"), true);
        var encoded = ProgramModule.CODEC.encodeStart(JsonOps.INSTANCE, fragment).getOrThrow();
        assertEquals(fragment, ProgramModule.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertEquals(List.of("test:shared"), fragment.imports()); assertEquals(1, fragment.program().bundles().size());
    }
    @Test void linkedVoltshotResolvesSharedJoltByNamedImportsAndCanStillExportFlattenedProgram() throws Exception {
        var jolt = new ProgramModule(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("jolt")).getOrThrow(), List.of(), true);
        var voltshot = new ProgramModule(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("voltshot")).getOrThrow(), List.of("test:jolt"), false);
        var catalogue = ProgramCatalogue.compile(Map.of("test:jolt", jolt, "test:voltshot", voltshot));
        assertEquals(Set.of("test:voltshot"), catalogue.keySet());
        var linked = catalogue.get("test:voltshot"); assertEquals(4, linked.program().buffs().size());
        assertEquals(CompiledEffects.link(List.of(jolt.program(), voltshot.program())).program(), linked.program());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, linked).getOrThrow();
        assertEquals(linked.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
    @Test void diamondImportsIncludeSharedDefinitionsOnceButKeepEachIndependentRoot() {
        var sources = new HashMap<String, ProgramModule>();
        sources.put("test:shared", module("shared", true));
        sources.put("test:left", module("left", true, "test:shared")); sources.put("test:right", module("right", true, "test:shared"));
        sources.put("test:root", module("root", false, "test:left", "test:right"));
        sources.put("test:other", module("other", false, "test:shared"));
        var catalog = ProgramCatalogue.compile(sources);
        assertEquals(List.of("test:shared", "test:left", "test:right", "test:root"), catalog.get("test:root").program().bundles().stream().map(EffectProgram.Bundle::id).toList());
        assertEquals(2, catalog.get("test:other").program().bundles().size());
        sources.clear(); assertEquals(2, catalog.size()); assertThrows(UnsupportedOperationException.class, catalog::clear);
    }
    @Test void mutuallyReferencingModulesAreCollectedOnceAndValidatedTogether() {
        var a = decode("""
                {"version":"v1","imports":["test:b"],"bundles":[{"id":"test:apply","rules":[{"id":"apply","on":"test:apply","do":[
                 {"type":"chorus:grant_buff","buff":"test:status"}]}]}]}
                """);
        var b = decode("""
                {"version":"v1","fragment":true,"imports":["test:a"],"buffs":[{"definition":{"id":"test:status","version":"v1","duration":1}}]}
                """);
        var compiled = ProgramCatalogue.compile(Map.of("test:a", a, "test:b", b)).get("test:a");
        assertEquals(1, compiled.program().buffs().size()); assertEquals(1, compiled.program().bundles().size());
        assertEquals(1, ProgramCatalogue.compile(Map.of("test:self", module("self", false, "test:self"))).size());
    }
    @Test void missingImportsAndCrossVersionEdgesFailEvenForUnusedFragments() {
        var missing = assertThrows(IllegalArgumentException.class, () -> ProgramCatalogue.compile(Map.of("test:unused", module("unused", true, "test:missing"))));
        assertTrue(missing.getMessage().contains("test:unused -> test:missing"));
        var newer = decode("{\"version\":\"v2\",\"fragment\":true}");
        var mixed = assertThrows(IllegalArgumentException.class, () -> ProgramCatalogue.compile(Map.of("test:a", module("a", true, "test:b"), "test:b", newer)));
        assertTrue(mixed.getMessage().contains("Mixed module versions"));
        // Independent catalogues may pin distinct versions without importing one another.
        assertEquals(2, ProgramCatalogue.compile(Map.of("test:a", module("a", false), "test:b", decode("{\"version\":\"v2\"}"))).size());
    }
    @Test void separateIdentitiesCannotSilentlyOverrideDuplicateDefinitions() {
        var sources = Map.of("test:a", module("same", true), "test:b", module("same", true), "test:root", module("root", false, "test:a", "test:b"));
        var error = assertThrows(IllegalArgumentException.class, () -> ProgramCatalogue.compile(sources));
        assertTrue(error.getMessage().contains("test:root") && error.getMessage().contains("Duplicate definition"));
    }
    @Test void rootCompilationRejectsUnresolvedReferencesAndCrossFragmentUnitErrors() {
        var root = decode("""
                {"version":"v1","imports":["test:definitions"],"bundles":[{"id":"test:apply","rules":[{"id":"apply","on":"test:go","do":[
                {"type":"chorus:grant_buff","buff":"test:status","duration":{"type":"chorus:constant","value":2,"unit":"damage"}}]}]}]}
                """);
        var definitions = decode("{\"version\":\"v1\",\"fragment\":true,\"buffs\":[{\"definition\":{\"id\":\"test:status\",\"version\":\"v1\",\"duration\":1}}]}");
        assertThrows(IllegalArgumentException.class, () -> ProgramCatalogue.compile(Map.of("test:root", root, "test:definitions", definitions)));
        assertThrows(IllegalArgumentException.class, () -> ProgramCatalogue.compile(Map.of("test:root", root, "test:definitions", module("empty", true))));
    }
    @Test void strictModuleDecodingRejectsTyposBadKindsAndDuplicateImports() {
        for (String fields : List.of("\"improts\":[]", "\"imports\":12", "\"imports\":[\"no namespace\"]", "\"fragment\":[]", "\"imports\":[\"test:a\",\"test:a\"]")) {
            assertTrue(ProgramModule.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"version\":\"v1\"," + fields + "}")).isError(), fields);
        }
        assertThrows(IllegalArgumentException.class, () -> ProgramCatalogue.compile(Map.of("bad id", module("a", true))));
    }
    @Test void deepImportChainsDoNotNeedARecursionLimit() {
        var sources = new HashMap<String, ProgramModule>();
        for (int i = 0; i < 3000; i++) sources.put("test:n" + i, module("n" + i, i != 0, i == 2999 ? new String[]{} : new String[]{"test:n" + (i + 1)}));
        assertEquals(3000, ProgramCatalogue.compile(sources).get("test:n0").program().bundles().size());
    }
}
