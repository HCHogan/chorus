package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.core.codec.AtomicJsonFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One instance per locked world; save protocol, not a replacement for the process lock or actual flush evidence. */
public final class WorldCheckpointStore {
    private enum Phase { RUNNING, PREPARED, COMMITTED }
    private record Disk(UUID run, Phase phase, Optional<UUID> save, Optional<CheckpointArchive> archive,
            Set<String> required, Set<String> confirmed, Optional<String> failure) {
        private Disk {
            required = Set.copyOf(required); confirmed = Set.copyOf(confirmed);
            if (!required.containsAll(confirmed)) throw new IllegalArgumentException("Unexpected save confirmation");
            if (phase == Phase.RUNNING ? save.isPresent() || !required.isEmpty() || !confirmed.isEmpty() || failure.isPresent()
                    : save.isEmpty() || archive.isEmpty() || required.isEmpty()) throw new IllegalArgumentException("Invalid checkpoint store phase");
            if (phase == Phase.COMMITTED && (!required.equals(confirmed) || failure.isPresent())) throw new IllegalArgumentException("Unproven committed checkpoint");
            if (phase != Phase.RUNNING && archive.flatMap(CheckpointArchive::physicalEffects).isPresent() && !required.contains("physical_effects"))
                throw new IllegalArgumentException("Physical checkpoint has no native entity save proof requirement");
        }
        JsonObject encode() {
            var out = new JsonObject(); out.addProperty("format", 1); out.addProperty("run", run.toString()); out.addProperty("phase", phase.name());
            out.add("save", save.<JsonElement>map(id -> new JsonPrimitive(id.toString())).orElse(JsonNull.INSTANCE));
            out.add("archive", archive.<JsonElement>map(CheckpointArchive::encode).orElse(JsonNull.INSTANCE));
            out.add("required", strings(required)); out.add("confirmed", strings(confirmed));
            out.add("failure", failure.<JsonElement>map(JsonPrimitive::new).orElse(JsonNull.INSTANCE)); return out;
        }
        static Disk decode(JsonObject object) {
            CheckpointArchive.fields(object, Set.of("format", "run", "phase", "save", "archive", "required", "confirmed", "failure")); CheckpointArchive.format(object);
            return new Disk(CheckpointArchive.uuid(object.get("run")), Phase.valueOf(CheckpointArchive.string(object.get("phase"))),
                    object.get("save").isJsonNull() ? Optional.empty() : Optional.of(CheckpointArchive.uuid(object.get("save"))),
                    object.get("archive").isJsonNull() ? Optional.empty() : Optional.of(CheckpointArchive.decode(object.get("archive"))),
                    parts(object.getAsJsonArray("required")), parts(object.getAsJsonArray("confirmed")),
                    object.get("failure").isJsonNull() ? Optional.empty() : Optional.of(CheckpointArchive.string(object.get("failure"))));
        }
    }
    private final Path file;
    public WorldCheckpointStore(Path file) { this.file = Objects.requireNonNull(file).toAbsolutePath(); }
    private static JsonArray strings(Set<String> values) { var array = new JsonArray(); values.stream().sorted().forEach(array::add); return array; }
    private static Set<String> parts(JsonArray array) {
        var result = new HashSet<String>();
        for (var value : array) {
            String part = CheckpointArchive.string(value);
            if (part.isBlank() || !result.add(part)) throw new IllegalArgumentException("Blank or duplicate save part");
        }
        return result;
    }
    private Optional<Disk> read() throws IOException {
        try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return Optional.of(Disk.decode(JsonParser.parseReader(input).getAsJsonObject())); }
        catch (NoSuchFileException missing) { return Optional.empty(); }
    }
    /** Must run under the existing world/process lock, before any live world work or entity loading. */
    public synchronized Session begin() throws IOException {
        return begin(false, false);
    }
    /** Opt in only when the host restores the directory and every domain together, before accepting live work. */
    public synchronized Session beginCoordinated() throws IOException {
        return begin(true, false);
    }
    /** Opt in only when the host also authenticates saved physical entities before allowing them to execute. */
    public synchronized Session beginPhysical() throws IOException {
        return begin(true, true);
    }
    private Session begin(boolean coordinated, boolean physical) throws IOException {
        var previous = read();
        if (previous.filter(value -> value.phase() != Phase.COMMITTED).isPresent())
            throw new IllegalStateException("World checkpoint has no completed save; retained data requires explicit recovery");
        var run = UUID.randomUUID(); var recovered = previous.flatMap(Disk::archive);
        if (!coordinated && recovered.flatMap(CheckpointArchive::resourceDirectory).isPresent())
            throw new IllegalStateException("Resource checkpoint requires coordinated host restoration");
        if (!physical && recovered.flatMap(CheckpointArchive::physicalEffects).isPresent())
            throw new IllegalStateException("Physical checkpoint requires native entity restoration");
        // Invalidation precedes returning recoverable state. A crash after this point cannot replay the old archive.
        AtomicJsonFile.write(file, new Disk(run, Phase.RUNNING, Optional.empty(), recovered, Set.of(), Set.of(), Optional.empty()).encode());
        return new Session(run, recovered, physical);
    }
    public final class Session {
        private final UUID run;
        private final Optional<CheckpointArchive> recovered;
        private final boolean physical;
        private Save current;
        private boolean committed;
        private Session(UUID run, Optional<CheckpointArchive> recovered, boolean physical) { this.run = run; this.recovered = recovered; this.physical = physical; }
        public Optional<CheckpointArchive> recovered() { return recovered; }
        private Disk owned() throws IOException {
            var disk = read().orElseThrow(() -> new IllegalStateException("Checkpoint store disappeared"));
            if (!disk.run().equals(run)) throw new IllegalStateException("Checkpoint store belongs to another run");
            return disk;
        }
        /** Only prepares data. No world-save success is inferred from writing this file. */
        public Save prepare(CheckpointArchive archive, Set<String> required) throws IOException {
            synchronized (WorldCheckpointStore.this) {
            Objects.requireNonNull(archive); required = Set.copyOf(required);
            if (required.isEmpty() || required.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Expected named world save parts");
            if (archive.physicalEffects().isPresent() && (!physical || !required.contains("physical_effects")))
                throw new IllegalStateException("Physical checkpoint requires an opted-in host and native entity save proof");
            if (archive.physicalEffects().isEmpty() && (recovered.flatMap(CheckpointArchive::physicalEffects).isPresent()
                    || current != null && current.archive.physicalEffects().isPresent()))
                throw new IllegalStateException("A physical checkpoint cannot discard its entity manifest");
            archive.physicalEffects().ifPresent(next -> {
                recovered.flatMap(CheckpointArchive::physicalEffects).ifPresent(previous -> PhysicalEffects.requireSuccessor(previous, next));
                if (current != null) current.archive.physicalEffects().ifPresent(previous -> PhysicalEffects.requireSuccessor(previous, next));
            });
            if (committed) throw new IllegalStateException("Committed run cannot continue world work");
            var disk = owned();
            if (disk.phase() == Phase.COMMITTED) throw new IllegalStateException("Run was already committed");
            var next = new Save(UUID.randomUUID(), archive, required);
            AtomicJsonFile.write(file, next.disk(Phase.PREPARED).encode()); current = next; return next;
            }
        }
        private void active(Save save) {
            if (save != current) throw new IllegalStateException("Stale save completion");
        }
        public final class Save {
            private final UUID id;
            private final CheckpointArchive archive;
            private final Set<String> required;
            private final Set<String> confirmed = new HashSet<>();
            private Optional<String> failure = Optional.empty();
            private Save(UUID id, CheckpointArchive archive, Set<String> required) { this.id = id; this.archive = archive; this.required = required; }
            private Disk disk(Phase phase) { return new Disk(run, phase, Optional.of(id), Optional.of(archive), required, confirmed, failure); }
            /** Call only after the corresponding actual save/flush has succeeded, not merely been scheduled. */
            public void confirm(String part) {
                synchronized (WorldCheckpointStore.this) {
                    active(this);
                    if (committed || failure.isPresent()) throw new IllegalStateException("Save is no longer accepting confirmations");
                    if (!required.contains(part)) throw new IllegalArgumentException("Unexpected save part: " + part);
                    confirmed.add(part);
                }
            }
            /** A failure after an early commit invalidates that same save, but never a newer run/save. */
            public void failed(String reason) throws IOException {
                synchronized (WorldCheckpointStore.this) {
                    active(this); if (reason.isBlank()) throw new IllegalArgumentException("Expected save failure reason");
                    var disk = owned();
                    if (!disk.save().equals(Optional.of(id))) throw new IllegalStateException("Failure belongs to an older save");
                    failure = Optional.of(reason);
                    AtomicJsonFile.write(file, disk(Phase.PREPARED).encode());
                }
            }
            public void commit() throws IOException {
                synchronized (WorldCheckpointStore.this) {
                    active(this);
                    if (committed || failure.isPresent() || !confirmed.equals(required)) throw new IllegalStateException("World save has not succeeded for every required part");
                    var disk = owned();
                    if (disk.phase() != Phase.PREPARED || !disk.save().equals(Optional.of(id)) || !disk.archive().equals(Optional.of(archive))
                            || !disk.required().equals(required) || disk.failure().isPresent()) throw new IllegalStateException("Prepared save changed before commit");
                    AtomicJsonFile.write(file, disk(Phase.COMMITTED).encode()); committed = true;
                }
            }
        }
    }
}
