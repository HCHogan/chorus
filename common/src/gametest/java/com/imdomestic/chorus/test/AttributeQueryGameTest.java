package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class AttributeQueryGameTest {
    static String id(LivingEntity e){return e.getUUID().toString();}
    static class Harness implements AutoCloseable {
        final GameTestHelper h;final LivingEntity owner,target;final MinecraftEffectRuntime runtime;final BuffInstance.Origin origin;
        DamageSnapshot attack;
        Harness(GameTestHelper h)throws Exception {
            this.h=h;owner=h.spawnWithNoFreeWill(EntityTypes.COW,2,40,2);target=h.spawnWithNoFreeWill(EntityTypes.COW,4,40,2);
            for(var e:List.of(owner,target)){e.setNoGravity(true);e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);e.setHealth(100);}
            owner.setHealth(10);origin=new BuffInstance.Origin(id(owner),"test:original","weapon","");
            var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("attribute_queries")).getOrThrow();
            var state=EffectState.empty().withSource(source("driver","driver",owner)).withSource(source("attack","attack",owner)).withSource(source("boost","boost",owner));
            var world=new MinecraftWorldActions(h.getLevel(),ref->h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity living?living:null,_ ->h.getLevel().damageSources().mobAttack(owner),(_,_) ->true,_ ->{});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program,state,new EffectClock((_,_) ->new EffectClock.Rate(0,List.of())),world,
                    (victim,source,amount)->attack==null?MinecraftEffectRuntime.nativeSource(victim,source,amount):attack.command(id(victim)));
        }
        EffectSource source(String instance,String bundle,LivingEntity holder){return new EffectSource(instance,"test:"+bundle,id(holder),new BuffInstance.Origin(id(holder),instance,"weapon",""),Set.of());}
        void signal(String type){runtime.start(new RuleEngine.Signal(type,new EffectEvent(id(owner),id(target),origin,Set.of("test:contamination"),Map.of())));h.assertTrue(runtime.failure().isEmpty(),"attribute rule failed");}
        DamageReceipt hit(){target.damageCooldownTime=0;var receipt=MinecraftDamageExecutor.execute("attribute-"+UUID.randomUUID(),target,h.getLevel().damageSources().mobAttack(owner),10,false);h.assertTrue(runtime.failure().isEmpty(),"attribute damage failed");return receipt;}
        @Override public void close(){runtime.close();owner.discard();target.discard();}
    }
    @GameCase(environment="chorus_gametest:attribute_later",maxTicks=30)
    public void actualDelayedHealingSeparatesCapturedAndCurrentAttributesAfterSourceRemoval(GameTestHelper h)throws Exception {
        var t=new Harness(h);try{
            t.runtime.bind(t.source("foreign","boost",t.target));t.signal("test:later");near(h,t.owner.getHealth(),10,"query has no world effect");t.runtime.unbind("boost");t.runtime.unbind("driver");
            h.runAfterDelay(3,()->{try(t){t.runtime.prepare();near(h,t.owner.getHealth(),21,"six captured then five current healing");h.assertTrue(t.runtime.failure().isEmpty(),"delayed attribute failed");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase public void nativeHitsUseFrozenOwnerAndCurrentVictimAttributeContributions(GameTestHelper h)throws Exception {
        try(var t=new Harness(h)){
            t.attack=t.runtime.captureDamage(new DamageCommand("unknown",t.origin,10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:damage")));
            t.runtime.unbind("boost");t.runtime.unbind("attack");t.runtime.bind(t.source("victim","boost",t.target));
            near(h,t.hit().outgoing().orElseThrow().output().value(),17,"frozen owner and live victim");near(h,t.target.getHealth(),83,"first native hit");
            t.runtime.unbind("victim");near(h,t.hit().outgoing().orElseThrow().output().value(),16,"victim contribution removed");near(h,t.target.getHealth(),67,"second native hit");
        }h.succeed();
    }
}
