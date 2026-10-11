package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponStatDamageTest {
    static final String D="chorus_d2:",PROFILE=D+"empowering_damage";
    static EffectSource scaling(String owner,String id){return new EffectSource(id,D+"weapon_stat_damage",owner,new BuffInstance.Origin(owner,id,"",""),Set.of());}
    static ImpactData impact(int target){return new ImpactData(Map.of("weapons_stat_target",new Measure(target,Unit.COUNT),"radiant_champion",new Measure(0,Unit.COUNT)));}
    static DamageCommand attack(String owner,String ammo,Set<String> tags){
        return new DamageCommand("enemy",new BuffInstance.Origin(owner,"shot","weapon","ability",ammo.isEmpty()?Set.of():Set.of(D+ammo+"_ammo")),100,"minecraft:generic",tags,Set.of(),false,Optional.of(PROFILE));
    }
    static DamageCommand measured(DamageCommand c,int target){return new DamageCommand(c.target(),c.source(),c.amount(),c.damageType(),c.tags(),c.killTags(),c.nonLethal(),c.scalingProfile(),c.snapshot(),impact(target),c.shieldScalingProfile(),c.consumptions(),c.reactions(),c.proc(),c.batch(),c.group());}
    static double expected(double s,String ammo,int target){return 100*(1+switch(target){case 1->.0015*Math.min(s,100);case 2->(ammo.equals("heavy")?.001:.0015)*Math.max(s-100,0);case 3->.0005*Math.max(s-100,0);default->0;});}
    static class Harness extends ArmorChargeTest.Harness {
        Harness()throws Exception{this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode)throws Exception{
            super("armor_stats","armor_stat_inputs","weapon_stat_damage","empowering_damage","weapon_surge","radiant","weapon_surge_inputs");
            if(mode!=state().mode())session=new EffectSession(p,EffectCheckpointTest.clock(),1,state().withMode(mode),this::world);
            equip(scaling("player","scaling"));bind("surge_inputs","test:surge_inputs","player");
        }
        void armor(String owner,double s){session.start(now(),new EquipmentChange(owner,state().equipment().getOrDefault(owner,Loadout.EMPTY),ArmorStatInputsTest.one(owner+"-armor",s)).signal());healthy();}
        double damage(String ammo,int target){return damage(attack("player",ammo,Set.of("chorus:weapon_damage")),target);}
        double damage(DamageCommand c,int target){return p.outgoing(state(),measured(c,target),c.amount()).orElseThrow().output().value();}
        void radiant(){session.start(now(),new RuleEngine.Signal("test:surge_radiant",new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("duration",new Measure(10,Unit.SECOND)))));healthy();}
    }
    @Test void continuousStatAmmoAndRecipientMatrixUsesActualRecipientInsteadOfActivityMode()throws Exception {
        for(var mode:EffectState.Mode.values()) {
            var h=new Harness(mode);
            for(double s:List.of(0.,1.,99.,100.,101.,150.,199.,200.)) {
                h.armor("player",s);
                for(String ammo:List.of("primary","special","heavy"))for(int target=0;target<=3;target++)
                    assertEquals(expected(s,ammo,target),h.damage(ammo,target),1e-10,mode+"/"+ammo+"/"+s+"/"+target);
            }
        }
    }
    @Test void onlyTheAttackOwnersWeaponsAttributeContributes()throws Exception {
        var h=new Harness();h.armor("player",0);h.armor("giver",200);h.equip(scaling("giver","other-scaling"));
        assertEquals(100,h.damage("primary",2));
        assertEquals(115,h.damage(attack("giver","primary",Set.of("chorus:weapon_damage")),2),1e-10);
    }
    @Test void weaponIdentityCannotInventCreditAndContradictoryAmmoOrAbilityTagsDoNotQualify()throws Exception {
        var h=new Harness();h.armor("player",200);
        for(String credit:List.of("ability_damage","grenade_damage","melee_damage","super_damage","class_damage","glaive_melee"))
            assertEquals(100,h.damage(attack("player","primary",Set.of("chorus:weapon_damage","chorus:"+credit)),1));
        assertEquals(100,h.damage(attack("player","primary",Set.of()),1));assertEquals(100,h.damage("",1));
        var c=attack("player","primary",Set.of("chorus:weapon_damage"));
        var conflict=new DamageCommand(c.target(),new BuffInstance.Origin("player","shot","weapon","",Set.of(D+"primary_ammo",D+"heavy_ammo")),100,c.damageType(),c.tags(),Set.of(),false,c.scalingProfile());
        assertEquals(100,h.damage(conflict,2));
    }
    @Test void allArmorSourcesSumBeforeTheCapAndDuplicateScalingProvidersDoNotMultiply()throws Exception {
        var h=new Harness();var gear=new Loadout(Map.of(D+"arms",ArmorStatInputsTest.gear("arms","arms",150),D+"helmet",ArmorStatInputsTest.gear("helmet","helmet",150)),Optional.empty());
        h.session.start(0,new EquipmentChange("player",Loadout.EMPTY,gear).signal());h.equip(scaling("player","duplicate"));var state=h.state();
        assertEquals(115,h.damage("primary",2),1e-10);assertSame(state,h.state());
    }
    @Test void sourceStatAndAmmoAreCapturedWhileEveryVictimSelectsItsOwnCurrentBracket()throws Exception {
        var h=new Harness();h.armor("player",100);var saved=h.p.captureDamage(h.state(),attack("player","heavy",Set.of("chorus:weapon_damage")));
        h.armor("player",200);
        assertEquals(115,h.damage(saved.command("enemy"),1),1e-10);assertEquals(100,h.damage(saved.command("enemy"),2));assertEquals(100,h.damage(saved.command("enemy"),3));
        assertEquals(110,h.damage("heavy",2),1e-10);assertEquals(105,h.damage("heavy",3),1e-10);
        h.remove("scaling");assertEquals(115,h.damage(saved.command("enemy"),1),1e-10);assertEquals(100,h.damage("heavy",1));
    }
    @Test void weaponsFontsShareChargeWithSurgesAndStatFactorStaysSeparateFromRadiantAndPerks()throws Exception {
        var h=new Harness();h.armor("player",150);for(int i=0;i<3;i++){h.mod("weapons_font",i);h.mod("solar_weapon_surge",i);}h.gain(1);h.radiant();h.bind("perk","test:surge_perk","player");
        var c=attack("player","primary",Set.of("chorus:weapon_damage","chorus:solar_damage"));
        assertEquals(100*1.15*1.22*1.2*1.1,h.damage(c,2),1e-9);var saved=h.p.captureDamage(h.state(),c);h.spend(1);
        assertEquals(100*1.075*1.2*1.1,h.damage(c,2),1e-9);assertEquals(100*1.15*1.22*1.2*1.1,h.damage(saved.command("enemy"),2),1e-9);
    }
    @Test void namedStatFactorCanBeOmittedWithoutErasingOtherContributionsAndHeavyConflictIsVisible()throws Exception {
        var h=new Harness();h.armor("player",150);h.radiant();var c=measured(attack("player","heavy",Set.of("chorus:weapon_damage")),1);
        var result=h.p.outgoing(h.state(),c,100).orElseThrow();assertEquals(138,result.output().value(),1e-10);
        assertTrue(result.contributionConfidence().contains(NumericContribution.Confidence.CONFLICT));
        assertEquals(120,result.withoutFactors(Set.of(D+"weapons_stat")).output().value(),1e-10);
        assertFalse(h.p.outgoing(h.state(),measured(attack("player","heavy",Set.of("chorus:weapon_damage")),2),100).orElseThrow().contributionConfidence().contains(NumericContribution.Confidence.CONFLICT));
    }
    @Test void sessionAndAttackRoundTripsKeepAttributeStateAndPinnedSourceValues()throws Exception {
        var h=new Harness();h.armor("player",150);var saved=h.p.captureDamage(h.state(),attack("player","special",Set.of("chorus:weapon_damage")));
        var restored=EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(saved),DamageSnapshot.class);assertEquals(saved,restored);h.restore();h.armor("player",0);
        assertEquals(107.5,h.damage(restored.command("enemy"),2),1e-10);assertEquals(100,h.damage("special",2));
    }
    @Test void actualSolarGenerationKeepsInitialAmmoAndStatAfterGearChangesAndASecondApplier()throws Exception {
        var h=new SolarDamageScalingTest.Harness(EffectState.Mode.PVE,"weapon_stat_damage","armor_stats","armor_stat_inputs");
        h.bind(scaling("first","stat"));h.session.start(0,new EquipmentChange("first",Loadout.EMPTY,ArmorStatInputsTest.one("first-armor",150)).signal());
        var original=SolarTest.FIRST.origin();var source=new EffectSource("first","test:scorch_source","first",new BuffInstance.Origin("first",original.source(),original.weapon(),original.ability(),Set.of("chorus:weapon_damage",D+"special_ammo")),Set.of());h.bind(source);
        h.views.put("target",new EntityQuery.View(true,false,1000,1000,0,Set.of(D+"rank_and_file"),Set.of()));h.views.put("neighbor",new EntityQuery.View(true,false,1000,1000,0,Set.of(D+"boss"),Set.of()));
        h.apply(0,source,60);h.session.start(0,new EquipmentChange("first",h.state().equipment().get("first"),Loadout.EMPTY).signal());h.until(500_000);
        assertEquals(1.6236*1.15,h.amounts.getFirst(),1e-9);h.apply(600_000,SolarTest.SECOND,40);assertEquals(3,h.damage.size());
        for(int i=1;i<h.damage.size();i++)assertEquals(67.6*(h.damage.get(i).target().equals("neighbor")?1.075:1.15),h.amounts.get(i),1e-9);
    }
}
