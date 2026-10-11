package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Published community curve fits are distinct from calibrated physical animations. */
class WeaponStatHandlingTest {
    static final String D="chorus_d2:", FACTOR=D+"weapons_handling_duration";
    static CompiledEffects program()throws Exception {
        var parts=new ArrayList<EffectProgram>(); var bindings=json("weapon_handling_test_calibration");
        for(String name:List.of("weapon_stats","surplus","surplus_weapon","wellspring_targets","wellspring","character_stats","armor_stats","armor_stat_inputs","amplified","weapon_stat_handling","weapon_handling_curves","weapon_stat_handling_inputs")) {
            var data=json(name);
            if(name.equals("surplus_weapon"))for(var w:data.getAsJsonArray("weapons")) {
                var weapon=w.getAsJsonObject();weapon.add("handling",bindings.get(weapon.get("item").getAsString()).deepCopy());
            }
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        }return CompiledEffects.link(parts);
    }
    static double curve(String weapon,boolean stow,double points) {
        if(weapon.equals("a"))return stow?.48414822-.00268436*points:.51985381-.00279338*points;
        return stow?.5711336032-.003388663968*points:.5388744939-.003271255061*points;
    }
    static class Harness extends WeaponHandlingTest.Harness {
        Harness(boolean charged)throws Exception {
            super(WeaponStatHandlingTest.program());equip(SurplusTest.equipment("normal","none","primary"));
            session.start(now(),new AbilityChange("player",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of(D+"grenade","test:grenade",D+"melee","test:melee_double",D+"class","test:class"))).signal());
            bind("test:wellspring_input",true);bind("test:amplified_inputs",true);bind(D+"weapon_stat_handling",true);
            if(charged)session.start(now(),new RuleEngine.Signal("test:fill",new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("grenade",new Measure(1,Unit.CHARGE),"melee",new Measure(1,Unit.CHARGE),"class",new Measure(1,Unit.CHARGE)))));
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if(request.command() instanceof EntityQuery q)return new EntityQuery.Result(q,Optional.of(AmplifiedTest.view(true,true,Set.of(),Optional.of(AmplifiedTest.movement(false)))));
            return super.world(request);
        }
        void armor(double points) {var old=state().equipment().get("player");var slots=new TreeMap<>(old.slots());slots.put(D+"arms",ArmorStatInputsTest.gear("armor","arms",points));equip(new Loadout(slots,old.drawn()));}
        void amplify(String holder,double duration) {session.start(now(),new RuleEngine.Signal("test:amplified",new EffectEvent("player",holder,new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));}
        void use(String slot) {session.start(now(),new AbilityUse.Request("player",D+slot,"ability-"+ ++sequence,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of())).signal());}
        CalculationPipeline.Result timing(String weapon,boolean stow,double base,EffectState.Mode mode) {
            String family=weapon.equals("a")?"auto_rifle":"shotgun",phase=stow?"stow":"ready";
            var profiles=new ArrayList<>(List.of(D+"weapon_handling",D+family+"_"+phase+"_time",D+"handling_animation",FACTOR));if(stow)profiles.add(D+family+"_stow_floor");
            var tag="chorus:weapon_"+phase;
            return program.calculatePipeline(state().withMode(mode),"player",new EffectEvent("player",weapon,new BuffInstance.Origin("player",weapon,weapon,"",Set.of(tag)),Set.of(tag),Map.of()),profiles,new Measure(base,Unit.STAT_POINT));
        }
        void healthy(){assertTrue(session.state().idle()&&session.state().engine().failure().isEmpty(),()->session.state().engine().failure().toString());}
    }
    @Test void twoPublishedCurvesAndBothPhasesConsumeCharacterDurationWithoutAddingHandlingPoints()throws Exception {
        var h=new Harness(false);
        for(double points:List.of(0.,1.,9.,40.,99.,100.,101.,150.,200.)) {
            h.armor(points);
            for(var mode:EffectState.Mode.values())for(String weapon:List.of("a","b"))for(boolean stow:List.of(false,true))for(double base:List.of(0.,50.,100.)) {
                var result=h.timing(weapon,stow,base,mode);double expected=curve(weapon,stow,base)*(1-.001*Math.min(points,100));if(stow)expected=Math.max(curve(weapon,true,100),expected);
                assertEquals(expected,result.output().value(),1e-12);assertEquals(base,result.steps().getFirst().output().value());
            }
        }
    }
    @Test void surplusAndAmplifiedPointsClampBeforeCurvesAndFinalStowFloorIncludesEveryDurationScalar()throws Exception {
        var h=new Harness(true);h.armor(100);h.amplify("player",15);
        for(String weapon:List.of("a","b"))for(boolean stow:List.of(false,true)) {
            double stat=weapon.equals("a")?100:50;var result=h.timing(weapon,stow,10,EffectState.Mode.PVE);
            assertEquals(stat,result.steps().getFirst().output().value());double expected=curve(weapon,stow,stat)*.95*.9;if(stow)expected=Math.max(curve(weapon,true,100),expected);
            assertEquals(expected,result.output().value(),1e-12);
        }
        var stow=h.timing("a",true,10,EffectState.Mode.PVE);assertEquals(curve("a",true,100),stow.output().value(),1e-12);
        assertTrue(stow.steps().get(3).output().value()<stow.output().value(),"all duration multipliers precede the floor");
        var ready=h.timing("a",false,10,EffectState.Mode.PVE);assertEquals(curve("a",false,100)*.95,ready.withoutFactors(Set.of(FACTOR)).output().value(),1e-12);
        assertEquals(curve("a",false,100)*.9,ready.withoutFactors(Set.of(D+"handling_animation")).output().value(),1e-12);
        assertEquals(curve("a",false,100),ready.withoutFactors(Set.of(FACTOR,D+"handling_animation")).output().value(),1e-12);
    }
    @Test void foreignBuffAndDuplicateSystemProvidersCannotContaminateTheWeaponOwner()throws Exception {
        var h=new Harness(false);h.amplify("ally",15);assertEquals(curve("a",false,10),h.timing("a",false,10,EffectState.Mode.PVE).output().value(),1e-12);
        h.armor(100);h.session.start(h.now(),SourceChange.bind(new EffectSource("duplicate",D+"weapon_stat_handling","player",new BuffInstance.Origin("player","duplicate","",""),Set.of())));
        var before=h.state();assertEquals(curve("a",false,10)*.9,h.timing("a",false,10,EffectState.Mode.PVE).output().value(),1e-12);assertSame(before,h.state());
    }
    @Test void acceptedStowKeepsItsPerksWhileNextReadyReadsCurrentArmorAndCharges()throws Exception {
        var h=new Harness(true);h.armor(100);var old=h.state().equipment().get("player");var slots=new TreeMap<>(old.slots());slots.put("test:secondary",new Loadout.Gear("b","test:shotgun",Map.of("perk","normal")));h.equip(new Loadout(slots,old.drawn()));
        h.select("secondary");var stow=h.plan();assertEquals(curve("a",true,70)*.9,stow.duration().value(),1e-12);
        h.armor(0);h.use("melee");assertEquals(stow,h.plan());h.until(stow.dueAt()-1);assertEquals(Optional.of("test:primary"),h.physical.drawn());
        h.until(stow.dueAt());assertTrue(h.physical.drawn().isEmpty());var ready=h.plan();assertEquals(curve("b",false,35),ready.duration().value(),1e-12);
        h.armor(100);h.until(ready.dueAt()-1);assertTrue(h.physical.drawn().isEmpty());h.until(ready.dueAt());assertEquals(Optional.of("test:secondary"),h.physical.drawn());h.healthy();
    }
    @Test void amplifiedExpiryDuringReadyDoesNotMoveItsAcceptedDeadline()throws Exception {
        var h=new Harness(false);h.equip(new Loadout(h.physical.slots(),Optional.empty()));h.armor(100);h.amplify("player",.1);h.select("primary");var ready=h.plan();
        assertEquals(curve("a",false,50)*.95*.9,ready.duration().value(),1e-12);h.until(100_000);assertEquals(ready,h.plan());assertTrue(h.state().buffs().instances().isEmpty());
        h.until(ready.dueAt());assertEquals(Optional.of("test:primary"),h.physical.drawn());h.select("secondary");assertEquals(curve("a",true,10)*.9,h.plan().duration().value(),1e-12);h.healthy();
    }
    @Test void savedPhasesKeepCurvesNamedFactorsAndExactDeadlinesAfterSourcesChange()throws Exception {
        var h=new Harness(true);h.armor(100);h.amplify("player",15);h.select("secondary");var stow=h.plan();h.until(50_000);h.restore();assertEquals(stow,h.plan());
        h.bind(D+"weapon_stat_handling",false);h.until(stow.dueAt());var ready=h.plan();assertEquals(curve("b",false,50)*.95,ready.duration().value(),1e-12);h.restore();assertEquals(ready,h.plan());
        assertEquals(curve("b",false,50),ready.calculation().orElseThrow().withoutFactors(Set.of(D+"handling_animation")).output().value(),1e-12);
        h.until(ready.dueAt());assertEquals(Optional.of("test:secondary"),h.physical.drawn());assertEquals(2,h.applications.size());h.healthy();
    }
    @Test void enhancedHandlingRequiresItsCalibrationBeforeChangingThePhysicalSelection()throws Exception {
        var h=new Harness(true);h.equip(SurplusTest.equipment("enhanced","none","primary"));var before=h.state();
        assertThrows(IllegalArgumentException.class,()->h.select("secondary"));assertSame(before,h.state());assertTrue(h.applications.isEmpty());assertNull(h.plan());
    }
}
