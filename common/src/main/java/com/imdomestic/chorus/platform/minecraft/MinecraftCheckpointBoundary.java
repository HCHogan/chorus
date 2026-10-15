package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Pins all catalogue-backed rule runtimes during native saving. The host still owns world quiescence and final shutdown. */
public final class MinecraftCheckpointBoundary implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftCheckpointBoundary> ACTIVE = Collections.synchronizedMap(new IdentityHashMap<>());
    private final MinecraftServer server;
    private final Map<String, ServerLevel> levels;
    private final Map<String, MinecraftEffectRuntime> runtimes;
    private final Map<String, String> catalogues;
    private final Map<String, Long> times = new TreeMap<>();
    private final List<String> issues = new ArrayList<>();
    private CheckpointArchive archive;
    private MinecraftEffectRuntime inspecting;
    private boolean settling = true, closed;

    private MinecraftCheckpointBoundary(MinecraftServer server, Map<String, ServerLevel> levels,
            Map<String, MinecraftEffectRuntime> runtimes, Map<String, String> catalogues) {
        this.server = server; this.levels = levels; this.runtimes = runtimes; this.catalogues = catalogues;
    }
    private static void thread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Checkpoint boundary belongs to the server thread");
    }
    private IllegalStateException reject(String issue) {
        if (!issues.contains(issue)) issues.add(issue);
        return new IllegalStateException(issue);
    }
    static void changingRuntime(MinecraftServer server) {
        var boundary = ACTIVE.get(server);
        if (boundary != null) throw boundary.reject("Cannot install or restore a runtime inside a checkpoint boundary");
    }
    static void enteringRuntime(MinecraftServer server, MinecraftEffectRuntime runtime) {
        var boundary = ACTIVE.get(server);
        if (boundary != null && !boundary.settling && boundary.inspecting != runtime)
            throw boundary.reject("Cannot operate a runtime inside a checkpoint boundary");
    }
    static void enteringResources(MinecraftServer server){
        var boundary=ACTIVE.get(server);
        if(boundary!=null&&!boundary.settling)throw boundary.reject("Cannot change resources inside a checkpoint boundary");
    }
    private static Map<String, ServerLevel> levels(MinecraftServer server) {
        var result = new TreeMap<String, ServerLevel>();
        server.getAllLevels().forEach(level -> result.put(level.dimension().identifier().toString(), level));
        return Map.copyOf(result);
    }
    private static Map<String, MinecraftEffectRuntime> runtimes(Map<String, ServerLevel> levels) {
        var result = new TreeMap<String, MinecraftEffectRuntime>();
        levels.forEach((id, level) -> MinecraftEffectRuntime.installed(level).ifPresent(runtime -> result.put(id, runtime)));
        return Map.copyOf(result);
    }
    /** Settle all dimensions first; capture final states only after cross-dimension reactions have completed. */
    public static MinecraftCheckpointBoundary open(MinecraftServer server) {
        thread(server);
        var previous = ACTIVE.get(server);
        if (previous != null) throw previous.reject("Checkpoint boundary is already open");
        var levels = levels(server); var runtimes = runtimes(levels); var names = new TreeMap<String, String>();
        runtimes.forEach((id, runtime) -> names.put(id, EffectPrograms.catalogueId(levels.get(id), runtime).toString()));
        var boundary = new MinecraftCheckpointBoundary(server, levels, runtimes, Map.copyOf(names));
        if (ACTIVE.putIfAbsent(server, boundary) != null) throw new IllegalStateException("Checkpoint boundary opened while settling");
        try {
            // Preparing can execute due rules across dimensions, but cannot recursively open another boundary.
            // Committed work is never rolled back if this phase fails.
            new TreeMap<>(runtimes).values().forEach(MinecraftEffectRuntime::prepare);
            var resources=EffectPrograms.resourceRuntime(server);
            if(resources.isPresent()&&resources.orElseThrow().drain(Math.max(1,runtimes.size())*256).pending())
                throw new IllegalStateException("Resource feedback remains pending at the save boundary");
            if (!levels.equals(levels(server)) || !runtimes.equals(runtimes(levels)))
                throw new IllegalStateException("World dimensions or runtimes changed while settling checkpoints");
            boundary.settling = false;
            levels.forEach((id, level) -> boundary.times.put(id, level.getGameTime()));
            var entries = new ArrayList<CheckpointArchive.Entry>();
            for (var entry : new TreeMap<>(runtimes).entrySet()) {
                var saved = boundary.inspect(entry.getValue());
                entries.add(new CheckpointArchive.Entry(names.get(entry.getKey()), saved.dimension(), saved.gameTime(), saved.effects(), saved.equipmentOwners(), saved.actorOwners(), saved.abilityOwners()));
            }
            var captured=resources.map(MinecraftResourceRuntime::settledResources);
            boundary.archive = new CheckpointArchive(entries,captured.map(saved->saved.directory()),captured.map(saved->saved.placement()));
            boundary.requireUnchanged(); return boundary;
        } catch (RuntimeException | Error failure) { boundary.close(); throw failure; }
    }
    private MinecraftEffectRuntime.Checkpoint inspect(MinecraftEffectRuntime runtime) {
        inspecting = runtime;
        try { return runtime.settledCheckpoint(); }
        finally { inspecting = null; }
    }
    /** Persistent diagnostics survive a caller catching a rejected operation or restoring a changed clock. */
    public List<String> issues() { thread(server); return List.copyOf(issues); }
    public CheckpointArchive archive() { requireUnchanged(); return archive; }
    public void requireUnchanged() {
        thread(server);
        if (closed || ACTIVE.get(server) != this) throw new IllegalStateException("Checkpoint boundary is no longer active");
        if (!issues.isEmpty()) throw new IllegalStateException("Checkpoint boundary was violated: " + issues);
        try {
            if (!levels.equals(levels(server)) || !runtimes.equals(runtimes(levels))) throw new IllegalStateException("World dimensions or runtimes changed");
            for (var entry : levels.entrySet()) if (entry.getValue().getGameTime() != times.get(entry.getKey()))
                throw new IllegalStateException("World clock changed during checkpoint boundary");
            for (var entry : archive.dimensions()) {
                var runtime = runtimes.get(entry.dimension());
                if (!catalogues.get(entry.dimension()).equals(EffectPrograms.catalogueId(levels.get(entry.dimension()), runtime).toString()))
                    throw new IllegalStateException("Checkpoint catalogue identity changed");
                var saved = inspect(runtime);
                MinecraftEffectRuntime.validateCheckpointEquipment(levels.get(entry.dimension()), runtime.program(), saved);
                MinecraftEffectRuntime.validateCheckpointActors(levels.get(entry.dimension()), runtime.program(), saved);
                MinecraftEffectRuntime.validateCheckpointAbilities(levels.get(entry.dimension()), runtime.program(), saved);
                if (!saved.effects().equals(entry.effects()) || !saved.equipmentOwners().equals(entry.equipmentOwners()) || !saved.actorOwners().equals(entry.actorOwners()) || !saved.abilityOwners().equals(entry.abilityOwners()))
                    throw new IllegalStateException("Rule state changed during checkpoint boundary");
            }
            var resources=EffectPrograms.resourceRuntime(server);
            var captured=resources.map(MinecraftResourceRuntime::settledResources);
            if(!captured.map(saved->saved.directory()).equals(archive.resourceDirectory())
                    ||!captured.map(saved->saved.placement()).equals(archive.placement()))
                throw new IllegalStateException("Resource or participant directory changed during checkpoint boundary");
        } catch (RuntimeException failure) { throw reject(failure.toString()); }
    }
    /** Return actual native evidence while all rule runtimes remain pinned; this does not commit a store or stop the server. */
    public MinecraftWorldSave.Report saveNative() {
        requireUnchanged();
        var result = MinecraftWorldSave.save(server);
        requireUnchanged();
        return result;
    }
    /** Release only this boundary. No time advancement, actions, discarded input replay or runtime cleanup is implied. */
    @Override public void close() {
        thread(server);
        if (closed) return;
        if (!ACTIVE.remove(server, this)) throw new IllegalStateException("Checkpoint boundary ownership changed");
        closed = true;
    }
}
