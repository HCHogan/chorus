package com.imdomestic.chorus.core.codec;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordTreeMigrationTest {
    public record Old(int amount) {
        public Old { if (amount < 0) throw new IllegalArgumentException("Negative amount"); }
    }
    public record Middle(int amount, String label) {}
    public record Current(int amount, String label, boolean active) {}
    public record Envelope(List<Current> list, Map<Current, Optional<Current>> map) {}
    public enum OldMode { PVE, PVP }
    public enum Mode { PVE, PVP, MIXED }
    static Map<String, Class<?>> types() { return Map.of("test:old", Old.class, "test:middle", Middle.class,
            "test:current", Current.class, "test:envelope", Envelope.class, "test:old_mode", OldMode.class, "test:mode", Mode.class); }
    static RecordTreeCodec codec() {
        return new RecordTreeCodec(types(), List.of(
                new RecordTreeCodec.Migration<>(Old.class, Middle.class, o -> new Middle(o.amount(), "legacy")),
                new RecordTreeCodec.Migration<>(Middle.class, Current.class, o -> new Current(o.amount(), o.label(), false)),
                new RecordTreeCodec.Migration<>(OldMode.class, Mode.class, o -> Mode.valueOf(o.name()))));
    }
    @Test void upgradesBeforeCheckingNestedCollectionsAndMapKeysAndWritesOnlyCurrentTypes() {
        var codec = codec(); var old = new RecordTreeCodec(types());
        var value = new Current(3, "legacy", false);
        var envelope = new Envelope(List.of(value), Map.of(value, Optional.of(value)));
        var tree = codec.encode(envelope).getAsJsonObject();
        tree.getAsJsonObject("v").getAsJsonObject("list").getAsJsonArray("v").set(0, old.encode(new Old(3)));
        var pair = tree.getAsJsonObject("v").getAsJsonObject("map").getAsJsonArray("v").get(0).getAsJsonObject();
        pair.add("key", old.encode(new Middle(3, "legacy")));
        pair.getAsJsonObject("value").getAsJsonArray("v").set(0, old.encode(new Old(3)));
        assertEquals(envelope, codec.decode(tree, Envelope.class));
        assertFalse(codec.encode(codec.decode(tree, Envelope.class)).toString().contains("test:old"));
        assertEquals(Mode.PVP, codec.decode(old.encode(OldMode.PVP), Mode.class));
        assertEquals(Set.of(Old.class, Middle.class, OldMode.class), codec.retiredTypes());
        assertEquals(Set.of(Current.class, Envelope.class, Mode.class), codec.types());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new Old(3)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(new Middle(3, "legacy"))));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(OldMode.PVE));
    }
    @Test void rejectsMalformedOldDataBeforeCallingMigrationAndStillValidatesParentTypes() {
        int[] calls = {0};
        var codec = new RecordTreeCodec(types(), List.of(new RecordTreeCodec.Migration<>(Old.class, Current.class,
                o -> { calls[0]++; return new Current(o.amount(), "legacy", false); })));
        for (String json : List.of(
                "{\"$\":\"test:old\",\"v\":{}}",
                "{\"$\":\"test:old\",\"v\":{\"amount\":3}}",
                "{\"$\":\"test:old\",\"v\":{\"amount\":{\"$\":\"int\",\"v\":\"-1\"}}}",
                "{\"$\":\"test:old\",\"v\":{\"amount\":{\"$\":\"int\",\"v\":\"3\"},\"extra\":true}}"))
            assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonParser.parseString(json), Current.class));
        assertEquals(0, calls[0]);
        var tree = new RecordTreeCodec(types()).encode(new Old(3));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(tree, Old.class));
        assertEquals(1, calls[0]);
    }
    @Test void detectsCollidingMigratedSetMembersAndMapKeys() {
        var codec = codec(); var old = new RecordTreeCodec(types());
        var a = new Old(3); var b = new Middle(3, "legacy");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(old.encode(Set.of(a, b)), Set.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(old.encode(Map.of(a, "one", b, "two")), Map.class));
    }
    @Test void rejectsCyclesDuplicatesMissingRegistrationAndInvalidMigrationResults() {
        var first = new RecordTreeCodec.Migration<>(Old.class, Middle.class, o -> new Middle(o.amount(), "legacy"));
        var back = new RecordTreeCodec.Migration<>(Middle.class, Old.class, o -> new Old(o.amount()));
        assertThrows(IllegalArgumentException.class, () -> new RecordTreeCodec(types(), List.of(first, back)));
        assertThrows(IllegalArgumentException.class, () -> new RecordTreeCodec(types(), List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> new RecordTreeCodec(Map.of("test:old", Old.class), List.of(first)));
        var identity = new RecordTreeCodec.Migration<>(Old.class, Old.class, o -> o);
        assertThrows(IllegalArgumentException.class, () -> new RecordTreeCodec(types(), List.of(identity)));
        var invalid = new RecordTreeCodec(types(), List.of(new RecordTreeCodec.Migration<>(Old.class, Current.class, _ -> null)));
        var tree = new RecordTreeCodec(types()).encode(new Old(3));
        assertThrows(IllegalArgumentException.class, () -> invalid.decode(tree, Current.class));
        assertEquals(tree, new RecordTreeCodec(types()).encode(new Old(3)), "Migration must not mutate its input tree");
    }
}
