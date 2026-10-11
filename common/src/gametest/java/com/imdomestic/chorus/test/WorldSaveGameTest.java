package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.Codec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.storage.LevelResource;

/** Actual server-wide saves in the isolated GameTest server, not a process restart or automatic checkpoint commit. */
public class WorldSaveGameTest {
    private static final class Probe extends SavedData {
        final int value; Runnable encode = () -> {};
        Probe(int value) { this.value = value; }
    }
    private static final Codec<Probe> CODEC = Codec.INT.fieldOf("value").xmap(Probe::new, value -> { value.encode.run(); return value.value; }).codec();
    private static final SavedDataType<Probe> PROBE = new SavedDataType<>(Identifier.parse("chorus_gametest:world_save_probe"), () -> new Probe(0), CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private static CompoundTag read(Path file) throws Exception { return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()); }
    private static void refused(GameTestHelper h, Runnable action) {
        boolean refused = false; try { action.run(); } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "Unproven world save was accepted");
    }
    @GameCase public void bindingsResolveDistinctActualStoragesAndRejectAnotherThread(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer(); var bindings = MinecraftWorldSave.bindings(server);
        h.assertValueEqual(bindings.regions().size(), bindings.levels().size() * 3, "three native region stores per live dimension");
        var identities = Collections.newSetFromMap(new IdentityHashMap<>()); identities.addAll(bindings.regions().values());
        h.assertValueEqual(identities.size(), bindings.regions().size(), "different dimensions and storage types cannot share evidence");
        h.assertValueEqual(bindings.data().size(), bindings.levels().size() + 1, "global and dimension SavedData stores");
        h.assertTrue(bindings.data().get("server") == server.getDataStorage(), "global saved data identity");
        for (var entry : bindings.levels().entrySet()) {
            var level = entry.getValue(); String id = entry.getKey();
            h.assertTrue(bindings.regions().get(id + "/chunks") == level.getChunkSource().chunkMap, "actual terrain storage");
            h.assertTrue(bindings.regions().get(id + "/poi") == ((MinecraftRegionStorageAccess) level.getPoiManager()).chorus$regionStorage(), "actual POI storage");
            h.assertTrue(bindings.data().get(id) == level.getDataStorage(), "actual dimension saved data");
        }
        h.assertValueEqual(bindings.files().size(), bindings.players().size() + 1, "online player files and level.dat");
        try (var executor = Executors.newSingleThreadExecutor()) {
            h.assertTrue(executor.submit(() -> {
                try { MinecraftWorldSave.save(server); return false; } catch (IllegalStateException expected) { return true; }
            }).get(5, TimeUnit.SECONDS), "wrong thread reached native save");
        }
        h.succeed();
    }
    @GameCase public void aDisabledDimensionRejectsTheSaveWithoutClearingItsSettingOrSchedulingWrites(GameTestHelper h) {
        var server = h.getLevel().getServer(); var bindings = MinecraftWorldSave.bindings(server); boolean previous = h.getLevel().noSave;
        var counts = new TreeMap<String, Long>(); bindings.data().forEach((name, storage) -> counts.put(name, ((MinecraftSavedDataSaveAccess) storage).chorus$savedDataSaves().report().scheduled()));
        try {
            h.getLevel().noSave = true; refused(h, () -> MinecraftWorldSave.save(server));
            h.assertTrue(h.getLevel().noSave, "save bypassed the dimension's noSave setting");
            bindings.data().forEach((name, storage) -> h.assertValueEqual(((MinecraftSavedDataSaveAccess) storage).chorus$savedDataSaves().report().scheduled(), counts.get(name), "preflight scheduled native data writes"));
        } finally { h.getLevel().noSave = previous; }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:world_save")
    public void realServerSaveReadsBackStateAndRejectsFailedPlayerFilesOrChangedMembership(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer(); var owner = NativeMeleeGameTest.player(h); owner.setHealth(7);
        var cow = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); cow.setHealth(5);
        var probe = new Probe(23); server.getDataStorage().set(PROBE, probe); var reentrant = new AtomicBoolean();
        probe.encode = () -> { try { MinecraftWorldSave.save(server); } catch (IllegalStateException expected) { reentrant.set(true); } };
        var paths = (MinecraftPlayerSaveAccess) MinecraftWorldSave.bindings(server).playerStorage(); var playerFile = paths.chorus$playerFile(owner.getUUID());
        var backup = playerFile.resolveSibling(owner.getUUID() + ".dat_old"); var latePlayers = new ArrayList<ServerPlayer>();
        var checkpointDirectory = Files.createTempDirectory("chorus-world-save-proof-");
        boolean obstructed = false;
        try {
            var good = MinecraftWorldSave.save(server); good.requireSuccess();
            h.assertTrue(reentrant.get(), "nested save was not rejected before reentering native writers");
            h.assertTrue(good.players().contains(owner.getUUID()), "online player omitted from report");
            h.assertValueEqual(read(playerFile).getFloatOr("Health", -1), 7f, "actual saved player health");
            h.assertTrue(read(server.getWorldPath(LevelResource.LEVEL_DATA_FILE)).contains("Data"), "level.dat not saved");
            var probeFile = PROBE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
            h.assertValueEqual(read(probeFile).getCompoundOrEmpty("data").getIntOr("value", -1), 23, "actual global SavedData file");
            var bindings = MinecraftWorldSave.bindings(server); var entityStorage = bindings.regions().get(h.getLevel().dimension().identifier() + "/entities");
            var entities = entityStorage.read(cow.chunkPosition()).get(10, TimeUnit.SECONDS).orElseThrow().getListOrEmpty("Entities");
            h.assertTrue(entities.stream().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast)
                    .anyMatch(tag -> tag.read("UUID", UUIDUtil.CODEC).filter(cow.getUUID()::equals).isPresent() && tag.getFloatOr("Health", -1) == 5f), "live entity absent from saved entity region");
            // The new UUID belongs only to this test; the first save must not have made an older backup.
            h.assertTrue(!Files.exists(backup), "unexpected preexisting fixture player backup");
            Files.createDirectory(backup); Files.writeString(backup.resolve("occupied"), "owned test obstruction"); obstructed = true;
            owner.setHealth(9);
            probe.encode = () -> { probe.encode = () -> {}; latePlayers.add(NativeMeleeGameTest.player(h)); }; probe.setDirty();
            var failed = MinecraftWorldSave.save(server);
            refused(h, failed::requireSuccess);
            h.assertTrue(!failed.files().successful(), "failed native replacement was ignored by aggregate");
            h.assertTrue(failed.issues().stream().anyMatch(issue -> issue.contains("players")), "online membership change was ignored");
            h.assertTrue(failed.regions().values().stream().allMatch(RegionSaveJournal.Report::successful), "unrelated region evidence failed");
            h.assertValueEqual(read(playerFile).getFloatOr("Health", -1), 7f, "native failed replacement retained previous player file");
            var store = new WorldCheckpointStore(checkpointDirectory.resolve("checkpoint.json"));
            var save = store.begin().prepare(new CheckpointArchive(List.of()), Set.of("native_state"));
            refused(h, () -> { failed.requireSuccess(); save.confirm("native_state"); }); save.failed(failed.toString());
            refused(h, () -> { try { save.commit(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
            Files.delete(backup.resolve("occupied")); Files.delete(backup); obstructed = false;
            var next = MinecraftWorldSave.save(server); next.requireSuccess();
            h.assertValueEqual(read(playerFile).getFloatOr("Health", -1), 9f, "new whole save after obstruction removed");
            h.assertTrue(next.players().contains(latePlayers.getFirst().getUUID()), "new complete scope omitted late player");
            h.assertTrue(Files.isRegularFile(paths.chorus$playerFile(latePlayers.getFirst().getUUID())), "late player never saved");
        } finally {
            probe.encode = () -> {}; owner.discard(); cow.discard(); latePlayers.forEach(ServerPlayer::discard);
            if (obstructed) { Files.deleteIfExists(backup.resolve("occupied")); Files.delete(backup); }
            try (var files = Files.walk(checkpointDirectory)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        h.succeed();
    }
}
