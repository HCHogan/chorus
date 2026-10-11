package com.imdomestic.chorus.platform.minecraft;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SavedDataSaveJournalTest {
    private static final Path FILE = Path.of("save/data/fixture.dat");
    private static void emptyBatch(SavedDataSaveJournal journal) { journal.schedule(() -> CompletableFuture.completedFuture(null)); }
    @Test void anActualEmptyBatchCanSucceedButANewJournalIsNotEvidence() {
        var journal = new SavedDataSaveJournal(); var before = journal.report();
        assertFalse(before.successful()); emptyBatch(journal); journal.report().requireSuccess();
        assertFalse(before.successful()); assertEquals(0, journal.report().written());
        assertFalse(new SavedDataSaveJournal().report().successful());
    }
    @Test void anEmptyLaterBatchAndNormalCloseCannotHideAnEarlierPendingWrite() throws Exception {
        var journal = new SavedDataSaveJournal(); var write = new CompletableFuture<Void>();
        assertSame(write, journal.schedule(() -> write)); emptyBatch(journal);
        var proof = journal.completion(); assertFalse(proof.isDone());
        journal.close(() -> emptyBatch(journal)); assertTrue(journal.report().closed()); assertFalse(journal.report().successful());
        write.complete(null); proof.get(5, TimeUnit.SECONDS).requireSuccess(); assertEquals(3, journal.report().scheduled());
    }
    @Test void swallowedFileFailureReturnsEvidencePromptlyWithoutCancellingPendingBatches() throws Exception {
        var journal = new SavedDataSaveJournal(); var batch = new CompletableFuture<Void>(); journal.schedule(() -> batch);
        var proof = journal.completion(); var failure = new IOException("disk");
        assertSame(failure, assertThrows(IOException.class, () -> journal.io(SavedDataSaveJournal.Kind.WRITE, FILE, () -> { throw failure; })));
        var report = proof.get(5, TimeUnit.SECONDS); assertFalse(report.successful()); assertEquals(1, report.pending().size()); assertFalse(batch.isDone());
        assertEquals(FILE.toAbsolutePath().normalize().toString(), report.failures().getFirst().operation().target());
        batch.complete(null); journal.io(SavedDataSaveJournal.Kind.DIRECTORY, FILE.getParent(), () -> {});
        journal.io(SavedDataSaveJournal.Kind.WRITE, FILE, () -> {}); emptyBatch(journal);
        assertEquals(1, journal.report().written()); assertFalse(journal.report().successful()); assertEquals(0, report.written());
    }
    @Test void synchronousEncodingErrorsCancelledBatchesAndFailedCloseKeepOriginalOutcomes() {
        var journal = new SavedDataSaveJournal(); var failure = new IllegalStateException("encode");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> journal.schedule(() -> { throw failure; })));
        var cancelled = new CompletableFuture<Void>(); assertSame(cancelled, journal.schedule(() -> cancelled)); cancelled.cancel(false);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> journal.close(() -> { throw failure; })));
        emptyBatch(journal); assertEquals(3, journal.report().failures().size()); assertTrue(journal.report().pending().isEmpty());
        assertFalse(journal.report().closed()); assertFalse(journal.report().successful());
    }
    @Test void evidenceIncludesLaterPendingWorkWithoutClaimingItWasAwaited() throws Exception {
        var journal = new SavedDataSaveJournal(); var first = new CompletableFuture<Void>(); var second = new CompletableFuture<Void>();
        journal.schedule(() -> first); var proof = journal.completion(); journal.schedule(() -> second); first.complete(null);
        var report = proof.get(5, TimeUnit.SECONDS); assertFalse(report.successful()); assertEquals(1, report.pending().size());
        second.complete(null); journal.completion().get(5, TimeUnit.SECONDS).requireSuccess();
    }
    @Test void closeWaitingOnAnIoThreadDoesNotHoldTheCompletionLock() throws Exception {
        var journal = new SavedDataSaveJournal(); var batch = new CompletableFuture<Void>(); journal.schedule(() -> batch);
        try (var executor = Executors.newSingleThreadExecutor()) {
            journal.close(() -> {
                try {
                    executor.submit(() -> {
                        journal.io(SavedDataSaveJournal.Kind.WRITE, FILE, () -> {}); batch.complete(null); return null;
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
        journal.completion().get(5, TimeUnit.SECONDS).requireSuccess(); assertEquals(1, journal.report().written());
    }
}
