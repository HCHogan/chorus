package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Synthetic charge timing/damage, real core equipment/ammunition/continuations and the Explosive Head consumer. */
class WeaponChargeTest {
    static JsonObject data()throws Exception{
        var data=json("explosive_head_weapon");var weapon=data.getAsJsonArray("weapons").get(0).getAsJsonObject();
        weapon.add("charging",json("weapon_charge_test_calibration"));
        weapon.add("handling",JsonParser.parseString("{\"ready\":{\"value\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"}},\"stow\":{\"value\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"}}}"));
        for(var step:weapon.getAsJsonObject("fire").getAsJsonArray("on_fire")){
            var action=step.getAsJsonObject().getAsJsonObject("action");if(action==null||!action.get("type").getAsString().equals("chorus:capture_damage"))continue;
            var value=new JsonObject();value.addProperty("type","chorus:arithmetic");value.addProperty("op","mul");var operands=new JsonArray();operands.add(action.get("amount"));operands.add(json("weapon_charge_damage_test_calibration"));value.add("of",operands);action.add("amount",value);
        }return data;
    }
    static CompiledEffects program(JsonObject data)throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:List.of("explosive_head","explosive_head_test_calibration","explosive_payload","explosive_payload_test_calibration","weapon_stats","weapon_charge"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());return CompiledEffects.link(parts);
    }
    static class Harness extends TimedPayloadTest.Harness {
        boolean allowed=true,unknownVerify,unknownLaunch;final List<WeaponCharge.Verify> checks=new ArrayList<>();
        Harness()throws Exception{this(data());}
        Harness(JsonObject data)throws Exception{this(program(data));}
        Harness(CompiledEffects program){super(program,ExplosiveHeadTest.TAG,false);bind("observer-charge","test:charge_observer","a");}
        @Override void equip(String a,String b,String drawn){equip(new Loadout.Gear("a","test:bow",Map.of("perk",a)),new Loadout.Gear("b","test:bow",Map.of("perk",b)),drawn);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){
            if(r.command() instanceof WeaponCharge.Verify q){checks.add(q);if(unknownVerify)throw new IllegalStateException("unknown full-charge verification");return new WeaponCharge.Verified(q,allowed);}
            if(r.command() instanceof WeaponHandling.Apply q)return new WeaponHandling.Applied(q,true);
            if(r.command() instanceof WeaponReload.Verify q)return new WeaponReload.Verified(q,true);
            var result=super.world(r);if(unknownLaunch&&r.command() instanceof ProjectileFlight.Launch)throw new IllegalStateException("unknown launch after creation");return result;
        }
        WeaponCharge.Plan charge(){return state().charging().get("player");}
        WeaponCharge.Receipt input(String token,WeaponCharge.Edge edge){return input("player",token,edge);}
        WeaponCharge.Receipt input(String holder,String token,WeaponCharge.Edge edge){return input(new WeaponCharge.Request(holder,token,edge));}
        WeaponCharge.Receipt input(WeaponCharge.Request request){var before=state();var result=program.charge(before,request);session.observe(now(),result.emitted(),new WeaponCharge.Commit(before,result.state()));healthy();return (WeaponCharge.Receipt)result.result();}
        int ammo(){return state().ammunition().get("a").magazine();}
        void select(String slot){var before=state();var result=program.switchWeapon(before,new WeaponHandling.Request("player","switch-"+ ++sequence,Optional.of("test:"+slot)));session.observe(now(),result.emitted(),new WeaponHandling.Commit(before,result.state()));healthy();}
    }
    @Test void pressDoesNotPayAndPartialReleaseScalesBothDelayedDamageComponents()throws Exception{
        var h=new Harness();assertEquals(WeaponCharge.Outcome.ACCEPTED,h.input("a",WeaponCharge.Edge.PRESS).outcome());assertEquals(10,h.ammo());assertTrue(h.shots.isEmpty());
        h.until(200_000);var fired=h.input("a",WeaponCharge.Edge.RELEASE);assertEquals(WeaponCharge.Outcome.FIRED,fired.outcome());assertEquals(9,h.ammo());assertNull(h.charge());assertTrue(h.checks.isEmpty());
        h.hit(5,false);assertEquals(List.of(2.5),h.amounts);h.until(400_000);assertEquals(List.of(2.5,3.25),h.amounts);assertEquals(WeaponCharge.Outcome.NO_PRESS,h.input("a",WeaponCharge.Edge.RELEASE).outcome());assertEquals(1,h.shots.size());
    }
    @Test void minimumBoundaryAcceptsExactlyOnceAndEarlyReleaseConsumesThePressWithoutPayment()throws Exception{
        var h=new Harness();h.input("early",WeaponCharge.Edge.PRESS);h.until(99_999);assertEquals(WeaponCharge.Outcome.TOO_EARLY,h.input("early",WeaponCharge.Edge.RELEASE).outcome());assertEquals(10,h.ammo());assertNull(h.charge());
        h.until(100_000);h.input("exact",WeaponCharge.Edge.PRESS);h.until(200_000);assertEquals(WeaponCharge.Outcome.FIRED,h.input("exact",WeaponCharge.Edge.RELEASE).outcome());assertEquals(9,h.ammo());h.hit(5,false);assertEquals(1.25,h.amounts.getFirst());
    }
    @Test void fullReadinessDoesNotAutoFireAndPerfectWindowIsAnExplicitContentExpression()throws Exception{
        for(long release:new long[]{400_000,500_000,500_001}){
            var h=new Harness();h.input("held",WeaponCharge.Edge.PRESS);h.until(399_999);assertEquals(WeaponCharge.Phase.CHARGING,h.charge().phase());h.until(400_000);assertEquals(WeaponCharge.Phase.READY,h.charge().phase());assertEquals(10,h.ammo());assertTrue(h.shots.isEmpty());assertEquals(List.of("test:charge_started","test:charge_ready"),h.cues);
            h.until(release);var ready=h.charge();assertEquals((release-400_000)/1_000_000d,ready.numbers(release).get("charge_overdraw").value());h.input("held",WeaponCharge.Edge.RELEASE);h.hit(5,true);assertEquals(release<=500_000?18:15,h.amounts.getFirst(),1e-9);h.until(release+200_000);assertEquals(release<=500_000?7.8:6.5,h.amounts.getLast(),1e-9);
        }
    }
    @Test void duplicateAndMismatchedEdgesCannotRestartOrReleaseTheCurrentCharge()throws Exception{
        var h=new Harness();h.input("first",WeaponCharge.Edge.PRESS);var plan=h.charge();h.until(200_000);assertEquals(WeaponCharge.Outcome.ALREADY_HELD,h.input("second",WeaponCharge.Edge.PRESS).outcome());assertEquals(plan,h.charge());
        assertEquals(WeaponCharge.Outcome.STALE,h.input("second",WeaponCharge.Edge.RELEASE).outcome());assertEquals(WeaponCharge.Outcome.STALE,h.input("second",WeaponCharge.Edge.CANCEL).outcome());assertEquals(plan,h.charge());
        assertEquals(WeaponCharge.Outcome.CANCELLED,h.input("first",WeaponCharge.Edge.CANCEL).outcome());h.input("next",WeaponCharge.Edge.PRESS);var next=h.charge();h.session.start(h.now(),new RuleEngine.Signal(WeaponCharge.DUE,plan));assertEquals(next,h.charge());h.until(600_000);assertEquals(1,h.checks.size());
    }
    @Test void acceptedTimingSurvivesSourceChangesCheckpointAndReadyOverdraw()throws Exception{
        var h=new Harness();h.bind("fast","test:charge_fast","a");h.input("fast",WeaponCharge.Edge.PRESS);var plan=h.charge();assertEquals(200_000,plan.fullAt());assertTrue(plan.full().calculation().isPresent());
        h.session.start(h.now(),SourceChange.remove("fast"));h.until(100_000);h.restore();assertEquals(plan,h.charge());h.until(200_000);h.restore();assertEquals(WeaponCharge.Phase.READY,h.charge().phase());h.until(350_000);h.input("fast",WeaponCharge.Edge.RELEASE);h.hit(5,false);assertEquals(5,h.amounts.getFirst());
        h.until(550_000);h.input("slow",WeaponCharge.Edge.PRESS);assertEquals(950_000,h.charge().fullAt());
    }
    @Test void changingTheHeldGearCancelsButUnrelatedEquipmentPreservesTheCharge()throws Exception{
        var data=data();data.getAsJsonObject("equipment").getAsJsonArray("slots").add(JsonParser.parseString("{\"id\":\"test:armor\",\"accepts\":[\"test:armor\"]}"));data.getAsJsonObject("equipment").getAsJsonArray("items").add(JsonParser.parseString("{\"id\":\"test:armor\",\"tags\":[\"test:armor\"]}"));var h=new Harness(data);h.input("held",WeaponCharge.Edge.PRESS);var plan=h.charge();
        var before=h.state().equipment().get("player");var slots=new HashMap<>(before.slots());slots.put("test:armor",new Loadout.Gear("armor","test:armor",Map.of()));h.session.start(h.now(),new EquipmentChange("player",before,new Loadout(slots,before.drawn())).signal());assertEquals(plan,h.charge());
        h.equip("enhanced","enhanced","primary");assertNull(h.charge());h.until(400_000);assertTrue(h.checks.isEmpty()&&h.shots.isEmpty());assertEquals(10,h.ammo());
    }
    @Test void rejectedReloadPreservesChargeWhileAcceptedReloadAndSwitchCancelIt()throws Exception{
        var h=new Harness();h.input("full",WeaponCharge.Edge.PRESS);var first=h.charge();h.session.start(h.now(),new WeaponReload.Request("player","full").signal());assertEquals(first,h.charge());
        h.until(200_000);h.input("full",WeaponCharge.Edge.RELEASE);h.until(400_000);h.input("reload",WeaponCharge.Edge.PRESS);h.session.start(h.now(),new WeaponReload.Request("player","reload").signal());assertNull(h.charge());assertTrue(h.state().reloads().containsKey("player"));assertEquals(WeaponCharge.Outcome.BUSY,h.input("busy",WeaponCharge.Edge.PRESS).outcome());
        h.until(600_000);h.input("switch",WeaponCharge.Edge.PRESS);h.select("secondary");assertNull(h.charge());assertEquals(WeaponCharge.Outcome.BUSY,h.input("stow",WeaponCharge.Edge.PRESS).outcome());h.until(800_000);h.healthy();
    }
    @Test void startAndReleaseGatesAreSeparateAndRejectedReleaseRetiresTheGesture()throws Exception{
        var h=new Harness();h.bind("deny","test:charge_denied","a");assertEquals(WeaponCharge.Outcome.RESTRICTED,h.input("denied",WeaponCharge.Edge.PRESS).outcome());assertNull(h.charge());h.session.start(h.now(),SourceChange.remove("deny"));h.input("later",WeaponCharge.Edge.PRESS);h.until(200_000);h.bind("deny","test:charge_denied","a");
        var result=h.input("later",WeaponCharge.Edge.RELEASE);assertEquals(WeaponCharge.Outcome.FIRE_REJECTED,result.outcome());assertEquals(WeaponFire.Outcome.RESTRICTED,result.fire().orElseThrow().outcome());assertNull(h.charge());assertEquals(10,h.ammo());
        h.session.start(h.now(),SourceChange.remove("deny"));assertEquals(WeaponCharge.Outcome.NO_PRESS,h.input("later",WeaponCharge.Edge.RELEASE).outcome());
    }
    @Test void directFireCannotBypassChargingAndCooldownStartsOnlyAtAcceptedRelease()throws Exception{
        var h=new Harness();assertEquals(WeaponFire.Outcome.CHARGE_REQUIRED,((WeaponFire.Receipt)h.program.fire(h.state(),new WeaponFire.Request("player","bypass")).result()).outcome());h.input("shot",WeaponCharge.Edge.PRESS);h.until(200_000);h.input("shot",WeaponCharge.Edge.RELEASE);
        assertEquals(WeaponCharge.Outcome.COOLDOWN,h.input("soon",WeaponCharge.Edge.PRESS).outcome());h.until(350_000);assertEquals(WeaponCharge.Outcome.ACCEPTED,h.input("next",WeaponCharge.Edge.PRESS).outcome());
        var plain=new Harness(json("explosive_head_weapon"));assertEquals(WeaponCharge.Outcome.NOT_CONFIGURED,plain.input("plain",WeaponCharge.Edge.PRESS).outcome());plain.fire();assertEquals(9,plain.ammo());
    }
    @Test void fullBoundaryHostRejectionCancelsAndUnknownVerificationStopsWithoutReplay()throws Exception{
        var denied=new Harness();denied.allowed=false;denied.input("held",WeaponCharge.Edge.PRESS);denied.until(400_000);assertNull(denied.charge());assertEquals(10,denied.ammo());assertEquals(List.of("test:charge_started","test:charge_ended"),denied.cues);
        var unknown=new Harness();unknown.unknownVerify=true;unknown.input("held",WeaponCharge.Edge.PRESS);assertThrows(IllegalStateException.class,()->unknown.until(400_000));assertEquals(10,unknown.ammo());assertThrows(IllegalStateException.class,()->unknown.until(500_000));assertEquals(1,unknown.checks.size());
    }
    @Test void unknownLaunchAfterReleaseKeepsPaymentAndCannotReplayTheConsumedPress()throws Exception{
        var h=new Harness();h.input("shot",WeaponCharge.Edge.PRESS);h.until(200_000);h.unknownLaunch=true;assertThrows(IllegalStateException.class,()->h.input("shot",WeaponCharge.Edge.RELEASE));assertEquals(9,h.ammo());assertNull(h.charge());assertEquals(1,h.shots.size());assertThrows(IllegalStateException.class,()->h.until(400_000));assertEquals(1,h.shots.size());
    }
    @Test void independentHoldersHaveIndependentTimingAndOnlyTheMatchedHolderPays()throws Exception{
        var h=new Harness();h.entity("other",1000,20);var other=new Loadout(Map.of("test:primary",new Loadout.Gear("c","test:bow",Map.of("perk","normal"))),Optional.of("test:primary"));h.session.start(0,new EquipmentChange("other",Loadout.EMPTY,other).signal());h.input("player","one",WeaponCharge.Edge.PRESS);h.until(100_000);h.input("other","two",WeaponCharge.Edge.PRESS);
        h.until(200_000);h.input("player","one",WeaponCharge.Edge.RELEASE);assertEquals(1,h.state().charging().size());assertEquals(10,h.state().ammunition().get("c").magazine());h.until(500_000);h.input("other","two",WeaponCharge.Edge.RELEASE);assertEquals(9,h.state().ammunition().get("c").magazine());assertTrue(h.state().charging().isEmpty());
    }
    @Test void invalidTimingAndUnitsFailBeforeAcceptingAPress()throws Exception{
        for(double full:new double[]{0,-.1,.05}){var data=data();var charge=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging");charge.getAsJsonObject("full").remove("profile");charge.getAsJsonObject("full").getAsJsonObject("value").addProperty("value",full);assertThrows(IllegalArgumentException.class,()->program(data));}
        var data=data();data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging").getAsJsonObject("minimum").getAsJsonObject("value").addProperty("unit","meter");assertThrows(IllegalArgumentException.class,()->program(data));
        var dynamic=data();dynamic.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging").getAsJsonObject("full").getAsJsonObject("value").addProperty("value",.05);var h=new Harness(dynamic);var before=h.state();assertThrows(IllegalArgumentException.class,()->h.input("bad",WeaponCharge.Edge.PRESS));assertSame(before,h.state());
    }
    @Test void releaseEvaluatesItsOwnCostAndMissingAmmunitionEndsWithoutASecondAttempt()throws Exception{
        for(long elapsed:new long[]{200_000,400_000}){
            var data=data();data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").add("cost",JsonParser.parseString("{\"type\":\"chorus:round\",\"mode\":\"ceiling\",\"input\":{\"type\":\"chorus:arithmetic\",\"op\":\"mul\",\"of\":[{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"round\"},{\"type\":\"chorus:event_number\",\"name\":\"charge_fraction\",\"unit\":\"multiplier\"}]}}"));
            var h=new Harness(data);h.input("cost",WeaponCharge.Edge.PRESS);assertEquals(10,h.ammo());h.until(elapsed);h.input("cost",WeaponCharge.Edge.RELEASE);assertEquals(elapsed==200_000?9:8,h.ammo());
        }
        var h=new Harness();h.input("empty",WeaponCharge.Edge.PRESS);h.until(200_000);var before=h.state();var spent=com.imdomestic.chorus.effect.ammo.Ammunition.spend(before.ammunition().get("a"),com.imdomestic.chorus.effect.ammo.Ammunition.Pool.MAGAZINE,10);h.session.observe(h.now(),List.of(),new WeaponCharge.Commit(before,before.withAmmo(spent.after())));
        var result=h.input("empty",WeaponCharge.Edge.RELEASE);assertEquals(WeaponFire.Outcome.NO_AMMUNITION,result.fire().orElseThrow().outcome());assertNull(h.charge());assertTrue(h.shots.isEmpty());assertEquals(WeaponCharge.Outcome.NO_PRESS,h.input("empty",WeaponCharge.Edge.RELEASE).outcome());
    }
    @Test void aBoundHolderLifeSurvivesCheckpointAndCannotBeDowngradedOrReplacedByUuid()throws Exception{
        var h=new Harness();var reference=new com.imdomestic.chorus.effect.target.EntityAnchor.Reference("player","life-one","test:world");h.input(new WeaponCharge.Request("player","bound",WeaponCharge.Edge.PRESS,Optional.of(reference)));h.until(200_000);h.restore();assertEquals(Optional.of(reference),h.charge().holderReference());
        assertEquals(WeaponCharge.Outcome.STALE,h.input("bound",WeaponCharge.Edge.RELEASE).outcome());
        var replacement=new com.imdomestic.chorus.effect.target.EntityAnchor.Reference("player","life-two","test:world");assertEquals(WeaponCharge.Outcome.STALE,h.input(new WeaponCharge.Request("player","bound",WeaponCharge.Edge.RELEASE,Optional.of(replacement))).outcome());assertEquals(10,h.ammo());
        assertEquals(WeaponCharge.Outcome.FIRED,h.input(new WeaponCharge.Request("player","bound",WeaponCharge.Edge.RELEASE,Optional.of(reference))).outcome());assertEquals(9,h.ammo());
    }
}
