package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.Ammunition;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** One automatic shot per accepted press, distinct from a future continuous-trigger/recharge controller. */
class WeaponAutoChargeTest {
    static JsonObject data()throws Exception{
        var d=WeaponChargeTest.data();d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging").addProperty("trigger","full");return d;
    }
    @Test void fullBoundaryPaysAndLaunchesExactlyOnceWithoutARelease()throws Exception{
        var h=new WeaponChargeTest.Harness(data());h.input("auto",WeaponCharge.Edge.PRESS);assertEquals(WeaponCharge.Trigger.FULL,h.charge().trigger());assertEquals(10,h.ammo());
        h.until(399_999);assertTrue(h.shots.isEmpty());h.until(400_000);assertNull(h.charge());assertEquals(9,h.ammo());assertEquals(1,h.shots.size());assertEquals(400_000,h.state().shots().get("a").firedAt());
        assertEquals(List.of("test:charge_started","test:charge_ready","test:charge_ended"),h.cues);h.until(2_000_000);assertEquals(1,h.shots.size());assertEquals(WeaponCharge.Outcome.NO_PRESS,h.input("auto",WeaponCharge.Edge.RELEASE).outcome());
        h.input("next",WeaponCharge.Edge.PRESS);h.until(2_400_000);assertEquals(2,h.shots.size());assertEquals(8,h.ammo());
    }
    @Test void earlyReleaseCancelsEvenAboveMinimumAndNeverLeavesAnAutomaticTimer()throws Exception{
        for(long at:new long[]{0,50_000,100_000,200_000,399_999}){
            var h=new WeaponChargeTest.Harness(data());h.input("auto",WeaponCharge.Edge.PRESS);h.until(at);assertEquals(WeaponCharge.Outcome.CANCELLED,h.input("auto",WeaponCharge.Edge.RELEASE).outcome());assertNull(h.charge());h.until(1_000_000);assertEquals(10,h.ammo());assertTrue(h.shots.isEmpty());
        }
    }
    @Test void staleReleaseCannotCancelAnAutomaticGestureButMatchingCancelCan()throws Exception{
        var h=new WeaponChargeTest.Harness(data());h.input("one",WeaponCharge.Edge.PRESS);var plan=h.charge();assertEquals(WeaponCharge.Outcome.STALE,h.input("old",WeaponCharge.Edge.RELEASE).outcome());assertEquals(plan,h.charge());h.until(100_000);h.input("one",WeaponCharge.Edge.CANCEL);h.until(500_000);assertTrue(h.shots.isEmpty());
    }
    @Test void fullFireSamplesCurrentCostWithFullAndHeldMeasurementsAndTriggerReference()throws Exception{
        var d=data();var fire=d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire");
        fire.add("if",JsonParser.parseString("{\"type\":\"chorus:all\",\"of\":[{\"type\":\"chorus:event_flag\",\"name\":\"fully_charged\"},{\"type\":\"chorus:event_flag\",\"name\":\"charge_held\"},{\"type\":\"chorus:event_reference\",\"name\":\"charge_trigger\",\"is\":\"full\"}]}"));
        fire.getAsJsonObject("cost").addProperty("value",2);var h=new WeaponChargeTest.Harness(d);h.input("auto",WeaponCharge.Edge.PRESS);assertEquals(10,h.ammo());h.until(400_000);assertEquals(8,h.ammo());assertEquals(1,h.shots.size());
        h.hit(5,true);assertEquals(982,h.health.get("target"),1e-9); // Synthetic perfect-draw expression receives full fraction and zero overdraw.
    }
    @Test void ammunitionSpentDuringChargeRejectsTheShotAndEndsTheIntent()throws Exception{
        var h=new WeaponChargeTest.Harness(data());h.input("auto",WeaponCharge.Edge.PRESS);h.until(200_000);var before=h.state();var spent=Ammunition.spend(before.ammunition().get("a"),Ammunition.Pool.MAGAZINE,10);
        h.session.observe(h.now(),List.of(),new WeaponCharge.Commit(before,before.withAmmo(spent.after())));h.until(400_000);assertNull(h.charge());assertTrue(h.shots.isEmpty());assertEquals(0,h.ammo());h.until(2_000_000);assertTrue(h.shots.isEmpty());
    }
    @Test void completeGateAndCurrentFireConditionCanRejectAfterAnAllowedStartAndContinue()throws Exception{
        for(boolean gate:List.of(false,true)){
            var d=data();if(gate)d.getAsJsonArray("bundles").add(JsonParser.parseString("{\"id\":\"test:auto_complete_denied\",\"action_gates\":[{\"id\":\"complete\",\"action\":\"weapon_fire\",\"if\":{\"type\":\"chorus:event_reference\",\"name\":\"action_phase\",\"is\":\"complete\"}}]}"));
            else d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").add("if",JsonParser.parseString("{\"type\":\"chorus:not\",\"of\":{\"type\":\"chorus:event_flag\",\"name\":\"fully_charged\"}}"));
            var h=new WeaponChargeTest.Harness(d);if(gate)h.bind("deny","test:auto_complete_denied","a");assertEquals(WeaponCharge.Outcome.ACCEPTED,h.input("auto",WeaponCharge.Edge.PRESS).outcome());h.until(400_000);assertNull(h.charge());assertTrue(h.shots.isEmpty());assertEquals(10,h.ammo());
        }
    }
    @Test void deniedOrUnknownHostVerificationCannotPayOrLaunch()throws Exception{
        var denied=new WeaponChargeTest.Harness(data());denied.allowed=false;denied.input("auto",WeaponCharge.Edge.PRESS);denied.until(400_000);assertNull(denied.charge());assertTrue(denied.shots.isEmpty());assertEquals(10,denied.ammo());
        var unknown=new WeaponChargeTest.Harness(data());unknown.unknownVerify=true;unknown.input("auto",WeaponCharge.Edge.PRESS);assertThrows(IllegalStateException.class,()->unknown.until(400_000));assertEquals(10,unknown.ammo());assertTrue(unknown.shots.isEmpty());assertThrows(IllegalStateException.class,()->unknown.until(500_000));assertEquals(1,unknown.checks.size());
    }
    @Test void unknownLaunchRetainsCommittedPaymentAndCannotRetryAutomaticFire()throws Exception{
        var h=new WeaponChargeTest.Harness(data());h.unknownLaunch=true;h.input("auto",WeaponCharge.Edge.PRESS);assertThrows(IllegalStateException.class,()->h.until(400_000));assertNull(h.charge());assertEquals(9,h.ammo());assertEquals(1,h.shots.size());assertThrows(IllegalStateException.class,()->h.until(800_000));assertEquals(1,h.shots.size());
    }
    @Test void acceptedModeAndAcceleratedTimingSurviveDomainRestoreAndBuffRemoval()throws Exception{
        var h=new WeaponChargeTest.Harness(data());h.bind("fast","test:charge_fast","a");h.input("auto",WeaponCharge.Edge.PRESS);h.until(100_000);h.session.start(h.now(),SourceChange.remove("fast"));h.restore();assertEquals(WeaponCharge.Trigger.FULL,h.charge().trigger());assertEquals(200_000,h.charge().fullAt());h.until(200_000);assertEquals(1,h.shots.size());assertEquals(200_000,h.state().shots().get("a").firedAt());
    }
    @Test void independentHoldersReachTheirOwnAcceptedDeadlines()throws Exception{
        var h=new WeaponChargeTest.Harness(data());h.entity("other",1000,20);var other=new Loadout(Map.of("test:primary",new Loadout.Gear("c","test:bow",Map.of("perk","normal"))),Optional.of("test:primary"));h.session.start(0,new EquipmentChange("other",Loadout.EMPTY,other).signal());
        h.input("player","one",WeaponCharge.Edge.PRESS);h.until(100_000);h.input("other","two",WeaponCharge.Edge.PRESS);h.until(400_000);assertEquals(9,h.ammo());assertEquals(10,h.state().ammunition().get("c").magazine());h.until(500_000);assertEquals(9,h.state().ammunition().get("c").magazine());assertEquals(2,h.shots.size());
    }
    @Test void omittedTriggerStaysReleaseOnlyAndUnknownModesFailLinking()throws Exception{
        var h=new WeaponChargeTest.Harness();h.input("bow",WeaponCharge.Edge.PRESS);h.until(400_000);assertEquals(WeaponCharge.Trigger.RELEASE,h.charge().trigger());assertTrue(h.shots.isEmpty());
        var d=data();d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging").addProperty("trigger","unknown");assertThrows(IllegalStateException.class,()->WeaponChargeTest.program(d));
    }
}
