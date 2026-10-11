package com.imdomestic.chorus.platform.minecraft;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SaveSerializationCaptureTest {
    @Test void nestedScopesAttributeProblemsToTheCurrentWriterAndCleanUpAfterExceptions() {
        var outer = new ArrayList<String>(); var inner = new ArrayList<String>(); var failure = new IllegalStateException("encode");
        SaveSerializationCapture.observe(outer::add, () -> {
            SaveSerializationCapture.problem("outer");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> SaveSerializationCapture.observe(inner::add, () -> { throw failure; })));
            SaveSerializationCapture.problem("resumed"); return null;
        });
        SaveSerializationCapture.problem("after"); assertEquals(List.of("outer", "resumed"), outer);
        assertEquals(List.of(failure.toString()), inner);
    }
    @Test void aCaughtSerializationErrorStillInvalidatesACompletedFileReplacement() {
        var path = Path.of("save/player.dat"); var scope = MinecraftSaveCapture.observe(Set.of(path));
        try (scope) {
            MinecraftSaveCapture.saving(path, () -> {
                MinecraftSaveCapture.replaced(path, true);
                SaveSerializationCapture.problem("missing serialized field");
            });
        }
        var report = scope.report(); assertThrows(IllegalStateException.class, report::requireSuccess);
        assertEquals(MinecraftSaveCapture.Outcome.SERIALIZATION_FAILED, report.writes().getFirst().outcome());
    }
    @Test void serializationScopeIsNotInheritedByOtherThreads() throws Exception {
        var messages = new ArrayList<String>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            SaveSerializationCapture.observe(messages::add, () -> {
                try { executor.submit(() -> SaveSerializationCapture.problem("another thread")).get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                return null;
            });
        }
        assertTrue(messages.isEmpty());
    }
    @Test void anEncodingOperationRemainsPendingAndItsFailureCannotBeErasedByFlush() {
        var journal = new RegionSaveJournal(); var failure = new IllegalStateException("serialize");
        journal.submit(RegionSaveJournal.Kind.FLUSH, "all", _ -> CompletableFuture.completedFuture(null));
        journal.encode("chunk", attempt -> {
            assertFalse(journal.report().successful()); assertEquals(RegionSaveJournal.Kind.ENCODE, journal.report().pending().getFirst().kind());
            attempt.failed(failure); return "partial data";
        });
        journal.submit(RegionSaveJournal.Kind.FLUSH, "all", _ -> CompletableFuture.completedFuture(null));
        assertFalse(journal.report().successful()); assertEquals(1, journal.report().failures().size());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> journal.encode("another", _ -> { throw failure; })));
    }
}
