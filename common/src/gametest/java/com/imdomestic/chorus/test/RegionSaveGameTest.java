package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.platform.minecraft.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.storage.*;

/** Native asynchronous region files, including an earlier failed write followed by a successful flush. */
public class RegionSaveGameTest {
    private static final ChunkPos POS = new ChunkPos(3, 4);
    private static final class Storage implements AutoCloseable {
        final Path root = Files.createTempDirectory("chorus-region-save-");
        final Path folder = root.resolve("region");
        final SimpleRegionStorage nativeStorage;
        final RegionSaveJournal journal;
        Storage(String type) throws Exception {
            nativeStorage = open(folder, type);
            journal = ((MinecraftRegionSaveAccess) nativeStorage.chunkScanner()).chorus$regionSaves();
        }
        @Override public void close() throws Exception {
            try { nativeStorage.close(); }
            finally { try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); } }
        }
    }
    private static SimpleRegionStorage open(Path folder, String type) {
        return new SimpleRegionStorage(new RegionStorageInfo("chorus-fixture", Level.OVERWORLD, type), folder, DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }
    private static CompoundTag tag(int number) { var tag = new CompoundTag(); tag.putInt("probe", number); return tag; }
    private static <T> T waitFor(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void refused(GameTestHelper h, Runnable action) {
        boolean refused = false; try { action.run(); } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "Incomplete region save was accepted");
    }
    @GameCase public void writesAndDeletesNeedARealFlushBeforeTheyCanConfirmStorage(GameTestHelper h) throws Exception {
        // These labels share the same native worker protocol; this does not substitute for entity/POI serialization tests.
        for (String type : List.of("chunk", "entities", "poi")) try (var storage = new Storage(type)) {
            h.assertTrue(!storage.journal.report().successful(), "unflushed fresh worker was accepted");
            waitFor(storage.nativeStorage.write(POS, tag(1))); waitFor(storage.nativeStorage.synchronize(false));
            h.assertTrue(!waitFor(storage.journal.completion()).successful(), "drain counted as flush");
            waitFor(storage.nativeStorage.synchronize(true)); waitFor(storage.journal.completion()).requireSuccess();
            waitFor(storage.nativeStorage.write(POS, () -> tag(2)));
            h.assertTrue(!waitFor(storage.journal.completion()).successful(), "old flush covered new write");
            waitFor(storage.nativeStorage.synchronize(true)); waitFor(storage.journal.completion()).requireSuccess();
            try (var reader = open(storage.folder, type)) {
                h.assertValueEqual(waitFor(reader.read(POS)).orElseThrow().getIntOr("probe", -1), 2, "actual persisted region data");
            }
            waitFor(storage.nativeStorage.write(POS, IOWorker.STORE_EMPTY));
            h.assertTrue(!waitFor(storage.journal.completion()).successful(), "region deletion was not tracked");
            waitFor(storage.nativeStorage.synchronize(true)); waitFor(storage.journal.completion()).requireSuccess();
            try (var reader = open(storage.folder, type)) { h.assertTrue(waitFor(reader.read(POS)).isEmpty(), "actual region deletion"); }
            var store = new WorldCheckpointStore(storage.root.resolve("checkpoint.json"));
            var save = store.begin().prepare(new CheckpointArchive(List.of()), Set.of("region"));
            storage.nativeStorage.close(); storage.journal.report().requireSuccess(); save.confirm("region"); save.commit();
            h.assertTrue(store.begin().recovered().orElseThrow().dimensions().isEmpty(), "explicit confirmed empty-runtime archive");
        }
        h.succeed();
    }
    @GameCase public void anEarlierWriteFailureSurvivesASuccessfulFlushAndCannotConfirmTheArchive(GameTestHelper h) throws Exception {
        try (var broken = new Storage("entities"); var healthy = new Storage("entities")) {
            Files.writeString(broken.folder, "region directory obstructed");
            var error = waitFor(broken.nativeStorage.write(POS, tag(1)).handle((_, failure) -> failure));
            h.assertTrue(error != null, "native asynchronous write should fail");
            // The failed PendingStore is already removed. Vanilla's later synchronize can succeed.
            waitFor(broken.nativeStorage.synchronize(true)); var report = waitFor(broken.journal.completion());
            h.assertTrue(report.pending().isEmpty() && report.failures().size() == 1, "completed failure evidence was lost");
            refused(h, report::requireSuccess);
            Files.delete(broken.folder); waitFor(broken.nativeStorage.write(POS, tag(2))); waitFor(broken.nativeStorage.synchronize(true));
            refused(h, waitFor(broken.journal.completion())::requireSuccess);
            waitFor(healthy.nativeStorage.write(POS, tag(3))); waitFor(healthy.nativeStorage.synchronize(true));
            waitFor(healthy.journal.completion()).requireSuccess(); // Same storage labels, different worker and world path.
            var store = new WorldCheckpointStore(broken.root.resolve("checkpoint.json"));
            var save = store.begin().prepare(new CheckpointArchive(List.of()), Set.of("region"));
            refused(h, () -> { broken.journal.report().requireSuccess(); save.confirm("region"); });
            save.failed(broken.journal.report().toString());
            refused(h, () -> { try { save.commit(); } catch (java.io.IOException io) { throw new java.io.UncheckedIOException(io); } });
            refused(h, () -> { try { store.begin(); } catch (java.io.IOException io) { throw new java.io.UncheckedIOException(io); } });
        }
        h.succeed();
    }
    @GameCase public void queuedAndCoalescedWritesRemainPendingUntilTheirActualCompletion(GameTestHelper h) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var storage = new Storage("chunk")) {
            try {
                var first = storage.nativeStorage.write(POS, () -> {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture supplier release timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    return tag(1);
                });
                h.assertTrue(entered.await(10, TimeUnit.SECONDS), "native supplier did not start");
                var second = storage.nativeStorage.write(POS, tag(2)); var flush = storage.nativeStorage.synchronize(true);
                var proof = storage.journal.completion();
                h.assertValueEqual(storage.journal.report().pending().size(), 3, "two queued writes and flush");
                h.assertTrue(!proof.isDone() && !storage.journal.report().successful(), "queued I/O was accepted as completed");
                release.countDown(); waitFor(first); waitFor(second); waitFor(flush); waitFor(proof).requireSuccess();
                try (var reader = open(storage.folder, "chunk")) {
                    h.assertValueEqual(waitFor(reader.read(POS)).orElseThrow().getIntOr("probe", -1), 2, "latest coalesced data persisted");
                }
            } finally { release.countDown(); }
        }
        h.succeed();
    }
    private static Object field(Object object, Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    @GameCase public void aSupplierFailureReturnsFailedEvidenceEvenWhenTheNativeFutureNeverEnds(GameTestHelper h) throws Exception {
        try (var storage = new Storage("chunk")) {
            var write = storage.nativeStorage.write(POS, () -> { throw new IllegalStateException("expected fixture serialization failure"); });
            var report = waitFor(storage.journal.completion());
            refused(h, report::requireSuccess);
            h.assertTrue(!write.isDone(), "fixture no longer reproduces the native unresolved future");
            h.assertValueEqual(report.pending().size(), 1, "unfinished native write retained");
            h.assertTrue(report.failures().getFirst().cause().contains("expected fixture serialization failure"), "supplier failure absent");
            waitFor(storage.nativeStorage.synchronize(true)); // No PendingStore was created before supplier failure.
            refused(h, waitFor(storage.journal.completion())::requireSuccess);
        }
        h.succeed();
    }
    @GameCase public void realFlushAndSwallowedCloseErrorsInvalidateAnEarlierSuccessfulReport(GameTestHelper h) throws Exception {
        try (var storage = new Storage("poi")) {
            waitFor(storage.nativeStorage.write(POS, tag(1))); waitFor(storage.nativeStorage.synchronize(true));
            waitFor(storage.journal.completion()).requireSuccess();
            // Close only this fixture's actual channel to inject native force/close IOException paths.
            var files = field(storage.nativeStorage.chunkScanner(), IOWorker.class, "storage");
            var cache = (Map<?, ?>) field(files, RegionFileStorage.class, "regionCache");
            var region = ((Optional<?>) cache.values().iterator().next()).orElseThrow();
            ((FileChannel) field(region, RegionFile.class, "file")).close();
            h.assertTrue(waitFor(storage.nativeStorage.synchronize(true).handle((_, failure) -> failure)) != null, "native force should fail");
            refused(h, waitFor(storage.journal.completion())::requireSuccess);
            storage.nativeStorage.close(); // IOWorker logs and swallows the RegionFileStorage.close exception.
            var report = storage.journal.report(); refused(h, report::requireSuccess);
            h.assertTrue(report.closed() && report.pending().isEmpty(), "native close observation unfinished");
            h.assertTrue(report.failures().stream().anyMatch(f -> f.operation().kind() == RegionSaveJournal.Kind.FLUSH), "missing failed flush");
            h.assertTrue(report.failures().stream().anyMatch(f -> f.operation().kind() == RegionSaveJournal.Kind.CLOSE), "swallowed close error was lost");
        }
        h.succeed();
    }
}
