package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.effect.TimelineIdentity;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import static com.imdomestic.chorus.effect.persistence.PhysicalEffects.*;

/** Independent host format. Launch, Spawn and Signal retain existing DATA encodings; physical progress has its own exact shape. */
public final class PhysicalEffectsCodec {
    private PhysicalEffectsCodec() {}
    public static JsonObject encode(Directory directory) {
        var result = new JsonObject(); result.addProperty("format", 1);
        var active = new JsonArray(); new TreeMap<>(directory.active()).values().forEach(value -> active.add(encodeSnapshot(value)));
        var retired = new JsonArray(); new TreeMap<>(directory.retired()).values().forEach(value -> retired.add(encodeTombstone(value)));
        result.add("active", active); result.add("retired", retired); return result;
    }
    public static Directory decode(JsonElement input) {
        var object = object(input, Set.of("format", "active", "retired"));
        if (!(object.get("format") instanceof JsonPrimitive format) || !format.isNumber() || !format.getAsString().equals("1"))
            throw new IllegalArgumentException("Unsupported physical directory format");
        var active = new TreeMap<String, Snapshot>(); var retired = new TreeMap<String, Tombstone>();
        for (var row : array(object.get("active"))) {
            var snapshot = decodeSnapshot(row);
            if (active.put(snapshot.identity().entity(), snapshot) != null) throw new IllegalArgumentException("Duplicate active physical UUID");
        }
        for (var row : array(object.get("retired"))) {
            var tombstone = decodeTombstone(row);
            if (retired.put(tombstone.identity().entity(), tombstone) != null) throw new IllegalArgumentException("Duplicate retired physical UUID");
        }
        return new Directory(active, retired);
    }
    public static JsonObject encodeSnapshot(Snapshot snapshot) {
        var row = new JsonObject(); row.add("identity", identity(snapshot.identity()));
        row.addProperty("revision", Long.toString(snapshot.revision()));
        row.addProperty("captured_game_time", Long.toString(snapshot.capturedGameTime()));
        row.addProperty("captured_micros", Long.toString(snapshot.capturedMicros()));
        row.addProperty("chunk_x", Integer.toString(snapshot.chunkX())); row.addProperty("chunk_z", Integer.toString(snapshot.chunkZ()));
        row.addProperty("native_digest", snapshot.nativeDigest()); row.add("payload", encodePayload(snapshot.payload())); return row;
    }
    public static Snapshot decodeSnapshot(JsonElement input) {
        var row = object(input, Set.of("identity", "revision", "captured_game_time", "captured_micros", "chunk_x", "chunk_z", "native_digest", "payload"));
        return new Snapshot(identity(row.get("identity")), number(row, "revision"), number(row, "captured_game_time"), number(row, "captured_micros"),
                Math.toIntExact(number(row, "chunk_x")), Math.toIntExact(number(row, "chunk_z")), string(row.get("native_digest")), decodePayload(row.get("payload")));
    }
    private static JsonObject encodeTombstone(Tombstone value) {
        var row = new JsonObject(); row.add("identity", identity(value.identity())); row.addProperty("revision", Long.toString(value.revision()));
        row.addProperty("reason", value.reason()); return row;
    }
    private static Tombstone decodeTombstone(JsonElement input) {
        var row = object(input, Set.of("identity", "revision", "reason"));
        return new Tombstone(identity(row.get("identity")), number(row, "revision"), string(row.get("reason")));
    }
    private static JsonObject identity(Identity value) {
        var row = new JsonObject(); row.addProperty("entity", value.entity()); row.addProperty("kind", kind(value.kind()));
        row.addProperty("domain", value.domain()); row.addProperty("timeline", value.timeline().value()); row.addProperty("catalogue", value.catalogue()); return row;
    }
    private static Identity identity(JsonElement input) {
        var row = object(input, Set.of("entity", "kind", "domain", "timeline", "catalogue"));
        return new Identity(string(row.get("entity")), kind(row.get("kind")), string(row.get("domain")),
                new TimelineIdentity(string(row.get("timeline"))), string(row.get("catalogue")));
    }
    /** Also usable for terminal diagnostic payloads. Only a validated Snapshot can enter the active directory. */
    public static JsonObject encodePayload(Payload value) {
        var row = new JsonObject(); row.addProperty("kind", kind(value.kind()));
        switch (value) {
            case Projectile projectile -> {
                row.add("launch", EffectCheckpointCodec.DATA.encode(projectile.launch())); row.addProperty("age_micros", Long.toString(projectile.ageMicros()));
                row.addProperty("traveled", Double.toString(projectile.traveled())); row.add("progress", progress(projectile.progress()));
                var inside = new JsonArray(); projectile.inside().stream().sorted().forEach(inside::add); row.add("inside", inside);
                row.add("tracking_target", projectile.trackingTarget().<JsonElement>map(JsonPrimitive::new).orElse(JsonNull.INSTANCE));
                row.addProperty("launch_owner_kind", projectile.launchOwnerKind());
            }
            case Pickup pickup -> {
                row.add("spawn", EffectCheckpointCodec.DATA.encode(pickup.spawn())); row.addProperty("created_micros", Long.toString(pickup.createdMicros()));
                row.addProperty("consumed", pickup.consumed());
            }
            case Construct construct -> {
                row.add("spawn", EffectCheckpointCodec.DATA.encode(construct.spawn())); row.addProperty("created_at", Long.toString(construct.createdAt()));
                row.addProperty("expires_at", Long.toString(construct.expiresAt())); row.addProperty("admitted", construct.admitted());
                row.add("ended", construct.ended().map(EffectCheckpointCodec.DATA::encode).orElse(JsonNull.INSTANCE));
            }
        }
        return row;
    }
    public static Payload decodePayload(JsonElement input) {
        if (!(input instanceof JsonObject row)) throw new IllegalArgumentException("Physical payload must be an object");
        return switch (kind(row.get("kind"))) {
            case PROJECTILE -> {
                object(row, Set.of("kind", "launch", "age_micros", "traveled", "progress", "inside", "tracking_target", "launch_owner_kind"));
                var inside = new TreeSet<String>();
                for (var id : array(row.get("inside"))) if (!inside.add(string(id))) throw new IllegalArgumentException("Duplicate inside UUID");
                yield new Projectile(EffectCheckpointCodec.DATA.decode(row.get("launch"), ProjectileFlight.Launch.class), number(row, "age_micros"), decimal(row, "traveled"),
                        progress(row.get("progress")), inside,
                        optionalString(row.get("tracking_target")), string(row.get("launch_owner_kind")));
            }
            case PICKUP -> {
                object(row, Set.of("kind", "spawn", "created_micros", "consumed"));
                yield new Pickup(EffectCheckpointCodec.DATA.decode(row.get("spawn"), WorldPickup.Spawn.class), number(row, "created_micros"), flag(row, "consumed"));
            }
            case CONSTRUCT -> {
                object(row, Set.of("kind", "spawn", "created_at", "expires_at", "admitted", "ended"));
                yield new Construct(EffectCheckpointCodec.DATA.decode(row.get("spawn"), WorldConstruct.Spawn.class), number(row, "created_at"), number(row, "expires_at"),
                        flag(row, "admitted"), row.get("ended").isJsonNull() ? Optional.empty()
                                : Optional.of(EffectCheckpointCodec.DATA.decode(row.get("ended"), RuleEngine.Signal.class)));
            }
        };
    }
    private static JsonObject progress(ProjectileFlight.Progress value) {
        var row = new JsonObject(); row.addProperty("sequence", Long.toString(value.sequence()));
        row.addProperty("bounces", Long.toString(value.bounces())); row.addProperty("entity_contacts", Long.toString(value.entityContacts()));
        row.addProperty("terminal", value.terminal()); var hits = new JsonArray();
        new TreeMap<>(value.hits()).forEach((target, count) -> {
            var hit = new JsonObject(); hit.addProperty("target", target); hit.addProperty("count", Long.toString(count)); hits.add(hit);
        });
        row.add("hits", hits); return row;
    }
    private static ProjectileFlight.Progress progress(JsonElement input) {
        var row = object(input, Set.of("sequence", "bounces", "entity_contacts", "terminal", "hits"));
        var hits = new TreeMap<String, Long>();
        for (var value : array(row.get("hits"))) {
            var hit = object(value, Set.of("target", "count")); String target = string(hit.get("target"));
            if (!UUID.fromString(target).toString().equals(target)) throw new IllegalArgumentException("Noncanonical physical contact UUID");
            if (hits.put(target, number(hit, "count")) != null) throw new IllegalArgumentException("Duplicate physical contact UUID");
        }
        return new ProjectileFlight.Progress(number(row, "sequence"), number(row, "bounces"), number(row, "entity_contacts"), hits, flag(row, "terminal"));
    }
    private static String kind(Kind kind) { return kind.name().toLowerCase(Locale.ROOT); }
    private static Kind kind(JsonElement value) {
        return switch (string(value)) {
            case "projectile" -> Kind.PROJECTILE;
            case "pickup" -> Kind.PICKUP;
            case "construct" -> Kind.CONSTRUCT;
            default -> throw new IllegalArgumentException("Unknown physical effect kind");
        };
    }
    private static JsonObject object(JsonElement input, Set<String> fields) {
        if (!(input instanceof JsonObject object) || !object.keySet().equals(fields)) throw new IllegalArgumentException("Unexpected physical effect fields");
        return object;
    }
    private static JsonArray array(JsonElement input) {
        if (!(input instanceof JsonArray array)) throw new IllegalArgumentException("Physical effect collection must be an array"); return array;
    }
    private static String string(JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) throw new IllegalArgumentException("Physical effect value must be a string");
        return primitive.getAsString();
    }
    private static Optional<String> optionalString(JsonElement value) { return value.isJsonNull() ? Optional.empty() : Optional.of(string(value)); }
    private static long number(JsonObject row, String field) {
        String text = string(row.get(field)); long value = Long.parseLong(text);
        if (!Long.toString(value).equals(text)) throw new IllegalArgumentException("Noncanonical physical integer: " + field); return value;
    }
    private static double decimal(JsonObject row, String field) {
        String text = string(row.get(field)); double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || !Double.toString(value).equals(text)) throw new IllegalArgumentException("Noncanonical physical decimal: " + field); return value;
    }
    private static boolean flag(JsonObject row, String field) {
        if (!(row.get(field) instanceof JsonPrimitive value) || !value.isBoolean()) throw new IllegalArgumentException("Physical flag must be boolean: " + field);
        return value.getAsBoolean();
    }
}
