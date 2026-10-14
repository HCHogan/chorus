package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.effect.EffectSession;
import java.util.*;

/** All dimension snapshots belonging to one host save attempt. The host still proves world writes succeeded. */
public record CheckpointArchive(List<Entry> dimensions) {
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
    }
    private static void identifier(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Expected canonical namespaced identifier: " + value);
    }
    public JsonObject encode() {
        var output = new JsonObject(); output.addProperty("format", 3); var entries = new JsonArray();
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
        var object = input.getAsJsonObject(); fields(object, Set.of("format", "dimensions"));
        if (!(object.get("format") instanceof JsonPrimitive format) || !format.isNumber()
                || !Set.of(new java.math.BigDecimal(1), new java.math.BigDecimal(2), new java.math.BigDecimal(3)).contains(format.getAsBigDecimal().stripTrailingZeros()))
            throw new IllegalArgumentException("Unsupported checkpoint archive format");
        int version = format.getAsInt();
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
        return new CheckpointArchive(entries);
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
