package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.core.codec.RecordTreeCodec;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.EffectProgram;
import com.imdomestic.chorus.rule.TimelineEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.Optional;

/** Versioned, exact JSON checkpoint data and atomic single-file replacement; not a Minecraft world-save transaction. */
public final class EffectCheckpointCodec {
    private EffectCheckpointCodec() {}
    public static final int FORMAT = 3;
    public static final RecordTreeCodec DATA = CheckpointTypes.create();
    private static final Set<String> FIELDS_V1 = Set.of("format", "program", "state", "time_micros", "next_event", "next_frame");

    private static final Set<String> FIELDS_V2 = Set.of("format", "program", "state", "time_micros", "next_event", "next_frame", "resource_routing");
    private static final Set<String> FIELDS_V3 = Set.of("format", "program", "state", "time_micros", "next_event", "next_frame", "resource_routing", "timeline_identity");

    public static JsonObject encode(EffectSession.Checkpoint checkpoint) {
        var output = new JsonObject(); output.addProperty("format", checkpoint.identity().isPresent() ? FORMAT : checkpoint.resourceRouting() ? 2 : 1);
        if (checkpoint.identity().isPresent() || checkpoint.resourceRouting()) output.addProperty("resource_routing", checkpoint.resourceRouting());
        checkpoint.identity().ifPresent(identity -> output.addProperty("timeline_identity", identity.value()));
        output.add("program", DATA.encode(checkpoint.program()));
        var timeline = checkpoint.timeline();
        if (timeline.domain().buffs().timeMicros() != timeline.timeMicros()) throw new IllegalArgumentException("Checkpoint and domain clocks differ");
        output.add("state", DATA.encode(timeline.domain()));
        output.addProperty("time_micros", Long.toString(timeline.timeMicros()));
        output.addProperty("next_event", Long.toString(timeline.nextEvent()));
        output.addProperty("next_frame", Long.toString(timeline.nextFrame()));
        return output;
    }
    public static EffectSession.Checkpoint decode(JsonElement input) {
        var object = input.getAsJsonObject();
        var format = object.get("format");
        if (!(format instanceof JsonPrimitive primitive) || !primitive.isNumber()) throw new IllegalArgumentException("Unsupported checkpoint format");
        int version = primitive.getAsBigDecimal().intValueExact();
        if (version != 1 && version != 2 && version != FORMAT) throw new IllegalArgumentException("Unsupported checkpoint format");
        if (!object.keySet().equals(version == 1 ? FIELDS_V1 : version == 2 ? FIELDS_V2 : FIELDS_V3)) throw new IllegalArgumentException("Checkpoint fields differ from format " + version);
        boolean routing = false;
        if (version >= 2) {
            if (!(object.get("resource_routing") instanceof JsonPrimitive routed) || !routed.isBoolean()) throw new IllegalArgumentException("Invalid resource routing mode");
            routing = routed.getAsBoolean();
        }
        Optional<TimelineIdentity> identity = Optional.empty();
        if (version == 3) {
            if (!(object.get("timeline_identity") instanceof JsonPrimitive value) || !value.isString())
                throw new IllegalArgumentException("Invalid checkpoint timeline identity");
            identity = Optional.of(new TimelineIdentity(value.getAsString()));
        }
        var program = DATA.decode(object.get("program"), EffectProgram.class);
        var state = DATA.decode(object.get("state"), EffectState.class);
        long time = sequence(object, "time_micros");
        if (state.buffs().timeMicros() != time) throw new IllegalArgumentException("Checkpoint and domain clocks differ");
        return new EffectSession.Checkpoint(program, new TimelineEngine.Checkpoint<>(program.version(), state, time,
                sequence(object, "next_event"), sequence(object, "next_frame")), routing, identity);
    }
    private static long sequence(JsonObject object, String field) {
        var value = object.get(field);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) throw new IllegalArgumentException("Checkpoint " + field + " must be a decimal string");
        return Long.parseLong(primitive.getAsString());
    }
    public static EffectSession.Checkpoint read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { return decode(JsonParser.parseReader(reader)); }
    }
    /** Validate/encode before touching the previous file. Unsupported atomic rename fails without a non-atomic fallback. */
    public static void write(Path path, EffectSession.Checkpoint checkpoint) throws IOException {
        com.imdomestic.chorus.core.codec.AtomicJsonFile.write(path, encode(checkpoint));
    }
}
