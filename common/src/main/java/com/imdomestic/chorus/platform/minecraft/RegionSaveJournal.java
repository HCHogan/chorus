package com.imdomestic.chorus.platform.minecraft;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** One native I/O worker's lifetime evidence, including failures already removed from its pending queue. */
public final class RegionSaveJournal {
    public enum Kind { WRITE, DRAIN, FLUSH, CLOSE }
    public record Operation(long sequence, Kind kind, String target) {}
    public record Failure(Operation operation, String cause) {}
    public record Report(long sequence, long latestWrite, long flushedThrough, boolean closed,
            List<Operation> pending, List<Failure> failures) {
        public Report { pending = List.copyOf(pending); failures = List.copyOf(failures); }
        public boolean successful() { return pending.isEmpty() && failures.isEmpty() && flushedThrough >= latestWrite; }
        public void requireSuccess() { if (!successful()) throw new IllegalStateException("Region saves not proven: " + this); }
    }

    // Submission order must match native queue order; completion callbacks must not need this lock.
    private final Object submission = new Object();
    private final Map<Long, Attempt> pending = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private final CompletableFuture<Void> failureObserved = new CompletableFuture<>();
    private long sequence, latestWrite;
    private long flushedThrough = -1;
    private boolean closed;

    public final class Attempt {
        private final Operation operation;
        private final long precedingWrite;
        private final boolean afterClose;
        private final CompletableFuture<Void> observed = new CompletableFuture<>();
        private boolean failed;
        private Attempt(Operation operation, long precedingWrite, boolean afterClose) {
            this.operation = operation; this.precedingWrite = precedingWrite; this.afterClose = afterClose;
        }
        /** Records an error even when vanilla swallows it or never completes the returned future. */
        public void failed(Throwable error) {
            Objects.requireNonNull(error);
            synchronized (RegionSaveJournal.this) {
                if (!failed) { failed = true; failures.add(new Failure(operation, describe(error))); }
            }
            failureObserved.complete(null);
        }
        private void complete(Throwable error) {
            if (error != null) failed(error);
            synchronized (RegionSaveJournal.this) {
                pending.remove(operation.sequence());
                if (!failed && operation.kind() == Kind.FLUSH) flushedThrough = Math.max(flushedThrough, precedingWrite);
                if (operation.kind() == Kind.CLOSE) closed = true;
            }
            // Do not run arbitrary observers while holding the state lock.
            observed.complete(null);
        }
    }
    private static String describe(Throwable error) { return error.getClass().getName() + ": " + error.getMessage(); }
    private synchronized Attempt begin(Kind kind, String target) {
        Objects.requireNonNull(kind); Objects.requireNonNull(target);
        sequence = Math.incrementExact(sequence);
        if (kind == Kind.WRITE) latestWrite = sequence;
        var attempt = new Attempt(new Operation(sequence, kind, target), latestWrite, closed && kind != Kind.CLOSE);
        pending.put(sequence, attempt);
        return attempt;
    }
    /** Returns the original future unchanged; the observer never repairs, retries or cancels native I/O. */
    public <T> CompletableFuture<T> submit(Kind kind, String target, Function<Attempt, CompletableFuture<T>> original) {
        synchronized (submission) {
            var attempt = begin(kind, target);
            if (attempt.afterClose) attempt.failed(new IllegalStateException("Operation submitted after region worker closed"));
            try {
                var result = Objects.requireNonNull(original.apply(attempt));
                result.whenComplete((_, error) -> attempt.complete(error));
                return result;
            } catch (RuntimeException | Error error) { attempt.complete(error); throw error; }
        }
    }
    /** For native close errors caught internally, which cannot be inferred from close() returning. */
    public void failedClose(Throwable error) {
        synchronized (this) {
            sequence = Math.incrementExact(sequence);
            failures.add(new Failure(new Operation(sequence, Kind.CLOSE, "storage"), describe(Objects.requireNonNull(error))));
        }
        failureObserved.complete(null);
    }
    public synchronized Report report() {
        return new Report(sequence, latestWrite, flushedThrough, closed,
                pending.values().stream().map(attempt -> attempt.operation).toList(), failures);
    }
    /** Returns evidence after already submitted observations finish, or promptly on a known failure. */
    public CompletableFuture<Report> completion() {
        CompletableFuture<?>[] observations;
        synchronized (this) { observations = pending.values().stream().map(attempt -> attempt.observed).toArray(CompletableFuture[]::new); }
        return CompletableFuture.anyOf(CompletableFuture.allOf(observations), failureObserved).thenApply(_ -> report());
    }
}
