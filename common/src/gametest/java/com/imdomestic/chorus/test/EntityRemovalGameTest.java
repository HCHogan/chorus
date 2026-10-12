package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public class EntityRemovalGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"entity_removal",_->{},true);IncandescentGameTest.bind(t,"driver","test:removal","");return t;
    }
    static List<String> cues(ProjectileGameTest.Harness t){return t.cues.stream().map(c->c.cue()).toList();}
    static List<EntityLifecycle.Removed> captured(ProjectileGameTest.Harness t){
        return t.runtime.state().engine().domain().timers().values().stream().map(EffectState.Timer::signal).map(RuleEngine.Signal::payload).filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast).map(p->p.cause().signal().payload()).filter(EntityLifecycle.Removed.class::isInstance).map(EntityLifecycle.Removed.class::cast).toList();
    }
    static void settled(ProjectileGameTest.Harness t){t.runtime.prepare();t.h.assertTrue(t.runtime.failure().isEmpty()&&t.runtime.state().idle(),"removal runtime failed: "+t.runtime.failure());}
    @GameCase public void discardCapturesTheOldLifeAndAllAnchorsOnceBeforeCheckpoint(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var victim=t.cow(2.5,46,3.5);var expected=new EnumMap<TargetQuery.Anchor,WorldPosition>(TargetQuery.Anchor.class);for(var a:TargetQuery.Anchor.values())expected.put(a,EventPositionObservationGameTest.point(victim,a));
            var life=MinecraftEntityLife.get(victim).id();victim.discard();h.assertTrue(t.cues.isEmpty(),"removal recursively entered rules");var replacement=TimedPayloadGameTest.replace(t,victim);victim.setPos(victim.getX()+100,victim.getY()+10,victim.getZ());victim.setRemoved(Entity.RemovalReason.KILLED);
            var checkpoint=t.runtime.checkpoint();h.assertTrue(checkpoint.effects().timeline().domain().timers().size()==1,"checkpoint did not settle exactly one removal");
            var fact=captured(t).getFirst();h.assertValueEqual(fact.kind(),EntityLifecycle.Kind.REMOVED,"discard kind");h.assertValueEqual(fact.reason(),"minecraft:discarded","first native reason preserved");h.assertValueEqual(fact.reference().life(),life,"old life retained");h.assertTrue(!MinecraftEntityLife.get(replacement).id().equals(life),"test replacement reused life");
            for(var a:TargetQuery.Anchor.values())h.assertValueEqual(fact.anchor(a).position(),Optional.of(expected.get(a)),"historical anchor "+a);
            h.assertTrue(fact.event().observedEntities().orElseThrow().require(victim.getUUID().toString()).isEmpty(),"removed entity advertised available");h.assertValueEqual(cues(t),List.of("test:removed"),"duplicate removal or kill reward");
            settled(t);h.assertValueEqual(cues(t),List.of("test:removed"),"prepare replayed removal");
        }h.succeed();
    }
    @GameCase public void directSetRemovedKeepsChunkAndPlayerUnloadReasonsWithoutDeathFacts(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            for(var reason:List.of(Entity.RemovalReason.UNLOADED_TO_CHUNK,Entity.RemovalReason.UNLOADED_WITH_PLAYER)){
                var entity=t.cow(2.5,46,3.5);entity.setRemoved(reason);settled(t);
                var fact=captured(t).stream().filter(x->x.reference().target().equals(entity.getUUID().toString())).findFirst().orElseThrow();
                h.assertValueEqual(fact.kind(),EntityLifecycle.Kind.UNLOADED,"unload kind");h.assertValueEqual(fact.reason(),"minecraft:"+reason.name().toLowerCase(Locale.ROOT),"unload reason");h.assertTrue(fact.wasAlive(),"live unload");
            }
            h.assertValueEqual(cues(t),List.of("test:removed","test:removed"),"unload became death or kill");
        }h.succeed();
    }
    @GameCase public void actualNativeDeathAndLaterRemovalHaveDistinctFacts(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var victim=t.cow(2.5,46,3.5);victim.hurtServer(h.getLevel(),h.getLevel().damageSources().playerAttack(t.owner),1000);settled(t);
            h.assertTrue(victim.isDeadOrDying()&&!victim.isRemoved(),"test requires a present corpse");h.assertValueEqual(cues(t),List.of("test:death","test:kill"),"confirmed native death facts");
            victim.remove(Entity.RemovalReason.KILLED);settled(t);var fact=captured(t).getFirst();h.assertValueEqual(fact.kind(),EntityLifecycle.Kind.DESTROYED,"native killed removal");h.assertTrue(!fact.wasAlive(),"corpse removal was alive");h.assertValueEqual(cues(t),List.of("test:death","test:kill","test:removed"),"removal made another death or kill");
        }h.succeed();
    }
    @GameCase public void committedRemovalWaitsForParentActionsAndUnknownOutcomeRetainsItsEvidence(GameTestHelper h)throws Exception {
        for(boolean unknown:List.of(false,true))try(var t=open(h)){
            var victim=t.cow(2.5,46,3.5);var point=EventPositionObservationGameTest.point(victim,TargetQuery.Anchor.FEET);
            t.onCue=cue->{if(cue.cue().equals("test:erase")){victim.discard();if(unknown)throw new IllegalStateException("Injected unknown removal callback outcome");}};
            var signal=new RuleEngine.Signal("test:erase",new EffectEvent(t.owner.getUUID().toString(),victim.getUUID().toString(),new com.imdomestic.chorus.effect.buff.BuffInstance.Origin(t.owner.getUUID().toString(),"driver","",""),Set.of(),Map.of()));
            if(unknown){
                boolean stopped=false;try{t.runtime.start(signal);}catch(IllegalStateException expected){stopped=true;}h.assertTrue(stopped&&t.runtime.failure().isPresent()&&victim.isRemoved(),"committed removal was lost or replayed");
                var facts=t.runtime.failure().orElseThrow().committedCombatFacts();var removal=facts.stream().map(RuleEngine.Signal::payload).filter(EntityLifecycle.Removed.class::isInstance).map(EntityLifecycle.Removed.class::cast).findFirst().orElseThrow();h.assertValueEqual(removal.anchor(TargetQuery.Anchor.FEET).position(),Optional.of(point),"failure lost removal evidence");t.runtime.prepare();h.assertValueEqual(cues(t),List.of("test:erase"),"failed action replayed or generated rewards");
            }else{t.runtime.start(signal);settled(t);h.assertValueEqual(cues(t),List.of("test:erase","test:after_erase","test:removed"),"removal facts ran inside parent frame");}
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:entity_removal_delayed",maxTicks=25)
    public void detachedWorkUsesHistoricalPositionAfterSourceAndTargetDisappear(GameTestHelper h)throws Exception {
        var t=open(h);try{
            var victim=t.cow(2.5,46,3.5);var point=EventPositionObservationGameTest.point(victim,TargetQuery.Anchor.FEET);victim.discard();settled(t);t.runtime.unbind("driver");victim.setPos(victim.getX()+100,victim.getY(),victim.getZ());
            t.finish(3,()->{h.assertValueEqual(cues(t),List.of("test:removed","test:delayed_removal"),"detached removal work");h.assertValueEqual(t.queries.size(),2,"historical queries");h.assertTrue(t.queries.stream().allMatch(q->((TargetQuery.PositionCenter)q.center()).position().equals(Optional.of(point))),"delayed work used a new entity location");});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase public void runtimeCloseDoesNotExecutePendingRemovalRewards(GameTestHelper h)throws Exception {
        try(var t=open(h)){var victim=t.cow(2.5,46,3.5);victim.discard();t.runtime.close();h.assertTrue(t.cues.isEmpty(),"closing runtime executed queued rewards");}
        h.succeed();
    }
    @GameCase public void actualDimensionTransferRecordsTheOldWorldAndLife(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var victim=t.cow(2.5,46,3.5);var point=EventPositionObservationGameTest.point(victim,TargetQuery.Anchor.FEET);var life=MinecraftEntityLife.get(victim).id();var destination=Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));destination.getChunk(0,0);
            LivingEntity moved=null;try{
                moved=(LivingEntity)victim.teleport(new TeleportTransition(destination,new Vec3(8,90,8),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING));settled(t);h.assertTrue(moved!=null&&victim.isRemoved(),"actual transfer");
                var fact=captured(t).getFirst();h.assertValueEqual(fact.kind(),EntityLifecycle.Kind.TRANSFERRED,"transfer kind");h.assertValueEqual(fact.reference().life(),life,"old life reference");h.assertValueEqual(MinecraftEntityLife.get(moved).id(),life,"new native object is same life");h.assertValueEqual(fact.anchor(TargetQuery.Anchor.FEET).position(),Optional.of(point),"old world removal position");
            }finally{if(moved!=null)moved.discard();}
        }h.succeed();
    }
    @GameCase public void immediateReenableInsideTheNativeCallbackCannotEraseCommittedRemoval(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var entity=new net.minecraft.world.entity.animal.cow.Cow(EntityTypes.COW,h.getLevel()){
                @Override public void onRemoval(Entity.RemovalReason reason){super.onRemoval(reason);unsetRemoved();}
            };
            entity.setPos(t.owner.getX()+2,t.owner.getY()+3,t.owner.getZ());entity.setNoGravity(true);entity.setNoAi(true);h.getLevel().addFreshEntity(entity);t.entities.add(entity);var point=EventPositionObservationGameTest.point(entity,TargetQuery.Anchor.FEET);
            entity.setRemoved(Entity.RemovalReason.DISCARDED);h.assertTrue(!entity.isRemoved(),"callback did not re-enable the same object");h.assertTrue(t.cues.isEmpty(),"field write executed rules recursively");settled(t);
            h.assertValueEqual(captured(t).getFirst().anchor(TargetQuery.Anchor.FEET).position(),Optional.of(point),"committed removal disappeared after re-enable");
            h.getLevel().addFreshEntity(entity);entity.setRemoved(Entity.RemovalReason.DISCARDED);settled(t);h.assertValueEqual(captured(t).size(),2,"second native transition was suppressed for the same life");h.assertValueEqual(cues(t),List.of("test:removed","test:removed"),"lifecycle transitions or rewards duplicated");
        }h.succeed();
    }

}
