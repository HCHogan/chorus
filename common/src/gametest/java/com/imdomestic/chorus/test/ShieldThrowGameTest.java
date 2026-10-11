package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ShieldThrowGameTest {
    static final String D="chorus_d2:", ABILITY=D+"shield_throw", SLOT=D+"melee", BUFF=D+"void_overshield";
    static String id(Entity e){return e.getUUID().toString();}
    static void prepare(JsonObject data){
        var params=data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters");
        ThreadedSpikeGameTest.json("shield_throw_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->params.getAsJsonObject(e.getKey()).add("value",e.getValue()));
        for(String name:List.of("void_overshield","void_overshield_inputs","shield_throw_energy","shield_throw_damage_test_calibration","character_stats","combat_damage"))GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json(name));
        for(String name:List.of("test:projectile","test:power")){var b=new JsonObject();b.addProperty("id",name);data.getAsJsonArray("bundles").add(b);}
        GamblersDodgeGameTest.merge(data,JsonParser.parseString("""
            {"version":"compendium-2026-10-05","buffs":[{"definition":{"id":"test:other_shield","version":"compendium-2026-10-05","duration":10,"components":{"numbers":{"capacity":{"initial":20,"unit":"damage"}}}},"shield":{"capacity":"capacity"}}],"bundles":[{"id":"test:other_shield","rules":[{"id":"arm","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:grant_buff","buff":"test:other_shield"}]}]}]}
            """).getAsJsonObject());
    }
    static ProjectileGameTest.Harness open(GameTestHelper h)throws Exception{var t=new ProjectileGameTest.Harness(h,"shield_throw",ShieldThrowGameTest::prepare,true);t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);return t;}
    static EffectState state(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain();}
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t,String name){return state(t).buffs().instances().values().stream().filter(b->b.definition().id().equals(name)&&b.key().holder().equals(id(t.owner))).findFirst();}
    static double capacity(ProjectileGameTest.Harness t,String name){return buff(t,name).map(b->b.components().numbers().get("capacity")).orElse(0.0);}
    static void select(ProjectileGameTest.Harness t,boolean yes){t.runtime.abilities(new AbilityChange(id(t.owner),state(t).abilities().getOrDefault(id(t.owner),AbilityLoadout.EMPTY),yes?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY));}
    static void cast(ProjectileGameTest.Harness t){t.h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"Shield Throw accepted");}
    static void settled(ProjectileGameTest.Harness t){t.h.assertTrue(t.runtime.failure().isEmpty()&&t.runtime.state().idle(),"shield runtime failed: "+t.runtime.failure());}
    static void grant(ProjectileGameTest.Harness t,String name,double amount,double duration){
        var source=new EffectSource(name,"test:void_granter",id(t.owner),new BuffInstance.Origin(id(t.owner),name,"",""),Set.of(),Map.of("amount",new Measure(amount,Unit.DAMAGE),"duration",new Measure(duration,Unit.SECOND)));
        t.runtime.bind(source);t.runtime.start(new RuleEngine.Signal("test:grant_void",new EffectEvent(id(t.owner),id(t.owner),source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",name,"bundle",source.bundle()))));settled(t);
    }
    @GameCase public void actualPaidThrowHitsFiveTargetsUnderExplicitContinuationCalibrationAndCapsSharedShield(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            for(int i=0;i<6;i++)ThreadedSpikeGameTest.target(t,2.5,44+i*2,3.5,1000);
            select(t,true);cast(t);select(t,false);var p=t.projectiles.getFirst();for(int i=0;i<50&&!p.isRemoved();i++)p.tick();settled(t);
            h.assertTrue(p.isRemoved()&&p.progress().entityContacts()==5,"four shared continuations must end at fifth contact in this calibration");h.assertValueEqual(t.hits.size(),5,"wrong physical contact count");
            for(int i=0;i<5;i++){near(h,t.entities.get(i).getHealth(),600,"actual 400 Void melee damage");h.assertValueEqual(t.hits.get(i).source().ability(),ABILITY,"ability credit");}
            near(h,t.entities.get(5).getHealth(),1000,"sixth target hit after contact budget ended");near(h,capacity(t,BUFF),45,"shared shield overflow invented capacity");
            near(h,state(t).resources().get(new ResourceState.Key(id(t.owner),D+"shield_throw_energy")).value(),0,"paid throw refunded after deselection");
        }h.succeed();
    }
    @GameCase public void actualGuardianReceivesSeventyDamageAndThrowerReceivesFifteenShield(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            var target=NativeMeleeGameTest.player(h);t.entities.add(target);target.setPos(h.absoluteVec(new Vec3(2.5,46,3.5)));target.setNoGravity(true);target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(1000);
            select(t,true);cast(t);var p=t.projectiles.getFirst();for(int i=0;i<20&&t.hits.isEmpty();i++)p.tick();settled(t);
            near(h,target.getHealth(),930,"PvE runtime must still use Guardian damage");near(h,capacity(t,BUFF),15,"one actual hit must grant fifteen shield");
        }h.succeed();
    }
    @GameCase public void nativeDamageClassifiesActualCreditOwnerAndOnlyCombatantShieldBudgetGetsReduction(GameTestHelper h)throws Exception{
        for(String kind:List.of("mob","player","environment"))try(var t=open(h)){
            t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);t.owner.setHealth(200);grant(t,"shield",45,10);
            var actor=kind.equals("player")?NativeMeleeGameTest.player(h):t.cow(4,40,3.5);if(kind.equals("player"))t.entities.add(actor);
            var damage=kind.equals("environment")?h.getLevel().damageSources().generic():kind.equals("player")?h.getLevel().damageSources().playerAttack((net.minecraft.world.entity.player.Player)actor):h.getLevel().damageSources().mobAttack(actor);
            t.owner.hurtServer(h.getLevel(),damage,170);settled(t);near(h,t.owner.getHealth(),kind.equals("mob")?180:75,"actual shield-only damage resistance for "+kind);
            h.assertTrue(buff(t,BUFF).isEmpty(),"fully broken shield retained defensive Buff");
        }h.succeed();
    }
    @GameCase public void otherShieldLayersKeepFifoOrderAndRecreatedVoidPoolGetsANewAge(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            grant(t,"first",45,10);long old=buff(t,BUFF).orElseThrow().generation();
            t.runtime.bind(new EffectSource("other","test:other_shield",id(t.owner),new BuffInstance.Origin(id(t.owner),"other","",""),Set.of()));
            var actor=t.cow(4,40,3.5);t.owner.hurtServer(h.getLevel(),h.getLevel().damageSources().mobAttack(actor),160);settled(t);
            near(h,t.owner.getHealth(),10,"shield overflow bypassed later layer");near(h,capacity(t,"test:other_shield"),10,"later layer did not absorb unscaled ten");h.assertTrue(buff(t,BUFF).isEmpty(),"broken Void layer remained");
            grant(t,"second",15,10);h.assertTrue(buff(t,BUFF).orElseThrow().generation()>old,"recreated shield reused FIFO age");
            // Compute against the actual remaining world state, avoiding native hurt invulnerability timers.
            var origin=new BuffInstance.Origin(id(actor),"probe","","",Set.of("chorus:combatant"));var command=new com.imdomestic.chorus.effect.combat.DamageCommand(id(t.owner),origin,30,"minecraft:generic",Set.of(),Set.of(),false);
            var plan=t.runtime.program().shields(state(t),command,30);h.assertValueEqual(plan.layers().getFirst().before().definition().id(),"test:other_shield","recreated Void jumped ahead of older layer");near(h,plan.layers().getLast().after(),9,"only residual twenty gets Void resistance");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:void_shield_expiry",maxTicks=30)
    public void realTicksExpireASourceProvidedWindowAfterItsGrantorIsUnbound(GameTestHelper h)throws Exception{
        var t=open(h);try{grant(t,"short",15,.2);t.runtime.unbind("short");t.finish(7,()->{h.assertTrue(buff(t,BUFF).isEmpty(),"shield window did not expire independently");near(h,t.owner.getHealth(),10,"shield expiry changed health");});}catch(Throwable e){t.close();throw e;}
    }
    @GameCase public void actualWallBounceAndGravityDoNotCountAsAHitGrant(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            select(t,true);cast(t);var p=t.projectiles.getFirst();p.setPos(t.owner.position().add(0,8,0));p.setDeltaMovement(new Vec3(0,-1,0));
            var block=BlockPos.containing(t.owner.position().add(0,6,0));t.blocks.put(block,h.getLevel().getBlockState(block));h.getLevel().setBlockAndUpdate(block,Blocks.STONE.defaultBlockState());
            for(int i=0;i<6&&p.progress().bounces()==0;i++)p.tick();settled(t);
            h.assertTrue(p.progress().bounces()==1&&!p.isRemoved(),"actual wall did not bounce");h.assertTrue(t.hits.isEmpty()&&buff(t,BUFF).isEmpty(),"wall contact granted overshield");
        }h.succeed();
    }
    @GameCase public void unknownRealDamageRetainsPaymentAndHealthLossWithoutGrantingShieldOrRepeating(GameTestHelper h)throws Exception{
        try(var t=open(h)){
            var target=ThreadedSpikeGameTest.target(t,2.5,46,3.5,1000);select(t,true);cast(t);t.failAfterDamage=true;var p=t.projectiles.getFirst();for(int i=0;i<30&&!p.isRemoved();i++)p.tick();
            h.assertTrue(t.runtime.failure().isPresent()&&p.isRemoved(),"unknown hit did not stop runtime");near(h,target.getHealth(),600,"known world damage rolled back");h.assertTrue(buff(t,BUFF).isEmpty(),"unknown hit invented shield");p.tick();h.assertValueEqual(t.hits.size(),1,"unknown hit replayed");
        }h.succeed();
    }
}
