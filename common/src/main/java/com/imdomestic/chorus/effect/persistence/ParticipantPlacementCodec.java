package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.effect.resource.ParticipantPlacement;
import java.util.*;

/** Independent character and Buff locations. Resource leases never imply either placement. */
public final class ParticipantPlacementCodec {
    private ParticipantPlacementCodec() {}
    public static JsonObject encode(ParticipantPlacement.Directory directory) {
        var result = new JsonObject(); result.addProperty("format", 1);
        result.addProperty("next_generation", Long.toString(directory.nextGeneration()));
        result.add("characters", entries(directory.characters())); result.add("buffs", entries(directory.buffs()));
        return result;
    }
    private static JsonArray entries(Map<String, ParticipantPlacement.Lease> placements) {
        var result = new JsonArray();
        new TreeMap<>(placements).forEach((holder, lease) -> {
            var row = new JsonObject(); row.addProperty("holder", holder); row.addProperty("domain", lease.domain());
            row.addProperty("generation", Long.toString(lease.generation())); result.add(row);
        });
        return result;
    }
    public static ParticipantPlacement.Directory decode(JsonElement input) {
        var object = input.getAsJsonObject();
        CheckpointArchive.fields(object, Set.of("format", "next_generation", "characters", "buffs")); CheckpointArchive.format(object);
        return new ParticipantPlacement.Directory(number(object, "next_generation"), entries(object.getAsJsonArray("characters")), entries(object.getAsJsonArray("buffs")));
    }
    private static Map<String, ParticipantPlacement.Lease> entries(JsonArray input) {
        var result = new TreeMap<String, ParticipantPlacement.Lease>();
        for (var value : input) {
            var row = value.getAsJsonObject(); CheckpointArchive.fields(row, Set.of("holder", "domain", "generation"));
            String holder = CheckpointArchive.string(row.get("holder"));
            var lease = new ParticipantPlacement.Lease(CheckpointArchive.string(row.get("domain")), number(row, "generation"));
            if (result.put(holder, lease) != null) throw new IllegalArgumentException("Duplicate participant placement holder");
        }
        return result;
    }
    private static long number(JsonObject object, String field) {
        String text = CheckpointArchive.string(object.get(field)); long value = Long.parseLong(text);
        if (!Long.toString(value).equals(text)) throw new IllegalArgumentException("Noncanonical participant placement number: " + field);
        return value;
    }
}
