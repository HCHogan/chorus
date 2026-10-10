package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.Action;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.damagesource.DamageSource;

public class EventEntityObservationGameTest {
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"event_entities",data->{
            for(String id:List.of("projectile","power"))data.getAsJsonArray("bundles").add(JsonParser.parseString("{\"id\":\"test:"+id+"\"}"));
        },true);
        IncandescentGameTest.bind(t,"driver","test:driver","");return t;
    }
    @GameCase public void managedAndNativeKillsReadReceiptAfterDeathReactionDiscardsTheCorpse(GameTestHelper h)throws Exception{
        for(boolean managed:List.of(false,true))try(var t=harness(h)){
            var victim=t.cow(2.5,46,3.5);victim.setHealth(5);victim.addTag("test:tier");
            t.onCue=cue->{if(cue.cue().equals("test:discard")){victim.removeTag("test:tier");victim.discard();}};
            DamageReceipt receipt;
            if(managed){EventBuffObservationGameTest.event(t,victim,"attack");receipt=t.receipts.getFirst();}
            else receipt=MinecraftDamageExecutor.execute("entity-observation/"+UUID.randomUUID(),victim,h.getLevel().damageSources().playerAttack(t.owner),10,false);
            h.assertTrue(victim.isRemoved(),"death reaction removed the real entity");
            var observation=receipt.observedEntities().orElseThrow();var v=observation.require(victim.getUUID().toString()).orElseThrow();
            h.assertTrue(!v.alive()&&v.health()==0&&v.entityTags().contains("test:tier"),"receipt retains corpse metadata before reaction");
            h.assertValueEqual(v.typeTags(),victim.getType().builtInRegistryHolder().tags().map(tag->tag.location().toString()).collect(java.util.stream.Collectors.toSet()),"registry type tags captured");
            h.assertTrue(observation.require(t.owner.getUUID().toString()).orElseThrow().player(),"actual credited player observed");
            h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(),List.of("test:discard","test:classified","test:copied"),"kill and copied event use original classification");
            h.assertTrue(t.runtime.failure().isEmpty(),"entity observation runtime failed: "+t.runtime.failure());
        }h.succeed();
    }
    @GameCase public void retainedVictimLogicalAliasesAndUnavailableOwnersHaveExplicitEvidence(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var victim=t.cow(2.5,46,3.5);victim.addTag("test:tier");victim.discard();
            var nativeSource=h.getLevel().damageSources().playerAttack(t.owner);
            for(String owner:List.of("logical-owner",UUID.randomUUID().toString(),"logical-victim")){
                var command=new DamageCommand("logical-victim",new BuffInstance.Origin(owner,"test:source","",""),1,"minecraft:generic",Set.of(),Set.of(),false);
                var observed=t.runtime.observeEntities(command,victim,nativeSource).orElseThrow();
                h.assertTrue(observed.require("logical-victim").orElseThrow().entityTags().contains("test:tier"),"retained target survives removal and logical reference");
                if(owner.equals("logical-owner")){
                    h.assertTrue(!observed.observed(owner),"unresolved alias is unobserved, not an invented absence");
                    h.assertTrue(!observed.positionObserved(owner,TargetQuery.Anchor.FEET),"unresolved alias has no historical position");
                }
                else if(!owner.equals("logical-victim")){
                    h.assertTrue(observed.observed(owner)&&observed.require(owner).isEmpty(),"UUID lookup confirmed unavailable");
                    for(var anchor:TargetQuery.Anchor.values())h.assertTrue(observed.positionObserved(owner,anchor)&&observed.requirePosition(owner,anchor).isEmpty(),"missing actor anchor remains unavailable");
                }
                for(var anchor:TargetQuery.Anchor.values())h.assertValueEqual(observed.requirePosition("logical-victim",anchor),Optional.of(EventPositionObservationGameTest.point(victim,anchor)),"retained removed victim position");
            }
            var self=new DamageCommand(t.owner.getUUID().toString(),new BuffInstance.Origin(t.owner.getUUID().toString(),"self","",""),1,"minecraft:generic",Set.of(),Set.of(),false);
            h.assertValueEqual(t.runtime.observeEntities(self,t.owner,nativeSource).orElseThrow().entities().size(),1,"self-hit has one consistent observation");
        }h.succeed();
    }
    @GameCase public void observationFailureCleansNativeScopeAndReportsCommittedDamageWithoutReplay(GameTestHelper h){
        var level=h.getLevel();var victim=h.spawnWithNoFreeWill(EntityTypes.COW,2,40,3);victim.setNoGravity(true);victim.setHealth(10);
        class Observer implements DamageCapture.Observer {
            int prepared,failed,abandoned;boolean fail=true;final List<DamageCapture.Observed> hits=new ArrayList<>();
            public void prepare(){prepared++;}
            public DamageCommand describe(LivingEntity target,DamageSource source,float amount){return MinecraftEffectRuntime.nativeSource(target,source,amount);}
            public Optional<CalculationProfile.Result> outgoing(DamageCommand c,double a){return Optional.empty();}
            public Optional<CalculationProfile.Result> defense(DamageCommand c,double a){return Optional.empty();}
            public ShieldDamage.Planned shields(DamageCommand c,double a,DamageBasis basis){return ShieldDamage.plan(a,List.of());}
            public Optional<EntityObservation> observeEntities(DamageCommand c,LivingEntity t,DamageSource s){if(fail)throw new IllegalStateException("Injected entity observation failure");return Optional.of(new EntityObservation(0,Map.of(c.target(),Optional.of(new EntityQuery.View(t.isAlive(),false,t.getHealth(),t.getMaxHealth(),t.getAbsorptionAmount())))));}
            public void abandoned(String id){abandoned++;}
            public void committed(List<DamageCapture.Observed> observations){hits.addAll(observations);}
            public void failed(Throwable error,List<DamageCapture.Observed> observations){failed++;hits.addAll(observations);}
        }
        var nextVictim=h.spawnWithNoFreeWill(EntityTypes.COW,3,40,3);nextVictim.setNoGravity(true);nextVictim.setHealth(10);
        var observer=new Observer();DamageCapture.install(level,observer);
        try{
            boolean threw=false;try{MinecraftDamageExecutor.execute("failed-observation",victim,level.damageSources().generic(),1,false);}catch(IllegalStateException expected){threw=true;}
            h.assertTrue(threw&&observer.failed==1&&observer.abandoned==1,"capture failure reaches observer and abandons current reservation");
            HealingGameTest.near(h,victim.getHealth(),9,"committed damage is not rolled back");
            observer.fail=false;
            var second=MinecraftDamageExecutor.execute("next-observation",nextVictim,level.damageSources().generic(),1,false);
            h.assertValueEqual(observer.prepared,2,"next damage starts a fresh outer scope");h.assertValueEqual(observer.hits.size(),2,"each actual native damage reported once");
            h.assertTrue(second.observedEntities().isPresent()&&observer.hits.getFirst().receipt().observedEntities().isEmpty(),"failed observation remains unknown");
            HealingGameTest.near(h,nextVictim.getHealth(),9,"next independent damage executes normally");
            HealingGameTest.near(h,victim.getHealth(),9,"failed observation does not replay first damage");
        }finally{DamageCapture.remove(level,observer);victim.discard();nextVictim.discard();}h.succeed();
    }
}
