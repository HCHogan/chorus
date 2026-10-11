package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.random.RandomState;
import com.imdomestic.chorus.rule.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectCheckpointCodecTest {
    @TempDir Path directory;
    private static EffectSession.Checkpoint saved(EffectState state) throws Exception {
        return new EffectSession(load("periodic"), EffectCheckpointTest.clock(), 1, state, _ -> { throw new AssertionError(); }).checkpoint();
    }
    @Test void everyDataFixtureRetainsItsFullDefinitionTreeAndDeterministicEncoding() throws Exception {
        Path fixtures = Path.of(Objects.requireNonNull(getClass().getResource("/effects/periodic.json")).toURI()).getParent();
        try (var paths = Files.list(fixtures)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = path.getFileName().toString();
                var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                // Numeric calibration inputs and test settings are not rule catalogues; calibration programs still round-trip.
                if (!json.has("version")) {
                    assertTrue(name.endsWith("_test_calibration.json") || name.equals("dual_loader_options.json") || name.equals("incremental_reload_settings.json"), name);
                    continue;
                }
                var decoded = com.imdomestic.chorus.effect.data.ProgramModule.CODEC.parse(JsonOps.INSTANCE, json);
                assertTrue(decoded.result().isPresent(), () -> path + ": " + decoded.error());
                var program = decoded.getOrThrow().program(); // Imports are linked before the compiled catalogue is checkpointed.
                var data = EffectCheckpointCodec.DATA.encode(program);
                var restored = EffectCheckpointCodec.DATA.decode(JsonParser.parseString(data.toString()), program.getClass());
                assertEquals(program, restored, path.toString());
                assertEquals(data, EffectCheckpointCodec.DATA.encode(restored), path.toString());
            }
        }
    }
    @Test void exactLongCountersRandomBitsNegativeZeroAndOptionalValuesRoundTrip() throws Exception {
        long large = 9_007_199_254_740_993L;
        var domain = EffectState.empty().withBuffs(new BuffStore(large, large + 17, Map.of())).withRandom(new RandomState(Long.MIN_VALUE, large));
        var base = saved(domain);
        var checkpoint = new EffectSession.Checkpoint(base.program(), new TimelineEngine.Checkpoint<>(base.program().version(), domain, large, large + 1, large + 2));
        var tree = EffectCheckpointCodec.encode(checkpoint);
        assertEquals(Long.toString(large), tree.get("time_micros").getAsString());
        assertEquals(checkpoint, EffectCheckpointCodec.decode(JsonParser.parseString(tree.toString())));
        var options = List.of(OptionalLong.of(Long.MAX_VALUE), OptionalLong.empty(), OptionalInt.of(Integer.MIN_VALUE), OptionalInt.empty(),
                OptionalDouble.of(-0.0), OptionalDouble.empty(), Optional.of(new Measure(-0.0, Unit.DAMAGE)), Optional.empty());
        assertEquals(options, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(options), List.class));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(-0.0), Double.class)));
    }
    @Test void absentReserveStaysUnlimitedAndExplicitZeroReserveStaysFinite() throws Exception {
        var state = EffectState.empty().withAmmo(new AmmoState("unlimited", 17, 10, Optional.empty()))
                .withAmmo(new AmmoState("empty", 0, 10, Optional.of(new AmmoState.Reserve(0, 90))));
        var value = saved(state);
        assertEquals(value, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(value)));
        assertTrue(EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(value)).timeline().domain().ammunition().get("unlimited").reserve().isEmpty());
    }
    @Test void unknownVersionsFieldsTypesInvalidClocksAndNonStringIdentitiesAreRejected() throws Exception {
        var input = EffectCheckpointCodec.encode(saved(EffectState.empty()));
        for (String fault : List.of("version", "fractional_version", "missing", "extra", "type", "clock", "zero_sequence", "overflow_sequence", "numeric_sequence", "domain_missing", "domain_extra")) {
            var changed = input.deepCopy();
            switch (fault) {
                case "version" -> changed.addProperty("format", 2);
                case "fractional_version" -> changed.addProperty("format", 1.5);
                case "missing" -> changed.remove("program");
                case "extra" -> changed.addProperty("ignored", true);
                case "type" -> changed.getAsJsonObject("state").addProperty("$", "java.lang.Runtime");
                case "clock" -> changed.addProperty("time_micros", "1");
                case "zero_sequence" -> changed.addProperty("next_frame", "0");
                case "overflow_sequence" -> changed.addProperty("next_event", "9223372036854775808");
                case "numeric_sequence" -> changed.addProperty("next_event", 1);
                case "domain_missing" -> changed.getAsJsonObject("state").getAsJsonObject("v").remove("timers");
                case "domain_extra" -> changed.getAsJsonObject("state").getAsJsonObject("v").addProperty("ignored", true);
            }
            assertThrows(RuntimeException.class, () -> EffectCheckpointCodec.decode(changed), fault);
        }
    }
    @Test void nestedGenericTypesConstructorsDuplicateKeysAndDuplicateSetMembersAreValidated() {
        var source = source("test:periodic");
        var bad = EffectCheckpointCodec.DATA.encode(source).getAsJsonObject();
        bad.getAsJsonObject("v").add("parameters", EffectCheckpointCodec.DATA.encode(Map.of("amount", "not a measure")));
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(bad, EffectSource.class));
        var invalid = EffectCheckpointCodec.DATA.encode(new RandomState(1, 0)).getAsJsonObject();
        invalid.getAsJsonObject("v").add("cursor", EffectCheckpointCodec.DATA.encode(-1L));
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(invalid, RandomState.class));
        var map = EffectCheckpointCodec.DATA.encode(Map.of("key", source)).getAsJsonObject();
        map.getAsJsonArray("v").add(map.getAsJsonArray("v").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(map, Map.class));
        var set = EffectCheckpointCodec.DATA.encode(Set.of("tag")).getAsJsonObject();
        set.getAsJsonArray("v").add("tag");
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(set, Set.class));
        var optional = EffectCheckpointCodec.DATA.encode(OptionalLong.of(1)).getAsJsonObject();
        optional.getAsJsonArray("v").set(0, EffectCheckpointCodec.DATA.encode(1));
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(optional, OptionalLong.class));
        for (double number : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.encode(number));
            var nonfinite = EffectCheckpointCodec.DATA.encode(1.0).getAsJsonObject(); nonfinite.addProperty("v", Double.toString(number));
            assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(nonfinite, Double.class));
        }
    }
    private record ForeignPayload(String data) implements RuleEngine.Payload {}
    @Test void atomicFileReplacementPreservesPreviousCheckpointOnUnsupportedDataAndLeavesNoTemporaryFile() throws Exception {
        var path = directory.resolve("checkpoint.json");
        var first = saved(EffectState.empty()); EffectCheckpointCodec.write(path, first);
        assertEquals(first, EffectCheckpointCodec.read(path)); byte[] before = Files.readAllBytes(path);
        var foreign = saved(EffectState.empty().schedule(new EffectState.Timer("custom", 1, 0, 1, new RuleEngine.Signal("foreign", new ForeignPayload("data")), Optional.empty())));
        var error = assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.write(path, foreign));
        assertTrue(error.getMessage().contains("Unregistered stored type")); assertTrue(error.getMessage().contains("payload"));
        assertArrayEquals(before, Files.readAllBytes(path));
        var next = saved(EffectState.empty().withRandom(new RandomState(48, 2))); EffectCheckpointCodec.write(path, next);
        assertEquals(next, EffectCheckpointCodec.read(path));
        try (var files = Files.list(directory)) { assertEquals(List.of(path), files.toList()); }
    }
    @Test void mapsAndSetsEncodeDeterministicallyButCustomComparatorCodeIsNotSilentlyDiscarded() {
        var a = new LinkedHashMap<String, Object>(); a.put("b", Set.of("y", "x")); a.put("a", 4L);
        var b = new LinkedHashMap<String, Object>(); b.put("a", 4L); b.put("b", new LinkedHashSet<>(List.of("x", "y")));
        assertEquals(EffectCheckpointCodec.DATA.encode(a), EffectCheckpointCodec.DATA.encode(b));
        var sorted = new TreeMap<Double, Double>(); sorted.put(1.0, 7.0); sorted.put(2.0, 9.0);
        var curve = new Curve.Table(sorted, Curve.Interpolation.LINEAR, Curve.Boundary.ERROR);
        var copy = EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(curve), Curve.Table.class);
        assertEquals(8, copy.evaluate(1.5)); assertEquals(curve, copy);
        var custom = new TreeMap<String, Integer>(Comparator.reverseOrder()); custom.put("x", 1);
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.encode(custom));
    }
    private static EffectState domainCopy(EffectState state) {
        var copy = EffectCheckpointCodec.DATA.decode(JsonParser.parseString(EffectCheckpointCodec.DATA.encode(state).toString()), EffectState.class);
        assertEquals(state, copy); return copy;
    }
    @Test void activeReloadAndPartiallyCompletedShotKeepTheirPlansGearAndMembership() throws Exception {
        var reload = new WeaponReloadTest.Harness(); reload.equip(WeaponReloadTest.pair("primary")); reload.reload();
        assertFalse(reload.state().reloads().isEmpty()); assertFalse(reload.state().equipment().isEmpty());
        var copy = domainCopy(reload.state());
        assertEquals(copy.reloads().get("player"), copy.timers().values().stream().filter(t -> t.signal().payload() instanceof com.imdomestic.chorus.effect.weapon.WeaponReload.Plan).findFirst().orElseThrow().signal().payload());
        var shot = new ShotGroupTest.Harness(); shot.fire(); shot.until(50_000); shot.impact(0, "enemy", 1, true);
        assertFalse(shot.state().shots().isEmpty()); assertFalse(shot.state().shotGroups().isEmpty());
        domainCopy(shot.state());
    }
    @Test void damageGroupsAndTalliesKeepHistoricalGrantsAndAlreadyCountedReceiptIdentities() throws Exception {
        var group = new DamageGroupTest.Harness(); group.run("arm"); group.run("delayed");
        assertFalse(group.state().damageGroups().values().iterator().next().grants().isEmpty());
        var state = domainCopy(group.state());
        assertEquals(group.program.outgoing(group.state(), group.commands.getFirst(), 10), group.program.outgoing(state, group.commands.getFirst(), 10));
        var handle = new DamageTallies.Handle("tally", 0, 1_000_000);
        var receipt = new DamageReceipt("counted", DamageReceipt.Outcome.APPLIED, 2, 3, 4, Optional.of("death"), false);
        state = DamageTallies.record(DamageTallies.begin(state, handle).state(), handle, receipt).state();
        var persisted = domainCopy(state);
        assertEquals(state, DamageTallies.record(persisted, handle, receipt).state());
        assertEquals(new DamageTallies.Summary(1, 1, 1, 1, 2, 3, 4), DamageTallies.read(persisted, handle).summary().orElseThrow());
    }
    @Test void buffComponentSnapshotsAndObservedEmptyWorldDataStayDistinctFromMissing() throws Exception {
        var h = new DamageSnapshotComponentTest.Harness(); h.send(0, "init"); h.send(0, "save");
        domainCopy(h.state());
        var components = h.instance().components().number("n", com.imdomestic.chorus.effect.buff.BuffComponents.Update.ADD, 1).remember("seen", "event")
                .reference("ref", "entity").targets("members", Targets.Identities.of(List.of("member")))
                .position("place", Optional.of(new WorldPosition("test:dimension", 1, 2, 3)));
        assertEquals(components, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(components), components.getClass()));
        var observation = com.imdomestic.chorus.effect.buff.BuffObservation.capture(h.state().buffs(), "player", "empty");
        var event = event(source("test:periodic")).withObservedBuffs(Optional.of(observation));
        var restored = EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(event), EffectEvent.class);
        assertTrue(restored.observedBuffs().orElseThrow().available("empty"));
        assertFalse(restored.observedBuffs().orElseThrow().available("missing"));
        assertEquals(event, restored);
    }
}
