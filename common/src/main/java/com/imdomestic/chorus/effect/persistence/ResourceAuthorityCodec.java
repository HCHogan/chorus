package com.imdomestic.chorus.effect.persistence;

import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import java.util.*;

/** Supervisor data only. Saving it alone is not an atomic save of the participating world sessions. */
public final class ResourceAuthorityCodec {
    private ResourceAuthorityCodec() {}
    public static JsonObject encode(ResourceAuthority.Directory directory) {
        var result = new JsonObject(); result.addProperty("format", 1); result.addProperty("next_generation", Long.toString(directory.nextGeneration()));
        var holders = new JsonArray();
        directory.holders().values().forEach(partition -> {
            var row = new JsonObject(); row.addProperty("holder", partition.holder()); row.addProperty("domain", partition.lease().domain());
            row.addProperty("generation", Long.toString(partition.lease().generation())); row.addProperty("revision", Long.toString(partition.revision()));
            row.addProperty("time_micros", Long.toString(partition.timeMicros())); row.add("program", EffectCheckpointCodec.DATA.encode(partition.program()));
            var accounts = new JsonArray(); partition.accounts().values().forEach(value -> accounts.add(EffectCheckpointCodec.DATA.encode(value)));
            row.add("accounts", accounts); holders.add(row);
        });
        result.add("holders", holders); return result;
    }
    public static ResourceAuthority.Directory decode(JsonElement input) {
        var object = input.getAsJsonObject(); CheckpointArchive.fields(object, Set.of("format", "next_generation", "holders")); CheckpointArchive.format(object);
        var holders = new TreeMap<String, ResourceAuthority.Partition>(); var validated = new HashSet<EffectProgram>();
        for (var value : object.getAsJsonArray("holders")) {
            var row = value.getAsJsonObject(); CheckpointArchive.fields(row, Set.of("holder", "domain", "generation", "revision", "time_micros", "program", "accounts"));
            String holder = CheckpointArchive.string(row.get("holder"));
            var lease = new ResourceAuthority.Lease(CheckpointArchive.string(row.get("domain")), number(row, "generation"));
            var program = EffectCheckpointCodec.DATA.decode(row.get("program"), EffectProgram.class);
            if (validated.add(program)) new CompiledEffects(program);
            var accounts = new TreeMap<String, ResourceState>();
            for (var encoded : row.getAsJsonArray("accounts")) {
                var account = EffectCheckpointCodec.DATA.decode(encoded, ResourceState.class);
                if (accounts.put(account.key().resource(), account) != null) throw new IllegalArgumentException("Duplicate resource in authority partition");
            }
            var partition = new ResourceAuthority.Partition(holder, lease, number(row, "revision"), number(row, "time_micros"), program, accounts);
            if (holders.put(holder, partition) != null) throw new IllegalArgumentException("Duplicate resource authority holder");
        }
        return new ResourceAuthority.Directory(number(object, "next_generation"), holders);
    }
    private static long number(JsonObject object, String field) {
        String text = CheckpointArchive.string(object.get(field)); long value = Long.parseLong(text);
        if (!Long.toString(value).equals(text)) throw new IllegalArgumentException("Noncanonical resource authority number: " + field);
        return value;
    }
}
