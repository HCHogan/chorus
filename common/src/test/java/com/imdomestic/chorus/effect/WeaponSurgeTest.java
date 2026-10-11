package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponSurgeTest {
    static final String D="chorus_d2:",PROFILE=D+"empowering_damage";
    static final List<String> ELEMENTS=List.of("kinetic","arc","solar","void","stasis","strand");
    static final double[] PVE={1,1.1,1.17,1.22},PVP={1,1.03,1.045,1.055};
    static class Harness extends ArmorChargeTest.Harness {
        Harness()throws Exception{this(false);}
        Harness(boolean pvp)throws Exception{super("weapon_surge","empowering_damage","radiant","weapon_surge_inputs");bind("surge_inputs","test:surge_inputs","player");if(pvp)session=new EffectSession(p,EffectCheckpointTest.clock(),1,state().withMode(EffectState.Mode.PVP),this::world);}
        void surge(String element,int copies){for(int i=0;i<copies;i++)mod(element+"_weapon_surge",i);}
        void signal(String kind,double duration){session.start(now(),new RuleEngine.Signal("test:surge_"+kind,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));healthy();}
        DamageCommand command(String owner,String element,boolean weapon){return new DamageCommand("enemy",new BuffInstance.Origin(owner,"shot","weapon","",Set.of(D+"void")),10,"minecraft:generic",Set.of("chorus:"+element+"_damage",weapon?"chorus:weapon_damage":"chorus:ability_damage"),Set.of(),false,Optional.of(PROFILE));}
        double damage(String element){return damage(command("player",element,true));}
        double damage(DamageCommand command){return p.outgoing(state(),new DamageCommand(command.target(),command.source(),command.amount(),command.damageType(),command.tags(),command.killTags(),command.nonLethal(),command.scalingProfile(),command.snapshot(),RadiantTest.impact(0)),10).orElseThrow().output().value();}
    }
    @Test void allSixElementsAndBothModesUseCopyTiersAndOnlyTheirMatchingWeaponDamage()throws Exception{
        for(boolean pvp:List.of(false,true))for(String element:ELEMENTS)for(int copies=1;copies<=3;copies++){
            var h=new Harness(pvp);h.surge(element,copies);assertEquals(10,h.damage(element));h.gain(1);assertEquals(10*(pvp?PVP:PVE)[copies],h.damage(element),1e-12);assertEquals(10_000_000,h.deadline());
            for(String other:ELEMENTS)if(!other.equals(element))assertEquals(10,h.damage(other),1e-12);
            assertEquals(10,h.damage(h.command("player",element,false)));assertEquals(10,h.damage(h.command("giver",element,true)));
            h.gain(2);assertEquals(10*(pvp?PVP:PVE)[copies],h.damage(element),1e-12,"charge count changes lifetime, not tier");
        }
    }
    @Test void mixedElementCopiesAreIndependentAndAnInjectedFourthModCannotManufactureTierFour()throws Exception{
        var h=new Harness();h.surge("solar",4);h.surge("arc",2);h.gain(3);assertEquals(12.2,h.damage("solar"),1e-12);assertEquals(11.7,h.damage("arc"),1e-12);assertEquals(10,h.damage("void"));h.unmod("solar_weapon_surge",3);h.unmod("solar_weapon_surge",2);assertEquals(11.7,h.damage("solar"),1e-12);assertEquals(11.7,h.damage("arc"),1e-12);
    }
    @Test void independentTierFourUsesTheHighestSameElementMultiplierInBothModesWithoutNeedingArmorCharge()throws Exception{
        for(boolean pvp:List.of(false,true))for(String element:ELEMENTS){var h=new Harness(pvp);h.signal("four_"+element,2);assertEquals(pvp?10.6:12.5,h.damage(element),1e-12);assertEquals(0,h.charges());h.surge(element,3);h.gain(1);assertEquals(pvp?10.6:12.5,h.damage(element),1e-12);h.until(2_000_000);assertEquals(10*(pvp?PVP:PVE)[3],h.damage(element),1e-12);assertEquals(10_000_000,h.deadline());}
    }
    @Test void maskedLowerTierKeepsItsDecayAndCannotReturnAfterItsOwnChargePoolExpires()throws Exception{
        var h=new Harness();h.surge("solar",3);h.gain(1);h.signal("four_solar",12);h.until(10_000_000);assertEquals(0,h.charges());assertEquals(12.5,h.damage("solar"),1e-12);h.until(12_000_000);assertEquals(10,h.damage("solar"));
    }
    @Test void radiantAndIndependentPerkMultiplyOutsideTheSurgeMaximumGroup()throws Exception{
        var h=new Harness();h.surge("solar",3);h.gain(1);h.signal("radiant",5);h.bind("perk","test:surge_perk","player");assertEquals(10*1.2*1.22*1.1,h.damage("solar"),1e-12);h.signal("four_solar",2);assertEquals(10*1.2*1.25*1.1,h.damage("solar"),1e-12);
        assertEquals(10,h.damage(h.command("player","solar",false)),1e-12);
    }
    @Test void attackCaptureKeepsTheSelectedTierAfterChargeConsumptionCopyRemovalAndHigherTierExpiry()throws Exception{
        var h=new Harness();h.surge("solar",3);h.gain(1);var shot=h.p.captureDamage(h.state(),h.command("player","solar",true));h.signal("four_solar",1);var high=h.p.captureDamage(h.state(),h.command("player","solar",true));h.spend(1);for(int i=0;i<3;i++)h.unmod("solar_weapon_surge",i);h.until(1_000_000);
        assertEquals(12.2,h.p.outgoing(h.state(),shot.command("enemy"),10).orElseThrow().output().value(),1e-12);assertEquals(12.5,h.p.outgoing(h.state(),high.command("enemy"),10).orElseThrow().output().value(),1e-12);assertEquals(10,h.damage("solar"));
    }
    @Test void timeDilationAndOtherPassiveModsShareOneDecayAndRemovingSurgeOnlyRemovesItsOwnBenefit()throws Exception{
        var h=new Harness();h.surge("solar",2);h.mod("health_font",0);h.mod("time_dilation",0);h.gain(2);assertEquals(15_000_000,h.deadline());h.until(5_000_000);h.unmod("solar_weapon_surge",0);h.unmod("solar_weapon_surge",1);assertEquals(15_000_000,h.deadline());assertEquals(10,h.damage("solar"));assertEquals(70,h.stat("health",50));h.until(15_000_000);assertEquals(1,h.charges());
    }
    @Test void removingTheLastSurgeStopsItsPassiveDecayWhileAnotherEnablingModKeepsCharges()throws Exception{
        var h=new Harness();h.mod("charged_up",0);h.surge("solar",1);h.gain(2);h.until(5_000_000);h.unmod("solar_weapon_surge",0);assertTrue(h.buff("armor_charge_decay").isEmpty());h.until(40_000_000);assertEquals(2,h.charges());assertEquals(10,h.damage("solar"));h.surge("solar",1);assertEquals(50_000_000,h.deadline());assertEquals(11,h.damage("solar"),1e-12);
    }
    @Test void sessionCheckpointPreservesIndependentTimersAndCurrentSourceQueriesWithoutCopyCaches()throws Exception{
        var h=new Harness();h.surge("solar",3);h.gain(2);h.signal("four_solar",12);h.until(9_000_000);h.restore();h.until(10_000_000);assertEquals(1,h.charges());assertEquals(12.5,h.damage("solar"),1e-12);h.until(12_000_000);assertEquals(12.2,h.damage("solar"),1e-12);h.until(20_000_000);assertEquals(10,h.damage("solar"));
    }
    @Test void weaponCreditAndActualDamageElementMatterRatherThanOriginalSourceElementOrAbilityName()throws Exception{
        var h=new Harness();h.surge("solar",3);h.gain(1);var weaponEffect=new DamageCommand("enemy",new BuffInstance.Origin("player","incandescent","weapon","",Set.of(D+"void")),10,"minecraft:generic",Set.of("chorus:weapon_damage","chorus:solar_damage",D+"scorch"),Set.of(),false,Optional.of(PROFILE));assertEquals(12.2,h.damage(weaponEffect),1e-12);
        var ability=new DamageCommand("enemy",new BuffInstance.Origin("player","ability","","test:solar",Set.of(D+"solar")),10,"minecraft:generic",Set.of("chorus:solar_damage","chorus:ability_damage"),Set.of(),false,Optional.of(PROFILE));assertEquals(10,h.damage(ability));
    }
    @Test void compositeWeaponFixturePreservesExistingRampageBehaviorApartFromItsProfileDestination()throws Exception{
        var original=EffectTestSupport.json("rampage");var adapted=EffectTestSupport.json("weapon_surge_weapon");assertEquals(original.get("buffs"),adapted.get("buffs"));var bundles=adapted.getAsJsonArray("bundles").deepCopy();bundles.remove(0);
        for(var b:bundles)if(b.getAsJsonObject().has("modifiers"))for(var m:b.getAsJsonObject().getAsJsonArray("modifiers")){m.getAsJsonObject().addProperty("profile","test:weapon_damage");m.getAsJsonObject().addProperty("group","weapon_perk");}
        assertEquals(original.get("bundles"),bundles);
    }
}
