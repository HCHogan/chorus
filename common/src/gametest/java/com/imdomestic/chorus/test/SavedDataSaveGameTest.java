package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.Codec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.*;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.storage.SavedDataStorage;

/** Real SavedDataStorage encoding, asynchronous batches and caught file errors. */
public class SavedDataSaveGameTest {
    private static final class Probe extends SavedData {
        final int value;
        Probe(int value) { this.value = value; }
    }
    private static final Codec<Probe> CODEC = Codec.INT.fieldOf("value").xmap(Probe::new, value -> value.value).codec();
    private static SavedDataType<Probe> type(String name) { return type(name, CODEC); }
    private static SavedDataType<Probe> type(String name, Codec<Probe> codec) {
        return new SavedDataType<>(Identifier.fromNamespaceAndPath("chorus_gametest", name), () -> new Probe(0), codec, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    }
    private static final class Storage implements AutoCloseable {
        final Path root = Files.createTempDirectory("chorus-saved-data-");
        final Path folder = root.resolve("data");
        final SavedDataStorage nativeStorage;
        final SavedDataSaveJournal journal;
        boolean closed;
        Storage(GameTestHelper h) throws Exception {
            nativeStorage = new SavedDataStorage(folder, DataFixers.getDataFixer(), h.getLevel().registryAccess());
            journal = ((MinecraftSavedDataSaveAccess) nativeStorage).chorus$savedDataSaves();
        }
        Path file(SavedDataType<?> type) { return type.id().withSuffix(".dat").resolveAgainst(folder); }
        void finish() { if (!closed) { nativeStorage.close(); closed = true; } }
        void blockOn(CompletableFuture<?> future) throws Exception {
            var field = SavedDataStorage.class.getDeclaredField("pendingWriteFuture"); field.setAccessible(true); field.set(nativeStorage, future);
        }
        @Override public void close() throws Exception {
            try { finish(); }
            finally { try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); } }
        }
    }
    private static <T> T waitFor(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static int read(Path file) throws Exception {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data").getIntOr("value", -1);
    }
    private static void refused(GameTestHelper h, Runnable action) {
        boolean refused = false; try { action.run(); } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "Incomplete saved data was accepted");
    }
    @GameCase public void actualFilesCompleteAcrossIndividualAndPartitionedBatches(GameTestHelper h) throws Exception {
        try (var storage = new Storage(h)) {
            refused(h, storage.journal.report()::requireSuccess);
            var first = type("single"); storage.nativeStorage.set(first, new Probe(7));
            waitFor(storage.nativeStorage.scheduleSave()); waitFor(storage.journal.completion()).requireSuccess();
            h.assertValueEqual(read(storage.file(first)), 7, "actual NBT file");
            int count = Util.maxAllowedExecutorThreads() + 1; var types = new ArrayList<SavedDataType<Probe>>();
            for (int i = 0; i < count; i++) { var type = type("partitioned/" + i); types.add(type); storage.nativeStorage.set(type, new Probe(i)); }
            waitFor(storage.nativeStorage.scheduleSave()); var report = waitFor(storage.journal.completion()); report.requireSuccess();
            h.assertValueEqual(report.written(), (long) count + 1, "both native scheduling branches wrote every file");
            for (int i = 0; i < count; i++) h.assertValueEqual(read(storage.file(types.get(i))), i, "partitioned NBT content");
            storage.finish(); storage.journal.report().requireSuccess(); h.assertTrue(storage.journal.report().closed(), "close not observed");
        }
        h.succeed();
    }
    @GameCase public void swallowedDirectoryFailureAndClearedDirtyFlagsCannotConfirmTheArchive(GameTestHelper h) throws Exception {
        try (var broken = new Storage(h); var healthy = new Storage(h)) {
            var type = type("same_id"); var data = new Probe(9); broken.nativeStorage.set(type, data);
            Files.writeString(broken.folder, "blocked directory");
            waitFor(broken.nativeStorage.scheduleSave()); // Native future succeeds despite caught IOException.
            h.assertTrue(!data.isDirty(), "native dirty flag was not cleared before the failed write");
            waitFor(broken.nativeStorage.scheduleSave()); var report = waitFor(broken.journal.completion());
            refused(h, report::requireSuccess); h.assertValueEqual(report.written(), 0L, "no actual file completed");
            h.assertTrue(report.failures().stream().anyMatch(f -> f.operation().kind() == SavedDataSaveJournal.Kind.DIRECTORY), "caught directory error lost");
            healthy.nativeStorage.set(type, new Probe(12)); waitFor(healthy.nativeStorage.scheduleSave());
            waitFor(healthy.journal.completion()).requireSuccess(); h.assertValueEqual(read(healthy.file(type)), 12, "separate instance with same ID");
            Files.delete(broken.folder); data.setDirty(); waitFor(broken.nativeStorage.scheduleSave());
            h.assertValueEqual(read(broken.file(type)), 9, "later actual save succeeded"); refused(h, waitFor(broken.journal.completion())::requireSuccess);
            var store = new WorldCheckpointStore(broken.root.resolve("checkpoint.json"));
            var save = store.begin().prepare(new CheckpointArchive(List.of()), Set.of("saved_data"));
            refused(h, () -> { broken.journal.report().requireSuccess(); save.confirm("saved_data"); });
            save.failed(broken.journal.report().toString());
            refused(h, () -> { try { save.commit(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
            refused(h, () -> { try { store.begin(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
        }
        h.succeed();
    }
    @GameCase public void aCaughtNbtWriteFailureIsDistinctFromADirectoryPreparationFailure(GameTestHelper h) throws Exception {
        try (var storage = new Storage(h)) {
            var type = type("occupied_file"); var file = storage.file(type); Files.createDirectories(file);
            Files.writeString(file.resolve("retained"), "untouched fixture"); storage.nativeStorage.set(type, new Probe(3));
            waitFor(storage.nativeStorage.scheduleSave()); var report = waitFor(storage.journal.completion());
            refused(h, report::requireSuccess); h.assertValueEqual(report.written(), 0L, "failed NBT write counted as completed");
            h.assertTrue(report.failures().stream().anyMatch(f -> f.operation().kind() == SavedDataSaveJournal.Kind.WRITE
                    && f.operation().target().equals(file.toAbsolutePath().normalize().toString())), "actual file failure path missing");
            h.assertTrue(report.failures().stream().noneMatch(f -> f.operation().kind() == SavedDataSaveJournal.Kind.DIRECTORY), "directory preparation did not fail");
            h.assertValueEqual(Files.readString(file.resolve("retained")), "untouched fixture", "native failed write preserved directory contents");
        }
        h.succeed();
    }
    @GameCase public void emptySaveAndNativeCloseDoNotAwaitAnEarlierBatchButTheJournalDoes(GameTestHelper h) throws Exception {
        var release = new CompletableFuture<Void>();
        try (var storage = new Storage(h)) {
            CompletableFuture<?> batch = null;
            try {
                storage.blockOn(release); var type = type("delayed"); storage.nativeStorage.set(type, new Probe(17));
                batch = storage.nativeStorage.scheduleSave(); var empty = storage.nativeStorage.scheduleSave();
                h.assertTrue(empty.isDone() && !batch.isDone(), "fixture did not reproduce native empty-batch behavior");
                var proof = storage.journal.completion(); storage.finish();
                h.assertTrue(storage.journal.report().closed() && !proof.isDone(), "normal close hid earlier native work");
                refused(h, storage.journal.report()::requireSuccess); h.assertTrue(!Files.exists(storage.file(type)), "delayed batch unexpectedly wrote early");
                release.complete(null); waitFor(batch); waitFor(proof).requireSuccess();
                h.assertValueEqual(read(storage.file(type)), 17, "earlier batch wrote after native close returned");
            } finally { release.complete(null); if (batch != null) waitFor(batch); }
        }
        h.succeed();
    }
    @GameCase public void encodingFailureRetainsEvidenceAfterEarlierEntriesLostTheirDirtyFlag(GameTestHelper h) throws Exception {
        var calls = new AtomicInteger(); var failure = new IllegalStateException("expected saved data encoder failure");
        var codec = Codec.INT.xmap(Probe::new, value -> { if (calls.incrementAndGet() == 2) throw failure; return value.value; });
        var first = new Probe(1); var second = new Probe(2);
        try (var storage = new Storage(h)) {
            var a = type("encode_a", codec); var b = type("encode_b", codec); storage.nativeStorage.set(a, first); storage.nativeStorage.set(b, second);
            try {
                Throwable observed = null; try { storage.nativeStorage.scheduleSave(); } catch (IllegalStateException error) { observed = error; }
                h.assertTrue(observed == failure, "original encoding exception changed");
                h.assertTrue(first.isDirty() != second.isDirty(), "fixture did not partially clear native dirty flags");
                var report = waitFor(storage.journal.completion()); refused(h, report::requireSuccess);
                h.assertTrue(report.pending().isEmpty() && report.written() == 0, "failed encoding scheduled physical writes");
                h.assertTrue(!Files.exists(storage.file(a)) && !Files.exists(storage.file(b)), "partial collection wrote a file");
                first.setDirty(false); second.setDirty(false); waitFor(storage.nativeStorage.scheduleSave());
                refused(h, waitFor(storage.journal.completion())::requireSuccess);
            } finally { first.setDirty(false); second.setDirty(false); }
        }
        h.succeed();
    }
    @GameCase public void anEarlierExceptionalBatchPreventsChainedFilesAndCannotBecomeAnEmptySuccess(GameTestHelper h) throws Exception {
        var release = new CompletableFuture<Void>(); var failure = new IllegalStateException("expected earlier batch failure");
        try (var storage = new Storage(h)) {
            try {
                storage.blockOn(release); var first = type("first"); var second = type("second");
                storage.nativeStorage.set(first, new Probe(1)); var a = storage.nativeStorage.scheduleSave();
                storage.nativeStorage.set(second, new Probe(2)); var b = storage.nativeStorage.scheduleSave();
                release.completeExceptionally(failure);
                h.assertTrue(waitFor(a.handle((_, error) -> error)) != null && waitFor(b.handle((_, error) -> error)) != null, "original exceptional chains changed");
                waitFor(storage.nativeStorage.scheduleSave()); var report = waitFor(storage.journal.completion());
                refused(h, report::requireSuccess); h.assertValueEqual(report.written(), 0L, "failed chain wrote files");
                h.assertTrue(!Files.exists(storage.file(first)) && !Files.exists(storage.file(second)), "unexpected chained file");
            } finally { release.completeExceptionally(failure); }
        }
        h.succeed();
    }
}
