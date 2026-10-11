package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual Solar snapshots and delayed ticks, with shared content modifiers rather than copied bonuses. */
class SolarDamageScalingTest {
    static final String D="chorus_d2:";
    static class Harness extends SolarTest.Harness {
        static String[] modules(String... additional) {
            var modules=new ArrayList<>(List.of("solar","solar_test_calibration","solar_test_source","empowering_damage","radiant",
                    "weapon_surge","weapon_surge_inputs","armor_charge","armor_charge_mods","armor_charge_inputs",
                    "character_stats","solar_damage_perks"));modules.addAll(List.of(additional));return modules.toArray(String[]::new);
        }
        Harness(EffectState.Mode mode,String... additional)throws Exception {
            super(link(modules(additional)),mode);
            for(var s:List.of(SolarTest.FIRST,SolarTest.SECOND)) {
                views.put(s.holder(),SolarTest.view(false,false));bind(ArmorChargeTest.system(s.holder()));
                for(String name:List.of("armor_charge_inputs","surge_inputs"))bind(new EffectSource(name+"/"+s.holder(),"test:"+name,s.holder(),s.origin(),Set.of()));
            }
        }
        void bind(EffectSource s){session.start(state().buffs().timeMicros(),SourceChange.bind(s));healthy();}
        void remove(String s){session.start(state().buffs().timeMicros(),SourceChange.remove(s));healthy();}
        void event(EffectSource source,String event,Map<String,Measure> values,Set<String> tags) {
            session.start(state().buffs().timeMicros(),new RuleEngine.Signal(event,new EffectEvent(source.holder(),source.holder(),source.origin(),tags,values)));healthy();
        }
        void charge(EffectSource s,String element,int copies) {
            for(int i=0;i<copies;i++)bind(ArmorChargeTest.mod(s.holder(),element+"_weapon_surge",i));
            event(s,"test:gain_armor_charge",Map.of("stacks",new Measure(1,Unit.COUNT),"bonus",new Measure(1,Unit.COUNT)),Set.of());
        }
        void radiant(EffectSource s,double duration){event(s,"test:surge_radiant",Map.of("duration",new Measure(duration,Unit.SECOND)),Set.of());}
        void rampage(EffectSource s) {
            bind(new EffectSource("rampage/"+s.holder(),D+"rampage",s.holder(),s.origin(),Set.of()));
            event(s,"chorus:kill",Map.of(),Set.of("chorus:weapon_kill"));
        }
    }
    @Test void eachSurgeTierMultipliesRadiantAndEligibleRampageOnActualTicksAndIgnitions()throws Exception {
        for(int copies=1;copies<=3;copies++) {
            var h=new Harness(EffectState.Mode.PVE);h.charge(SolarTest.FIRST,"solar",copies);h.radiant(SolarTest.FIRST,10);h.rampage(SolarTest.FIRST);
            h.apply(0,SolarTest.FIRST,60);h.until(500_000);
            double factor=WeaponSurgeTest.PVE[copies]*1.2*1.1;
            assertEquals(1.6236*factor,h.amounts.getFirst(),1e-9);
            h.apply(600_000,SolarTest.SECOND,40);
            assertEquals(67.6*factor,h.amounts.getLast(),1e-9);
            assertEquals(SolarTest.FIRST.origin(),h.damage.getLast().source());
            var trace=h.program.outgoing(h.state(),h.damage.getLast(),0).orElseThrow().trace();
            assertTrue(trace.contributions().stream().anyMatch(c->c.selected()&&c.contribution().stage().equals("weapon_surge")));
        }
    }
    @Test void sourceCreditRatherThanAWeaponIdentityDecidesWhetherWeaponBuffsApply()throws Exception {
        for(String credit:List.of("weapon","grenade","melee","super")) {
            var h=new Harness(EffectState.Mode.PVE);h.charge(SolarTest.FIRST,"solar",3);h.radiant(SolarTest.FIRST,10);h.rampage(SolarTest.FIRST);
            var source=SolarTest.source("first",credit);h.bind(source);h.apply(0,source,60);h.until(500_000);
            assertEquals(1.6236*(credit.equals("weapon")?1.22*1.2*1.1:1),h.amounts.getFirst(),1e-9);
            h.apply(600_000,source,40);assertEquals(67.6*(credit.equals("weapon")?1.22*1.2*1.1:1),h.amounts.getLast(),1e-9);
        }
    }
    @Test void championEligibilityIsObservedSeparatelyForEveryExplosionVictim()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.charge(SolarTest.FIRST,"solar",3);h.radiant(SolarTest.FIRST,10);
        h.views.put("neighbor",new EntityQuery.View(false,false,1000,1000,0,Set.of(D+"champion"),Set.of()));
        h.apply(0,SolarTest.FIRST,100);
        for(int i=0;i<h.damage.size();i++)assertEquals(67.6*1.22*(h.damage.get(i).target().equals("neighbor")?1.3:1.2),h.amounts.get(i),1e-9);
    }
    @Test void laterApplierAndLaterBonusesCannotReplaceTheInitialDamageSnapshot()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.apply(0,SolarTest.FIRST,60);
        h.charge(SolarTest.FIRST,"solar",3);h.radiant(SolarTest.FIRST,10);h.rampage(SolarTest.FIRST);
        h.charge(SolarTest.SECOND,"solar",3);h.radiant(SolarTest.SECOND,10);h.rampage(SolarTest.SECOND);
        h.until(500_000);assertEquals(1.6236,h.amounts.getFirst(),1e-9);
        h.apply(600_000,SolarTest.SECOND,40);assertEquals(67.6,h.amounts.getLast(),1e-9);
        assertEquals(SolarTest.FIRST.origin(),h.damage.getLast().source());
    }
    @Test void spendingChargeRemovingModsAndExpiringRadiantDoNotRewritePendingSolarDamage()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.charge(SolarTest.FIRST,"solar",3);h.radiant(SolarTest.FIRST,.1);h.rampage(SolarTest.FIRST);
        h.apply(0,SolarTest.FIRST,60);
        h.event(SolarTest.FIRST,"test:spend_armor_charge",Map.of("stacks",new Measure(1,Unit.COUNT),"bonus",new Measure(0,Unit.COUNT)),Set.of());
        for(int i=0;i<3;i++)h.remove("solar_weapon_surge/"+i+"/first");h.remove("rampage/first");h.until(500_000);
        assertEquals(1.6236*1.22*1.2*1.1,h.amounts.getFirst(),1e-9);
        h.apply(600_000,SolarTest.SECOND,40);assertEquals(67.6*1.22*1.2*1.1,h.amounts.getLast(),1e-9);
        h.apply(2_200_000,SolarTest.FIRST,60);h.until(2_700_000);assertEquals(1.6236,h.amounts.getLast(),1e-9);
    }
    @Test void wrongElementSurgeDoesNotApplyEvenWhenTheWeaponHasAnIdentity()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.charge(SolarTest.FIRST,"void",3);h.apply(0,SolarTest.FIRST,100);
        h.amounts.forEach(v->assertEquals(67.6,v,1e-9));
    }
    @Test void pvpUsesItsOwnSurgeAndRadiantTablesAndKeepsNonlethalScorch()throws Exception {
        var h=new Harness(EffectState.Mode.PVP);h.views.put("target",SolarTest.view(true,false));h.charge(SolarTest.FIRST,"solar",3);h.radiant(SolarTest.FIRST,10);
        h.apply(0,SolarTest.FIRST,60);h.until(500_000);assertEquals(.7*1.055*1.1,h.amounts.getFirst(),1e-9);assertTrue(h.damage.getFirst().nonLethal());
        h.apply(600_000,SolarTest.SECOND,40);
        for(int i=1;i<h.damage.size();i++)assertEquals((h.damage.get(i).target().equals("target")?12:20)*1.055*1.1,h.amounts.get(i),1e-9);
    }
}
