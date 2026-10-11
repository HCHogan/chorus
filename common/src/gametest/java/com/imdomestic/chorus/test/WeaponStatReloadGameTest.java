package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.SurplusGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Character stat duration scaling is consumed by accepted native weapon reload timelines. */
public class WeaponStatReloadGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h,boolean incremental)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"surplus",data->{
            prepare(data);RadiantGameTest.merge(data,"character_stats","weapon_stat_reload","weapon_stat_reload_inputs","armor_charge","armor_charge_mods","armor_charge_inputs","orb_of_power");
            SolarFragmentStatsGameTest.armor(data);
            for(var p:ThreadedSpikeGameTest.json("starvation_inputs").getAsJsonArray("profiles"))
                if(p.getAsJsonObject().get("id").getAsString().equals("chorus_d2:orb_of_power_base_energy"))data.getAsJsonArray("profiles").add(p);
            if(incremental) {
                var reload=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload");var repeat=reload.deepCopy();var insert=new JsonObject();
                insert.add("rounds",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"round\"}"));insert.add("repeat",repeat);reload.add("insert",insert);
            }
        },true);
        try {start(t,false,1,1,1);IncandescentGameTest.bind(t,"weapons_reload","chorus_d2:weapon_stat_reload","");
            t.runtime.bind(ArmorChargeGameTest.system(owner(t)));IncandescentGameTest.bind(t,"armor_inputs","test:armor_charge_inputs","");return t;
        }catch(Exception|Error e){t.close();throw e;}
    }
    static void armor(ProjectileGameTest.Harness t,double points){SolarFragmentStatsGameTest.equipArmor(t,"armor/"+UUID.randomUUID(),"arms",points);}
    static com.imdomestic.chorus.effect.weapon.WeaponReload.Plan plan(ProjectileGameTest.Harness t){return state(t).reloads().get(owner(t));}
    static int magazine(ProjectileGameTest.Harness t){return state(t).ammunition().get("a").magazine();}
    @GameCase(environment="chorus_gametest:weapon_stat_reload_timing",maxTicks=85)
    public void actualArmorAndSurplusComposeAfterTheCurveAndExistingReloadKeepsItsDeadline(GameTestHelper h)throws Exception {
        var t=open(h,false);try {armor(t,100);spend(t);reload(t);var first=plan(t);near(h,first.duration().value(),1.17,"Weapons time scalar follows Surplus and archetype curve");
            armor(t,0);use(t,"melee");h.assertValueEqual(plan(t),first,"armor and perk changes cannot move an accepted deadline");
            RampageGameTest.at(t,22,()->h.assertValueEqual(magazine(t),4,"not before old deadline"));
            RampageGameTest.at(t,25,()->{h.assertValueEqual(magazine(t),5,"old scaled reload completed");spend(t);reload(t);near(h,plan(t).duration().value(),1.65,"new reload reads current armor and remaining charges");});
            t.finish(60,()->{h.assertValueEqual(magazine(t),5,"second physical transfer complete");h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(),28,"two reserves paid");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_reload_font",maxTicks=75)
    public void actualOrbFontAndChargeSpendChangeOnlyTheNextReload(GameTestHelper h)throws Exception {
        var t=open(h,false);try {armor(t,50);for(int i=0;i<3;i++)ArmorChargeGameTest.mod(t,"weapons_font",i);ArmorChargeGameTest.orb(t);
            near(h,SolarFragmentStatsGameTest.points(t,"weapons"),100,"real Orb activates fifty Font points");spend(t);reload(t);var first=plan(t);near(h,first.duration().value(),1.17,"Font reaches maximum duration benefit");ArmorChargeGameTest.spend(t,1);
            near(h,SolarFragmentStatsGameTest.points(t,"weapons"),50,"actual charge payment removes Font points");h.assertValueEqual(plan(t),first,"pending plan remains accepted");
            RampageGameTest.at(t,25,()->{h.assertValueEqual(magazine(t),5,"first transfer at captured rate");spend(t);reload(t);near(h,plan(t).duration().value(),1.235,"next reload has only armor's five percent duration reduction");});
            t.finish(52,()->{h.assertValueEqual(magazine(t),5,"second transfer complete");h.assertValueEqual(ArmorChargeGameTest.charges(t),0,"reload cannot restore spent charge");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_reload_insert",maxTicks=95)
    public void everyRealInsertionKeepsItsAcceptedTimingAndTheNextReadsCurrentArmor(GameTestHelper h)throws Exception {
        var t=open(h,true);try {armor(t,100);for(int i=0;i<3;i++)spend(t);reload(t);armor(t,0);
            RampageGameTest.at(t,22,()->h.assertValueEqual(magazine(t),2,"initial insertion still waiting"));
            RampageGameTest.at(t,25,()->{h.assertValueEqual(magazine(t),3,"one round physically loaded");near(h,plan(t).duration().value(),1.3,"second step read zero armor");armor(t,100);});
            RampageGameTest.at(t,51,()->{h.assertValueEqual(magazine(t),4,"second insertion kept its old rate");near(h,plan(t).duration().value(),1.17,"third step read restored armor");});
            t.finish(75,()->{h.assertValueEqual(magazine(t),5,"three insertions filled the magazine");h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(),27,"exactly three reserve rounds transferred");h.assertTrue(state(t).reloads().isEmpty(),"full magazine stops repetition");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_stat_reload_curves")
    public void twoPhysicalWeaponsUseTheirOwnPerkAndCurveWithTheSameCharacterMultiplier(GameTestHelper h)throws Exception {
        try(var t=open(h,false)) {armor(t,200);spend(t);reload(t);near(h,plan(t).duration().value(),1.17,"rifle has Surplus and capped Weapons bonus");
            PugilistGameTest.draw(t,"secondary");h.assertTrue(state(t).reloads().isEmpty(),"switch cancels pending reload");
            t.runtime.start(new com.imdomestic.chorus.rule.RuleEngine.Signal("test:spend_ammo",new EffectEvent(owner(t),"b",new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of())));
            reload(t);near(h,plan(t).duration().value(),2.52,"shotgun curve has no other weapon's Surplus");h.assertValueEqual(magazine(t),4,"cancelled rifle cannot receive ammunition early");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_stat_reload_unknown",maxTicks=40)
    public void unknownReactionAfterScaledReloadKeepsTransferredAmmoAndAppliedHealing(GameTestHelper h)throws Exception {
        var t=open(h,false);try {armor(t,100);IncandescentGameTest.bind(t,"probe","test:weapons_reload_probe","a");spend(t);reload(t);float hp=t.owner.getHealth();t.failAfterHealing=true;
            h.runAfterDelay(25,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown healing receipt stops runtime");h.assertValueEqual(magazine(t),5,"actual ammunition transfer remains committed");
                h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(),29,"reserve payment remains committed");near(h,t.owner.getHealth(),hp+1,"reload reaction actually healed once");
                int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"unknown reload reaction is never replayed");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
