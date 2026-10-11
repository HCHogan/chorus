package com.imdomestic.chorus.platform.minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** SavedDataStorage evidence: completed batches alone cannot prove that vanilla's caught file writes succeeded. */
public final class SavedDataSaveJournal {
    public enum Kind { SCHEDULE, DIRECTORY, WRITE, CLOSE }
    public record Operation(long sequence, Kind kind, String target) {}
    public record Failure(Operation operation, String cause) {}
    public record Report(long scheduled, long written, boolean closed, List<Operation> pending, List<Failure> failures) {
        public Report { pending = List.copyOf(pending); failures = List.copyOf(failures); }
        public boolean successful() { return scheduled > 0 && pending.isEmpty() && failures.isEmpty(); }
        public void requireSuccess() { if (!successful()) throw new IllegalStateException("Saved data not proven: " + this); }
    }
    @FunctionalInterface public interface IoAction { void run() throws IOException; }
    private record Attempt(Operation operation, CompletableFuture<Void> observed) {}
    private final Map<Long, Attempt> pending = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private final CompletableFuture<Void> failureObserved = new CompletableFuture<>();
    private long sequence, scheduled, written;
    private boolean closed;

    private synchronized Attempt begin(Kind kind, String target) {
        sequence = Math.incrementExact(sequence);
        var attempt = new Attempt(new Operation(sequence, kind, target), new CompletableFuture<>());
        pending.put(sequence, attempt);
        if (kind == Kind.SCHEDULE) scheduled = Math.incrementExact(scheduled);
        return attempt;
    }
    private void finish(Attempt attempt, Throwable failure) {
        synchronized (this) {
            pending.remove(attempt.operation().sequence());
            if (failure != null) failures.add(new Failure(attempt.operation(), failure.getClass().getName() + ": " + failure.getMessage()));
            else if (attempt.operation().kind() == Kind.WRITE) written = Math.incrementExact(written);
            else if (attempt.operation().kind() == Kind.CLOSE) closed = true;
        }
        // Completion listeners must not execute while holding the journal's state lock.
        if (failure != null) failureObserved.complete(null);
        attempt.observed().complete(null);
    }
    /** Observe every batch, retaining earlier pending futures when a later empty batch finishes immediately. */
    public <T> CompletableFuture<T> schedule(Supplier<CompletableFuture<T>> original) {
        var attempt = begin(Kind.SCHEDULE, "storage");
        try {
            var future = Objects.requireNonNull(original.get());
            future.whenComplete((_, failure) -> finish(attempt, failure));
            return future;
        } catch (RuntimeException | Error failure) { finish(attempt, failure); throw failure; }
    }
    /** Records the actual directory/file result before vanilla's tryWrite can catch the IOException. */
    public void io(Kind kind, Path path, IoAction original) throws IOException {
        if (kind != Kind.DIRECTORY && kind != Kind.WRITE) throw new IllegalArgumentException("Expected directory or file I/O");
        var attempt = begin(kind, Objects.requireNonNull(path).toAbsolutePath().normalize().toString());
        try { original.run(); }
        catch (IOException | RuntimeException | Error failure) { finish(attempt, failure); throw failure; }
        finish(attempt, null);
    }
    /** A normal close still leaves any earlier asynchronous batch pending in the report. */
    public void close(Runnable original) {
        var attempt = begin(Kind.CLOSE, "storage");
        try { original.run(); }
        catch (RuntimeException | Error failure) { finish(attempt, failure); throw failure; }
        finish(attempt, null);
    }
    public synchronized Report report() {
        return new Report(scheduled, written, closed, pending.values().stream().map(Attempt::operation).toList(), failures);
    }
    /** Current evidence after already submitted observations finish, or promptly on a known failure; never cancels native work. */
    public CompletableFuture<Report> completion() {
        CompletableFuture<?>[] observations;
        synchronized (this) { observations = pending.values().stream().map(Attempt::observed).toArray(CompletableFuture[]::new); }
        return CompletableFuture.anyOf(CompletableFuture.allOf(observations), failureObserved).thenApply(_ -> report());
    }
}
