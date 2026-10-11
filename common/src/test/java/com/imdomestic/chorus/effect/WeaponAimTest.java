package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponAimTest {
    static JsonObject data()throws Exception {
        var data=json("weapon_handling");var aiming=json("weapon_aim");
        for(String field:List.of("profiles","bundles"))aiming.getAsJsonArray(field).forEach(v->data.getAsJsonArray(field).add(v));
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().add("aiming",json("weapon_aim_test_calibration"));return data;
    }
    static class Harness extends WeaponHandlingTest.Harness {
        boolean allowAim=true,unknownAim;final List<WeaponAim.Verify> checks=new ArrayList<>();
        Harness()throws Exception{this(compile(data()));}
        Harness(CompiledEffects program){super(program);equip(WeaponHandlingTest.loadout("primary"));bind("test:aim_observer",true);cues.clear();}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request){
            if(request.command() instanceof WeaponAim.Verify verify){checks.add(verify);if(unknownAim)throw new IllegalStateException("unknown aim verification");return new WeaponAim.Verified(verify,allowAim);}
            if(request.command() instanceof HealingCommand heal)return new HealingReceipt("heal",heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);
            return super.world(request);
        }
        WeaponAim.Plan aim(){return state().aiming().get("player");}
        WeaponAim.Receipt aim(boolean pressed){return aim("player",pressed);}
        WeaponAim.Receipt aim(String holder,boolean pressed){var before=state();var result=program.aim(before,new WeaponAim.Request(holder,"aim-"+ ++sequence,pressed));session.observe(now(),result.emitted(),new WeaponAim.Commit(before,result.state()));return (WeaponAim.Receipt)result.result();}
        boolean held(){return aim()!=null&&aim().held();}
        void healthy(){assertTrue(session.state().idle()&&session.state().engine().failure().isEmpty(),()->session.state().engine().failure().toString());}
        DamageCommand attack(){return new DamageCommand("unknown",new BuffInstance.Origin("player","shot","a",""),10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:weapon_damage"));}
        void other(){var gear=new Loadout.Gear("other-gun","test:rifle",Map.of("perk","kill_clip"));session.start(now(),new EquipmentChange("other",Loadout.EMPTY,new Loadout(Map.of("test:primary",gear),Optional.of("test:primary"))).signal());}
    }
    @Test void inputReadyAndExitAreSeparateFactsAtExactDeadlines()throws Exception {
        var h=new Harness();assertEquals(WeaponAim.Outcome.ACCEPTED,h.aim(true).outcome());var entering=h.aim();assertTrue(h.held());assertEquals(WeaponAim.Phase.ENTERING,entering.phase());assertEquals(200_000,entering.dueAt());
        assertEquals(List.of("test:aim_started"),h.cues);h.until(199_999);assertTrue(h.checks.isEmpty());h.until(200_000);assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());assertTrue(h.state().timers().isEmpty());
        assertEquals(List.of("test:aim_started","test:aim_ready"),h.cues);h.until(400_000);assertEquals(WeaponAim.Outcome.UNCHANGED,h.aim(true).outcome());assertEquals(2,h.cues.size());
        h.aim(false);assertFalse(h.held());assertEquals(WeaponAim.Phase.EXITING,h.aim().phase());h.until(499_999);assertNotNull(h.aim());h.until(500_000);assertNull(h.aim());
        assertEquals(List.of("test:aim_started","test:aim_ready","test:aim_released","test:aim_ended"),h.cues);assertEquals(2,h.checks.size());assertEquals(Optional.of("test:primary"),h.physical.drawn());h.healthy();
    }
    @Test void bothEdgesFreezeTheirOwnTimingAndReversalRestartsOnlyTheNewPhase()throws Exception {
        var h=new Harness();h.bind("test:aim_fast",true);h.aim(true);var first=h.aim();assertEquals(100_000,first.dueAt());h.bind("test:aim_fast",false);h.until(40_000);h.aim(false);var exiting=h.aim();assertEquals(140_000,exiting.dueAt());
        assertEquals(WeaponAim.Outcome.UNCHANGED,h.aim(false).outcome());h.until(70_000);h.aim(true);var current=h.aim();assertEquals(270_000,current.dueAt());
        h.session.start(h.now(),new RuleEngine.Signal(WeaponAim.DUE,first));h.session.start(h.now(),new RuleEngine.Signal(WeaponAim.DUE,exiting));assertEquals(current,h.aim());h.until(269_999);assertTrue(h.checks.isEmpty());h.until(270_000);assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());assertEquals(1,h.checks.size());h.healthy();
    }
    @Test void rejectedPressAndRejectedReloadPreserveAimWhileAcceptedReloadAndSwitchCancelIt()throws Exception {
        var h=new Harness();h.bind("test:aim_denied",true);var before=h.state();var denied=h.aim(true);assertEquals(WeaponAim.Outcome.RESTRICTED,denied.outcome());assertEquals(ActionGate.Kind.WEAPON_AIM,denied.restriction().orElseThrow().action());assertSame(before,h.state());
        h.bind("test:aim_denied",false);h.aim(true);var pending=h.aim();h.bind("test:aim_denied",true);h.aim(false);assertEquals(WeaponAim.Phase.EXITING,h.aim().phase(),"release cannot be trapped by an aim restriction");h.bind("test:aim_denied",false);h.aim(true);
        h.session.start(h.now(),new WeaponReload.Request("player","reload").signal());assertNull(h.aim());assertTrue(h.state().reloads().containsKey("player"));assertEquals(WeaponAim.Outcome.BUSY,h.aim(true).outcome());
        h.select("secondary");assertEquals(WeaponAim.Outcome.BUSY,h.aim(true).outcome());h.until(h.plan().dueAt());h.until(h.plan().dueAt());h.aim(true);assertNotNull(h.aim());h.select("primary");assertNull(h.aim());
        h.session.start(h.now(),new RuleEngine.Signal(WeaponAim.DUE,pending));assertNull(h.aim());h.healthy();
    }
    @Test void fullMagazineReloadRejectionDoesNotCancelHeldAim()throws Exception {
        var h=new Harness();h.aim(true);
        // Fill through the existing accepted reload, then establish a fresh aim.
        h.session.start(h.now(),new WeaponReload.Request("player","fill").signal());h.until(h.state().reloads().get("player").dueAt());h.aim(true);var accepted=h.aim();
        var result=h.program.reload(h.state(),new WeaponReload.Request("player","full"));assertEquals(WeaponReload.Outcome.FULL,((WeaponReload.Receipt)result.result()).outcome());assertEquals(accepted,result.state().aiming().get("player"));h.healthy();
    }
    @Test void unrelatedArmorPreservesAimButWeaponChangesAndOldDueCannotRestoreIt()throws Exception {
        var data=data();data.getAsJsonObject("equipment").getAsJsonArray("slots").add(JsonParser.parseString("{\"id\":\"test:armor\",\"accepts\":[\"test:armor\"]}"));data.getAsJsonObject("equipment").getAsJsonArray("items").add(JsonParser.parseString("{\"id\":\"test:armor\",\"tags\":[\"test:armor\"]}"));
        var h=new Harness(compile(data));h.aim(true);var accepted=h.aim();var slots=new TreeMap<>(h.physical.slots());slots.put("test:armor",new Loadout.Gear("armor","test:armor",Map.of()));h.equip(new Loadout(slots,h.physical.drawn()));assertEquals(accepted,h.aim());
        slots.remove("test:primary");h.equip(new Loadout(slots,Optional.empty()));assertNull(h.aim());h.equip(WeaponHandlingTest.loadout("primary"));h.until(accepted.dueAt());h.session.start(h.now(),new RuleEngine.Signal(WeaponAim.DUE,accepted));assertNull(h.aim());assertTrue(h.checks.isEmpty());h.healthy();
    }
    @Test void noDrawnWeaponAndUnconfiguredPrototypeDoNotInventAim()throws Exception {
        var h=new Harness();h.equip(new Loadout(h.physical.slots(),Optional.empty()));assertEquals(WeaponAim.Outcome.EMPTY_HANDS,h.aim(true).outcome());assertEquals(WeaponAim.Outcome.UNCHANGED,h.aim(false).outcome());
        h.equip(new Loadout(Map.of("test:primary",new Loadout.Gear("plain","test:primary_ammo",Map.of())),Optional.of("test:primary")));assertEquals(WeaponAim.Outcome.NOT_CONFIGURED,h.aim(true).outcome());assertNull(h.aim());h.healthy();
    }
    @Test void hostRejectionCompletionRestrictionAndUnknownVerificationNeverClaimReady()throws Exception {
        var denied=new Harness();denied.aim(true);denied.allowAim=false;denied.until(200_000);assertNull(denied.aim());assertFalse(denied.cues.contains("test:aim_ready"));denied.healthy();
        var restricted=new Harness();restricted.aim(true);restricted.bind("test:aim_denied",true);restricted.until(200_000);assertNull(restricted.aim());assertFalse(restricted.cues.contains("test:aim_ready"));restricted.healthy();
        var unknown=new Harness();unknown.aim(true);unknown.unknownAim=true;assertThrows(IllegalStateException.class,()->unknown.until(200_000));assertEquals(WeaponAim.Phase.ENTERING,unknown.aim().phase());assertFalse(unknown.cues.contains("test:aim_ready"));
        int n=unknown.checks.size();assertThrows(IllegalStateException.class,()->unknown.until(200_000));assertEquals(n,unknown.checks.size());assertThrows(IllegalStateException.class,unknown.session::checkpoint);
    }
    @Test void allThreePhasesRestoreWithoutReplayAndKeepTheirOriginalDeadline()throws Exception {
        var h=new Harness();h.bind("test:aim_fast",true);h.aim(true);h.until(50_000);var entry=h.aim();h.restore();assertEquals(entry,h.aim());assertTrue(h.checks.isEmpty());
        h.until(entry.dueAt());var aimed=h.aim();h.restore();assertEquals(aimed,h.aim());h.until(300_000);assertEquals(1,h.checks.size());h.aim(false);var exit=h.aim();h.restore();h.until(exit.dueAt());assertNull(h.aim());assertEquals(2,h.checks.size());h.healthy();
    }
    @Test void sourceAimIsCapturedAtFireWhileTargetAimRemainsLiveAtImpact()throws Exception {
        var h=new Harness();h.bind("test:aim_bonus",true);h.bind("test:aim_target_bonus",true);h.other();h.aim(true);
        var hip=h.program.captureDamage(h.state(),h.attack());h.until(200_000);var aimed=h.program.captureDamage(h.state(),h.attack());h.aim(false);
        assertEquals(10,h.program.outgoing(h.state(),hip.command("other"),10).orElseThrow().output().value());assertEquals(20,h.program.outgoing(h.state(),aimed.command("other"),10).orElseThrow().output().value());
        h.aim("other",true);h.until(400_000);assertEquals(30,h.program.outgoing(h.state(),aimed.command("other"),10).orElseThrow().output().value());
        h.aim("other",false);assertEquals(20,h.program.outgoing(h.state(),aimed.command("other"),10).orElseThrow().output().value());h.healthy();
    }
    @Test void zeroTransitionsCompleteOnceAtTheSameBoundaryAndNegativeConstantsAreRejected()throws Exception {
        var data=data();var cfg=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("aiming");
        for(String phase:List.of("enter","exit")){cfg.getAsJsonObject(phase).remove("profile");cfg.getAsJsonObject(phase).getAsJsonObject("value").addProperty("value",0);}
        var h=new Harness(compile(data));h.aim(true);assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());h.aim(false);assertNull(h.aim());assertEquals(2,h.checks.size());h.healthy();
        cfg.getAsJsonObject("enter").getAsJsonObject("value").addProperty("value",-1);assertThrows(RuntimeException.class,()->compile(data));
    }
    @Test void invalidCalculatedDurationCannotCancelThePreviousAcceptedPhase()throws Exception {
        var data=data();
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("aiming").getAsJsonObject("exit").getAsJsonObject("value").addProperty("value",-1);
        var h=new Harness(compile(data));h.aim(true);var before=h.state();
        assertThrows(IllegalArgumentException.class,()->h.aim(false));assertEquals(before,h.state());h.until(200_000);assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());h.healthy();
    }
}
