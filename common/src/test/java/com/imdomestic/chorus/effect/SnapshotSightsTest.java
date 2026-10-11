package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotSightsTest {
    static final String D="chorus_d2:";
    static final List<String> PARTS=List.of("box_breathing","weapon_stats","snapshot_sights","weapon_ads_curves","amplified","weapon_stat_handling_inputs","surplus","wellspring_targets","character_stats","armor_stats","armor_stat_inputs","weapon_stat_handling");
    static CompiledEffects program(JsonObject consumer)throws Exception {
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,consumer).getOrThrow());
        for(String name:PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());return CompiledEffects.link(parts);
    }
    static Loadout.Gear gear(String instance,String ammo,String snapshot,boolean surplus){
        var choices=new HashMap<String,String>();choices.put("perk","normal");if(snapshot!=null)choices.put("snapshot",snapshot);if(surplus)choices.put("surplus","normal");
        String item=ammo.equals("primary")?"test:rifle":"test:"+ammo+"_rifle";
        return new Loadout.Gear(instance,item,choices,ammo.equals("heavy")?Map.of("snapshot_ads_delta",new Measure(-.2,Unit.DELTA)):Map.of());
    }
    static class Harness extends WeaponHandlingTest.Harness {
        final List<ProjectileFlight.Launch> launches=new ArrayList<>();
        Harness()throws Exception{this(json("weapon_ads_weapon"),EffectState.Mode.PVE);}
        Harness(JsonObject data,EffectState.Mode mode)throws Exception{
            super(program(data));session=new EffectSession(program,EffectCheckpointTest.clock(),256,EffectState.empty().withMode(mode),this::world);
            equip(new Loadout(Map.of("test:primary",gear("a","primary","normal",false),"test:secondary",gear("b","primary",null,false)),Optional.of("test:primary")));
            bind("test:aim_observer",true);bind("test:box_inputs",true);bind("test:amplified_inputs",true);bind(D+"weapon_stat_handling",true);bind("test:wellspring_input",true);
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){return switch(r.command()){
            case WeaponAim.Verify q -> new WeaponAim.Verified(q,true);
            case EntityQuery q -> new EntityQuery.Result(q,Optional.of(AmplifiedTest.view(true,true,Set.of(),Optional.of(AmplifiedTest.movement(false)))));
            case PositionQuery q -> new PositionQuery.Result(q,Optional.of(WeaponFireTest.POINT));
            case DirectionQuery q -> new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",0,1,0)));
            case ProjectileFlight.Launch q -> {launches.add(q);yield new ProjectileFlight.Receipt(q,ProjectileFlight.Outcome.LAUNCHED,Optional.of("shot/"+launches.size()));}
            default -> super.world(r);
        };}
        void primary(Loadout.Gear gear){var slots=new TreeMap<>(physical.slots());slots.put("test:primary",gear);equip(new Loadout(slots,Optional.of("test:primary")));}
        WeaponAim.Plan aim(){return state().aiming().get("player");}
        void aim(boolean down){session.start(now(),new WeaponAim.Request("player","aim/"+ ++sequence,down).signal());}
        void amplify(String owner,double seconds){session.start(now(),new RuleEngine.Signal("test:amplified",new EffectEvent("player",owner,new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("duration",new Measure(seconds,Unit.SECOND)))));}
        void armor(double points){var slots=new TreeMap<>(physical.slots());slots.put(D+"arms",ArmorStatInputsTest.gear("armor","arms",points));equip(new Loadout(slots,physical.drawn()));}
        void charged(){session.start(now(),new AbilityChange("player",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of(D+"grenade","test:grenade",D+"melee","test:melee_double",D+"class","test:class"))).signal());session.start(now(),new RuleEngine.Signal("test:fill",new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("grenade",new Measure(1,Unit.CHARGE),"melee",new Measure(1,Unit.CHARGE),"class",new Measure(1,Unit.CHARGE)))));}
        EffectEvent query(String owner,String weapon,Set<String> tags){return new EffectEvent(owner,weapon,new BuffInstance.Origin(owner,weapon,weapon,"",tags),tags,Map.of());}
        CalculationPipeline.Result duration(String curve,double base,String owner,String weapon,Set<String> tags){return program.calculatePipeline(state(),"player",query(owner,weapon,tags),List.of(D+"weapon_handling",D+curve+"_ads_time",D+"handling_animation",D+"ads_duration"),new Measure(base,Unit.STAT_POINT));}
        double stability(String weapon,double base){return program.calculate(state(),"player",query("player",weapon,Set.of()),D+"weapon_stability",new Measure(base,Unit.STAT_POINT),List.of()).output().value();}
        boolean box(){return state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals(D+"box_breathing"));}
        void healthy(){assertTrue(session.state().idle()&&session.state().engine().failure().isEmpty(),()->session.state().engine().failure().toString());}
    }
    static Set<String> tags(String ammo,String phase){return Set.of(D+ammo+"_ammo","chorus:weapon_"+phase);}
    static double auto(double points){return .38975-.001875*points;}
    static double sniper(double points){return .5021325414-.00194998437*points;}

    @Test void ammoClassificationAndEnhancementProduceAcceptedPhasesInBothModes()throws Exception{
        for(var mode:EffectState.Mode.values())for(String ammo:List.of("primary","special","heavy"))for(String option:List.of("normal","enhanced")){
            var h=new Harness(json("weapon_ads_weapon"),mode);h.primary(gear("a",ammo,option,false));h.aim(true);
            double seconds=(ammo.equals("primary")?auto(10)*.5:sniper(10)*.8);var plan=h.aim();assertEquals(seconds,plan.duration().value(),1e-12);assertEquals(WeaponHandling.micros(new Measure(seconds,Unit.SECOND)),plan.dueAt());
            assertEquals(option.equals("enhanced")?15:10,h.stability("a",10));assertEquals(10,h.stability("b",10));
            h.until(plan.dueAt()-1);assertEquals(WeaponAim.Phase.ENTERING,h.aim().phase());h.until(plan.dueAt());assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());
            h.aim(false);assertEquals(.1,h.aim().duration().value());h.healthy();
        }
    }
    @Test void everyPublishedNonzeroCurveUsesClampedHandlingBeforeTheScalarWithoutAStowFloor()throws Exception{
        var h=new Harness();
        for(var curve:CURVES.entrySet())for(double input:List.of(-50.,0.,10.,50.,100.,200.)){
            double points=Math.max(0,Math.min(100,input));var result=h.duration(curve.getKey(),input,"player","a",tags("primary","aim_enter"));
            assertEquals((curve.getValue()[0]+curve.getValue()[1]*points)*.5,result.output().value(),1e-12,curve.getKey()+" at "+input);
            assertEquals(points,result.steps().getFirst().output().value());
        }
        var fastest=h.duration("auto_rifle",100,"player","a",tags("primary","aim_enter"));assertTrue(fastest.output().value()<auto(100));assertEquals(auto(100),fastest.withoutFactors(Set.of(D+"ads_perks")).output().value(),1e-12);
    }
    @Test void surplusAmplifiedAndSnapshotComposeButCharacterWeaponsDoesNotSpeedAds()throws Exception{
        var h=new Harness();h.primary(gear("a","primary","normal",true));h.charged();h.amplify("player",15);h.aim(true);var plan=h.aim();
        assertEquals(auto(100)*.95*.5,plan.duration().value(),1e-12);assertEquals(100,plan.calculation().orElseThrow().steps().getFirst().output().value());
        var before=h.duration("auto_rifle",10,"player","a",tags("primary","aim_enter"));h.armor(200);var after=h.duration("auto_rifle",10,"player","a",tags("primary","aim_enter"));assertEquals(before.output(),after.output());assertEquals(plan,h.aim());
        assertEquals(auto(100)*.5,after.withoutFactors(Set.of(D+"handling_animation")).output().value(),1e-12);assertEquals(auto(100)*.95,after.withoutFactors(Set.of(D+"ads_perks")).output().value(),1e-12);h.healthy();
    }
    @Test void duplicateProvidersDoNotDoubleAdsOrEnhancedStability()throws Exception{
        var h=new Harness();h.primary(gear("a","primary","enhanced",false));
        for(String id:List.of("intrinsic","trait"))h.session.start(h.now(),SourceChange.bind(new EffectSource(id,D+"snapshot_sights","player",new BuffInstance.Origin("player",id,"a",""),Set.of("chorus:enhanced"))));
        h.aim(true);assertEquals(auto(10)*.5,h.aim().duration().value(),1e-12);assertEquals(15,h.stability("a",10));assertEquals(100,h.stability("a",99));h.healthy();
    }
    @Test void queryIsolationRejectsForeignWeaponsOwnersAndAmbiguousAmmoAndLeavesOtherPhasesAlone()throws Exception{
        var h=new Harness();h.amplify("ally",15);
        for(String phase:List.of("ready","stow","aim_exit"))assertEquals(auto(10),h.duration("auto_rifle",10,"player","a",tags("primary",phase)).output().value(),1e-12);
        assertEquals(auto(10),h.duration("auto_rifle",10,"other","a",tags("primary","aim_enter")).output().value(),1e-12);
        assertEquals(auto(10),h.duration("auto_rifle",10,"player","b",tags("primary","aim_enter")).output().value(),1e-12);
        assertEquals(auto(10),h.duration("auto_rifle",10,"player","a",Set.of("chorus:weapon_aim_enter")).output().value(),1e-12);
        assertEquals(auto(10),h.duration("auto_rifle",10,"player","a",Set.of("chorus:weapon_aim_enter",D+"primary_ammo",D+"special_ammo")).output().value(),1e-12);h.healthy();
    }
    @Test void acceptedDeadlineAndTraceSurviveBuffExpiryAndCheckpointWhileNewEntryResamples()throws Exception{
        var h=new Harness();h.amplify("player",.05);h.aim(true);var accepted=h.aim();assertEquals(auto(50)*.95*.5,accepted.duration().value(),1e-12);
        h.until(50_000);assertEquals(accepted,h.aim());h.restore();assertEquals(accepted,h.aim());int cues=h.cues.size();h.until(accepted.dueAt()-1);assertEquals(cues,h.cues.size());h.until(accepted.dueAt());assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());
        h.aim(false);h.until(h.aim().dueAt());h.aim(true);assertEquals(auto(10)*.5,h.aim().duration().value(),1e-12);h.healthy();
    }
    @Test void powerNeedsAnExplicitValidParameterAndChangingItCancelsTheOldPlan()throws Exception{
        var h=new Harness();var supplied=gear("a","heavy","normal",false);var before=h.state();
        assertThrows(IllegalArgumentException.class,()->h.program.equipment().validate(new Loadout(Map.of("test:primary",new Loadout.Gear("a","test:heavy_rifle",supplied.choices())),Optional.of("test:primary"))));
        assertSame(before,h.state());h.primary(supplied);h.aim(true);var old=h.aim();assertEquals(sniper(10)*.8,old.duration().value(),1e-12);
        h.primary(new Loadout.Gear("a","test:heavy_rifle",supplied.choices(),Map.of("snapshot_ads_delta",new Measure(-.5,Unit.DELTA))));assertNull(h.aim());h.aim(true);assertEquals(sniper(10)*.5,h.aim().duration().value(),1e-12);h.healthy();
    }
    @Test void fasterReadinessDoesNotAdvanceBoxBreathingAndActualFireStillCapturesItsOwnBonus()throws Exception{
        var h=new Harness();h.aim(true);long ready=h.aim().dueAt();assertTrue(ready<1_500_000);h.until(ready);assertEquals(WeaponAim.Phase.AIMED,h.aim().phase());assertFalse(h.box());h.until(1_499_999);assertFalse(h.box());h.until(1_500_000);assertTrue(h.box());
        var before=h.state();var fire=h.program.fire(before,new WeaponFire.Request("player","shot"));h.session.observe(h.now(),fire.emitted(),new WeaponFire.Commit(before,fire.state()));
        var shot=(DamageSnapshot)h.launches.getLast().continuation().bindings().get("head");h.aim(false);
        var hit=shot.command("target",new ImpactData(Map.of("weakspot",new Measure(1,Unit.COUNT),"precision_delta",new Measure(1,Unit.DELTA))));assertEquals(28,h.program.outgoing(h.state(),hit,10).orElseThrow().output().value(),1e-12);assertFalse(h.box());h.healthy();
    }
    // Independent published coefficient oracle from the pinned Foundry archive; no consumer JSON is read here.
    static final Map<String,double[]> CURVES=Map.ofEntries(
        Map.entry("auto_rifle",new double[]{0.38975,-0.001875}),
        Map.entry("combat_bow",new double[]{0.5293778291,-0.001855658199}),
        Map.entry("fusion_rifle",new double[]{0.4112330226,-0.001693741678}),
        Map.entry("fusion_rifle_vex",new double[]{0.38975,-0.001875}),
        Map.entry("grenade_launcher",new double[]{0.50019128,-0.00187072}),
        Map.entry("hand_cannon",new double[]{0.3316666667,-0.001666666667}),
        Map.entry("linear_fusion_rifle",new double[]{0.4112330226,-0.001693741678}),
        Map.entry("machine_gun",new double[]{0.4574687027,-0.001234477537}),
        Map.entry("machine_gun_overture",new double[]{0.4584687027,-0.001234477537}),
        Map.entry("pulse_rifle",new double[]{0.4574687,-0.00196208}),
        Map.entry("rocket_launcher",new double[]{0.528984167,-0.002139258726}),
        Map.entry("scout_rifle",new double[]{0.4114236019,-0.001693527081}),
        Map.entry("shotgun",new double[]{0.451194332,-0.00233805668}),
        Map.entry("sidearm",new double[]{0.3435796703,-0.0022293956}),
        Map.entry("sniper_rifle",new double[]{0.5021325414,-0.00194998437}),
        Map.entry("submachine_gun",new double[]{0.3581576422,-0.001873200822}),
        Map.entry("trace_rifle",new double[]{0.38975,-0.001875}));
}
