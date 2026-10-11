package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.load;
import com.google.gson.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldCheckpointStoreTest {
    @TempDir Path directory;
    private static EffectSession.Checkpoint effects() throws Exception {
        return new EffectSession(load("periodic"), EffectCheckpointTest.clock(), 1, EffectState.empty(), _ -> { throw new AssertionError(); }).checkpoint();
    }
    private static CheckpointArchive archive(long tick) throws Exception {
        return new CheckpointArchive(List.of(new CheckpointArchive.Entry("test:program", "minecraft:overworld", tick, effects(),
                Set.of(UUID.fromString("12345678-1234-5678-9012-123456789012")))));
    }
    private Path file() { return directory.resolve("world.json"); }
    private WorldCheckpointStore store() { return new WorldCheckpointStore(file()); }
    @Test void completeMetadataRoundTripsAndOrderingDoesNotDependOnMapIteration() throws Exception {
        var a = archive(9_007_199_254_740_993L).dimensions().getFirst();
        var b = new CheckpointArchive.Entry("test:other", "minecraft:the_end", Long.MAX_VALUE, effects(), Set.of());
        var left = new CheckpointArchive(List.of(a, b)); var right = new CheckpointArchive(List.of(b, a));
        assertEquals(left, right); assertEquals(left.encode().toString(), right.encode().toString());
        assertEquals(left, CheckpointArchive.decode(JsonParser.parseString(left.encode().toString())));
        assertEquals(new CheckpointArchive(List.of()), CheckpointArchive.decode(new CheckpointArchive(List.of()).encode()));
    }
    @Test void malformedArchiveCannotHideMissingFieldsDuplicateDimensionsOwnersOrWrongTypes() throws Exception {
        var original = archive(7).encode(); var missing = original.deepCopy(); missing.remove("format");
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(missing));
        var extra = original.deepCopy(); extra.addProperty("extra", true); assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(extra));
        var unsupported = original.deepCopy(); unsupported.addProperty("format", 2); assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(unsupported));
        var repeated = original.deepCopy(); repeated.getAsJsonArray("dimensions").add(repeated.getAsJsonArray("dimensions").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(repeated));
        var owners = original.deepCopy(); var list = owners.getAsJsonArray("dimensions").get(0).getAsJsonObject().getAsJsonArray("equipment_owners"); list.add(list.get(0));
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(owners));
        for (String field : List.of("game_time", "program", "dimension")) {
            var wrong = original.deepCopy(); wrong.getAsJsonArray("dimensions").get(0).getAsJsonObject().addProperty(field, 4);
            assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(wrong));
        }
        assertThrows(IllegalArgumentException.class, () -> new CheckpointArchive.Entry("program", "minecraft:overworld", 7, effects(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CheckpointArchive.Entry("test:program", "minecraft:overworld", -1, effects(), Set.of()));
        var invalidUuid = original.deepCopy(); invalidUuid.getAsJsonArray("dimensions").get(0).getAsJsonObject().getAsJsonArray("equipment_owners").set(0, new JsonPrimitive("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(invalidUuid));
    }
    @Test void beginningInvalidatesAnOldCheckpointBeforeItCanBeUsed() throws Exception {
        var session = store().begin(); assertTrue(session.recovered().isEmpty());
        assertThrows(IllegalStateException.class, () -> store().begin()); // Running/crashed is not an empty save.
        var expected = archive(7); var save = session.prepare(expected, Set.of("players", "world"));
        save.confirm("players"); save.confirm("world"); save.commit();
        var recovered = store().begin(); assertEquals(expected, recovered.recovered().orElseThrow());
        assertThrows(IllegalStateException.class, () -> store().begin());
        assertThrows(IllegalStateException.class, save::commit);
        assertThrows(IllegalStateException.class, () -> save.failed("late failure from former run"));
        var next = recovered.prepare(archive(9), Set.of("world")); next.confirm("world"); next.commit();
        assertEquals(archive(9), store().begin().recovered().orElseThrow());
    }
    @Test void candidateFileAndPartialOrDuplicateConfirmationsAreNotSuccessfulWorldSaves() throws Exception {
        var session = store().begin(); var save = session.prepare(archive(7), Set.of("player:one", "dimension:overworld"));
        assertThrows(IllegalStateException.class, () -> store().begin()); assertThrows(IllegalStateException.class, save::commit);
        save.confirm("player:one"); save.confirm("player:one"); assertThrows(IllegalStateException.class, save::commit);
        assertThrows(IllegalArgumentException.class, () -> save.confirm("unrequested"));
        save.confirm("dimension:overworld"); save.commit(); assertTrue(store().begin().recovered().isPresent());
    }
    @Test void explicitWorldWriteFailureRetainsDataAndCannotBeMadeSuccessfulByMoreReceipts() throws Exception {
        var session = store().begin(); var expected = archive(7); var save = session.prepare(expected, Set.of("players", "world"));
        save.confirm("players"); save.failed("entity region write failed");
        assertThrows(IllegalStateException.class, () -> save.confirm("world")); assertThrows(IllegalStateException.class, save::commit);
        assertThrows(IllegalStateException.class, () -> store().begin());
        var data = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        assertEquals(expected, CheckpointArchive.decode(data.get("archive"))); assertEquals("entity region write failed", data.get("failure").getAsString());
    }
    @Test void staleCompletionCannotConfirmOrInvalidateAReplacementSave() throws Exception {
        var session = store().begin(); var first = session.prepare(archive(7), Set.of("world")); first.confirm("world");
        var second = session.prepare(archive(8), Set.of("world"));
        assertThrows(IllegalStateException.class, () -> first.confirm("world")); assertThrows(IllegalStateException.class, first::commit);
        assertThrows(IllegalStateException.class, () -> first.failed("old asynchronous error"));
        assertThrows(IllegalStateException.class, second::commit); second.confirm("world"); second.commit();
        assertEquals(archive(8), store().begin().recovered().orElseThrow());
    }
    @Test void lateFailureInvalidatesOnlyTheSameCommittedSave() throws Exception {
        var session = store().begin(); var save = session.prepare(archive(7), Set.of("world")); save.confirm("world"); save.commit();
        save.failed("host acknowledged flush too early"); assertThrows(IllegalStateException.class, () -> store().begin());
        assertThrows(IllegalStateException.class, () -> session.prepare(archive(8), Set.of("world")));
    }
    @Test void encodingFailureDoesNotReplaceAValidCandidateOrItsCompletionHandle() throws Exception {
        var session = store().begin(); var expected = archive(7); var save = session.prepare(expected, Set.of("world"));
        String before = Files.readString(file()); var effect = effects();
        var invalid = new EffectSession.Checkpoint(effect.program(), new TimelineEngine.Checkpoint<>(effect.program().version(), effect.timeline().domain(), 1, 1, 1));
        var broken = new CheckpointArchive(List.of(new CheckpointArchive.Entry("test:program", "minecraft:overworld", 7, invalid, Set.of())));
        assertThrows(IllegalArgumentException.class, () -> session.prepare(broken, Set.of("world"))); assertEquals(before, Files.readString(file()));
        save.confirm("world"); save.commit(); assertEquals(expected, store().begin().recovered().orElseThrow());
        try (var files = Files.list(directory)) { assertEquals(List.of(file()), files.toList()); }
    }
    @Test void missingProofCannotBeForgedByChangingPhaseAndCorruptionNeverFallsBackToEmpty() throws Exception {
        var session = store().begin(); session.prepare(archive(7), Set.of("world"));
        var json = JsonParser.parseString(Files.readString(file())).getAsJsonObject(); json.addProperty("phase", "COMMITTED"); Files.writeString(file(), json.toString());
        assertThrows(IllegalArgumentException.class, () -> store().begin()); assertEquals(json.toString(), Files.readString(file()));
        Files.writeString(file(), "{"); assertThrows(RuntimeException.class, () -> store().begin()); assertEquals("{", Files.readString(file()));
    }
    @Test void changedCandidateAndNewRunAreRejectedBeforeOldSessionWrites() throws Exception {
        var storage = store(); var session = storage.begin(); var save = session.prepare(archive(7), Set.of("world")); save.confirm("world");
        var altered = JsonParser.parseString(Files.readString(file())).getAsJsonObject(); altered.add("archive", archive(8).encode()); Files.writeString(file(), altered.toString());
        assertThrows(IllegalStateException.class, save::commit);
        var replacement = session.prepare(archive(9), Set.of("world")); replacement.confirm("world"); replacement.commit();
        storage.begin(); String before = Files.readString(file());
        assertThrows(IllegalStateException.class, () -> replacement.failed("old run")); assertEquals(before, Files.readString(file()));
    }
    @Test void callerMustNameRequiredSavesAndStopWorkAfterCommit() throws Exception {
        var session = store().begin();
        assertThrows(IllegalArgumentException.class, () -> session.prepare(archive(1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> session.prepare(archive(1), Set.of(" ")));
        var save = session.prepare(new CheckpointArchive(List.of()), Set.of("world")); save.confirm("world"); save.commit();
        assertThrows(IllegalStateException.class, () -> save.confirm("world")); assertThrows(IllegalStateException.class, () -> session.prepare(archive(2), Set.of("world")));
        assertEquals(List.of(), store().begin().recovered().orElseThrow().dimensions());
    }
}
