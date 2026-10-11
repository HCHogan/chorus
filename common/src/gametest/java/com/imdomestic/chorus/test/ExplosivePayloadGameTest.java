package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Synthetic calibration, real paid fire/collision/damage; direct and splash are separate native receipts. */
public class ExplosivePayloadGameTest {
    static final String TAG="chorus_d2:explosive_payload_damage";
    static ProjectileGameTest.Harness open(GameTestHelper h,Consumer<JsonObject> edit)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"explosive_payload_weapon",data->{RadiantGameTest.merge(data,"explosive_payload","explosive_payload_test_calibration","weapon_stats","weapon_range_curves","weapon_range_test_calibration");edit.accept(data);},true);
        try{
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+3.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+3.5);t.owner.setXRot(-90);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);
            var gear=new Loadout.Gear("a","test:hand_cannon",Map.of("perk","normal"));var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),gear);t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static void contact(ProjectileGameTest.Harness t,EffectProjectile shot){for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();t.h.assertTrue(shot.isRemoved(),"payload did not contact");t.h.assertTrue(t.runtime.failure().isEmpty(),"payload failed: "+t.runtime.failure());}
    static LivingEntity neighbor(ProjectileGameTest.Harness t,WorldPosition point,double distance){var e=t.cow(2.5,46,3.5);e.setPos(point.x()+distance,point.y()-e.getBbHeight()/2,point.z());return e;}
    static WorldPosition point(EffectProjectile shot,double d){var p=shot.launch().orElseThrow().position().orElseThrow();return new WorldPosition(p.dimension(),p.x(),p.y()+d,p.z());}
    static LivingEntity primary(ProjectileGameTest.Harness t,EffectProjectile shot,double d){return WeaponRangeFalloffGameTest.target(t,shot.launch().orElseThrow().position().orElseThrow(),d);}
    static void region(ProjectileGameTest.Harness t,LivingEntity e){var id=e.getUUID().toString();t.runtime.bind(new EffectSource("region/"+id,"test:weapon_region",id,new BuffInstance.Origin(id,"region","",""),Set.of(),Map.of("min_x",new Measure(-1,Unit.MULTIPLIER),"max_x",new Measure(2,Unit.MULTIPLIER),"min_y",new Measure(-1,Unit.MULTIPLIER),"max_y",new Measure(2,Unit.MULTIPLIER),"min_z",new Measure(-1,Unit.MULTIPLIER),"max_z",new Measure(2,Unit.MULTIPLIER))));}
    @GameCase(environment="chorus_gametest:payload_split",maxTicks=30)
    public void actualBodyAndPrecisionContactsHaveSeparateTravelAndBlastDamage(GameTestHelper h)throws Exception{
        for(boolean head:List.of(false,true))for(double d:new double[]{5,25.5,50})try(var t=open(h,_ -> {})){
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,d);if(head)region(t,target);contact(t,shot);
            double falloff=WeaponRangeFalloffGameTest.expected(d,20.5,30.5,.33)/10,direct=(head?15:5)*falloff;
            h.assertValueEqual(t.hits.size(),2,"one direct plus one full primary blast");near(h,t.receipts.get(0).outgoing().orElseThrow().output().value(),direct,"direct trace");near(h,t.receipts.get(1).outgoing().orElseThrow().output().value(),6.5,"range-independent nonprecision blast");near(h,target.getHealth(),100-direct-6.5,"both real native damage receipts apply");
            h.assertValueEqual(t.hits.get(0).tags().contains("chorus:precision"),head,"real geometry classification");h.assertTrue(!t.hits.get(1).tags().contains("chorus:precision"),"blast cannot inherit precision");h.assertValueEqual(t.hits.get(0).group(),t.hits.get(1).group(),"same attack group");h.assertValueEqual(target.getLastDamageSource().getEntity(),t.owner,"native attacker credit");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:payload_radial",maxTicks=30)
    public void explosionExcludesPrimaryDuplicationOwnerAndAlliesAndUsesEachNeighborsDistance(GameTestHelper h)throws Exception{
        try(var t=open(h,_ -> {})){
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);var impact=point(shot,5);var middle=neighbor(t,impact,1.5);var edge=neighbor(t,impact,3);var outside=neighbor(t,impact,3.02);var ally=neighbor(t,impact,2);
            var scoreboard=h.getLevel().getScoreboard();var team=scoreboard.addPlayerTeam("payload-"+UUID.randomUUID());try{
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(),team);scoreboard.addPlayerToTeam(ally.getScoreboardName(),team);contact(t,shot);
                near(h,target.getHealth(),88.5,"primary total");near(h,middle.getHealth(),96.75,"half-radius actual health");near(h,edge.getHealth(),100,"zero outer boundary");near(h,outside.getHealth(),100,"outside");near(h,ally.getHealth(),100,"team filter");near(h,t.owner.getHealth(),10,"owner excluded");h.assertValueEqual(t.hits.size(),3,"primary not also a radial neighbor");
            }finally{scoreboard.removePlayerTeam(team);}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:payload_death",maxTicks=30)
    public void lethalDirectStillExplodesAndBlastFinalBlowKeepsWeaponCreditWithoutPrecision(GameTestHelper h)throws Exception{
        for(boolean directKill:List.of(false,true))try(var t=open(h,_ -> {})){
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);target.setHealth(directKill?4:6);var nearTarget=neighbor(t,point(shot,5),1.5);nearTarget.setHealth(3);contact(t,shot);
            h.assertTrue(target.isDeadOrDying()&&nearTarget.isDeadOrDying(),"actual primary and neighbor final blows");h.assertValueEqual(t.hits.size(),directKill?2:3,"dead primary not damaged again");var last=t.hits.getLast();h.assertTrue(last.tags().contains(TAG)&&!last.tags().contains("chorus:precision")&&last.killTags().contains("chorus:weapon_kill"),"blast classification");h.assertTrue(t.receipts.getLast().lethal(),"actual lethal receipt");h.assertValueEqual(last.source().weapon(),"a","origin weapon");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:payload_wall",maxTicks=30)
    public void blockImpactHasOnlySplashWhileExpiredProjectileDoesNotExplode(GameTestHelper h)throws Exception{
        try(var t=open(h,_ -> {})){
            var shot=PugilistGameTest.fire(t);var muzzle=shot.launch().orElseThrow().position().orElseThrow();var wall=BlockPos.containing(muzzle.x(),muzzle.y()+5,muzzle.z());t.blocks.put(wall,h.getLevel().getBlockState(wall));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());var nearTarget=neighbor(t,new WorldPosition(muzzle.dimension(),muzzle.x(),wall.getY(),muzzle.z()),1.5);contact(t,shot);near(h,nearTarget.getHealth(),96.75,"wall splash");h.assertValueEqual(t.hits.size(),1,"no direct wall damage to neighbor");h.assertTrue(t.hits.getFirst().tags().contains(TAG),"only splash");
        }
        try(var t=open(h,d->{for(var w:d.getAsJsonArray("weapons"))for(var step:w.getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire"))if(step.getAsJsonObject().has("projectile"))step.getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("lifetime").addProperty("value",.05);})){
            var shot=PugilistGameTest.fire(t);contact(t,shot);h.assertTrue(t.hits.isEmpty()&&t.queries.isEmpty(),"expiry invented a blast");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:payload_snapshot",maxTicks=30)
    public void acceptedBothComponentsKeepSourceBonusAfterUnequipAndOwnerMovement(GameTestHelper h)throws Exception{
        try(var t=open(h,_ -> {})){
            IncandescentGameTest.bind(t,"bonus","test:payload_bonus","a");var shot=PugilistGameTest.fire(t);var target=primary(t,shot,25.5);region(t,target);t.runtime.unbind("bonus");var e=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);e.swap(t.owner,"test:primary",0,e.revision());t.owner.setPos(t.owner.getX()+8,t.owner.getY()+8,t.owner.getZ());contact(t,shot);
            near(h,target.getHealth(),100-15*.665*1.5-6.5*1.5,"both captured bonuses");h.assertTrue(t.hits.stream().allMatch(d->d.source().weapon().equals("a")),"source removal lost weapon attribution");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:payload_shield",maxTicks=30)
    public void shieldExcludesOnlyAdjustedPrecisionAndOneUseBuffCoversBothComponents(GameTestHelper h)throws Exception{
        try(var t=open(h,_ -> {})){
            bind(t,"test:payload_shield",true);var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);region(t,target);
            t.runtime.start(new com.imdomestic.chorus.rule.RuleEngine.Signal("test:shield",new EffectEvent(holder(t),target.getUUID().toString(),new BuffInstance.Origin(holder(t),"shield","",""),Set.of(),Map.of())));contact(t,shot);
            near(h,target.getHealth(),100,"shield preserves health");var shield=state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals("test:payload_shield")).findFirst().orElseThrow();near(h,shield.components().numbers().get("capacity"),88.5,"direct shield body 5 plus blast 6.5");h.assertTrue(t.receipts.getFirst().shields().getFirst().factorSuppression().isPresent(),"adjusted precision factor suppression trace");
        }
        try(var t=open(h,_ -> {})){
            t.runtime.start(new com.imdomestic.chorus.rule.RuleEngine.Signal("test:arm",new EffectEvent(holder(t),holder(t),new BuffInstance.Origin(holder(t),"arm","a",""),Set.of(),Map.of())));
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);near(h,target.getHealth(),77,"one-use bonus covers direct and blast");h.assertTrue(state(t).buffs().instances().isEmpty()&&state(t).damageGroups().isEmpty(),"consumed bonus and closed attack group");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:payload_unknown",maxTicks=30)
    public void unknownSecondDamageRetainsBothAppliedLossesWithoutReplayingContact(GameTestHelper h)throws Exception{
        try(var t=open(h,_ -> {})){
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);t.afterDamageReceipt=(d,r)->{if(d.tags().contains(TAG))t.failAfterDamage=true;};
            for(int i=0;i<35&&!shot.isRemoved();i++)shot.tick();h.assertTrue(t.runtime.failure().isPresent(),"unknown second damage must fail runtime");near(h,target.getHealth(),88.5,"both applied native losses remain");h.assertTrue(shot.isRemoved(),"consumed contact");int count=t.operations.size();shot.tick();t.runtime.prepare();h.assertValueEqual(t.operations.size(),count,"unknown contact was replayed");near(h,target.getHealth(),88.5,"damage not repeated");
        }h.succeed();
    }
}
