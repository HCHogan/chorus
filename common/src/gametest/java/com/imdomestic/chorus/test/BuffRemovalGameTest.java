package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class BuffRemovalGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;final List<LivingEntity> entities=new ArrayList<>();final LivingEntity target,other;
        final EffectSource first,second,cleaner;final MinecraftEffectRuntime runtime;
        final List<HealingCommand> heals=new ArrayList<>();boolean fail;
        Harness(GameTestHelper h){
            this.h=h;target=entity();other=entity();first=source(entity(),"first");second=source(entity(),"second");cleaner=source(entity(),"cleaner");
            var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("buff_removal")).getOrThrow();
            var world=new MinecraftWorldActions(h.getLevel(),id->entities.stream().filter(e->id(e).equals(id)).findFirst().orElse(null),_->h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program,EffectState.empty().withSource(first).withSource(second).withSource(cleaner),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),r->{
                if(r.command() instanceof HealingCommand heal){
                    heals.add(heal);
                    if(heal.amount()==1||heal.amount()==2)h.assertTrue(state().buffs().instances().values().stream().noneMatch(b->b.key().holder().equals(heal.target())&&b.definition().tags().contains("test:active_ability")),"cleanup observed a partial removal batch");
                }
                var receipt=world.apply(r);if(fail&&r.command() instanceof HealingCommand)throw new IllegalStateException("unknown after native batch cleanup");return receipt;
            },MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity entity(){var e=h.spawnWithNoFreeWill(EntityTypes.COW,2+entities.size(),40,2);e.setNoGravity(true);e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);e.setHealth(10);entities.add(e);return e;}
        static String id(LivingEntity e){return e.getUUID().toString();}
        static EffectSource source(LivingEntity e,String name){return new EffectSource(name,"test:removal",id(e),new BuffInstance.Origin(id(e),name,name+"-weapon",""),Set.of());}
        EffectState state(){return runtime.state().engine().domain();}
        void event(String type,EffectSource s,LivingEntity target){runtime.start(new RuleEngine.Signal("test:"+type,new EffectEvent(s.holder(),id(target),s.origin(),Set.of(),Map.of())));}
        @Override public void close(){runtime.close();entities.forEach(LivingEntity::discard);}
    }
    @GameCase(environment="chorus_gametest:buff_removal_lifetime",maxTicks=14)
    public void removingSeveralDefinitionsCommitsBeforeCleanupAndKeepsDetachedNativeHealing(GameTestHelper h){
        var t=new Harness(h);
        try{
            t.event("activate",t.first,t.target);t.event("alpha",t.second,t.target);t.event("activate",t.first,t.other);
            t.event("remove",t.cleaner,t.target);near(h,t.target.getHealth(),14,"all three ended reactions healed");near(h,t.other.getHealth(),10,"other holder unaffected");
            h.assertValueEqual(t.state().buffs().instances().size(),4,"other holder and unrelated status remain");
            h.runAfterDelay(6,()->{try(t){
                t.runtime.prepare();near(h,t.target.getHealth(),20,"only detached continuations survive removal");near(h,t.other.getHealth(),20,"other holder's attached and detached work both survive");
                h.assertValueEqual(t.heals.stream().filter(x->x.target().equals(Harness.id(t.target))).map(HealingCommand::amount).toList(),List.of(1.,1.,2.,3.,3.),"removed holder's exact cleanup and continuation sequence");
                h.assertTrue(t.runtime.failure().isEmpty(),"batch runtime failed");h.succeed();
            }});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:buff_removal_failure",maxTicks=14)
    public void anUnknownCleanupOutcomeKeepsTheWholeBatchRemovedAndDoesNotRepeatHealing(GameTestHelper h){
        var t=new Harness(h);
        try{
            t.event("activate",t.first,t.target);t.fail=true;boolean failed=false;
            try{t.event("remove",t.cleaner,t.target);}catch(IllegalStateException expected){failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"unknown world result must stop runtime");near(h,t.target.getHealth(),11,"first cleanup really committed");
            h.assertTrue(t.state().buffs().instances().values().stream().noneMatch(b->b.definition().tags().contains("test:active_ability")),"part of batch was restored after failure");
            h.runAfterDelay(6,()->{try(t){near(h,t.target.getHealth(),11,"failed cleanup never replayed");h.assertValueEqual(t.heals.size(),1,"no later world operation after unknown outcome");h.succeed();}});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
}
