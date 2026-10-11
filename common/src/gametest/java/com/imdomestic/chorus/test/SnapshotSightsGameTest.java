package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.WeaponAimGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.WeaponAim;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** Published ADS fits feed ordinary player commands and the existing physical fire/collision path. */
public class SnapshotSightsGameTest {
    static final String D="chorus_d2:";
    static ProjectileGameTest.Harness open(GameTestHelper h,String ammo,boolean enhanced,boolean surplus)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"weapon_ads_weapon",data->{
            RadiantGameTest.merge(data,"box_breathing","weapon_stats","snapshot_sights","weapon_ads_curves","amplified","weapon_stat_handling_inputs","surplus","wellspring_targets","character_stats","weapon_stat_handling");
            SolarFragmentStatsGameTest.armor(data);
        },true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+2.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+2.5);t.owner.setXRot(0);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);
            var choices=new HashMap<String,String>();choices.put("perk","normal");choices.put("snapshot",enhanced?"enhanced":"normal");if(surplus)choices.put("surplus","normal");
            String item=ammo.equals("primary")?"test:rifle":"test:"+ammo+"_rifle";
            var gear=new Loadout.Gear("a",item,choices,ammo.equals("heavy")?Map.of("snapshot_ads_delta",new Measure(-.2,Unit.DELTA)):Map.of());
            swap(t,gear);draw(t,"primary");bind(t,"test:aim_observer",true);bind(t,"test:amplified_inputs",true);bind(t,"test:wellspring_input",true);bind(t,D+"weapon_stat_handling",true);return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static void swap(ProjectileGameTest.Harness t,Loadout.Gear gear){var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),gear);t.owner.getInventory().setItem(0,stack);var equipment=PlayerEquipment.get(t.owner);equipment.swap(t.owner,"test:primary",0,equipment.revision());}
    static int remaining(ProjectileGameTest.Harness t){return Math.toIntExact((aim(t).dueAt()-state(t).buffs().timeMicros()+49_999)/50_000);}
    static double stability(ProjectileGameTest.Harness t){var origin=new BuffInstance.Origin(holder(t),"query","a","");return t.runtime.program().calculate(state(t),holder(t),new EffectEvent(holder(t),"a",origin,Set.of(),Map.of()),D+"weapon_stability",new Measure(10,Unit.STAT_POINT),List.of()).output().value();}
    static void primary(GameTestHelper h,boolean enhanced)throws Exception {
        var t=open(h,"primary",enhanced,false);try {input(t,true);near(h,aim(t).duration().value(),.1855,"primary curve times Snapshot");near(h,stability(t),enhanced?15:10,"separate enhanced Stability query");
        at(t,3,()->{settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.ENTERING,"early ready");});
        at(t,4,()->{settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"accepted microsecond deadline not observed");h.assertValueEqual(aim(t).dueAt(),185_500L,"curve deadline rounded to tick before acceptance");input(t,false);near(h,aim(t).duration().value(),.1,"Snapshot changed exit input");
            at(t,2,()->{try(t){settled(t);h.assertTrue(aim(t)==null,"exit retained plan");h.succeed();}});
        });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:snapshot_normal",maxTicks=30)
    public void NormalPrimaryUsesThePublishedCurveAndOnlyTheEnterScalar(GameTestHelper h)throws Exception{primary(h,false);}
    @GameCase(environment="chorus_gametest:snapshot_enhanced",maxTicks=30)
    public void EnhancedPrimaryKeepsAdsTimeAndAddsFiveStability(GameTestHelper h)throws Exception{primary(h,true);}
    @GameCase(environment="chorus_gametest:snapshot_special",maxTicks=30)
    public void SpecialWeaponUsesPointEightWithItsOwnSniperCurve(GameTestHelper h)throws Exception{
        var t=open(h,"special",false,false);try {input(t,true);near(h,aim(t).duration().value(),(.5021325414-.00194998437*10)*.8,"special curve and chosen community scalar");int ticks=remaining(t);
        at(t,ticks-1,()->{settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.ENTERING,"special became ready early");});
        at(t,ticks,()->{try(t){settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"special never completed");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:snapshot_power",maxTicks=35)
    public void PowerParameterIsExplicitAndMissingCalibrationCannotTransferOwnership(GameTestHelper h)throws Exception{
        var t=open(h,"heavy",false,false);try {input(t,true);var old=aim(t);near(h,old.duration().value(),(.5021325414-.00194998437*10)*.8,"explicit test-only power hypothesis");
        var e=PlayerEquipment.get(t.owner);var owned=e.snapshot();var gear=owned.items().get("test:primary").get(ChorusComponents.EQUIPMENT.get());boolean rejected=false;
        try{swap(t,new Loadout.Gear("bad","test:heavy_rifle",gear.choices()));}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"missing calibration accepted");var after=e.snapshot();
        h.assertValueEqual(after.revision(),owned.revision(),"rejected calibration changed revision");h.assertValueEqual(after.drawn(),owned.drawn(),"rejected calibration changed selection");h.assertValueEqual(after.items().keySet(),owned.items().keySet(),"rejected calibration changed slots");
        for(var slot:owned.items().keySet())h.assertTrue(ItemStack.matches(after.items().get(slot),owned.items().get(slot)),"rejected calibration moved physical ownership: "+slot);
        h.assertValueEqual(t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get()).instance(),"bad","rejected calibration removed inventory item");h.assertValueEqual(aim(t),old,"rejected calibration cancelled old phase");
        swap(t,new Loadout.Gear("b","test:heavy_rifle",gear.choices(),Map.of("snapshot_ads_delta",new Measure(-.5,Unit.DELTA))));draw(t,"primary");
        h.assertValueEqual(t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get()),gear,"replacement did not return the old physical weapon");
        h.assertTrue(aim(t)==null,"parameterized weapon replacement retained old aim");input(t,true);near(h,aim(t).duration().value(),(.5021325414-.00194998437*10)*.5,"fresh parameter did not reach timing");
        at(t,remaining(t),()->{try(t){settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"parameterized phase did not complete");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:snapshot_stacking",maxTicks=35)
    public void ChargedSurplusAndAmplifiedComposeWhileArmorWeaponsLeavesAdsUnchanged(GameTestHelper h)throws Exception{
        var t=open(h,"primary",false,true);try {
        t.runtime.abilities(new AbilityChange(holder(t),AbilityLoadout.EMPTY,new AbilityLoadout(Map.of(D+"grenade","test:grenade",D+"melee","test:melee_double",D+"class","test:class"))));
        t.runtime.start(new RuleEngine.Signal("test:fill",new EffectEvent(holder(t),holder(t),new BuffInstance.Origin(holder(t),"input","",""),Set.of(),Map.of("grenade",new Measure(1,Unit.CHARGE),"melee",new Measure(1,Unit.CHARGE),"class",new Measure(1,Unit.CHARGE)))));
        WeaponStatHandlingGameTest.amplify(t,.05);input(t,true);var accepted=aim(t);near(h,accepted.duration().value(),(.38975-.001875*100)*.95*.5,"combined stat cap and scalar");
        WeaponStatReloadGameTest.armor(t,200);h.assertValueEqual(aim(t),accepted,"armor changed accepted time");
        at(t,1,()->{settled(t);h.assertValueEqual(aim(t),accepted,"expired Amplified moved deadline");});
        at(t,2,()->{settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"sub-100ms duration incorrectly floored");input(t,false);at(t,2,()->{try(t){input(t,true);near(h,aim(t).duration().value(),(.38975-.001875*70)*.5,"new entry reads remaining Surplus without Weapons duration");h.succeed();}});});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:snapshot_restore",maxTicks=30)
    public void RestoredStatPointPipelineFinishesAtItsOriginalMicrosecondDeadline(GameTestHelper h)throws Exception{
        var t=open(h,"primary",true,false);try {input(t,true);at(t,3,()->{
            settled(t);var accepted=aim(t);var saved=t.runtime.checkpoint();var checkpoint=new MinecraftEffectRuntime.Checkpoint(saved.dimension(),saved.gameTime(),EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(saved.effects())),saved.equipmentOwners());var program=t.runtime.program();int count=t.cues.size();t.runtime.close();
            var resumed=MinecraftEffectRuntime.restore(h.getLevel(),program,checkpoint,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource);
            try{h.assertValueEqual(t.cues.size(),count,"restore replayed aim fact");h.assertValueEqual(resumed.state().engine().domain().aiming().get(holder(t)),accepted,"restore changed stat input or trace");h.runAfterDelay(1,()->{try(resumed;t){resumed.prepare();h.assertValueEqual(resumed.state().engine().domain().aiming().get(holder(t)).phase(),WeaponAim.Phase.AIMED,"saved curve deadline lost");h.assertTrue(resumed.failure().isEmpty(),"resumed runtime failed");h.succeed();}});}catch(Exception|Error error){resumed.close();t.close();throw error;}
        });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:snapshot_box",maxTicks=50)
    public void FasterAdsDoesNotShortenBoxBreathingAndTheRealProjectileKeepsItsCapturedBonus(GameTestHelper h)throws Exception{
        var t=open(h,"primary",false,false);try {input(t,true);
        at(t,4,()->{settled(t);h.assertValueEqual(aim(t).phase(),WeaponAim.Phase.AIMED,"Snapshot readiness");h.assertTrue(!BoxBreathingGameTest.active(t),"ready granted Box Breathing early");});
        at(t,29,()->{settled(t);h.assertTrue(!BoxBreathingGameTest.active(t),"Box Breathing timer started before input");});
        at(t,30,()->{try(t){settled(t);h.assertTrue(BoxBreathingGameTest.active(t),"Box Breathing wait not retained");var target=BoxBreathingGameTest.victim(t,true);var shot=PugilistGameTest.fire(t);input(t,false);BoxBreathingGameTest.impact(t,shot,target,28);near(h,target.getHealth(),72,"actual captured shot after release");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
}
