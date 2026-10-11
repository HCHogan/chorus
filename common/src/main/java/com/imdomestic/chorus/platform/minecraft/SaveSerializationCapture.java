package com.imdomestic.chorus.platform.minecraft;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Calling-thread serialization diagnostics, observed before native callers can log and discard them. */
public final class SaveSerializationCapture {
    private SaveSerializationCapture() {}
    private static final ThreadLocal<ArrayDeque<Consumer<String>>> SCOPES = new ThreadLocal<>();
    public static boolean active() { return SCOPES.get() != null; }
    public static <T> T observe(Consumer<String> failures, Supplier<T> original) {
        Objects.requireNonNull(failures); Objects.requireNonNull(original);
        if (SCOPES.get() == null) SCOPES.set(new ArrayDeque<>());
        SCOPES.get().push(failures);
        try { return call(original); }
        finally { SCOPES.get().pop(); if (SCOPES.get().isEmpty()) SCOPES.remove(); }
    }
    public static <T> T call(Supplier<T> original) {
        try { return original.get(); }
        catch (RuntimeException | Error error) { problem(error.toString()); throw error; }
    }
    public static void problem(String message) {
        var scopes = SCOPES.get(); if (scopes != null) scopes.peek().accept(message);
    }
}
