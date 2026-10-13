package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Source numbers are distinct from explicit timing, overlap, trajectory and world-unit calibrations. */
class SolarGrenadeTest {
    static final String D="chorus_d2:", ABILITY=D+"solar_grenade", FIELD=D+"solar_grenade_field";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var data=json("solar_grenade"); var calibration=json("solar_grenade_test_calibration");
        if(calibrated)calibration.getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        return CompiledEffects.link(List.of(FireboltTest.program(true).program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("solar_grenade_energy")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,calibration.get("program")).getOrThrow()));
    }
    static class Harness extends FireboltTest.Harness {
        final List<Long> times=new ArrayList<>();
        Harness() throws Exception {this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode) throws Exception {super(mode,program(true),ABILITY);positions.keySet().removeIf(k->k.startsWith("enemy"));}
        @Override DamageReceipt damage(RuleEngine.WorldRequest r,DamageCommand c){times.add(now());return super.damage(r,c);}
        List<BuffInstance> fields(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(FIELD)).toList();}
        List<Double> amounts(String phase){var out=new ArrayList<Double>();for(int i=0;i<hits.size();i++)if(hits.get(i).tags().contains(D+"solar_grenade_"+phase+"_damage"))out.add(amounts.get(i));return out;}
        List<Long> times(String phase){var out=new ArrayList<Long>();for(int i=0;i<hits.size();i++)if(hits.get(i).tags().contains(D+"solar_grenade_"+phase+"_damage"))out.add(times.get(i));return out;}
        List<String> targets(String phase){return hits.stream().filter(c->c.tags().contains(D+"solar_grenade_"+phase+"_damage")).map(DamageCommand::target).toList();}
        double scorch(String who){return buff("scorch",who).map(BuffInstance::quantity).orElse(0.);}
        void landAt(int flight,double x){send(launches.get(flight).finish(new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK,new WorldPosition("world",x,40,0),Optional.empty(),0,1,0,50_000)));}
        void magmaAt(int flight,double x){landAt(flight,x);}
    }
    @Test void missingCalibrationFailsBeforeUseAndPaidGrenadeUsesItsRechargeAndChunkScalar() throws Exception {
        var h=new Harness();assertThrows(IllegalArgumentException.class,()->program(false).useAbility(h.state(),h.request()));h.cast();assertEquals(0,h.energy());assertEquals(15,h.launches.getFirst().parameters().gravity());assertTrue(h.hits.isEmpty()&&h.fields().isEmpty());
        h.until(1_000_000);assertEquals(1/199.5,h.energy(),1e-12);assertEquals(.02,BleakWatcherAspectTest.gain(h.p,h.state(),"player",D+"grenade_energy",.04).grant().scaled(),1e-12);
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void impactHasItsOwnFalloffAndFractionalAshesWhileTheFixedFieldHasNoRadialFalloff() throws Exception {
        var h=new Harness();h.put("near",0);h.put("edge",3);h.bind(D+"ember_of_ashes");h.cast();h.land();assertEquals(21.5,h.scorch("near"));assertEquals(21.5,h.scorch("edge"));assertEquals(2,h.amounts("impact").size());assertTrue(h.amounts("impact").getFirst()!=h.amounts("impact").getLast());
        h.until(999_999);assertTrue(h.amounts("field").isEmpty());h.until(1_000_000);assertEquals(List.of(7.800000000000001,7.800000000000001),h.amounts("field"));assertEquals(21.5,h.scorch("near"));assertEquals(1,h.fields().size());
    }
    @Test void calibratedLifetimeRunsThirteenExactPulsesThenExpiresWithOnlyCurrentCombatantsScorched() throws Exception {
        var h=new Harness();h.cast();h.land();h.put("late",0);h.until(4_249_999);assertEquals(13,h.amounts("field").size());assertEquals(1_000_000L,h.times("field").getFirst());assertEquals(4_204_000L,h.times("field").getLast());assertEquals(0,h.scorch("late"));
        h.put("late",10);h.put("expiry",0);h.until(4_250_000);assertTrue(h.fields().isEmpty());assertEquals(17,h.scorch("expiry"));assertEquals(0,h.scorch("late"));h.until(7_000_000);assertEquals(13,h.amounts("field").size());
    }
    @Test void actualGuardianClassificationSelectsFractionalScorchTicksWithoutChangingCombatantRules() throws Exception {
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.players.add("guardian");h.cast();h.land();h.put("guardian",0);h.put("combatant",1);h.until(749_999);assertEquals(0,h.scorch("guardian"));h.until(750_000);assertEquals(1,h.scorch("guardian"));
            h.until(1_283_000);assertEquals(8.5,h.scorch("guardian"));assertEquals(0,h.scorch("combatant"));assertTrue(h.amounts("field").contains(2.5));assertTrue(h.amounts("field").contains(7.800000000000001));h.until(1_816_000);assertEquals(16,h.scorch("guardian"));
        }
    }
    @Test void targetSharedFieldGateStopsOverlappingDotButIndependentMagmaBlastsStillStack() throws Exception {
        var h=new Harness();h.touch();h.cast();h.land();h.full();h.cast();h.landAt(1,0);h.put("target",0);assertEquals(2,h.fields().size());h.until(1_000_000);assertEquals(1,h.amounts("field").size());assertEquals(4,h.launches.size());
        h.magmaAt(2,0);h.magmaAt(3,0);assertEquals(List.of(12.5,12.5),h.amounts("magma"));assertEquals(0,h.scorch("target"));
    }
    @Test void fieldAnchorMembersAndVisibilityAreResampledAndExpiryWithoutRemainingTargetsIsHarmless() throws Exception {
        var h=new Harness();h.cast();h.land();h.put("player",100);h.put("gone",5);h.put("inside",3);h.put("hidden",1);h.blocked.add("hidden");h.until(1_000_000);assertEquals(List.of("inside"),h.targets("field"));h.put("inside",5);h.put("late",1);h.until(1_267_000);assertEquals(List.of("inside","late"),h.targets("field"));h.positions.keySet().removeIf(k->!k.equals("player"));h.until(4_250_000);assertTrue(h.fields().isEmpty());
    }
    @Test void touchFieldAndPendingMagmaKeepSnapshotsAndParametersAfterUnequippingAndDomainRestore() throws Exception {
        var h=new Harness();h.touch();h.cast();h.land();h.choose(null);h.send(SourceChange.remove("touch"));h.restore();assertEquals(6_250_000,h.fields().getFirst().deadline());h.until(1_000_000);assertEquals(2,h.launches.size());
        var orb=h.launches.getLast();assertEquals(6,orb.parameters().speed());assertEquals(12,orb.parameters().gravity());assertTrue(orb.direction().orElseThrow().y()>0);h.restore();h.until(6_250_000);assertTrue(h.fields().isEmpty());int count=h.launches.size();h.until(7_000_000);assertEquals(count,h.launches.size());h.put("late",0);h.magmaAt(1,0);assertEquals(List.of(12.5),h.amounts("magma"));assertTrue(h.hits.stream().allMatch(c->c.source().ability().equals(ABILITY)));
    }
    @Test void everyMagmaEmissionAdvancesSavedRandomStateAndUsesARealIndependentProjectile() throws Exception {
        var h=new Harness();h.touch();h.cast();h.land();h.until(1_000_000);var first=h.launches.getLast();h.restore();h.until(1_600_000);var second=h.launches.getLast();assertNotEquals(first.direction(),second.direction());assertEquals(first.position(),second.position());assertEquals(first.origin(),second.origin());assertEquals(3,h.launches.size());
    }
    @Test void failedInitialContactDoesNotCreateAFieldAndManualRemovalDoesNotGrantExpiryScorch() throws Exception {
        for(var end:List.of(ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){var h=new Harness();h.cast();h.impact(0,end);assertTrue(h.fields().isEmpty()&&h.hits.isEmpty());assertEquals(0,h.energy());}
        var h=new Harness();h.cast();h.land();h.put("target",0);var field=h.fields().getFirst();var removed=Buffs.remove(h.state().buffs(),field.key(),Buffs.Reason.REMOVED);var state=h.state().withBuffs(removed.store());h.session=new EffectSession(h.p,EffectCheckpointTest.clock(),1,state,h::world);h.session.observe(h.now(),removed.signals());assertEquals(0,h.scorch("target"));h.until(5_000_000);assertTrue(h.amounts("field").isEmpty());
    }
    @Test void deniedScorchKeepsFieldDamageAndUnknownWorldResultsCannotReplay() throws Exception {
        var h=new Harness();h.put("target",0);h.denied.add("target");h.cast();h.land();assertEquals(0,h.scorch("target"));h.until(1_000_000);assertEquals(1,h.amounts("field").size());
        var t=new Harness();t.cast();t.land();t.put("target",0);t.failDamage=true;assertThrows(IllegalStateException.class,()->t.until(1_000_000));assertEquals(1,t.amounts("field").size());assertEquals(1,t.fields().size());assertThrows(IllegalStateException.class,()->t.until(2_000_000));assertEquals(1,t.amounts("field").size());
    }
    @Test void castGrenadeStatContributionsRemainFrozenForFieldAndMagmaAfterUnbinding() throws Exception {
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.send(SourceChange.bind(new EffectSource("stats","test:benevolence_stats","player",new BuffInstance.Origin("player","stats","",""),Set.of(),Map.of("grenade",new com.imdomestic.chorus.stat.Measure(200,com.imdomestic.chorus.stat.Unit.STAT_POINT),"melee",new com.imdomestic.chorus.stat.Measure(0,com.imdomestic.chorus.stat.Unit.STAT_POINT),"class_delta",new com.imdomestic.chorus.stat.Measure(0,com.imdomestic.chorus.stat.Unit.DELTA)))));
            h.touch();h.cast();h.send(SourceChange.remove("stats"));h.land();h.put("target",0);h.until(1_000_000);double multiplier=mode==EffectState.Mode.PVE?1.65:1.2;assertEquals(7.8*multiplier,h.amounts("field").getFirst(),1e-9);h.magmaAt(1,0);assertEquals(12.5*multiplier,h.amounts("magma").getFirst(),1e-9);
        }
    }
    @Test void unknownMagmaLaunchAndPeriodicStatusKeepTheirCommittedFieldAndNeverReplay() throws Exception {
        for(boolean launch:List.of(false,true)){
            var h=new Harness();if(launch)h.touch();h.cast();h.land();h.put("target",0);
            if(launch)h.failLaunch=true;else{h.players.add("target");h.failStatus=true;}
            assertThrows(IllegalStateException.class,()->h.until(launch?1_000_000:750_000));assertEquals(1,h.fields().size());int before=h.launches.size()+h.checks.size();assertThrows(IllegalStateException.class,()->h.until(2_000_000));assertEquals(before,h.launches.size()+h.checks.size());
        }
    }

}
