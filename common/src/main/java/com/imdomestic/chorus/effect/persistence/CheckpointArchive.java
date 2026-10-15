package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.effect.EffectSession;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.effect.resource.ResourceSessionAuthority;
import com.imdomestic.chorus.effect.resource.ParticipantPlacement;
import java.util.*;

/** All dimension snapshots belonging to one host save attempt. The host still proves world writes succeeded. */
public record CheckpointArchive(List<Entry> dimensions, Optional<ResourceAuthority.Directory> resourceDirectory,
        Optional<ParticipantPlacement.Directory> placement) {
    public CheckpointArchive(List<Entry> dimensions) { this(dimensions, Optional.empty(), Optional.empty()); }
    public CheckpointArchive(List<Entry> dimensions, Optional<ResourceAuthority.Directory> resourceDirectory) {
        this(dimensions, resourceDirectory, resourceDirectory.map(_ -> ParticipantPlacement.Directory.EMPTY));
    }
    public record Entry(String program, String dimension, long gameTime, EffectSession.Checkpoint effects, Set<UUID> equipmentOwners, Set<UUID> actorOwners, Set<UUID> abilityOwners) {
        public Entry(String program, String dimension, long gameTime, EffectSession.Checkpoint effects, Set<UUID> equipmentOwners, Set<UUID> actorOwners) {
            this(program, dimension, gameTime, effects, equipmentOwners, actorOwners, Set.of());
        }
        public Entry(String program, String dimension, long gameTime, EffectSession.Checkpoint effects, Set<UUID> equipmentOwners) {
            this(program, dimension, gameTime, effects, equipmentOwners, Set.of());
        }
        public Entry {
            identifier(program); identifier(dimension); Objects.requireNonNull(effects); equipmentOwners = Set.copyOf(equipmentOwners); actorOwners = Set.copyOf(actorOwners); abilityOwners = Set.copyOf(abilityOwners);
            if (gameTime < 0) throw new IllegalArgumentException("Invalid saved world clock");
        }
    }
    public CheckpointArchive {
        dimensions = dimensions.stream().sorted(Comparator.comparing(Entry::dimension)).toList();
        if (dimensions.stream().map(Entry::dimension).distinct().count() != dimensions.size()) throw new IllegalArgumentException("Duplicate saved dimension");
        Objects.requireNonNull(resourceDirectory); Objects.requireNonNull(placement);
        if (resourceDirectory.isPresent() != placement.isPresent())
            throw new IllegalArgumentException("Resource and participant directories require one joint checkpoint");
        if (resourceDirectory.isPresent()) new ResourceSessionAuthority.Checkpoint(resourceDirectory.orElseThrow(), domains(dimensions), placement.orElseThrow());
        else if (dimensions.stream().anyMatch(entry -> entry.effects().resourceRouting()))
            throw new IllegalArgumentException("Coordinated sessions require a joint resource checkpoint");
    }
    private static Map<String, EffectSession.Checkpoint> domains(List<Entry> dimensions) {
        var result = new TreeMap<String, EffectSession.Checkpoint>();
        dimensions.forEach(entry -> result.put(entry.dimension(), entry.effects())); return result;
    }
    /** The host must attach metadata to every captured domain, using exactly the captured effects. */
    public static CheckpointArchive coordinated(List<Entry> dimensions, ResourceSessionAuthority.Checkpoint checkpoint) {
        if (!domains(dimensions).equals(checkpoint.domains())) throw new IllegalArgumentException("Archive dimensions differ from the joint resource checkpoint");
        return new CheckpointArchive(dimensions, Optional.of(checkpoint.directory()), Optional.of(checkpoint.placement()));
    }
    public Optional<ResourceSessionAuthority.Checkpoint> resourceCheckpoint() {
        return resourceDirectory.map(directory -> new ResourceSessionAuthority.Checkpoint(directory, domains(dimensions), placement.orElseThrow()));
    }
    private static void identifier(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Expected canonical namespaced identifier: " + value);
    }
    public JsonObject encode() {
        var output = new JsonObject(); output.addProperty("format", resourceDirectory.isPresent() ? 5 : 3); var entries = new JsonArray();
        resourceDirectory.ifPresent(directory -> output.add("resource_directory", ResourceAuthorityCodec.encode(directory)));
        placement.ifPresent(directory -> output.add("participant_placement", ParticipantPlacementCodec.encode(directory)));
        for (var entry : dimensions) {
            var row = new JsonObject(); row.addProperty("program", entry.program()); row.addProperty("dimension", entry.dimension());
            row.addProperty("game_time", Long.toString(entry.gameTime())); row.add("effects", EffectCheckpointCodec.encode(entry.effects()));
            var owners = new JsonArray(); entry.equipmentOwners().stream().map(UUID::toString).sorted().forEach(owners::add); row.add("equipment_owners", owners);
            var actors = new JsonArray(); entry.actorOwners().stream().map(UUID::toString).sorted().forEach(actors::add); row.add("actor_owners", actors);
            var abilities = new JsonArray(); entry.abilityOwners().stream().map(UUID::toString).sorted().forEach(abilities::add); row.add("ability_owners", abilities);
            entries.add(row);
        }
        output.add("dimensions", entries); return output;
    }
    public static CheckpointArchive decode(JsonElement input) {
        var object = input.getAsJsonObject();
        if (!(object.get("format") instanceof JsonPrimitive format) || !format.isNumber()
                || !Set.of(new java.math.BigDecimal(1), new java.math.BigDecimal(2), new java.math.BigDecimal(3), new java.math.BigDecimal(4), new java.math.BigDecimal(5)).contains(format.getAsBigDecimal().stripTrailingZeros()))
            throw new IllegalArgumentException("Unsupported checkpoint archive format");
        int version = format.getAsInt();
        fields(object, version == 5 ? Set.of("format", "dimensions", "resource_directory", "participant_placement")
                : version == 4 ? Set.of("format", "dimensions", "resource_directory") : Set.of("format", "dimensions"));
        var entries = new ArrayList<Entry>();
        for (var value : object.getAsJsonArray("dimensions")) {
            var row = value.getAsJsonObject(); var expected = new HashSet<>(Set.of("program", "dimension", "game_time", "effects", "equipment_owners"));
            if (version >= 2) expected.add("actor_owners");
            if (version >= 3) expected.add("ability_owners");
            fields(row, expected);
            var owners = new HashSet<UUID>();
            for (var owner : row.getAsJsonArray("equipment_owners")) if (!owners.add(uuid(owner))) throw new IllegalArgumentException("Duplicate saved equipment owner");
            var actors = new HashSet<UUID>();
            if (version >= 2) for (var owner : row.getAsJsonArray("actor_owners")) if (!actors.add(uuid(owner))) throw new IllegalArgumentException("Duplicate saved actor owner");
            var abilities = new HashSet<UUID>();
            if (version >= 3) for (var owner : row.getAsJsonArray("ability_owners")) if (!abilities.add(uuid(owner))) throw new IllegalArgumentException("Duplicate saved ability owner");
            entries.add(new Entry(string(row.get("program")), string(row.get("dimension")), Long.parseLong(string(row.get("game_time"))),
                    EffectCheckpointCodec.decode(row.get("effects")), owners, actors, abilities));
        }
        return new CheckpointArchive(entries,
                version >= 4 ? Optional.of(ResourceAuthorityCodec.decode(object.get("resource_directory"))) : Optional.empty(),
                version == 5 ? Optional.of(ParticipantPlacementCodec.decode(object.get("participant_placement")))
                        : version == 4 ? Optional.of(ParticipantPlacement.Directory.EMPTY) : Optional.empty());
    }
    static void fields(JsonObject object, Set<String> expected) {
        if (!object.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected checkpoint archive fields");
    }
    static void format(JsonObject object) {
        if (!(object.get("format") instanceof JsonPrimitive p) || !p.isNumber() || p.getAsBigDecimal().intValueExact() != 1)
            throw new IllegalArgumentException("Unsupported checkpoint archive format");
    }
    static String string(JsonElement value) {
        if (!(value instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException("Expected checkpoint archive string");
        return p.getAsString();
    }
    static UUID uuid(JsonElement value) {
        String text = string(value); var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical checkpoint UUID");
        return id;
    }
}
