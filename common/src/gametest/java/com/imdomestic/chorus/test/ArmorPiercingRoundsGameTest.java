package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** The checked-in reference consumer uses actual independent equipment, hitscan geometry and shield receipts. */
public class ArmorPiercingRoundsGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h,String variant)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"armor_piercing_weapon",data->RadiantGameTest.merge(data,"armor_piercing_rounds","weapon_ballistics","weapon_stats","weapon_range_curves","weapon_range_test_calibration","armor_piercing_test_targets"),true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+2.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+2.5);t.owner.setXRot(-90);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);t.owner.getFoodData().setFoodLevel(0);
            equip(t,"a","primary",variant);equip(t,"b","secondary","enhanced");PugilistGameTest.draw(t,"primary");
            String owner=t.owner.getUUID().toString();t.runtime.bind(new EffectSource("shield-input","test:ap_shields",owner,new BuffInstance.Origin(owner,"shield-input","",""),Set.of()));return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static void equip(ProjectileGameTest.Harness t,String id,String slot,String variant){
        var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(id,"test:rifle",Map.of("magazine",variant)));t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:"+slot,0,e.revision());
    }
    static LivingEntity target(ProjectileGameTest.Harness t,double distance){var v=t.cow(2.5,50,2.5);v.setPos(t.owner.getX(),t.owner.getEyeY()+distance,t.owner.getZ());return v;}
    static void shield(ProjectileGameTest.Harness t,LivingEntity victim,String name){String owner=t.owner.getUUID().toString();t.runtime.start(new RuleEngine.Signal("test:ap_"+name,new EffectEvent(owner,victim.getUUID().toString(),new BuffInstance.Origin(owner,"shield-input","",""),Set.of(),Map.of())));}
    static void fire(ProjectileGameTest.Harness t){t.h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.ACCEPTED,"reference bullet accepted");t.h.assertTrue(t.runtime.failure().isEmpty(),"AP failure: "+t.runtime.failure());}
    @GameCase(environment="chorus_gametest:ap_layers")
    public void oneRoundPiercesTwoTargetsAndOnlyTheEligibleShieldGetsTheBonus(GameTestHelper h)throws Exception {
        try(var t=open(h,"normal")){
            var first=target(t,5);var second=target(t,10);var third=target(t,15);shield(t,first,"elemental");shield(t,second,"guardian");fire(t);
            near(h,first.getHealth(),95,"elemental shield spends five of the original ten damage");near(h,second.getHealth(),95.25,"Guardian shield spends full 5.25");near(h,third.getHealth(),100,"only one additional target");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"one cost");h.assertValueEqual(t.hits.size(),2,"two actual writes");near(h,t.receipts.getFirst().shields().getFirst().attackScaling().orElseThrow().output().value(),1.05,"elemental trace");near(h,t.receipts.getLast().shields().getFirst().attackScaling().orElseThrow().output().value(),1,"Guardian trace");
            h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"finished group leaked");h.assertTrue(t.projectiles.isEmpty(),"instant bullets spawned flight entities");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:ap_range")
    public void ordinaryAndEnhancedRangeChangeActualFalloffIndependentlyAtEachContact(GameTestHelper h)throws Exception {
        for(String variant:List.of("normal","enhanced"))try(var t=open(h,variant)){
            var first=target(t,20);var second=target(t,30);fire(t);double start=variant.equals("normal")?17.625:17.875;
            near(h,first.getHealth(),100-WeaponRangeFalloffGameTest.expected(20,start,40,.5),"first contact falloff");near(h,second.getHealth(),100-WeaponRangeFalloffGameTest.expected(30,start,40,.5),"second contact falloff");
            near(h,t.hits.getFirst().impact().number("range_start",Unit.METER).value(),start,"Range enters start curve");near(h,t.hits.getLast().impact().number("distance",Unit.METER).value(),30,"distance stays relative to original muzzle");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ap_equipment",maxTicks=20)
    public void switchingToTheEnhancedWeaponChangesRangeAndPenetrationWithoutBorrowingItsPerk(GameTestHelper h)throws Exception {
        var t=open(h,"none");try{target(t,20);var second=target(t,30);fire(t);h.assertValueEqual(t.hits.size(),1,"plain ammunition has no extra target");near(h,second.getHealth(),100,"unequipped weapon must not lend perk");near(h,t.hits.getFirst().impact().number("range_start",Unit.METER).value(),17,"plain Range");
            RampageGameTest.at(t,4,()->{try(t){PugilistGameTest.draw(t,"secondary");fire(t);h.assertValueEqual(t.hits.size(),3,"enhanced weapon penetrates");near(h,t.hits.getLast().impact().number("range_start",Unit.METER).value(),17.875,"enhanced Range");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"old weapon cost remains");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"b"),9,"new weapon pays its cost");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:ap_barrier")
    public void barrierBonusDoesNotAuthorizeDamageThroughAnImmuneLayer(GameTestHelper h)throws Exception {
        for(String name:List.of("barrier","immune_barrier","overshield","plain"))try(var t=open(h,"enhanced")){
            var target=target(t,5);shield(t,target,name);fire(t);near(h,target.getHealth(),name.equals("immune_barrier")?100:name.equals("barrier")?95:95.25,"shield qualification and immunity "+name);
            if(name.equals("immune_barrier")){near(h,t.receipts.getFirst().shieldLoss(),0,"immune layer unchanged");near(h,t.receipts.getFirst().healthLoss(),0,"perk did not bypass immunity");}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ap_wall")
    public void terrainStillStopsThePerkAfterTheFirstTarget(GameTestHelper h)throws Exception {
        try(var t=open(h,"normal")){
            var first=target(t,5);var second=target(t,12);var block=BlockPos.containing(t.owner.getX(),t.owner.getEyeY()+10,t.owner.getZ());t.blocks.put(block,h.getLevel().getBlockState(block));h.getLevel().setBlockAndUpdate(block,Blocks.STONE.defaultBlockState());fire(t);
            near(h,first.getHealth(),90,"front enemy damaged");near(h,second.getHealth(),100,"wall protects rear target");h.assertValueEqual(t.hits.size(),1,"no wall penetration");h.assertTrue(PrecisionAmmoGameTest.state(t).shotGroups().isEmpty(),"wall did not finish shot");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:ap_unknown")
    public void unknownShieldOverflowRetainsOnePaymentAndDoesNotReplayOrDamageTheNextTarget(GameTestHelper h)throws Exception {
        try(var t=open(h,"normal")){
            var first=target(t,5);var second=target(t,10);shield(t,first,"elemental");t.failAfterDamage=true;try{t.runtime.fire(t.owner);}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"injected failure missing");near(h,first.getHealth(),95,"applied shield overflow remains");near(h,second.getHealth(),100,"next captured target not written");h.assertValueEqual(PrecisionAmmoGameTest.magazine(t,"a"),9,"accepted payment remains");
            try{t.runtime.prepare();t.runtime.fire(t.owner);}catch(IllegalStateException expected){}near(h,first.getHealth(),95,"unknown result replayed");h.assertValueEqual(t.hits.size(),1,"second write after failure");h.succeed();
        }
    }
}
