package com.imdomestic.chorus.platform.minecraft;

import static com.imdomestic.chorus.platform.minecraft.RegionSaveJournal.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RegionSaveJournalTest {
    private static void complete(RegionSaveJournal journal, RegionSaveJournal.Kind kind) {
        journal.submit(kind, "fixture", _ -> CompletableFuture.completedFuture(null));
    }
    @Test void flushMustFollowEveryWriteAndDrainOrCloseCannotSubstituteForIt() {
        var journal = new RegionSaveJournal(); assertFalse(journal.report().successful());
        complete(journal, WRITE); complete(journal, DRAIN); assertFalse(journal.report().successful());
        complete(journal, FLUSH); var first = journal.report(); first.requireSuccess();
        complete(journal, WRITE); assertFalse(journal.report().successful()); assertTrue(first.successful());
        complete(journal, FLUSH); complete(journal, CLOSE); journal.report().requireSuccess(); assertTrue(journal.report().closed());
        var unflushed = new RegionSaveJournal(); complete(unflushed, CLOSE); assertFalse(unflushed.report().successful());
    }
    @Test void aFlushCannotCoverAWritingOperationSubmittedAfterItStarted() throws Exception {
        var journal = new RegionSaveJournal(); var flush = new CompletableFuture<Void>();
        complete(journal, WRITE); assertSame(flush, journal.submit(FLUSH, "all", _ -> flush));
        var observed = journal.completion(); assertFalse(observed.isDone()); complete(journal, WRITE);
        flush.complete(null); assertFalse(observed.get(5, TimeUnit.SECONDS).successful());
        complete(journal, FLUSH); journal.report().requireSuccess();
    }
    @Test void failedAndCancelledWritesSurviveLaterSuccessfulFlushesAndKeepOriginalFutures() throws Exception {
        var journal = new RegionSaveJournal(); var write = new CompletableFuture<Void>(); var cancelled = new CompletableFuture<Void>();
        assertSame(write, journal.submit(WRITE, "chunk:1", _ -> write)); journal.submit(WRITE, "chunk:2", _ -> cancelled);
        var failure = new IOException("disk"); write.completeExceptionally(failure); cancelled.cancel(false);
        assertSame(failure, assertThrows(CompletionException.class, write::join).getCause());
        complete(journal, FLUSH); var report = journal.completion().get(5, TimeUnit.SECONDS);
        assertEquals(2, report.failures().size()); assertTrue(report.pending().isEmpty()); assertThrows(IllegalStateException.class, report::requireSuccess);
        complete(journal, WRITE); complete(journal, FLUSH); assertFalse(journal.report().successful());
        var other = new RegionSaveJournal(); complete(other, FLUSH); other.report().requireSuccess();
    }
    @Test void thrownSubmissionAndSwallowedCloseErrorsRemainFailures() {
        var journal = new RegionSaveJournal(); var failure = new IllegalArgumentException("submission");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> journal.submit(WRITE, "chunk", _ -> { throw failure; })));
        complete(journal, FLUSH);
        journal.submit(CLOSE, "worker", _ -> { journal.failedClose(new IOException("close")); return CompletableFuture.completedFuture(null); });
        assertEquals(2, journal.report().failures().size()); assertTrue(journal.report().closed()); assertTrue(journal.report().pending().isEmpty());
        assertFalse(journal.report().successful());
    }
    @Test void supplierErrorsAreVisibleEvenIfVanillaNeverCompletesItsFuture() {
        var journal = new RegionSaveJournal(); var write = new CompletableFuture<Void>(); var attempt = new AtomicReference<RegionSaveJournal.Attempt>();
        journal.submit(WRITE, "chunk", observed -> { attempt.set(observed); return write; });
        attempt.get().failed(new IllegalStateException("encoding"));
        assertEquals(1, journal.report().failures().size()); assertEquals(1, journal.report().pending().size()); assertFalse(journal.completion().join().successful());
        write.complete(null); complete(journal, FLUSH); assertFalse(journal.report().successful()); // No later success erases the error.
    }
    @Test void completionsCanRunWhileNativeCloseWaitsWithoutLockingTheIoThread() throws Exception {
        var journal = new RegionSaveJournal(); var write = new CompletableFuture<Void>(); journal.submit(WRITE, "chunk", _ -> write);
        try (var executor = Executors.newSingleThreadExecutor()) {
            journal.submit(CLOSE, "worker", _ -> {
                try { executor.submit(() -> write.complete(null)).get(5, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError(error); }
                return CompletableFuture.completedFuture(null);
            });
        }
        assertTrue(journal.completion().get(5, TimeUnit.SECONDS).pending().isEmpty());
        complete(journal, FLUSH); assertFalse(journal.report().successful()); // Native work after close cannot repair proof.
    }
}
