package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.*;

/** Actual native file writers, including their swallowed pre-write and replacement failures. */
public class NativeSaveGameTest {
    private static final class Storage implements AutoCloseable {
        final Path root;
        final LevelStorageSource.LevelStorageAccess access;
        final PlayerDataStorage players;
        Storage() throws Exception {
            root = Files.createTempDirectory("chorus-native-save-"); access = LevelStorageSource.createDefault(root).createAccess("fixture"); players = access.createPlayerStorage();
        }
        Path player(ServerPlayer player) { return access.getLevelPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat"); }
        Path backup(ServerPlayer player) { return access.getLevelPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat_old"); }
        Path level() { return access.getLevelPath(LevelResource.LEVEL_DATA_FILE); }
        void metadata(int number) { var tag = new CompoundTag(); tag.putInt("probe", number); access.saveLevelData(new Dynamic<>(NbtOps.INSTANCE, tag)); }
        @Override public void close() throws Exception {
            try { access.close(); } finally { try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
        }
    }
    private static CompoundTag read(Path file) throws Exception { return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()); }
    private static void blocked(Path backup) throws Exception { Files.createDirectory(backup); Files.writeString(backup.resolve("occupied"), "cannot replace a nonempty directory"); }
    private static void unblock(Path backup) throws Exception { Files.delete(backup.resolve("occupied")); Files.delete(backup); }
    private static void refused(GameTestHelper h, Runnable action) {
        boolean refused = false; try { action.run(); } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "Incomplete native save was accepted");
    }
    @GameCase public void playerSaveReportsAnActualReplacementOnlyAfterTheCaptureCloses(GameTestHelper h) throws Exception {
        var player = h.makeMockServerPlayerInLevel(); player.setHealth(7);
        try (var storage = new Storage()) {
            var scope = MinecraftSaveCapture.observe(Set.of(storage.player(player)));
            try (scope) {
                storage.players.save(player); h.assertTrue(!scope.report().successful(), "unfinished save scope claimed completion");
                near(h, read(storage.player(player)).getFloatOr("Health", -1), 7, "actual player file health");
            }
            scope.report().requireSuccess(); h.assertValueEqual(scope.report().writes().size(), 1, "one observed player write");
            h.assertValueEqual(scope.report().writes().getFirst().outcome(), MinecraftSaveCapture.Outcome.REPLACED, "actual native replacement result");
        } finally { player.discard(); }
        h.succeed();
    }
    @GameCase public void aSwallowedFailureBeforeTemporaryFileCreationIsNotSuccessful(GameTestHelper h) throws Exception {
        var player = h.makeMockServerPlayerInLevel();
        try (var storage = new Storage()) {
            var directory = storage.access.getLevelPath(LevelResource.PLAYER_DATA_DIR); Files.delete(directory); Files.writeString(directory, "blocked parent");
            var scope = MinecraftSaveCapture.observe(Set.of(storage.player(player)));
            try (scope) { storage.players.save(player); } // Vanilla logs a warning and returns normally.
            h.assertValueEqual(scope.report().writes().getFirst().outcome(), MinecraftSaveCapture.Outcome.NO_REPLACEMENT, "no successful replace was observed");
            refused(h, scope.report()::requireSuccess); h.assertTrue(scope.report().missing().isEmpty(), "attempt was mistaken for a missing call");
        } finally { player.discard(); }
        h.succeed();
    }
    @GameCase public void failedPlayerReplacementKeepsTheOldFileAndCannotBeHiddenByALaterSuccess(GameTestHelper h) throws Exception {
        var player = h.makeMockServerPlayerInLevel(); player.setHealth(3);
        try (var storage = new Storage()) {
            storage.players.save(player); blocked(storage.backup(player)); player.setHealth(8);
            var scope = MinecraftSaveCapture.observe(Set.of(storage.player(player)));
            try (scope) {
                storage.players.save(player); near(h, read(storage.player(player)).getFloatOr("Health", -1), 3, "failed replace retained previous file");
                unblock(storage.backup(player)); storage.players.save(player); near(h, read(storage.player(player)).getFloatOr("Health", -1), 8, "subsequent native save actually succeeded");
            }
            h.assertValueEqual(scope.report().writes().stream().map(MinecraftSaveCapture.Write::outcome).toList(),
                    List.of(MinecraftSaveCapture.Outcome.REPLACEMENT_FAILED, MinecraftSaveCapture.Outcome.REPLACED), "both save attempts retained");
            refused(h, scope.report()::requireSuccess);
            var fresh = MinecraftSaveCapture.observe(Set.of(storage.player(player))); try (fresh) { storage.players.save(player); }
            fresh.report().requireSuccess();
        } finally { player.discard(); }
        h.succeed();
    }
    @GameCase public void levelDatReplacementFailureIsObservedDespiteItsVoidReturn(GameTestHelper h) throws Exception {
        try (var storage = new Storage()) {
            var success = MinecraftSaveCapture.observe(Set.of(storage.level())); try (success) { storage.metadata(1); }
            success.report().requireSuccess(); h.assertValueEqual(read(storage.level()).getCompoundOrEmpty("Data").getIntOr("probe", -1), 1, "native level.dat content");
            blocked(storage.access.getLevelPath(LevelResource.OLD_LEVEL_DATA_FILE));
            var failure = MinecraftSaveCapture.observe(Set.of(storage.level())); try (failure) { storage.metadata(2); }
            h.assertValueEqual(failure.report().writes().getFirst().outcome(), MinecraftSaveCapture.Outcome.REPLACEMENT_FAILED, "discarded native boolean captured");
            refused(h, failure.report()::requireSuccess); h.assertValueEqual(read(storage.level()).getCompoundOrEmpty("Data").getIntOr("probe", -1), 1, "previous metadata retained");
        }
        h.succeed();
    }
    @GameCase public void nestedCapturesTrackExactExpectedPlayersAndDoNotCountAnotherPlayersFile(GameTestHelper h) throws Exception {
        var first = h.makeMockServerPlayerInLevel(); var second = h.makeMockServerPlayerInLevel();
        try (var storage = new Storage()) {
            var outer = MinecraftSaveCapture.observe(Set.of(storage.player(first), storage.player(second)));
            var inner = MinecraftSaveCapture.observe(Set.of(storage.player(first)));
            try (outer; inner) {
                refused(h, outer::close); storage.players.save(first); inner.close(); inner.report().requireSuccess();
                h.assertTrue(!outer.report().successful(), "outer capture finalized early");
            }
            h.assertValueEqual(outer.report().missing(), Set.of(storage.player(second).toAbsolutePath().normalize()), "missing player's exact path");
            refused(h, outer.report()::requireSuccess);
            var unrelated = MinecraftSaveCapture.observe(Set.of(storage.player(first))); try (unrelated) { storage.players.save(second); }
            h.assertTrue(unrelated.report().writes().isEmpty(), "unrequested write was counted for another player"); refused(h, unrelated.report()::requireSuccess);
        } finally { first.discard(); second.discard(); }
        h.succeed();
    }
    @GameCase public void nativeFailureCannotConfirmTheWorldCheckpointStore(GameTestHelper h) throws Exception {
        var player = h.makeMockServerPlayerInLevel(); var program = Identifier.parse("chorus_gametest:loaded");
        try (var storage = new Storage(); var runtime = EffectPrograms.install(h.getLevel(), program, EffectState.Mode.PVE)) {
            var store = new WorldCheckpointStore(storage.root.resolve("checkpoint.json")); var session = store.begin();
            var archive = new CheckpointArchive(List.of(EffectPrograms.checkpoint(h.getLevel(), program))); var pending = session.prepare(archive, Set.of("players"));
            storage.players.save(player); blocked(storage.backup(player));
            var scope = MinecraftSaveCapture.observe(Set.of(storage.player(player))); try (scope) { storage.players.save(player); }
            refused(h, () -> { scope.report().requireSuccess(); pending.confirm("players"); });
            pending.failed(scope.report().toString());
            refused(h, () -> { try { pending.commit(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
            refused(h, () -> { try { store.begin(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
            h.assertValueEqual(EffectPrograms.checkpoint(h.getLevel(), program), archive.dimensions().getFirst(), "file failure did not rewrite rule state");
            h.assertTrue(runtime.failure().isEmpty(), "file failure became a fabricated gameplay failure");
        } finally { player.discard(); }
        h.succeed();
    }
}
