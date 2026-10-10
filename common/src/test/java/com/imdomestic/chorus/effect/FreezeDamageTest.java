package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FreezeDamageTest {
    static CompiledEffects program()throws Exception{return link("freeze","freeze_test_falloff","combat_damage","one_two_punch","strand_defense");}
    static EffectState frozen(CompiledEffects p,EffectState.Mode mode,int tier){return grant(p,EffectState.empty().withMode(mode),"chorus_d2:freeze","victim",new BuffInstance.Origin("freezer","freeze","",""),tier);}
    static EffectState grant(CompiledEffects p,EffectState s,String buff,String holder,BuffInstance.Origin origin,int tier){return s.withBuffs(Buffs.grant(s.buffs(),p.buff(buff),holder,holder,origin,1,tier,3_000_000).store());}
    static DamageCommand command(String victim,String...tags){return new DamageCommand(victim,new BuffInstance.Origin("attacker","hit","gun","ability"),100,"test:damage",Set.of(tags),Set.of(),false,Optional.of("chorus_d2:outgoing"));}
    static double damage(CompiledEffects p,EffectState s,String...tags){return p.outgoing(s,command("victim",tags),100).orElseThrow().output().value();}
    static EffectState oneTwo(CompiledEffects p,EffectState s,boolean handCannon){return grant(p,s,"chorus_d2:one_two_punch","attacker",new BuffInstance.Origin("attacker","perk","gun","",Set.of(handCannon?"chorus_d2:hand_cannon":"chorus_d2:shotgun")),1);}
    @Test void weaponCategoriesAndLightAbilitiesUseIndependentFrozenFactors()throws Exception{
        var p=program();for(var mode:EffectState.Mode.values())for(int tier:List.of(1,2,3,4,5,6)){
            var s=frozen(p,mode,tier);assertEquals(mode==EffectState.Mode.PVP?40:95,damage(p,s,"chorus:weapon_damage","chorus:primary_ammo"),1e-9);
            for(String ammo:List.of("special_ammo","heavy_ammo"))assertEquals(110,damage(p,s,"chorus:weapon_damage","chorus:"+ammo),1e-9);
            for(String element:List.of("arc","solar","void"))assertEquals(105,damage(p,s,"chorus:ability_damage","chorus:"+element),1e-9);
            for(String element:List.of("stasis","strand","kinetic"))assertEquals(100,damage(p,s,"chorus:ability_damage","chorus:"+element));
        }
    }
    @Test void basicAndGlaiveBonusUsesRecipientTierAndCanCombineWithLightAbilityFactor()throws Exception{
        var p=program();for(int tier:List.of(1,2,3,4,5,6))for(String kind:List.of("basic_melee","glaive_melee")){
            var s=frozen(p,EffectState.Mode.PVE,tier);double expected=tier==2||tier==3?100:220;
            assertEquals(expected,damage(p,s,"chorus:melee_damage","chorus:"+kind),1e-9);assertEquals(expected*1.05,damage(p,s,"chorus:melee_damage","chorus:"+kind,"chorus:ability_damage","chorus:arc"),1e-9);
        }
    }
    @Test void oneTwoAndActualTargetFreezeShareMaxInsteadOfMultiplyingSeparateProfiles()throws Exception{
        var p=program();for(var mode:EffectState.Mode.values())for(boolean handCannon:List.of(false,true)){
            double perk=handCannon?(mode==EffectState.Mode.PVP?.5:.75):(mode==EffectState.Mode.PVP?1:1.5);
            var s=oneTwo(p,frozen(p,mode,1),handCannon);assertEquals(100*(1+Math.max(1.2,perk)),damage(p,s,"chorus:melee_damage","chorus:basic_melee"),1e-9);
            var boss=oneTwo(p,frozen(p,mode,3),handCannon);assertEquals(100*(1+perk),damage(p,boss,"chorus:melee_damage","chorus:basic_melee"),1e-9);
            var result=p.outgoing(s,command("victim","chorus:melee_damage","chorus:basic_melee"),100).orElseThrow();assertEquals(2,result.trace().contributions().size());assertEquals(1,result.trace().contributions().stream().filter(c->c.selected()).count());
        }
    }
    @Test void capturedAttackReadsCurrentFreezeAndThawIndependentlyForEachVictim()throws Exception{
        var p=program();var launch=frozen(p,EffectState.Mode.PVE,1);var shot=p.captureDamage(launch,command("victim","chorus:weapon_damage","chorus:special_ammo"));assertTrue(shot.contributions().isEmpty());
        assertEquals(100,p.outgoing(EffectState.empty(),shot.command("victim"),100).orElseThrow().output().value());assertEquals(110,p.outgoing(launch,shot.command("victim"),100).orElseThrow().output().value(),1e-9);assertEquals(100,p.outgoing(launch,shot.command("neighbor"),100).orElseThrow().output().value());
    }
    @Test void freezeFactorThenTargetResistanceComposeWithoutLosingEitherProvider()throws Exception{
        var p=program();var s=grant(p,frozen(p,EffectState.Mode.PVE,4),"chorus_d2:woven_mail","victim",new BuffInstance.Origin("protector","mail","",""),1);var d=command("victim","chorus:weapon_damage","chorus:special_ammo");var outgoing=p.outgoing(s,d,100).orElseThrow();assertEquals(110,outgoing.output().value(),1e-9);assertEquals(60.5,p.defense(s,d,outgoing.output().value()).orElseThrow().output().value(),1e-9);
    }
    @Test void unclassifiedAndConflictingInputsDoNotGuessWeaponOrMeleeQualifications()throws Exception{
        var p=program();var s=frozen(p,EffectState.Mode.PVE,1);
        for(var tags:List.of(List.of("chorus:primary_ammo"),List.of("chorus:arc"),List.of("chorus:melee_damage"),List.of("chorus:basic_melee"),List.of("chorus:melee_damage","chorus:basic_melee","chorus:glaive_melee"),List.of("chorus:weapon_damage","chorus:primary_ammo","chorus:special_ammo"),List.of("chorus:weapon_damage","chorus:special_ammo","chorus:heavy_ammo")))assertEquals(100,damage(p,s,tags.toArray(String[]::new)));
        assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
    }
}
