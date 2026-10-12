package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Repeated cycles are domain timers, authorized by a still-live host gesture at every phase boundary. */
class WeaponRepeatChargeTest {
    static JsonObject data() throws Exception {
        var data = WeaponAutoChargeTest.data();
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging").addProperty("repeat", true);
        return data;
    }
    @Test void heldGestureAlternatesChargeAndRecoveryWithIndependentShotIdentities() throws Exception {
        var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS);
        h.until(400_000); var first = h.charge();
        assertEquals(WeaponCharge.Phase.RECOVERING, first.phase()); assertEquals(550_000, first.dueAt()); assertEquals(9, h.ammo());
        h.until(549_999); assertEquals(first, h.charge()); h.until(550_000);
        assertEquals(WeaponCharge.Phase.CHARGING, h.charge().phase()); assertEquals(1, h.charge().cycle()); assertEquals(950_000, h.charge().fullAt());
        assertEquals(WeaponCharge.Outcome.ALREADY_HELD, h.input("duplicate", WeaponCharge.Edge.PRESS).outcome());
        h.until(1_500_000); assertEquals(3, h.shots.size()); assertEquals(7, h.ammo()); assertEquals(2, h.charge().cycle());
        assertEquals(3, h.shots.stream().map(s -> s.origin().source()).distinct().count()); assertEquals(5, h.checks.size());
        assertEquals("held", h.charge().token());
    }
    @Test void releaseDuringRecoveryStopsFutureCyclesWithoutReversingPayment() throws Exception {
        var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS); h.until(450_000); var old = h.charge();
        assertEquals(WeaponCharge.Outcome.STALE, h.input("old", WeaponCharge.Edge.RELEASE).outcome()); assertEquals(old, h.charge());
        h.input("held", WeaponCharge.Edge.RELEASE); h.until(800_000); assertNull(h.charge()); assertEquals(1, h.shots.size()); assertEquals(9, h.ammo());
        h.input("fresh", WeaponCharge.Edge.PRESS); var fresh = h.charge(); h.session.start(h.now(), new RuleEngine.Signal(WeaponCharge.DUE, old)); assertEquals(fresh, h.charge());
        h.until(1_200_000); assertEquals(2, h.shots.size()); assertEquals(0, h.charge().cycle());
    }
    @Test void everyRecoveryAndFullBoundaryRequiresCurrentHostAuthority() throws Exception {
        for (long revoke : new long[]{500_000, 600_000}) {
            var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS); h.until(revoke); h.allowed = false;
            h.until(1_500_000); assertNull(h.charge()); assertEquals(1, h.shots.size()); assertEquals(9, h.ammo());
        }
    }
    @Test void eachCycleSamplesNewDurationButDoesNotRewriteItsAcceptedDeadline() throws Exception {
        var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS); h.until(500_000); h.bind("fast", "test:charge_fast", "a");
        h.until(550_000); assertEquals(750_000, h.charge().fullAt()); h.until(600_000); h.session.start(h.now(), SourceChange.remove("fast"));
        assertEquals(750_000, h.charge().fullAt()); h.until(750_000); assertEquals(2, h.shots.size()); h.until(900_000); assertEquals(1_300_000, h.charge().fullAt());
    }
    @Test void startAndContinueRestrictionsStopRearmingInsteadOfRetryingOnTheirOwn() throws Exception {
        for (String phase : List.of("start", "continue")) {
            var data = data(); data.getAsJsonArray("bundles").add(JsonParser.parseString("""
                {"id":"test:repeat_denied","action_gates":[{"id":"stop","action":"weapon_fire",
                 "if":{"type":"chorus:event_reference","name":"action_phase","is":"%s"}}]}
                """.formatted(phase)));
            var h = new WeaponChargeTest.Harness(data); h.input("held", WeaponCharge.Edge.PRESS); h.until(450_000); h.bind("deny", "test:repeat_denied", "a");
            h.until(550_000); assertNull(h.charge()); h.session.start(h.now(), SourceChange.remove("deny")); h.until(2_000_000); assertEquals(1, h.shots.size());
        }
    }
    @Test void exhaustedAmmunitionEndsTheLoopAtTheRejectedShotWithoutRefundOrRetries() throws Exception {
        var data = data(); data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine", 1);
        var h = new WeaponChargeTest.Harness(data); h.input("held", WeaponCharge.Edge.PRESS); h.until(950_000);
        assertNull(h.charge()); assertEquals(0, h.ammo()); assertEquals(1, h.shots.size()); h.until(4_000_000); assertEquals(1, h.shots.size());
    }
    @Test void recoveryAndSubsequentChargeRoundTripWithoutReplayingThePreviousShot() throws Exception {
        var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS); h.until(450_000); var recovery = h.charge(); h.restore(); assertEquals(recovery, h.charge());
        h.until(600_000); var next = h.charge(); h.restore(); assertEquals(next, h.charge()); h.until(950_000); assertEquals(2, h.shots.size()); assertEquals(8, h.ammo());
    }
    @Test void equipmentOrAcceptedReloadCancelsTheEntireGestureDuringRecovery() throws Exception {
        for (boolean reload : List.of(false, true)) {
            var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS); h.until(450_000);
            if (reload) h.session.start(h.now(), new WeaponReload.Request("player", "reload").signal()); else h.equip("normal", "enhanced", "secondary");
            assertNull(h.charge()); h.until(2_000_000); assertEquals(1, h.shots.size());
        }
    }
    @Test void unknownSecondLaunchPreservesItsPaymentAndPendingStateWithoutAnyReplay() throws Exception {
        var h = new WeaponChargeTest.Harness(data()); h.input("held", WeaponCharge.Edge.PRESS); h.until(600_000); h.unknownLaunch = true;
        assertThrows(IllegalStateException.class, () -> h.until(950_000)); assertEquals(8, h.ammo()); assertEquals(2, h.shots.size());
        assertEquals(WeaponCharge.Phase.RECOVERING, h.charge().phase()); assertThrows(IllegalStateException.class, () -> h.until(2_000_000)); assertEquals(2, h.shots.size());
    }
    @Test void continuedValidCyclesHaveNoRootOrGenerationCutoff() throws Exception {
        var data = data(); var weapon = data.getAsJsonArray("weapons").get(0).getAsJsonObject();
        weapon.getAsJsonObject("charging").getAsJsonObject("minimum").getAsJsonObject("value").addProperty("value", 0);
        weapon.getAsJsonObject("charging").getAsJsonObject("full").getAsJsonObject("value").addProperty("value", .000001);
        weapon.getAsJsonObject("fire").getAsJsonObject("interval").addProperty("value", .000001);
        weapon.getAsJsonObject("fire").getAsJsonObject("cost").addProperty("value", 0);
        var h = new WeaponChargeTest.Harness(data); h.input("held", WeaponCharge.Edge.PRESS); h.until(255);
        assertEquals(128, h.shots.size()); assertEquals(127, h.charge().cycle()); assertEquals(10, h.ammo());
        h.input("held", WeaponCharge.Edge.CANCEL); h.until(500); assertEquals(128, h.shots.size());
    }
    @Test void repeatRequiresFullAndOldDefinitionsKeepSingleCycleSemantics() throws Exception {
        var invalid = WeaponChargeTest.data(); invalid.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging").addProperty("repeat", true);
        assertThrows(RuntimeException.class, () -> WeaponChargeTest.program(invalid));
        var h = new WeaponChargeTest.Harness(WeaponAutoChargeTest.data()); h.input("old", WeaponCharge.Edge.PRESS); h.until(2_000_000); assertEquals(1, h.shots.size()); assertNull(h.charge());
    }
}
