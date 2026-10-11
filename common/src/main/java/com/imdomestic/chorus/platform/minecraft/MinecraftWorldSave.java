package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.mixin.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.*;

/** Explicit server-thread save of the native game-state files used by Chorus checkpoints; not a restart/commit coordinator. */
public final class MinecraftWorldSave {
    private MinecraftWorldSave() {}
    private static final Set<MinecraftServer> SAVING = Collections.newSetFromMap(new IdentityHashMap<>());
    public record Bindings(Map<String, ServerLevel> levels, Map<String, SimpleRegionStorage> regions,
            Map<String, SavedDataStorage> data, PlayerDataStorage playerStorage, Map<UUID, ServerPlayer> players,
            Set<Path> files, Map<String, Long> gameTimes) {
        public Bindings {
            levels = Map.copyOf(levels); regions = Map.copyOf(regions); data = Map.copyOf(data); players = Map.copyOf(players);
            files = Set.copyOf(files); gameTimes = Map.copyOf(gameTimes);
        }
    }
    public record Report(Map<String, Long> gameTimes, Set<UUID> players, MinecraftSaveCapture.Report files,
            Map<String, RegionSaveJournal.Report> regions, Map<String, SavedDataSaveJournal.Report> data, List<String> issues) {
        public Report {
            gameTimes = Map.copyOf(gameTimes); players = Set.copyOf(players); regions = Map.copyOf(regions); data = Map.copyOf(data); issues = List.copyOf(issues);
        }
        public boolean successful() {
            return issues.isEmpty() && files.successful() && regions.values().stream().allMatch(RegionSaveJournal.Report::successful)
                    && data.values().stream().allMatch(SavedDataSaveJournal.Report::successful);
        }
        public void requireSuccess() { if (!successful()) throw new IllegalStateException("Native world save not proven: " + this); }
    }
    private static void thread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("World save must run on the server thread");
    }
    private static RegionSaveJournal journal(SimpleRegionStorage storage) { return ((MinecraftRegionSaveAccess) storage.chunkScanner()).chorus$regionSaves(); }
    private static SavedDataSaveJournal journal(SavedDataStorage storage) { return ((MinecraftSavedDataSaveAccess) storage).chorus$savedDataSaves(); }
    private static SimpleRegionStorage region(Object owner) {
        if (!(owner instanceof MinecraftRegionStorageAccess access)) throw new IllegalStateException("Unsupported native storage owner: " + owner.getClass().getName());
        return access.chorus$regionStorage();
    }
    /** Resolve actual live instances; storage type labels alone do not identify a world or worker. */
    public static Bindings bindings(MinecraftServer server) {
        thread(server);
        var levels = new TreeMap<String, ServerLevel>(); var regions = new TreeMap<String, SimpleRegionStorage>();
        var data = new TreeMap<String, SavedDataStorage>(); var times = new TreeMap<String, Long>();
        data.put("server", server.getDataStorage());
        for (var level : server.getAllLevels()) {
            String id = level.dimension().identifier().toString(); levels.put(id, level); times.put(id, level.getGameTime());
            var entities = ((EntityStorageSaveAccessor) ((ServerLevelSaveAccessor) level).chorus$entityManager()).chorus$permanentStorage();
            regions.put(id + "/chunks", level.getChunkSource().chunkMap); regions.put(id + "/entities", region(entities));
            regions.put(id + "/poi", region(level.getPoiManager())); data.put(id, level.getDataStorage());
        }
        var playerList = server.getPlayerList(); var playerStorage = ((PlayerStorageSaveAccessor) playerList).chorus$playerStorage();
        var paths = (MinecraftPlayerSaveAccess) playerStorage; var players = new TreeMap<UUID, ServerPlayer>(); var files = new HashSet<Path>();
        files.add(server.getWorldPath(LevelResource.LEVEL_DATA_FILE).toAbsolutePath().normalize());
        for (var player : playerList.getPlayers()) {
            if (players.put(player.getUUID(), player) != null) throw new IllegalStateException("Duplicate online player UUID");
            files.add(paths.chorus$playerFile(player.getUUID()).toAbsolutePath().normalize());
        }
        return new Bindings(levels, regions, data, playerStorage, players, files, times);
    }
    private static void preflight(Bindings bindings) {
        if (SharedConstants.DEBUG_DONT_SAVE_WORLD || bindings.levels().isEmpty() || bindings.levels().values().stream().anyMatch(level -> level.noSave))
            throw new IllegalStateException("World is not enabled for a complete native save");
        bindings.regions().forEach((name, storage) -> {
            var report = journal(storage).report();
            if (report.closed() || !report.failures().isEmpty()) throw new IllegalStateException("Region storage requires explicit recovery: " + name + " " + report);
        });
        bindings.data().forEach((name, storage) -> {
            var report = journal(storage).report();
            if (report.closed() || !report.failures().isEmpty()) throw new IllegalStateException("Saved data requires explicit recovery: " + name + " " + report);
        });
    }
    /**
     * Save/flush the currently loaded dimensions and online player NBT. Normal reports include known write failures;
     * precondition failures throw before starting native saves. Callers still own the gameplay freeze and checkpoint epoch.
     */
    public static Report save(MinecraftServer server) {
        thread(server);
        synchronized (SAVING) { if (!SAVING.add(server)) throw new IllegalStateException("World save already in progress"); }
        try {
            var before = bindings(server); preflight(before); var issues = new ArrayList<String>();
            var capture = MinecraftSaveCapture.observe(before.files());
            try (capture) {
                try {
                    SaveSerializationCapture.observe(issues::add, () -> {
                        if (!server.saveEverything(true, true, false)) issues.add("Native save did not visit a dimension");
                        return null;
                    });
                    // POI and older SavedData batches are not uniformly drained by saveEverything's return.
                    for (var storage : before.regions().values()) storage.synchronize(true);
                    for (var storage : before.data().values()) storage.scheduleSave();
                    for (var storage : before.regions().values()) journal(storage).completion().join();
                    for (var storage : before.data().values()) journal(storage).completion().join();
                } catch (RuntimeException failure) { issues.add(failure.toString()); }
            }
            if (!before.equals(bindings(server))) issues.add("World dimensions, storage instances, players or game times changed during saving");
            var regions = new TreeMap<String, RegionSaveJournal.Report>(); before.regions().forEach((name, storage) -> regions.put(name, journal(storage).report()));
            var data = new TreeMap<String, SavedDataSaveJournal.Report>(); before.data().forEach((name, storage) -> data.put(name, journal(storage).report()));
            return new Report(before.gameTimes(), before.players().keySet(), capture.report(), regions, data, issues);
        } finally { synchronized (SAVING) { SAVING.remove(server); } }
    }
}
