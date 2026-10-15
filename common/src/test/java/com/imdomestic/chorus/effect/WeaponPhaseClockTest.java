package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.ammo.Ammunition;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Storage identity is historical; local phase coordinates preserve elapsed and remaining time. */
class WeaponPhaseClockTest {
    private static final long START = 10_000_000;
    private static final Loadout.Gear GEAR = new Loadout.Gear("weapon", "test:gun", Map.of());
    private static final Measure DURATION = new Measure(.2, Unit.SECOND);
    private static BuffInstance.Origin origin(String source) {
        return new BuffInstance.Origin("player", source, GEAR.instance(), "", Set.of("test:accepted"));
    }
    private static WeaponReload.Plan reload() {
        return new WeaponReload.Plan("player", "reload", GEAR, origin("reload/reload"), START,
                START + 200_000, DURATION, DURATION, Optional.empty());
    }
    private static WeaponHandling.Plan handling() {
        return new WeaponHandling.Plan("player", "switch", Optional.empty(), Optional.empty(), "test:primary",
                GEAR, origin("handling/switch"), WeaponHandling.Phase.STOW, START, START + 200_000,
                DURATION, DURATION, Optional.empty(), false);
    }
    private static WeaponAim.Plan aim() {
        return new WeaponAim.Plan("player", "aim", "test:primary", GEAR, origin("aim/aim"),
                WeaponAim.Phase.ENTERING, START, START + 200_000, DURATION, DURATION, Optional.empty());
    }
    private static WeaponFire.Shot shot(long at, String token) {
        var interval = new Measure(.15, Unit.SECOND);
        var cost = Ammunition.spend(new AmmoState(GEAR.instance(), 3, 5, Optional.empty()), Ammunition.Pool.MAGAZINE, 1);
        return new WeaponFire.Shot("player", token, GEAR, origin("shot/" + token), at, at + 150_000,
                interval, interval, Optional.empty(), cost);
    }
    private static WeaponCharge.Plan charge(boolean repeat) {
        var minimum = new Measure(.1, Unit.SECOND);
        var full = new Measure(.4, Unit.SECOND);
        return new WeaponCharge.Plan("player", "held", "test:primary", GEAR, origin("charge/held"),
                WeaponCharge.Phase.CHARGING, START, START + 400_000,
                new WeaponCharge.Timing(minimum, minimum, Optional.empty()), new WeaponCharge.Timing(full, full, Optional.empty()),
                Optional.empty(), repeat ? WeaponCharge.Trigger.FULL : WeaponCharge.Trigger.RELEASE, repeat, 0, Optional.empty());
    }
    private record Sample(Object value, String oldId, String currentId, Set<String> oldFields) {
        Sample(Object value, String name, String oldVersion, String version, String fields) {
            this(value, "chorus:effect/weapon/weapon_" + name + oldVersion,
                    "chorus:effect/weapon/weapon_" + name + version, Set.of(fields.split(" ")));
        }
        Object decode(JsonElement wire) { return EffectCheckpointCodec.DATA.decode(wire, value.getClass()); }
    }
    private static List<Sample> samples() {
        return List.of(
                new Sample(reload(), "reload/plan", "", "_v2", "holder token gear origin startedAt dueAt input duration calculation portion step phase"),
                new Sample(handling(), "handling/plan", "", "_v2", "holder token targetSlot target phaseSlot gear origin phase startedAt dueAt input duration calculation completed"),
                new Sample(aim(), "aim/plan", "", "_v2", "holder token slot gear origin phase startedAt dueAt input duration calculation"),
                new Sample(shot(START, "shot"), "fire/shot", "", "_v2", "holder token gear origin firedAt readyAt input interval calculation cost"),
                new Sample(charge(false), "charge/plan", "_v3", "_v4", "holder token slot gear origin phase startedAt fullAt minimum full holderReference trigger repeat cycle recovery"));
    }
    private static Object move(Object value, long from, long to) {
        return switch (value) {
            case WeaponReload.Plan p -> p.relocated(from, to);
            case WeaponHandling.Plan p -> p.relocated(from, to);
            case WeaponAim.Plan p -> p.relocated(from, to);
            case WeaponFire.Shot p -> p.relocated(from, to);
            case WeaponCharge.Plan p -> p.relocated(from, to);
            default -> throw new AssertionError(value);
        };
    }
    private static long offset(Object value) {
        return switch (value) {
            case WeaponReload.Plan p -> p.clockOffset();
            case WeaponHandling.Plan p -> p.clockOffset();
            case WeaponAim.Plan p -> p.clockOffset();
            case WeaponFire.Shot p -> p.clockOffset();
            case WeaponCharge.Plan p -> p.clockOffset();
            default -> throw new AssertionError(value);
        };
    }
    private static long start(Object value) {
        return switch (value) {
            case WeaponReload.Plan p -> p.startTime();
            case WeaponHandling.Plan p -> p.startTime();
            case WeaponAim.Plan p -> p.startTime();
            case WeaponFire.Shot p -> p.firedTime();
            case WeaponCharge.Plan p -> p.startTime();
            default -> throw new AssertionError(value);
        };
    }
    private static JsonObject fields(Object value) {
        return EffectCheckpointCodec.DATA.encode(value).getAsJsonObject().getAsJsonObject("v");
    }

    @Test void allFiveExactLegacyShapesDecodeWithZeroOffsetAndReencodeUnderNewIds() {
        for (var sample : samples()) {
            var wire = EffectCheckpointCodec.DATA.encode(sample.value()).getAsJsonObject();
            assertEquals(sample.currentId(), wire.get("$").getAsString());
            wire.addProperty("$", sample.oldId());
            wire.getAsJsonObject("v").remove("clockOffset");
            assertEquals(sample.oldFields(), wire.getAsJsonObject("v").keySet());
            var migrated = sample.decode(wire);
            assertEquals(sample.value(), migrated);
            assertEquals(0, offset(migrated));
            var encoded = EffectCheckpointCodec.DATA.encode(migrated).getAsJsonObject();
            assertEquals(sample.currentId(), encoded.get("$").getAsString());
            assertEquals(migrated, sample.decode(encoded));
        }
    }

    @Test void oldShapesCannotSilentlyConsumeNewOffsetsAndNewShapesRequireTheirOffset() {
        for (var sample : samples()) {
            var legacy = EffectCheckpointCodec.DATA.encode(sample.value()).getAsJsonObject();
            legacy.addProperty("$", sample.oldId());
            assertThrows(IllegalArgumentException.class, () -> sample.decode(legacy));
            var current = EffectCheckpointCodec.DATA.encode(sample.value()).getAsJsonObject();
            current.getAsJsonObject("v").remove("clockOffset");
            assertThrows(IllegalArgumentException.class, () -> sample.decode(current));
        }
    }

    @Test void shiftedRecordsKeepEveryHistoricalFieldAndSurviveRepeatedStorageRoundTrips() {
        for (var sample : samples()) {
            Object shifted = move(sample.value(), START + 50_000, 0);
            assertEquals(-START - 50_000, offset(shifted));
            assertEquals(-50_000, start(shifted));
            var accepted = fields(sample.value()); accepted.remove("clockOffset");
            var relocated = fields(shifted); relocated.remove("clockOffset");
            assertEquals(accepted, relocated);
            for (int i = 0; i < 3; i++) {
                var encoded = EffectCheckpointCodec.DATA.encode(shifted).getAsJsonObject();
                assertEquals(sample.currentId(), encoded.get("$").getAsString());
                var restored = sample.decode(encoded);
                assertEquals(shifted, restored);
                shifted = restored;
            }
        }
    }

    @Test void movingThroughDifferentEpochsAndBackRetainsAcceptanceAndAccumulatedElapsedTime() {
        for (var sample : samples()) {
            var first = move(sample.value(), START + 50_000, 0);
            var second = sample.decode(EffectCheckpointCodec.DATA.encode(move(first, 20_000, 5_000_000)));
            assertEquals(4_930_000, start(second));
            assertEquals(70_000, WeaponPhaseTime.elapsed(5_000_000, start(second)));
            var returned = move(second, 5_000_000, START + 70_000);
            assertEquals(sample.value(), returned);
        }
    }

    @Test void movedTimersUseLocalDeadlinesWhileCompletionIdentityAndReadyObservationsStayHistorical() {
        var reload = reload().relocated(START + 50_000, 0);
        var handling = handling().relocated(START + 50_000, 0);
        var aim = aim().relocated(START + 50_000, 0);
        var charge = charge(false).relocated(START + 50_000, 0);
        assertEquals(150_000, reload.timer().dueAt());
        assertEquals(reload().completionId(), reload.completionId());
        assertEquals(150_000, handling.timer().dueAt());
        assertEquals(150_000, aim.timer().dueAt());
        assertEquals(350_000, charge.timer().dueAt());
        assertEquals(.125, charge.numbers(0).get("charge_fraction").value(), 1e-12);
        var ready = charge(false).ready().relocated(START + 500_000, 0);
        assertEquals(-100_000, ready.fullTime());
        assertEquals(1, ready.numbers(0).get("charge_fraction").value());
        assertEquals(.1, ready.numbers(0).get("charge_overdraw").value(), 1e-12);
        assertThrows(IllegalStateException.class, ready::timer);
        var aimed = aim().aimed().relocated(START + 300_000, 0);
        assertEquals(-300_000, aimed.startTime());
        assertEquals(-100_000, aimed.dueTime());
        assertThrows(IllegalStateException.class, aimed::timer);
    }

    private static void legacy(JsonElement value) {
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(WeaponPhaseClockTest::legacy); return; }
        if (!value.isJsonObject()) return;
        var object = value.getAsJsonObject();
        if (object.has("$") && object.get("$").isJsonPrimitive()) {
            for (var sample : samples()) if (sample.currentId().equals(object.get("$").getAsString())) {
                object.addProperty("$", sample.oldId()); object.getAsJsonObject("v").remove("clockOffset"); break;
            }
        }
        object.entrySet().forEach(e -> legacy(e.getValue()));
    }

    @Test void recoveryMigratesBothNestedShotAndChargeAndPreservesThePaidReceiptAcrossMoves() {
        var charging = charge(true);
        var paid = shot(charging.fullAt(), charging.fireToken());
        var original = charging.recovering(paid);
        var oldWire = EffectCheckpointCodec.DATA.encode(original); legacy(oldWire);
        assertEquals(original, EffectCheckpointCodec.DATA.decode(oldWire, WeaponCharge.Plan.class));
        var shifted = original.relocated(START + 450_000, 0);
        assertEquals(-50_000, shifted.fullTime());
        assertEquals(100_000, shifted.dueAt());
        assertEquals(shifted.clockOffset(), shifted.recovery().orElseThrow().clockOffset());
        assertEquals(paid.cost(), shifted.recovery().orElseThrow().cost());
        assertEquals(paid.firedAt(), shifted.recovery().orElseThrow().firedAt());
        for (int i = 0; i < 3; i++) {
            var restored = EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(shifted), WeaponCharge.Plan.class);
            assertEquals(shifted, restored); shifted = restored;
        }
        assertEquals(original, shifted.relocated(0, START + 450_000));
    }

    @Test void relocationRejectsPreAcceptanceClocksForeverSentinelsAndOverflowWithoutChangingTheRecord() {
        for (var sample : samples()) {
            var before = EffectCheckpointCodec.DATA.encode(sample.value());
            assertThrows(IllegalArgumentException.class, () -> move(sample.value(), START - 1, 0));
            assertThrows(IllegalArgumentException.class, () -> move(sample.value(), START, -1));
            assertThrows(IllegalArgumentException.class, () -> move(sample.value(), START, Long.MAX_VALUE));
            assertThrows(ArithmeticException.class, () -> move(sample.value(), START, Long.MAX_VALUE - 1));
            var bad = before.deepCopy().getAsJsonObject();
            bad.getAsJsonObject("v").add("clockOffset", EffectCheckpointCodec.DATA.encode(Long.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> sample.decode(bad));
            assertEquals(before, EffectCheckpointCodec.DATA.encode(sample.value()));
        }
        assertThrows(IllegalArgumentException.class, () -> WeaponPhaseTime.local(Long.MAX_VALUE - 1, 1));
        assertThrows(ArithmeticException.class, () -> WeaponPhaseTime.local(Long.MAX_VALUE - 1, 2));
        assertThrows(ArithmeticException.class, () -> WeaponPhaseTime.relocate(Long.MIN_VALUE, 1, 0));
        assertThrows(ArithmeticException.class, () -> WeaponPhaseTime.elapsed(0, Long.MIN_VALUE));
    }
}
