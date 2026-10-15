package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.PhysicalEffectsTest.*;
import static com.imdomestic.chorus.effect.persistence.PhysicalEffects.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.projectile.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PhysicalEffectsCodecTest {
    @Test void allLiveKindsAndRetiredIdentitiesRoundTripDeterministicallyUsingExistingDataBodies() {
        var directory = fixture().directory(); var encoded = PhysicalEffectsCodec.encode(directory);
        var restored = PhysicalEffectsCodec.decode(JsonParser.parseString(encoded.toString()));
        assertEquals(directory, restored); assertEquals(encoded.toString(), PhysicalEffectsCodec.encode(restored).toString());
        var reversed = new LinkedHashMap<String, Snapshot>(); directory.active().values().stream().sorted(Comparator.comparing((Snapshot value) -> value.identity().entity()).reversed())
                .forEach(value -> reversed.put(value.identity().entity(), value));
        assertEquals(encoded.toString(), PhysicalEffectsCodec.encode(new Directory(reversed, directory.retired())).toString());
        assertEquals(EffectCheckpointCodec.DATA.encode(projectile().launch()), first(encoded).getAsJsonObject("payload").get("launch"));
        assertEquals(Set.of("sequence", "bounces", "entity_contacts", "terminal", "hits"), first(encoded).getAsJsonObject("payload").getAsJsonObject("progress").keySet());
        assertEquals("2", first(encoded).getAsJsonObject("payload").getAsJsonObject("progress").get("sequence").getAsString());
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.encode(directory), "host records must not grant replayable DATA capabilities");
    }
    @Test void foreignShotIssuerClockAndMemberAreNotReboundToThePhysicalDomain() {
        var original = projectile(); var launch = original.launch();
        var member = new ShotGroups.Member(ShotGroups.Handle.issued("historical-shot", ORIGIN, 3, 9_000_000, 15_000_000, "test:issuer"), 1);
        var captured = new ProjectileFlight.Launch(launch.position(), launch.direction(), launch.parameters(), launch.owner(), launch.continuation(), launch.impactSlot(), Optional.of(member), TARGET);
        var value = new Projectile(captured, original.ageMicros(), original.traveled(), original.progress(), original.inside(), original.trackingTarget(), original.launchOwnerKind());
        var restored = (Projectile) PhysicalEffectsCodec.decodePayload(PhysicalEffectsCodec.encodePayload(value));
        assertEquals(value, restored); assertEquals(member, restored.launch().member().orElseThrow());
        assertEquals(Optional.of("test:issuer"), restored.launch().member().orElseThrow().shot().issuer());
        assertEquals(TARGET, restored.launch().emitter());
    }
    @Test void terminalDiagnosticPayloadsRoundTripButNeverBecomeActiveDirectoryEntries() {
        var projectile = projectile(); var pickup = pickup(); var construct = construct();
        var terminal = new Projectile(projectile.launch(), projectile.ageMicros(), projectile.traveled(), projectile.progress().abandon(), projectile.inside(), Optional.empty(), projectile.launchOwnerKind());
        var collected = new Pickup(pickup.spawn(), pickup.createdMicros(), true);
        var ended = new Construct(construct.spawn(), construct.createdAt(), construct.expiresAt(), true,
                Optional.of(WorldConstruct.ended(construct.spawn(), CONSTRUCT, WorldConstruct.End.EXPIRED, "discarded", 5_000_000)));
        for (var value : List.<Payload>of(terminal, collected, ended)) {
            assertEquals(value, PhysicalEffectsCodec.decodePayload(PhysicalEffectsCodec.encodePayload(value)));
            String id = switch (value.kind()) { case PROJECTILE -> PROJECTILE; case PICKUP -> PICKUP; case CONSTRUCT -> CONSTRUCT; };
            assertThrows(IllegalArgumentException.class, () -> snapshot(identity(id, value.kind()), 1, value));
        }
    }
    @Test void unknownVersionUnknownFieldsWrongTypesAndMissingNestedFieldsFailClosed() {
        var encoded = PhysicalEffectsCodec.encode(fixture().directory());
        for (String fault : List.of("version", "fraction", "string_format", "extra", "missing", "object_active", "extra_snapshot", "missing_digest",
                "unknown_identity", "numeric_uuid", "unknown_kind", "upper_kind", "wrong_payload_kind", "extra_payload", "missing_launch", "wrong_launch_type", "string_consumed", "numeric_target")) {
            var invalid = encoded.deepCopy(); var row = first(invalid); var payload = row.getAsJsonObject("payload");
            switch (fault) {
                case "version" -> invalid.addProperty("format", 2);
                case "fraction" -> invalid.addProperty("format", 1.0);
                case "string_format" -> invalid.addProperty("format", "1");
                case "extra" -> invalid.addProperty("ignored", true);
                case "missing" -> invalid.remove("retired");
                case "object_active" -> invalid.add("active", new JsonObject());
                case "extra_snapshot" -> row.addProperty("ignored", true);
                case "missing_digest" -> row.remove("native_digest");
                case "unknown_identity" -> row.getAsJsonObject("identity").addProperty("ignored", true);
                case "numeric_uuid" -> row.getAsJsonObject("identity").addProperty("entity", 1);
                case "unknown_kind" -> row.getAsJsonObject("identity").addProperty("kind", "field");
                case "upper_kind" -> row.getAsJsonObject("identity").addProperty("kind", "PROJECTILE");
                case "wrong_payload_kind" -> payload.addProperty("kind", "pickup");
                case "extra_payload" -> payload.addProperty("ignored", true);
                case "missing_launch" -> payload.remove("launch");
                case "wrong_launch_type" -> payload.add("launch", EffectCheckpointCodec.DATA.encode(pickup().spawn()));
                case "string_consumed" -> invalid.getAsJsonArray("active").get(1).getAsJsonObject().getAsJsonObject("payload").addProperty("consumed", "false");
                case "numeric_target" -> payload.addProperty("tracking_target", 1);
            }
            assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decode(invalid), fault);
        }
    }
    @Test void canonicalIntegerStringsRejectNumericCoercionLeadingZerosSignsAndOverflow() {
        var encoded = PhysicalEffectsCodec.encode(fixture().directory());
        for (String text : List.of("01", "+0", "-0", "0.0", "1e0", " 0", "9223372036854775808", "-1")) {
            var invalid = encoded.deepCopy(); first(invalid).addProperty("revision", text);
            assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decode(invalid), text);
        }
        var numeric = encoded.deepCopy(); first(numeric).addProperty("revision", 0);
        assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decode(numeric));
        var tooWideChunk = encoded.deepCopy(); first(tooWideChunk).addProperty("chunk_x", "2147483648");
        assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decode(tooWideChunk));
        long large = 9_007_199_254_740_993L;
        var snapshot = new Snapshot(identity(PROJECTILE, Kind.PROJECTILE), large, large, large, Integer.MIN_VALUE, Integer.MAX_VALUE, DIGEST, projectile());
        assertEquals(snapshot, PhysicalEffectsCodec.decodeSnapshot(PhysicalEffectsCodec.encodeSnapshot(snapshot)));
    }
    @Test void canonicalFiniteDecimalAndBooleanValuesCannotBeForgedThroughJsonCoercion() {
        var base = PhysicalEffectsCodec.encodePayload(projectile());
        for (String text : List.of("4", "4.00", "0x1.0p2", "NaN", "Infinity", "-Infinity", "-1.0", " 4.0")) {
            var invalid = base.deepCopy(); invalid.addProperty("traveled", text);
            assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decodePayload(invalid), text);
        }
        var invalid = base.deepCopy(); invalid.addProperty("traveled", 4.0);
        assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decodePayload(invalid));
        var negativeZero = copy(projectile(), 100_000, -0.0, projectile().progress(), projectile().inside(), projectile().trackingTarget());
        var restored = (Projectile) PhysicalEffectsCodec.decodePayload(PhysicalEffectsCodec.encodePayload(negativeZero));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(restored.traveled()));
        var construct = PhysicalEffectsCodec.encodePayload(construct()); construct.addProperty("admitted", 1);
        assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decodePayload(construct));
    }
    @Test void duplicateActiveRetiredInsideAndCrossListIdentitiesAreRejected() {
        var encoded = PhysicalEffectsCodec.encode(fixture().directory());
        for (String fault : List.of("active", "retired", "inside", "both")) {
            var invalid = encoded.deepCopy();
            switch (fault) {
                case "active", "retired" -> { var entries = invalid.getAsJsonArray(fault); entries.add(entries.get(0).deepCopy()); }
                case "inside" -> first(invalid).getAsJsonObject("payload").getAsJsonArray("inside").add(TARGET);
                case "both" -> invalid.getAsJsonArray("retired").get(0).getAsJsonObject().add("identity", first(invalid).get("identity").deepCopy());
            }
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffectsCodec.decode(invalid), fault);
        }
    }
    @Test void nativeDigestIsOnlyASyntaxCheckedReferenceAndTerminalRecordsHaveNoRestorePayload() {
        var value = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, projectile());
        var changed = PhysicalEffectsCodec.encodeSnapshot(value); changed.addProperty("native_digest", "0".repeat(64));
        var decoded = PhysicalEffectsCodec.decodeSnapshot(changed);
        assertEquals("0".repeat(64), decoded.nativeDigest(), "actual typed NBT proof belongs to the native host");
        var directory = PhysicalEffectsCodec.encode(fixture().directory());
        assertEquals(Set.of("identity", "revision", "reason"), directory.getAsJsonArray("retired").get(0).getAsJsonObject().keySet());
        directory.getAsJsonArray("retired").get(0).getAsJsonObject().add("payload", PhysicalEffectsCodec.encodePayload(pickup()));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffectsCodec.decode(directory));
    }
    @Test void progressHasStrictFieldsCanonicalCountsAndUniqueCanonicalTargets() {
        var encoded = PhysicalEffectsCodec.encodePayload(projectile());
        for (String fault : List.of("extra", "missing", "numeric_sequence", "leading_zero", "plus", "fraction", "overflow", "negative_bounces", "string_terminal",
                "object_hits", "duplicate_target", "extra_hit", "missing_count", "numeric_count", "zero_count", "too_many_contacts", "noncanonical_target", "numeric_target")) {
            var invalid = encoded.deepCopy(); var progress = invalid.getAsJsonObject("progress");
            var hit = progress.getAsJsonArray("hits").get(0).getAsJsonObject();
            switch (fault) {
                case "extra" -> progress.addProperty("ignored", true);
                case "missing" -> progress.remove("bounces");
                case "numeric_sequence" -> progress.addProperty("sequence", 2);
                case "leading_zero" -> progress.addProperty("sequence", "02");
                case "plus" -> progress.addProperty("sequence", "+2");
                case "fraction" -> progress.addProperty("sequence", "2.0");
                case "overflow" -> progress.addProperty("sequence", "9223372036854775808");
                case "negative_bounces" -> progress.addProperty("bounces", "-1");
                case "string_terminal" -> progress.addProperty("terminal", "false");
                case "object_hits" -> progress.add("hits", new JsonObject());
                case "duplicate_target" -> progress.getAsJsonArray("hits").add(hit.deepCopy());
                case "extra_hit" -> hit.addProperty("ignored", true);
                case "missing_count" -> hit.remove("count");
                case "numeric_count" -> hit.addProperty("count", 1);
                case "zero_count" -> hit.addProperty("count", "0");
                case "too_many_contacts" -> hit.addProperty("count", "2");
                case "noncanonical_target" -> hit.addProperty("target", "1-1-1-1-1");
                case "numeric_target" -> hit.addProperty("target", 1);
            }
            assertThrows(RuntimeException.class, () -> PhysicalEffectsCodec.decodePayload(invalid), fault);
        }
    }
    @Test void progressPreservesHugeExactCountsAndSortsTargetsWithoutAddingAGlobalLimit() {
        long count = 9_007_199_254_740_993L; var original = projectile(); var launch = original.launch();
        var unlimited = new ProjectileFlight.Collision(ProjectileFlight.Limit.UNLIMITED, ProjectileFlight.Limit.UNLIMITED, ProjectileFlight.Limit.UNLIMITED, 1);
        var parameters = new ProjectileFlight.Parameters(20, 0, 1, 5_000_000, unlimited);
        var captured = new ProjectileFlight.Launch(launch.position(), launch.direction(), parameters, launch.owner(), launch.continuation(), launch.impactSlot());
        var hits = new LinkedHashMap<String, Long>(); hits.put(TARGET, count); hits.put(OWNER, 1L);
        var progress = new ProjectileFlight.Progress(count + 3, 2, count + 1, hits, false);
        var value = new Projectile(captured, 100_000, 4, progress, Set.of(TARGET), Optional.empty(), "player");
        var encoded = PhysicalEffectsCodec.encodePayload(value); var actual = encoded.getAsJsonObject("progress");
        assertEquals(Long.toString(count + 3), actual.get("sequence").getAsString());
        assertEquals(OWNER, actual.getAsJsonArray("hits").get(0).getAsJsonObject().get("target").getAsString());
        assertEquals(Long.toString(count), actual.getAsJsonArray("hits").get(1).getAsJsonObject().get("count").getAsString());
        assertEquals(value, PhysicalEffectsCodec.decodePayload(JsonParser.parseString(encoded.toString())));
        var maximum = new Projectile(captured, 100_000, 4, new ProjectileFlight.Progress(Long.MAX_VALUE, 0, Long.MAX_VALUE, Map.of(TARGET, Long.MAX_VALUE), true), Set.of(TARGET), Optional.empty(), "player");
        assertEquals(maximum, PhysicalEffectsCodec.decodePayload(PhysicalEffectsCodec.encodePayload(maximum)));
    }
    private static JsonObject first(JsonObject encoded) { return encoded.getAsJsonArray("active").get(0).getAsJsonObject(); }
}
