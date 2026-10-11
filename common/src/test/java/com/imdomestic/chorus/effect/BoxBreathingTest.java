package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BoxBreathingTest {
    static final String B="chorus_d2:box_breathing";
    static JsonObject data()throws Exception {
        var data=json("box_breathing_weapon");var perk=json("box_breathing");
        for(String field:List.of("profiles","buffs","bundles"))perk.getAsJsonArray(field).forEach(e->data.getAsJsonArray(field).add(e));return data;
    }
    static Loadout pair(String selected){return new Loadout(Map.of("test:primary",new Loadout.Gear("a","test:rifle",Map.of("perk","normal")),"test:secondary",new Loadout.Gear("b","test:rifle",Map.of("perk","enhanced"))),Optional.of("test:"+selected));}
    static class Harness extends WeaponHandlingTest.Harness {
        final List<ProjectileFlight.Launch> launches=new ArrayList<>();boolean failLaunch;
        Harness()throws Exception{this(data());}
        Harness(JsonObject data)throws Exception{this(data,EffectState.Mode.PVE);}
        Harness(JsonObject data,EffectState.Mode mode)throws Exception{super(compile(data));session=new EffectSession(program,EffectCheckpointTest.clock(),256,EffectState.empty().withMode(mode),this::world);equip(pair("primary"));bind("test:aim_observer",true);bind("test:box_inputs",true);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request){return switch(request.command()){
            case WeaponAim.Verify q -> new WeaponAim.Verified(q,true);
            case PositionQuery q -> new PositionQuery.Result(q,Optional.of(WeaponFireTest.POINT));
            case DirectionQuery q -> new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",0,1,0)));
            case ProjectileFlight.Launch q -> {launches.add(q);if(failLaunch)throw new IllegalStateException("unknown Box Breathing launch");yield new ProjectileFlight.Receipt(q,ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile/"+launches.size()));}
            default -> super.world(request);
        };}
        void aim(boolean pressed){session.start(now(),new WeaponAim.Request("player","aim/"+ ++sequence,pressed).signal());}
        WeaponFire.Receipt fire(){var request=new WeaponFire.Request("player","fire/"+ ++sequence);var result=program.fire(state(),request);session.observe(now(),result.emitted(),new WeaponFire.Commit(state(),result.state()));return (WeaponFire.Receipt)result.result();}
        DamageSnapshot shot(){return (DamageSnapshot)launches.getLast().continuation().bindings().get("head");}
        boolean active(String weapon){return state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals(B)&&b.origin().weapon().equals(weapon));}
        long due(){return state().timers().values().stream().filter(t->t.signal().type().equals(B+"_ready")).mapToLong(EffectState.Timer::dueAt).findFirst().orElse(-1);}
        void ammo(String kind,String weapon,int amount){session.start(now(),new RuleEngine.Signal("test:"+kind,new EffectEvent("foreign",weapon,new BuffInstance.Origin("foreign","ammo","",""),Set.of(),Map.of("amount",new Measure(amount,Unit.ROUND)))));}
        void healthy(){assertTrue(session.state().idle()&&session.state().engine().failure().isEmpty());}
        double hit(DamageSnapshot shot,int weak,double precision){var cmd=shot.command("victim",new ImpactData(Map.of("weakspot",new Measure(weak,Unit.COUNT),"precision_delta",new Measure(precision,Unit.DELTA))));return program.outgoing(state(),cmd,10).orElseThrow().output().value();}
    }
    @Test void normalAndEnhancedStartAtInputEvenBeforeTheAimTransitionFinishes()throws Exception {
        for(boolean enhanced:List.of(false,true)){
            var data=data();data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("aiming").getAsJsonObject("enter").getAsJsonObject("value").addProperty("value",2);
            var h=new Harness(data);h.equip(pair(enhanced?"secondary":"primary"));h.aim(true);long deadline=enhanced?1_400_000:1_500_000;String gun=enhanced?"b":"a";
            assertEquals(deadline,h.due());h.until(deadline-1);assertFalse(h.active(gun));h.until(deadline);assertTrue(h.active(gun));assertEquals(WeaponAim.Phase.ENTERING,h.state().aiming().get("player").phase());h.fire();assertEquals(28,h.hit(h.shot(),1,1),1e-9);h.healthy();
        }
    }
    @Test void onlyAcceptedShotsRestartTheFullWaitWithoutRequiringAnotherAimInput()throws Exception {
        var h=new Harness();h.aim(true);h.until(1_000_000);assertEquals(WeaponFire.Outcome.ACCEPTED,h.fire().outcome());assertEquals(2_500_000,h.due());assertEquals(20,h.hit(h.shot(),1,1));
        h.until(1_050_000);assertEquals(WeaponFire.Outcome.COOLDOWN,h.fire().outcome());assertEquals(2_500_000,h.due());h.until(2_499_999);assertFalse(h.active("a"));h.until(2_500_000);assertTrue(h.active("a"));h.fire();assertEquals(28,h.hit(h.shot(),1,1),1e-9);assertFalse(h.active("a"));assertEquals(4_000_000,h.due());h.healthy();
    }
    @Test void lastRoundCapturesItsBonusBeforeFireCostReactionsAndEmptyGunWaitsForRefill()throws Exception {
        var data=data();data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine",1);var h=new Harness(data);
        h.aim(true);h.until(1_500_000);assertTrue(h.active("a"));h.fire();assertEquals(0,h.state().ammunition().get("a").magazine());assertEquals(28,h.hit(h.shot(),1,1),1e-9);assertFalse(h.active("a"));assertEquals(-1,h.due());
        h.until(3_000_000);assertEquals(WeaponFire.Outcome.NO_AMMUNITION,h.fire().outcome());assertFalse(h.active("a"));h.ammo("grant","a",1);assertEquals(4_500_000,h.due());h.until(4_500_000);assertTrue(h.active("a"));h.healthy();
    }
    @Test void externalAmmoChangesUseTheRecipientWeaponAndOnlyBreakTheWaitAtZero()throws Exception {
        var h=new Harness();h.aim(true);h.until(500_000);h.ammo("spend","b",5);assertEquals(1_500_000,h.due());h.ammo("spend","a",1);assertEquals(1_500_000,h.due());h.ammo("spend","a",4);assertEquals(-1,h.due());
        h.until(2_000_000);assertFalse(h.active("a"));h.ammo("grant","a",2);assertEquals(3_500_000,h.due());h.until(3_500_000);assertTrue(h.active("a"));h.ammo("spend","a",2);assertFalse(h.active("a"));h.healthy();
    }
    @Test void releaseSwapAndPerkRemovalCancelTheirOwnWaitAndActiveBenefit()throws Exception {
        var h=new Harness();h.aim(true);h.until(1_000_000);h.aim(false);assertEquals(-1,h.due());h.until(1_500_000);assertFalse(h.active("a"));h.aim(true);assertEquals(3_000_000,h.due());h.until(3_000_000);h.aim(false);assertFalse(h.active("a"));
        h.equip(pair("secondary"));h.aim(true);h.until(4_400_000);assertTrue(h.active("b"));assertFalse(h.active("a"));
        var slots=new TreeMap<>(h.physical.slots());slots.put("test:secondary",new Loadout.Gear("b","test:rifle",Map.of("perk","none")));h.equip(new Loadout(slots,h.physical.drawn()));assertFalse(h.active("b"));assertEquals(-1,h.due());h.until(8_000_000);assertFalse(h.active("b"));h.healthy();
    }
    @Test void naturalWeakspotAndPrecisionAreIndependentAcrossModesAndWeaponFamilies()throws Exception {
        for(var mode:EffectState.Mode.values())for(String family:List.of("scout","special_sniper","heavy_sniper","glaive","heavy_other")){
            var data=data();var tags=new JsonArray();tags.add("test:weapon");data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().add("tags",tags);
            if(family.contains("sniper"))tags.add("chorus_d2:sniper_rifle");if(family.startsWith("heavy"))tags.add("chorus_d2:heavy_ammo");if(family.equals("glaive"))tags.add("chorus_d2:glaive");
            var h=new Harness(data,mode);h.aim(true);h.until(1_500_000);h.fire();var shot=h.shot();
            double bonus=Set.of("heavy_sniper","glaive").contains(family)?1.3:1.4;
            for(int weak:List.of(0,1))for(int precision:List.of(0,1))assertEquals(10*(1+precision)*(weak==1?bonus:1),h.hit(shot,weak,precision),1e-9,mode+" "+family+" weak="+weak+" precision="+precision);
            h.healthy();
        }
    }
    @Test void capturedBonusCannotLeakToAnotherWeaponOwnerOrExcludedDamageComponent()throws Exception {
        var h=new Harness();h.aim(true);h.until(1_500_000);
        for(String kind:List.of("own","other_weapon","other_owner","explosive","glaive_melee","ability")){
            var tags=new HashSet<String>();tags.add(kind.equals("ability")?"chorus:ability_damage":"chorus:weapon_damage");
            if(kind.equals("explosive"))tags.add("chorus:explosive_perk_damage");if(kind.equals("glaive_melee"))tags.add("chorus:glaive_melee");
            var origin=new BuffInstance.Origin(kind.equals("other_owner")?"other":"player","component",kind.equals("other_weapon")?"b":"a","");
            var command=new DamageCommand("unknown",origin,10,"minecraft:generic",tags,Set.of(),false,Optional.of("test:weapon_damage"));
            assertEquals(kind.equals("own")?28:kind.equals("other_owner")?10:20,h.hit(h.program.captureDamage(h.state(),command),1,1),1e-9,kind);
        }
        assertTrue(h.active("a"));h.healthy();
    }
    @Test void everyAcceptedShotConsumesTheBuffAndItsSnapshotSurvivesReleaseOrUnequip()throws Exception {
        var h=new Harness();h.aim(true);h.until(1_500_000);h.fire();var shot=h.shot();assertFalse(h.active("a"));assertEquals(10,h.hit(shot,0,0));
        h.aim(false);h.equip(Loadout.EMPTY);assertEquals(28,h.hit(shot,1,1),1e-9);assertEquals(20,h.hit(shot,0,1),1e-9,"synthetic precision bubble must not become a weakspot");assertEquals(14,h.hit(shot,1,0),1e-9);
        var saved=EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(shot),DamageSnapshot.class);assertEquals(shot,saved);assertThrows(IllegalArgumentException.class,()->h.program.outgoing(h.state(),saved.command("victim"),10));h.healthy();
    }
    @Test void partialWaitAndPermanentReadyStateRestoreWithoutReplayOrResettingTheTimer()throws Exception {
        var h=new Harness();h.aim(true);h.until(600_000);h.restore();assertEquals(1_500_000,h.due());assertTrue(h.launches.isEmpty());h.until(1_500_000);assertTrue(h.active("a"));h.restore();h.until(9_000_000);assertTrue(h.active("a"));h.fire();assertEquals(28,h.hit(h.shot(),1,1),1e-9);assertEquals(1,h.launches.size());h.healthy();
    }
    @Test void aimAssistDistanceUsesTheMatchingWeaponsReadyBuffAndStopsImmediatelyOnRelease()throws Exception {
        var h=new Harness();h.aim(true);h.until(1_500_000);
        for(String weapon:List.of("a","b")){
            var q=new EffectEvent("player","",new BuffInstance.Origin("player","query",weapon,""),Set.of(),Map.of());
            assertEquals(weapon.equals("a")?22:20,h.program.calculate(h.state(),"player",q,"chorus_d2:aim_assist_falloff_distance",new Measure(20,Unit.METER),List.of()).output().value(),1e-9);
        }
        h.aim(false);assertFalse(h.active("a"));h.healthy();
    }
    @Test void unknownPhysicalLaunchKeepsPaidAmmoAndCannotSpendOrLaunchTheSameShotAgain()throws Exception {
        var h=new Harness();h.aim(true);h.until(1_500_000);h.failLaunch=true;assertThrows(IllegalStateException.class,h::fire);assertEquals(4,h.state().ammunition().get("a").magazine());assertEquals(1,h.launches.size());assertEquals(28,h.hit(h.shot(),1,1),1e-9);
        assertThrows(IllegalStateException.class,()->h.until(1_500_000));assertEquals(1,h.launches.size());assertThrows(IllegalStateException.class,h.session::checkpoint);
    }
}
