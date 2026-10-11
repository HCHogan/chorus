package com.imdomestic.chorus.platform.minecraft;

import java.nio.file.Path;
import java.util.*;

/** Synchronous native player/level.dat save evidence; does not infer success from void methods returning. */
public final class MinecraftSaveCapture {
    private MinecraftSaveCapture() {}
    public enum Outcome { REPLACED, REPLACEMENT_FAILED, NO_REPLACEMENT, SERIALIZATION_FAILED, THREW }
    public record Write(Path target, Outcome outcome, Optional<String> failure) {
        public Write { target = path(target); Objects.requireNonNull(outcome); Objects.requireNonNull(failure); }
    }
    public record Report(Set<Path> expected, List<Write> writes, boolean settled) {
        public Report { expected = Set.copyOf(expected); writes = List.copyOf(writes); }
        public Set<Path> missing() { var result = new HashSet<>(expected); writes.forEach(write -> result.remove(write.target())); return Set.copyOf(result); }
        public boolean successful() { return settled && missing().isEmpty() && writes.stream().allMatch(write -> write.outcome() == Outcome.REPLACED); }
        public void requireSuccess() {
            if (!successful()) throw new IllegalStateException("Native saves incomplete: settled=" + settled + ", missing=" + missing()
                    + ", failed=" + writes.stream().filter(write -> write.outcome() != Outcome.REPLACED).toList());
        }
    }
    private static Path path(Path value) { return Objects.requireNonNull(value).toAbsolutePath().normalize(); }
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Attempt>> ATTEMPTS = new ThreadLocal<>();
    private static final class Attempt {
        final Path target; final List<Scope> recipients;
        Outcome outcome = Outcome.NO_REPLACEMENT; Optional<String> failure = Optional.empty();
        Attempt(Path target, List<Scope> recipients) { this.target = target; this.recipients = recipients; }
        void replacement(boolean success) {
            // A second write cannot erase evidence that the first replacement failed.
            if (outcome == Outcome.NO_REPLACEMENT || outcome == Outcome.REPLACED) outcome = success ? Outcome.REPLACED : Outcome.REPLACEMENT_FAILED;
        }
        void failed(Throwable error) { outcome = Outcome.THREW; failure = Optional.of(error.getClass().getSimpleName() + ": " + error.getMessage()); }
        void encodingFailed(String message) { outcome = Outcome.SERIALIZATION_FAILED; failure = Optional.of(message); }
    }
    public static final class Scope implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final Set<Path> expected;
        private final List<Write> writes = new ArrayList<>();
        private int openAttempts;
        private boolean closed;
        private Scope(Set<Path> expected) { this.expected = expected; }
        private void thread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Save capture belongs to its calling thread"); }
        public Report report() { thread(); return new Report(expected, writes, closed); }
        @Override public void close() {
            thread(); if (closed) return;
            if (SCOPES.get() == null || SCOPES.get().peek() != this || openAttempts != 0) throw new IllegalStateException("Save capture still running or closed out of order");
            closed = true; SCOPES.get().pop(); if (SCOPES.get().isEmpty()) SCOPES.remove();
        }
    }
    /** The caller supplies the exact expected paths, not just a count of save calls. */
    public static Scope observe(Set<Path> expected) {
        var files = new HashSet<Path>(); expected.forEach(value -> files.add(path(value)));
        var scope = new Scope(Set.copyOf(files));
        if (SCOPES.get() == null) SCOPES.set(new ArrayDeque<>()); SCOPES.get().push(scope); return scope;
    }
    /** Native save wrapper; original exceptions/rollback behavior remain owned by Minecraft. */
    public static void saving(Path target, Runnable original) {
        var scopes = SCOPES.get(); if (scopes == null) { original.run(); return; }
        var file = path(target); var recipients = scopes.stream().filter(scope -> scope.expected.contains(file)).toList();
        if (recipients.isEmpty()) { original.run(); return; }
        var attempt = new Attempt(file, recipients); recipients.forEach(scope -> scope.openAttempts++);
        if (ATTEMPTS.get() == null) ATTEMPTS.set(new ArrayDeque<>()); ATTEMPTS.get().push(attempt);
        try { SaveSerializationCapture.observe(attempt::encodingFailed, () -> { original.run(); return null; }); }
        catch (RuntimeException | Error error) { attempt.failed(error); throw error; }
        finally {
            ATTEMPTS.get().pop(); if (ATTEMPTS.get().isEmpty()) ATTEMPTS.remove();
            var write = new Write(file, attempt.outcome, attempt.failure);
            recipients.forEach(scope -> { scope.writes.add(write); scope.openAttempts--; });
        }
    }
    private static Optional<Attempt> attempt(Path target) {
        var attempts = ATTEMPTS.get(); if (attempts == null) return Optional.empty();
        var file = path(target); return attempts.stream().filter(value -> value.target.equals(file)).findFirst();
    }
    public static void replaced(Path target, boolean success) { attempt(target).ifPresent(value -> value.replacement(success)); }
    public static void replacementFailed(Path target, Throwable failure) { attempt(target).ifPresent(value -> value.failed(failure)); }
}
