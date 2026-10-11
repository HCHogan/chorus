package com.imdomestic.chorus.platform.minecraft;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MinecraftSaveCaptureTest {
    private final Path target = Path.of("save/player.dat");
    @Test void inFlightCaptureCannotBecomeACompletedProof() {
        var scope = MinecraftSaveCapture.observe(Set.of(target));
        try (scope) {
            MinecraftSaveCapture.saving(target, () -> {
                assertThrows(IllegalStateException.class, scope::close);
                MinecraftSaveCapture.replaced(target, true); assertFalse(scope.report().successful());
            });
        }
        scope.report().requireSuccess();
    }
    @Test void anExceptionOrFailedReplacementCannotBeErasedInsideTheSameAttempt() {
        var scope = MinecraftSaveCapture.observe(Set.of(target)); var failure = new IllegalStateException("unknown save");
        try (scope) {
            MinecraftSaveCapture.saving(target, () -> {
                MinecraftSaveCapture.replacementFailed(target, failure); MinecraftSaveCapture.replaced(target, true);
            });
            assertSame(failure, assertThrows(IllegalStateException.class, () -> MinecraftSaveCapture.saving(target, () -> { throw failure; })));
        }
        assertFalse(scope.report().successful()); assertEquals(2, scope.report().writes().size());
        assertTrue(scope.report().writes().stream().allMatch(write -> write.outcome() == MinecraftSaveCapture.Outcome.THREW && write.failure().isPresent()));
    }
    @Test void anotherThreadCannotCompleteOrCloseTheCallingThreadsScope() throws Exception {
        var scope = MinecraftSaveCapture.observe(Set.of(target));
        try (scope; var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, scope::close);
                MinecraftSaveCapture.saving(target, () -> MinecraftSaveCapture.replaced(target, true));
            }).get(10, TimeUnit.SECONDS);
        }
        assertEquals(Set.of(target.toAbsolutePath().normalize()), scope.report().missing()); assertFalse(scope.report().successful());
        // Closing clears thread state; a later genuine attempt is independent.
        var next = MinecraftSaveCapture.observe(Set.of(target)); try (next) { MinecraftSaveCapture.saving(target, () -> MinecraftSaveCapture.replaced(target, true)); }
        next.report().requireSuccess();
    }
}
