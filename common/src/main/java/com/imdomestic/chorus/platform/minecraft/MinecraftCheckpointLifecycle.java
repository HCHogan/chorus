package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.effect.persistence.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.storage.LevelResource;

/** Clean-stop persistence under Minecraft's existing world lock. Unclean runs require explicit recovery. */
public final class MinecraftCheckpointLifecycle {
    private MinecraftCheckpointLifecycle() {}
    private static final Map<MinecraftServer, Run> RUNS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<IOWorker, Run> CLOSING = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<String> PARTS = Set.of("native_files", "region_stores", "saved_data", "rule_boundary");
    private static final class Run {
        final MinecraftServer server;
        final WorldCheckpointStore.Session session;
        final Map<String, CheckpointArchive.Entry> recovered = new TreeMap<>();
        final Set<String> restored = new TreeSet<>();
        final List<String> issues = new ArrayList<>();
        boolean started, stopping, finalized;
        MinecraftCheckpointBoundary boundary;
        MinecraftWorldSave.Bindings bindings;
        MinecraftSaveCapture.Scope files;
        WorldCheckpointStore.Session.Save pending;
        Run(MinecraftServer server, WorldCheckpointStore.Session session) {
            this.server = server; this.session = session;
            session.recovered().ifPresent(archive -> archive.dimensions().forEach(entry -> recovered.put(entry.dimension(), entry)));
        }
        void issue(String message) { if (!issues.contains(message)) issues.add(message); }
        void fail(Throwable failure) {
            issue(failure.toString());
            if (pending != null) try { pending.failed(String.join("\n", issues)); }
            catch (IOException | RuntimeException recording) { failure.addSuppressed(recording); }
            Constants.LOG.error("Chorus checkpoint is not recoverable automatically; native shutdown cleanup continues", failure);
        }
    }
    public record Status(Path file, boolean started, boolean stopping, Set<String> restoredDimensions) {
        public Status { restoredDimensions = Set.copyOf(restoredDimensions); }
    }
    public static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("chorus-checkpoint.json").toAbsolutePath().normalize(); }
    public static Optional<Status> status(MinecraftServer server) {
        thread(server); var run = RUNS.get(server);
        return run == null ? Optional.empty() : Optional.of(new Status(file(server), run.started, run.stopping, run.restored));
    }
    private static void thread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Checkpoint lifecycle belongs to the server thread");
    }
    /** Before createLevels, while the original world lock is held and before entities are loaded. */
    public static void starting(MinecraftServer server) {
        thread(server);
        if (RUNS.containsKey(server)) throw new IllegalStateException("Checkpoint run already started");
        final Run run;
        try { run = new Run(server, new WorldCheckpointStore(file(server)).begin()); }
        catch (IOException error) { throw new UncheckedIOException("Cannot open Chorus world checkpoint", error); }
        RUNS.put(server, run);
        for (var saved : run.recovered.values()) {
            var dimension = Identifier.parse(saved.dimension());
            if (!server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).containsKey(dimension))
                throw new IllegalStateException("Saved Chorus dimension is unavailable: " + dimension);
            var program = EffectPrograms.find(server, Identifier.parse(saved.program())).orElseThrow(() -> new IllegalStateException("Saved Chorus catalogue is unavailable: " + saved.program()));
            if (!program.program().equals(saved.effects().program())) throw new IllegalStateException("Saved Chorus catalogue changed: " + saved.program());
            if (saved.gameTime() != server.getWorldData().overworldData().getGameTime())
                throw new IllegalStateException("Chorus checkpoint and level.dat have different world clocks");
        }
    }
    /** Constructor tail precedes loader level-load events and normal initial chunk/entity loading. */
    public static void created(ServerLevel level) {
        var run = RUNS.get(level.getServer()); if (run == null || run.started || run.stopping) return;
        thread(run.server); String id = level.dimension().identifier().toString(); var saved = run.recovered.get(id);
        if (saved == null) return;
        if (run.restored.contains(id)) throw new IllegalStateException("Saved Chorus dimension constructed twice: " + id);
        EffectPrograms.restore(level, saved); run.restored.add(id);
    }
    public static void started(MinecraftServer server) {
        thread(server); var run = Objects.requireNonNull(RUNS.get(server), "Missing checkpoint startup");
        if (!run.restored.equals(run.recovered.keySet())) throw new IllegalStateException("Not every saved Chorus dimension was restored");
        run.started = true;
    }
    /** Loader unload callbacks must not remove projections or sources before the last native writes finish. */
    static boolean deferUnload(ServerLevel level) {
        return deferStop(level.getServer());
    }
    static boolean deferStop(MinecraftServer server) { var run = RUNS.get(server); return run != null && run.stopping && run.boundary != null; }
    private static RegionSaveJournal journal(IOWorker worker) { return ((MinecraftRegionSaveAccess) worker).chorus$regionSaves(); }
    /** Only actual workers enlisted in this shutdown receive the additional final flush. */
    public static void beforeRegionClose(IOWorker worker) {
        var run = CLOSING.get(worker); if (run == null) return;
        try { worker.synchronize(true); journal(worker).completion().join().requireSuccess(); }
        catch (RuntimeException failure) { run.fail(failure); }
    }
    public static void stopping(MinecraftServer server, Runnable original) {
        thread(server); var run = RUNS.get(server);
        if (run == null) { original.run(); return; }
        if (run.stopping) throw new IllegalStateException("Reentrant native server shutdown");
        run.stopping = true;
        try {
            if (run.started) try {
                if (SharedConstants.DEBUG_DONT_SAVE_WORLD) throw new IllegalStateException("Native world saving is disabled");
                run.boundary = MinecraftCheckpointBoundary.open(server);
                run.bindings = MinecraftWorldSave.bindings(server);
                run.pending = run.session.prepare(run.boundary.archive(), PARTS);
                run.files = MinecraftSaveCapture.observe(run.bindings.files());
                run.bindings.regions().values().forEach(storage -> CLOSING.put((IOWorker) storage.chunkScanner(), run));
            } catch (IOException | RuntimeException failure) { run.fail(failure); }
            SaveSerializationCapture.observe(run::issue, () -> { original.run(); return null; });
            if (run.pending != null && (!run.finalized || !run.issues.isEmpty()))
                run.fail(new IllegalStateException("Native shutdown did not complete every checkpoint step: " + run.issues));
        } catch (RuntimeException | Error failure) { run.fail(failure); throw failure; }
        finally {
            CLOSING.entrySet().removeIf(entry -> entry.getValue() == run);
            try { if (run.files != null) run.files.close(); }
            finally {
                if (run.boundary != null) run.boundary.close();
                RUNS.remove(server, run);
                MinecraftEffectRuntime.stop(server);
            }
        }
    }
    /** Invoked after native file/region/data close and before LevelStorageAccess releases the world lock. */
    public static void beforeUnlock(MinecraftServer server) {
        var run = RUNS.get(server); if (run == null || !run.stopping || run.pending == null) return;
        try {
            if (run.finalized) throw new IllegalStateException("Checkpoint shutdown finalized twice");
            run.finalized = true;
            run.files.close(); run.files.report().requireSuccess();
            if (!run.issues.isEmpty()) throw new IllegalStateException("Native shutdown reported failures: " + run.issues);
            run.pending.confirm("native_files");
            for (var entry : run.bindings.regions().entrySet()) {
                var report = journal((IOWorker) entry.getValue().chunkScanner()).completion().join(); report.requireSuccess();
                if (!report.closed()) throw new IllegalStateException("Region storage not closed: " + entry.getKey());
            }
            run.pending.confirm("region_stores");
            for (var entry : run.bindings.data().entrySet()) {
                var report = ((MinecraftSavedDataSaveAccess) entry.getValue()).chorus$savedDataSaves().completion().join(); report.requireSuccess();
                if (!report.closed()) throw new IllegalStateException("SavedData storage not closed: " + entry.getKey());
            }
            run.pending.confirm("saved_data");
            run.boundary.requireUnchanged(); run.pending.confirm("rule_boundary");
            run.pending.commit();
            Constants.LOG.info("Chorus checkpoint committed before world unlock ({} dimensions)", run.boundary.archive().dimensions().size());
        } catch (IOException | RuntimeException failure) { run.fail(failure); }
    }
    public static void unlockFailed(MinecraftServer server, Throwable failure) { var run = RUNS.get(server); if (run != null) run.fail(failure); }
}
