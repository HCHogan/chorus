package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

/** The consumer supplies explicit synthetic hit regions; the tested hits are physical projectiles. */
public class BoxBreathingGameTest {
    static final String B="chorus_d2:box_breathing";
    static ProjectileGameTest.Harness openBox(GameTestHelper h,boolean enhanced,Consumer<JsonObject> edit)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"box_breathing_weapon",data->{RadiantGameTest.merge(data,"box_breathing");edit.accept(data);},true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+2.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+2.5);t.owner.setXRot(0);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);
            var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear("a","test:rifle",Map.of("perk",enhanced?"enhanced":"normal")));
            t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");WeaponAimGameTest.input(t,true);return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    static boolean active(ProjectileGameTest.Harness t){return active(state(t));}
    static boolean active(EffectState state){return state.buffs().instances().values().stream().anyMatch(b->b.definition().id().equals(B));}
    static LivingEntity victim(ProjectileGameTest.Harness t,boolean weak){var target=DragonflyGameTest.victim(t,weak,true);target.setHealth(100);return target;}
    static void impact(ProjectileGameTest.Harness t,EffectProjectile projectile,LivingEntity target,double damage){
        int n=t.receipts.size();for(int i=0;i<20&&!projectile.isRemoved();i++)projectile.tick();
        t.h.assertTrue(projectile.isRemoved()&&t.receipts.size()==n+1,"physical Box Breathing shot did not hit exactly once");
        HealingGameTest.near(t.h,t.receipts.getLast().outgoing().orElseThrow().output().value(),damage,"shot outgoing damage");
        t.h.assertValueEqual(t.hits.getLast().target(),target.getUUID().toString(),"wrong physical recipient");settled(t);
    }
    static void lastRound(GameTestHelper h,boolean enhanced)throws Exception {
        var t=openBox(h,enhanced,data->data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine",1));int tick=enhanced?28:30;
        at(t,tick-1,()->{settled(t);h.assertTrue(!active(t),"buff activated early");});
        at(t,tick,()->{try(t){
            settled(t);h.assertTrue(active(t),"held-input deadline missed");var target=victim(t,true);var shot=PugilistGameTest.fire(t);
            h.assertTrue(!active(t),"accepted shot did not consume buff");h.assertValueEqual(state(t).ammunition().get("a").magazine(),0,"last round not paid");WeaponAimGameTest.input(t,false);impact(t,shot,target,28);
            HealingGameTest.near(h,target.getHealth(),72,"real amplified weakspot hit after release");h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:box_breathing_normal",maxTicks=50)
    public void NormalLastRoundKeepsItsCapturedBonusAfterRelease(GameTestHelper h)throws Exception {lastRound(h,false);}
    @GameCase(environment="chorus_gametest:box_breathing_enhanced",maxTicks=50)
    public void EnhancedLastRoundStartsOneTenthOfASecondEarlier(GameTestHelper h)throws Exception {lastRound(h,true);}
    @GameCase(environment="chorus_gametest:box_breathing_rearm",maxTicks=100)
    public void EarlyPhysicalFireRestartsTheWaitAndBodyFireConsumesTheNextOpportunity(GameTestHelper h)throws Exception {
        var t=openBox(h,false,_ -> {});at(t,10,()->{
            var first=victim(t,false);impact(t,PugilistGameTest.fire(t),first,10);first.discard();
            at(t,29,()->{settled(t);h.assertTrue(!active(t),"early shot did not restart full wait");});
            at(t,30,()->{
                settled(t);h.assertTrue(active(t),"rearmed without another ADS input");var second=victim(t,false);impact(t,PugilistGameTest.fire(t),second,10);second.discard();h.assertTrue(!active(t),"body shot did not consume");
                at(t,1,()->h.assertValueEqual(t.runtime.fire(t.owner).outcome(),WeaponFire.Outcome.COOLDOWN,"rejected shot"));
                at(t,30,()->{try(t){settled(t);h.assertTrue(active(t),"rejected shot changed deadline");h.assertValueEqual(t.runtime.reload(t.owner).outcome(),com.imdomestic.chorus.effect.weapon.WeaponReload.Outcome.ACCEPTED,"reload accepted");h.assertTrue(!active(t),"reload retained aim perk");h.succeed();}});
            });
        });
    }
    @GameCase(environment="chorus_gametest:box_breathing_shield",maxTicks=50)
    public void ShieldSuppressesPrecisionFactorButRetainsNaturalWeakspotBonus(GameTestHelper h)throws Exception {
        var t=openBox(h,false,_ -> {});at(t,30,()->{try(t){
            settled(t);var target=victim(t,true);bind(t,"test:box_shield",true);
            t.runtime.start(new RuleEngine.Signal("test:shield",new EffectEvent(holder(t),target.getUUID().toString(),new BuffInstance.Origin(holder(t),"shield","",""),Set.of(),Map.of())));
            impact(t,PugilistGameTest.fire(t),target,28);HealingGameTest.near(h,target.getHealth(),100,"shield protected health");
            var shield=state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals("test:box_shield")).findFirst().orElseThrow();
            HealingGameTest.near(h,shield.components().numbers().get("capacity"),86,"shield removed only x2 precision, retained x1.4 weakspot");h.assertTrue(t.receipts.getLast().shields().getFirst().factorSuppression().isPresent(),"missing precision suppression trace");h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:box_breathing_glaive",maxTicks=50)
    public void GlaiveNaturalWeakspotGetsThirtyPercentWithoutAPrecisionMultiplier(GameTestHelper h)throws Exception {
        var t=openBox(h,false,data->{
            var tags=new com.google.gson.JsonArray();for(String tag:List.of("test:weapon","chorus_d2:glaive","chorus_d2:special_ammo"))tags.add(tag);
            data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().add("tags",tags);
            var branch=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(4).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject();
            branch.getAsJsonObject("if").getAsJsonArray("of").get(1).getAsJsonObject().getAsJsonObject("left").addProperty("value",0);
        });at(t,30,()->{try(t){settled(t);var target=victim(t,true);impact(t,PugilistGameTest.fire(t),target,13);HealingGameTest.near(h,target.getHealth(),87,"glaive weakspot");h.assertTrue(!t.hits.getLast().tags().contains("chorus:precision"),"zero-multiplier shot invented precision credit");h.succeed();}});
    }
    @GameCase(environment="chorus_gametest:box_breathing_bubble",maxTicks=50)
    public void SyntheticPrecisionRegionDoesNotInheritNaturalWeakspotDamage(GameTestHelper h)throws Exception {
        var t=openBox(h,false,_ -> {});at(t,30,()->{try(t){
            settled(t);var target=victim(t,false);String id=target.getUUID().toString();t.runtime.bind(new EffectSource("bubble/"+id,"test:box_bubble",id,new BuffInstance.Origin(id,"bubble","",""),Set.of()));
            impact(t,PugilistGameTest.fire(t),target,20);HealingGameTest.near(h,target.getHealth(),80,"synthetic precision without weakspot bonus");h.assertTrue(!active(t),"synthetic precision shot did not consume");h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:box_breathing_restore",maxTicks=50)
    public void WaitingPerkResumesOnItsOriginalDeadlineAndReleaseStillClearsIt(GameTestHelper h)throws Exception {
        var t=openBox(h,false,_ -> {});at(t,29,()->{
            settled(t);h.assertTrue(!active(t),"restore precondition");var saved=t.runtime.checkpoint();var stored=new MinecraftEffectRuntime.Checkpoint(saved.dimension(),saved.gameTime(),EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(saved.effects())),saved.equipmentOwners(), saved.actorOwners(), saved.abilityOwners());var program=t.runtime.program();t.runtime.close();
            var resumed=MinecraftEffectRuntime.restore(h.getLevel(),program,stored,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world,MinecraftEffectRuntime::nativeSource);
            try {
                h.assertTrue(!active(resumed.state().engine().domain()),"restore activated early");h.assertTrue(t.projectiles.isEmpty(),"restore replayed launch");
                h.runAfterDelay(1,()->{try(resumed;t){resumed.prepare();h.assertTrue(active(resumed.state().engine().domain()),"original perk deadline not retained");resumed.aim(t.owner,false);h.assertTrue(!active(resumed.state().engine().domain()),"resumed release retained bonus");h.assertTrue(resumed.failure().isEmpty(),"restored runtime failed");h.succeed();}});
            }catch(Exception|Error error){resumed.close();throw error;}
        });
    }
    @GameCase(environment="chorus_gametest:box_breathing_unknown",maxTicks=50)
    public void UnknownHitAfterConsumptionPreservesHealthLossAndDoesNotReplayTheShot(GameTestHelper h)throws Exception {
        var t=openBox(h,false,_ -> {});at(t,30,()->{try(t){
            settled(t);var target=victim(t,true);var shot=PugilistGameTest.fire(t);h.assertTrue(!active(t),"shot did not consume before flight");t.failAfterDamage=true;
            try{for(int i=0;i<20&&!shot.isRemoved();i++)shot.tick();}catch(RuntimeException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown hit did not fail runtime");HealingGameTest.near(h,target.getHealth(),72,"committed hit retained");h.assertValueEqual(state(t).ammunition().get("a").magazine(),4,"paid ammunition retained");
            int count=t.receipts.size();t.runtime.prepare();h.assertValueEqual(t.receipts.size(),count,"damage replayed");h.assertValueEqual(t.projectiles.size(),1,"launch replayed");h.succeed();
        }});
    }
}
