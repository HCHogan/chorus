package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.EffectProjectile;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class GamblersTrackingGameTest {
    static final String BUFF="chorus_d2:gamblers_dodge_tracking";
    static String id(Entity e){return e.getUUID().toString();}
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception {
        var t=GamblersDodgeGameTest.open(h,d->GamblersDodgeGameTest.merge(d,ThreadedSpikeGameTest.json("gamblers_tracking_inputs")));
        t.owner.setGameMode(GameType.SURVIVAL);return t;
    }
    static LivingEntity shooter(ProjectileGameTest.Harness t,boolean guardian){
        LivingEntity e;
        if(guardian){e=t.h.makeMockServerPlayerInLevel();t.entities.add(e);}else e=t.cow(2.5,40,3.5);
        // Keep the whole initial trace in the test's simulated chunk, including on NeoForge at a chunk edge.
        e.setPos(t.owner.getX(),t.owner.getY()+8,t.owner.getZ());e.setNoGravity(true);e.setYRot(0);e.setXRot(90);return e;
    }
    static EffectProjectile fire(ProjectileGameTest.Harness t,LivingEntity caster,String owner){
        var origin=new BuffInstance.Origin(owner,"seeker","","test:seeker");
        t.runtime.bind(new EffectSource("seeker","test:gambler_seeker",id(caster),origin,Set.of()));
        t.runtime.start(new RuleEngine.Signal("test:seeker",new EffectEvent(id(caster),id(t.owner),origin,Set.of(),Map.of())));
        return t.projectiles.getLast();
    }
    static Optional<BuffInstance> active(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.definition().id().equals(BUFF)).findFirst();}
    static void healthy(ProjectileGameTest.Harness t){t.h.assertTrue(t.runtime.failure().isEmpty()&&t.runtime.state().idle(),"dodge tracking failed: "+t.runtime.failure());}
    static void impact(ProjectileGameTest.Harness t,EffectProjectile p){p.setPos(t.owner.getBoundingBox().getCenter().add(0,0,-2));p.setDeltaMovement(new Vec3(0,0,3));p.tick();healthy(t);}

    @GameCase(environment="chorus_gametest:gambler_tracking_expiry",maxTicks=30)
    public void combatantLockBreaksDuringActualDodgeAndReturnsAfterExpiryDespiteSourceRemoval(GameTestHelper h)throws Exception {
        var t=open(h);try{
            var caster=shooter(t,false);var p=fire(t,caster,id(caster));p.tick();h.assertValueEqual(p.trackingTarget(),Optional.of(id(t.owner)),"initial combatant lock; flight="+p.progress()+", query="+t.runtime.projectileTrackingReport());
            var before=t.owner.position();GamblersDodgeGameTest.dodge(t);near(h,t.owner.getZ(),before.z+3,"actual paid dodge movement");
            t.runtime.unbind("seeker");caster.discard();GamblersDodgeGameTest.select(t,false);var velocity=p.getDeltaMovement();p.tick();
            h.assertTrue(p.trackingTarget().isEmpty()&&!p.isRemoved(),"dodge breaks detached combatant tracking");near(h,p.getDeltaMovement().distanceTo(velocity),0,"ballistic flight continues");
            var report=t.runtime.projectileTrackingReport().orElseThrow();h.assertValueEqual(report.input().references().get("tracking_owner_kind"),"mob","captured combatant qualification survives owner removal");
            h.assertTrue(report.input().observedEntities().orElseThrow().require(id(caster)).isEmpty(),"current owner is actually unavailable");
            h.assertTrue(active(t).isPresent(),"deselection discarded paid animation window");
            t.finish(8,()->{h.assertTrue(active(t).isEmpty(),"animation window did not expire");h.assertValueEqual(p.trackingTarget(),Optional.of(id(t.owner)),"real ticks reacquire without permanent immunity");near(h,t.owner.getHealth(),10,"distant slow projectile has not hit");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase public void guardianProjectileKeepsLockThroughActualDodgeRegardlessOfPveMode(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var caster=shooter(t,true);var p=fire(t,caster,id(caster));p.tick();h.assertValueEqual(p.trackingTarget(),Optional.of(id(t.owner)),"initial Guardian lock");
            GamblersDodgeGameTest.dodge(t);p.tick();healthy(t);h.assertTrue(active(t).isPresent(),"dodge animation window missing");
            h.assertValueEqual(p.trackingTarget(),Optional.of(id(t.owner)),"PvE mode must not turn player projectile into combatant projectile");
            var report=t.runtime.projectileTrackingReport().orElseThrow();h.assertValueEqual(report.input().references().get("tracking_owner_kind"),"player","native player classification");h.assertTrue(report.decision().orElseThrow().allowed(),"Guardian projectile was denied");
        }h.succeed();
    }
    @GameCase public void immuneTrackingStillAllowsPhysicalDamageDuringTheDodgeWindow(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var caster=shooter(t,false);var p=fire(t,caster,id(caster));GamblersDodgeGameTest.dodge(t);impact(t,p);
            h.assertTrue(p.isRemoved()&&p.progress().entityContacts()==1,"real sweep did not terminate at contact");near(h,t.owner.getHealth(),6,"dodge must not supply invulnerability frames");
            h.assertTrue(active(t).isPresent(),"ordinary damage cancelled animation window");h.assertValueEqual(t.hits.getLast().source().owner(),id(caster),"damage retains combatant owner credit");
            h.assertTrue(!t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(),"tracking query was actually denied before physical hit");
        }h.succeed();
    }
    @GameCase public void confirmedDeathClearsTrackingWindowAfterActualLethalImpact(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var caster=shooter(t,false);var p=fire(t,caster,id(caster));GamblersDodgeGameTest.dodge(t);GamblersDodgeGameTest.select(t,false);t.owner.setHealth(3);impact(t,p);
            h.assertTrue(!t.owner.isAlive()&&t.receipts.getLast().deathId().isPresent(),"physical damage must confirm real death");h.assertTrue(active(t).isEmpty(),"dead holder retained dodge window");
        }h.succeed();
    }
    @GameCase public void unavailableCreditOwnerIsNotInferredFromTheMobThatEmittedTheProjectile(GameTestHelper h)throws Exception {
        try(var t=open(h)){
            var caster=shooter(t,false);String missing=UUID.randomUUID().toString();var p=fire(t,caster,missing);p.tick();
            h.assertValueEqual(p.launch().orElseThrow().owner(),missing,"independent credit owner");h.assertValueEqual(p.launch().orElseThrow().emitter(),id(caster),"actual mob emitter");
            GamblersDodgeGameTest.dodge(t);p.tick();healthy(t);h.assertValueEqual(p.trackingTarget(),Optional.of(id(t.owner)),"unknown owner must not be guessed to be combatant from emitter");
            h.assertValueEqual(t.runtime.projectileTrackingReport().orElseThrow().input().references().get("tracking_owner_kind"),"unavailable","unknown classification remains explicit");
        }h.succeed();
    }
}
