package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParticipantCheckpointTest {
    @TempDir Path directory;
    private static ResourceSessionAuthority.Checkpoint independent() {
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseString("""
            {"version":"participant-checkpoint","resources":[{"id":"test:energy","capacity":2,"initial":1}],
             "buffs":[{"definition":{"id":"test:buff","version":"participant-checkpoint","duration":2,"max_stacks":3}}],
             "bundles":[{"id":"test:source","rules":[]}]}
            """)).getOrThrow().withResourceRouting();
        var origin = new BuffInstance.Origin(HOLDER, "source", "", "");
        var a = empty(1_000_000).withSource(new EffectSource("source", "test:source", HOLDER, origin, Set.of()));
        var b = account(empty(50_000), HOLDER, "energy", .4, 2);
        var c = empty(70_000); c = c.withBuffs(Buffs.grant(c.buffs(), program.buff("test:buff"), HOLDER, HOLDER, origin, 2, 1, 500_000).store());
        var resources = ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY, HOLDER, B, program, b);
        var domains = new TreeMap<String, EffectSession.Checkpoint>();
        Map.of(A, a, B, b, C, c).forEach((domain, state) -> domains.put(domain,
                new EffectSession(program, EffectCheckpointTest.clock(), 1, state, _ -> { throw new AssertionError("Unexpected world action"); }).checkpoint()));
        var placement = new ParticipantPlacement.Directory(8, Map.of(HOLDER, new ParticipantPlacement.Lease(A, 3)),
                Map.of(HOLDER, new ParticipantPlacement.Lease(C, 7)));
        return new ResourceSessionAuthority.Checkpoint(resources, domains, placement);
    }
    private static JsonObject placement(JsonObject archive) { return archive.getAsJsonObject("participant_placement"); }
    private static JsonObject character(JsonObject placement) { return placement.getAsJsonArray("characters").get(0).getAsJsonObject(); }
    private static JsonObject buff(JsonObject placement) { return placement.getAsJsonArray("buffs").get(0).getAsJsonObject(); }

    @Test void independentResourceCharacterAndBuffOwnersRestoreWithoutMovingStateOrReplayingActions() {
        var expected = independent(); var archive = ResourceCheckpointTest.archive(expected); var encoded = archive.encode();
        assertEquals(5, encoded.get("format").getAsInt());
        assertEquals(Set.of("format", "dimensions", "resource_directory", "participant_placement"), encoded.keySet());
        var decoded = CheckpointArchive.decode(JsonParser.parseString(encoded.toString()));
        assertEquals(archive, decoded); assertEquals(Optional.of(expected.placement()), decoded.placement());
        var saved = decoded.resourceCheckpoint().orElseThrow(); assertEquals(expected, saved);
        assertEquals(B, saved.directory().owner(HOLDER).lease().domain());
        assertEquals(A, saved.placement().characters().get(HOLDER).domain()); assertEquals(C, saved.placement().buffs().get(HOLDER).domain());
        var restored = ResourceSessionAuthority.restore(saved, ResourceCheckpointTest.policies(saved));
        assertEquals(saved, restored.authority().checkpoint());
        assertNotSame(saved.domains().get(A), restored.bindings().get(A).session().checkpoint());
    }

    @Test void placementCodecPreservesLargeGenerationsEmptyFacetsAndCanonicalOrder() {
        long large = 9_007_199_254_740_993L;
        var characters = new LinkedHashMap<String, ParticipantPlacement.Lease>();
        characters.put("z", new ParticipantPlacement.Lease(A, large)); characters.put("a", new ParticipantPlacement.Lease(B, 2));
        var expected = new ParticipantPlacement.Directory(Long.MAX_VALUE, characters, Map.of(HOLDER, new ParticipantPlacement.Lease(C, large + 1)));
        var encoded = ParticipantPlacementCodec.encode(expected);
        assertEquals("a", character(encoded).get("holder").getAsString());
        assertEquals(Long.toString(Long.MAX_VALUE), encoded.get("next_generation").getAsString());
        assertEquals(expected, ParticipantPlacementCodec.decode(JsonParser.parseString(encoded.toString())));
        var reordered = new ParticipantPlacement.Directory(expected.nextGeneration(), new TreeMap<>(characters), expected.buffs());
        assertEquals(encoded.toString(), ParticipantPlacementCodec.encode(reordered).toString());
        var retired = new ParticipantPlacement.Directory(large, Map.of(), Map.of());
        assertEquals(retired, ParticipantPlacementCodec.decode(ParticipantPlacementCodec.encode(retired)));
        assertEquals(ParticipantPlacement.Directory.EMPTY, ParticipantPlacementCodec.decode(ParticipantPlacementCodec.encode(ParticipantPlacement.Directory.EMPTY)));
    }

    @Test void legacyArchiveFourAndOldConstructorsDoNotInferPlacementFromResourcesOrLiveProjections() {
        var expected = independent(); var legacy = ResourceCheckpointTest.archive(expected).encode();
        legacy.addProperty("format", 4); legacy.remove("participant_placement");
        var decoded = CheckpointArchive.decode(legacy); var saved = decoded.resourceCheckpoint().orElseThrow();
        assertEquals(expected.directory(), saved.directory()); assertEquals(expected.domains(), saved.domains());
        assertEquals(ParticipantPlacement.Directory.EMPTY, saved.placement()); assertEquals(Optional.of(ParticipantPlacement.Directory.EMPTY), decoded.placement());
        assertFalse(saved.domains().get(A).timeline().domain().sources().isEmpty()); assertFalse(saved.domains().get(C).timeline().domain().buffs().instances().isEmpty());
        assertEquals(ParticipantPlacement.Directory.EMPTY, ResourceSessionAuthority.restore(saved, ResourceCheckpointTest.policies(saved)).authority().checkpoint().placement());
        assertEquals(saved, new ResourceSessionAuthority.Checkpoint(expected.directory(), expected.domains()));
        var previousConstructor = new CheckpointArchive(ResourceCheckpointTest.entries(expected), Optional.of(expected.directory()));
        assertEquals(decoded, previousConstructor); assertEquals(5, decoded.encode().get("format").getAsInt());
        assertEquals(ParticipantPlacement.Directory.EMPTY, ParticipantPlacementCodec.decode(decoded.encode().get("participant_placement")));
    }

    @Test void localArchivesRetainAbsentDirectoriesAndCannotSmuggleParticipantPlacement() {
        for (int version : List.of(1, 2, 3)) {
            var encoded = new CheckpointArchive(List.of()).encode(); encoded.addProperty("format", version);
            var decoded = CheckpointArchive.decode(encoded);
            assertTrue(decoded.resourceDirectory().isEmpty()); assertTrue(decoded.placement().isEmpty()); assertTrue(decoded.resourceCheckpoint().isEmpty());
            encoded.add("participant_placement", ParticipantPlacementCodec.encode(ParticipantPlacement.Directory.EMPTY));
            assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(encoded));
        }
        assertThrows(IllegalArgumentException.class, () -> new CheckpointArchive(List.of(), Optional.empty(), Optional.of(ParticipantPlacement.Directory.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> new CheckpointArchive(List.of(), Optional.of(ResourceAuthority.Directory.EMPTY), Optional.empty()));
    }

    @Test void currentArchiveRequiresBothDirectoriesAndExactVersionedFields() {
        var original = ResourceCheckpointTest.archive(independent()).encode();
        for (String field : List.of("resource_directory", "participant_placement")) {
            var missing = original.deepCopy(); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(missing));
        }
        var disguised = original.deepCopy(); disguised.addProperty("format", 4);
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(disguised));
        var extra = original.deepCopy(); placement(extra).addProperty("inferred", true);
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(extra));
        var missing = original.deepCopy(); placement(missing).remove("buffs");
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(missing));
        var row = original.deepCopy(); character(placement(row)).addProperty("revision", "0");
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(row));
    }

    @Test void placementDecoderRejectsDuplicateHoldersGenerationsAndNoncanonicalNumbers() {
        var original = ParticipantPlacementCodec.encode(independent().placement());
        for (String facet : List.of("characters", "buffs")) {
            var repeated = original.deepCopy(); var rows = repeated.getAsJsonArray(facet); rows.add(rows.get(0).deepCopy());
            assertThrows(IllegalArgumentException.class, () -> ParticipantPlacementCodec.decode(repeated));
        }
        var collision = original.deepCopy(); buff(collision).add("generation", character(collision).get("generation"));
        assertThrows(IllegalArgumentException.class, () -> ParticipantPlacementCodec.decode(collision));
        for (var value : List.of(new JsonPrimitive("03"), new JsonPrimitive("+3"), new JsonPrimitive("3.0"), new JsonPrimitive(3),
                new JsonPrimitive("0"), new JsonPrimitive("-1"), new JsonPrimitive("9223372036854775808"), new JsonPrimitive("8"))) {
            var invalid = original.deepCopy(); character(invalid).add("generation", value);
            assertThrows(IllegalArgumentException.class, () -> ParticipantPlacementCodec.decode(invalid));
        }
        for (var value : List.of(new JsonPrimitive("08"), new JsonPrimitive(8), new JsonPrimitive("0"), new JsonPrimitive("7"))) {
            var invalid = original.deepCopy(); invalid.add("next_generation", value);
            assertThrows(IllegalArgumentException.class, () -> ParticipantPlacementCodec.decode(invalid));
        }
        var holder = original.deepCopy(); character(holder).addProperty("holder", " ");
        assertThrows(IllegalArgumentException.class, () -> ParticipantPlacementCodec.decode(holder));
        var domain = original.deepCopy(); character(domain).addProperty("domain", "world");
        assertThrows(IllegalArgumentException.class, () -> ParticipantPlacementCodec.decode(domain));
    }

    @Test void jointArchiveRejectsMissingDomainsAndMisplacedCharacterOrBuffPartitions() {
        var original = ResourceCheckpointTest.archive(independent()).encode();
        for (String facet : List.of("characters", "buffs")) {
            var missing = original.deepCopy(); placement(missing).getAsJsonArray(facet).get(0).getAsJsonObject().addProperty("domain", "test:absent");
            assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(missing));
            var misplaced = original.deepCopy(); placement(misplaced).getAsJsonArray(facet).get(0).getAsJsonObject().addProperty("domain", B);
            assertThrows(IllegalStateException.class, () -> CheckpointArchive.decode(misplaced));
        }
    }

    @Test void jointWorldSaveKeepsBothPlacementsAndRejectsCorruptionBeforeInvalidatingTheCommittedFile() throws Exception {
        var expected = ResourceCheckpointTest.archive(independent()); var file = directory.resolve("participants.json");
        var store = new WorldCheckpointStore(file); var save = store.beginCoordinated().prepare(expected, Set.of("world", "players"));
        save.confirm("world"); save.confirm("players"); save.commit();
        String committed = Files.readString(file); var damaged = JsonParser.parseString(committed).getAsJsonObject();
        character(placement(damaged.getAsJsonObject("archive"))).addProperty("domain", B); Files.writeString(file, damaged.toString());
        String corrupt = Files.readString(file);
        assertThrows(IllegalStateException.class, store::beginCoordinated); assertEquals(corrupt, Files.readString(file));
        Files.writeString(file, committed);
        assertThrows(IllegalStateException.class, store::begin); assertEquals(committed, Files.readString(file));
        assertEquals(expected, store.beginCoordinated().recovered().orElseThrow());
    }
}
