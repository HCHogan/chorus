package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class RicochetRoundsGameTest {
    static void equip(ProjectileGameTest.Harness t,String id,String slot,String variant,boolean piercing){
        var choices=new HashMap<String,String>();choices.put("magazine",variant);if(piercing)choices.put("penetration","on");
        var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(id,"test:rifle",choices,Map.of("ricochet_bounces",new Measure(1,Unit.COUNT))));
        t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:"+slot,0,e.revision());
    }
    static ProjectileGameTest.Harness open(GameTestHelper h,String variant,boolean piercing)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"ricochet_rounds_weapon",d->{
            RadiantGameTest.merge(d,"ricochet_rounds","armor_piercing_rounds","weapon_ballistics","weapon_stats","weapon_range_curves","weapon_range_test_calibration","armor_piercing_test_targets");
            for(var v:d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire")){
                var s=v.getAsJsonObject();if(s.has("action") && s.getAsJsonObject("action").get("type").getAsString().equals("chorus:calculate_pipeline"))s.getAsJsonObject("action").getAsJsonObject("input").addProperty("value",0);
            }
        },true);
        try{t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+2.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+2.5);t.owner.setYRot(-45);t.owner.setXRot(0);t.owner.setGameMode(GameType.SURVIVAL);t.owner.getFoodData().setFoodLevel(0);
            equip(t,"a","primary",variant,piercing);equip(t,"b","secondary","enhanced",false);PugilistGameTest.draw(t,"primary");
            String owner=t.owner.getUUID().toString();t.runtime.bind(new EffectSource("shield-input","test:ap_shields",owner,new BuffInstance.Origin(owner,"shield-input","",""),Set.of()));return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static double stat(ProjectileGameTest.Harness t,String name,String weapon){String id=t.owner.getUUID().toString();return t.runtime.program().calculate(PrecisionAmmoGameTest.state(t),id,new EffectEvent(id,weapon,new BuffInstance.Origin(id,"query",weapon,""),Set.of(),Map.of()),"chorus_d2:weapon_"+name,new Measure(50,Unit.STAT_POINT),List.of()).output().value();}
    @GameCase(environment="chorus_gametest:ricochet_perk_damage")
    public void bothVariantsChangeEquippedStatsAndActualReflectedFalloff(GameTestHelper h)throws Exception {
        for(String variant:List.of("normal","enhanced"))try(var t=open(h,variant,false)){
            RicochetHitscanGameTest.wall(t);var first=RicochetHitscanGameTest.target(t,5,10);var second=RicochetHitscanGameTest.target(t,3,12);ArmorPiercingRoundsGameTest.fire(t);
            double distance=(10-first.getBbWidth()/2.0)*Math.sqrt(2),start=variant.equals("normal")?11.375:11.5;
            near(h,first.getHealth(),100-WeaponRangeFalloffGameTest.expected(distance,start,40,.5),"real reflected damage "+variant);near(h,second.getHealth(),100,"ricochet does not grant penetration");
            near(h,stat(t,"range","a"),variant.equals("normal")?55:56,"equipped range");near(h,stat(t,"stability","a"),variant.equals("normal")?60:61,"equipped stability");near(h,stat(t,"stability","other"),50,"other weapon isolation");
            near(h,t.hits.getFirst().impact().number("distance",Unit.METER).value(),distance,"travel distance");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"one payment");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"group leaked");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ricochet_perk_switch")
    public void changingTheDrawnWeaponEnablesReflectionWithoutBorrowingItsPerk(GameTestHelper h)throws Exception {
        var t=open(h,"none",false);
        try{RicochetHitscanGameTest.wall(t);var target=RicochetHitscanGameTest.target(t,5,10);ArmorPiercingRoundsGameTest.fire(t);near(h,target.getHealth(),100,"unmodified gun stops at wall");near(h,stat(t,"stability","a"),50,"no borrowed enhanced stability");
            PugilistGameTest.draw(t,"secondary");
            RampageGameTest.at(t,5,()->{try{ArmorPiercingRoundsGameTest.fire(t);h.assertValueEqual(t.hits.size(),1,"only enhanced weapon hit");h.assertTrue(target.getHealth()<100,"enhanced weapon did not reflect");h.assertValueEqual(t.hits.getFirst().source().weapon(),"b","correct weapon credit");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"first weapon payment");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"b"),9,"second weapon payment");h.succeed();}finally{t.close();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:ricochet_perk_layers")
    public void reflectionAndPenetrationComposeWithQualifiedShieldScaling(GameTestHelper h)throws Exception {
        try(var t=open(h,"normal",true)){
            RicochetHitscanGameTest.wall(t);var first=RicochetHitscanGameTest.target(t,5,10);var second=RicochetHitscanGameTest.target(t,3,12);
            ArmorPiercingRoundsGameTest.shield(t,first,"elemental");ArmorPiercingRoundsGameTest.shield(t,second,"guardian");ArmorPiercingRoundsGameTest.fire(t);
            double one=(10-first.getBbWidth()/2.0)*Math.sqrt(2),two=(12-second.getBbWidth()/2.0)*Math.sqrt(2);
            near(h,first.getHealth(),100-WeaponRangeFalloffGameTest.expected(one,12,40,.5)+5,"qualified shield input budget");near(h,second.getHealth(),100-WeaponRangeFalloffGameTest.expected(two,12,40,.5)+5.25,"unqualified shield input budget");
            near(h,t.receipts.getFirst().shields().getFirst().attackScaling().orElseThrow().output().value(),1.05,"first layer bonus");near(h,t.receipts.getLast().shields().getFirst().attackScaling().orElseThrow().output().value(),1,"second layer no bonus");
            h.assertValueEqual(t.hits.size(),2,"two real targets");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"single payment");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"group not settled");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:ricochet_perk_shield")
    public void ricochetAloneDoesNotBorrowArmorPiercingsShieldBonus(GameTestHelper h)throws Exception {
        try(var t=open(h,"enhanced",false)){
            RicochetHitscanGameTest.wall(t);var target=RicochetHitscanGameTest.target(t,5,10);ArmorPiercingRoundsGameTest.shield(t,target,"elemental");ArmorPiercingRoundsGameTest.fire(t);
            near(h,t.receipts.getFirst().shields().getFirst().attackScaling().orElseThrow().output().value(),1,"no AP source installed");near(h,t.receipts.getFirst().shieldLoss(),5.25,"shield takes nominal input");h.assertValueEqual(t.hits.size(),1,"one reflected entity");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:ricochet_perk_unknown")
    public void unknownDamageRetainsPaymentAndDoesNotContinueTheReflectedPath(GameTestHelper h)throws Exception {
        try(var t=open(h,"normal",true)){
            RicochetHitscanGameTest.wall(t);var first=RicochetHitscanGameTest.target(t,5,10);var second=RicochetHitscanGameTest.target(t,3,12);t.failAfterDamage=true;
            try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}h.assertTrue(t.runtime.failure().isPresent(),"failure missing");h.assertTrue(first.getHealth()<100,"first real damage not retained");near(h,second.getHealth(),100,"later contact executed after failure");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"payment retained");
            try{t.runtime.prepare();t.runtime.fire(t.owner);}catch(IllegalStateException expected){}h.assertValueEqual(t.hits.size(),1,"failed action replayed");h.succeed();
        }
    }
}
