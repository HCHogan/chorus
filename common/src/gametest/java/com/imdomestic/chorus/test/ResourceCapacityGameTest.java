package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Native observations of explicit capacity transitions; synthetic values, not exotic tuning. */
public class ResourceCapacityGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final LivingEntity owner; final MinecraftEffectRuntime runtime; int heals; boolean fail;
        Harness(GameTestHelper h) {
            this.h=h;owner=h.spawnWithNoFreeWill(EntityTypes.COW,2,4,2);owner.setNoGravity(true);owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);owner.setHealth(10);
            var p=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("resource_capacity")).getOrThrow();
            var world=new MinecraftWorldActions(h.getLevel(),id->h.getLevel().getEntity(UUID.fromString(id)) instanceof LivingEntity entity?entity:null,
                    _->h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),p,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{
                var result=world.apply(request);if(request.command() instanceof HealingCommand){heals++;if(fail)throw new IllegalStateException("Unknown native resize followup");}return result;
            },MinecraftEffectRuntime::nativeSource);
            runtime.bind(new EffectSource("input","test:capacity_inputs",id(),new BuffInstance.Origin(id(),"input","",""),Set.of()));
        }
        String id(){return owner.getUUID().toString();} ResourceState account(){return runtime.state().engine().domain().resources().get(new ResourceState.Key(id(),"test:energy"));}
        void send(String kind,double value){runtime.start(new RuleEngine.Signal("test:"+kind,new EffectEvent(id(),id(),new BuffInstance.Origin(id(),"input","",""),Set.of(),Map.of("amount",new Measure(value,Unit.CHARGE)))));}
        void healthy(){runtime.prepare();h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"capacity runtime failed: "+runtime.failure());}
        void at(int ticks,Runnable assertion){h.runAfterDelay(ticks,()->{try{healthy();assertion.run();}catch(RuntimeException|Error e){close();throw e;}});}
        @Override public void close(){runtime.close();owner.discard();}
    }
    @GameCase public void expandingAndShrinkingKeepOneAccountAndUseActualDiscardedEnergyForHealing(GameTestHelper h) {
        try(var t=new Harness(h)){
            t.send("grant",.7);t.send("resize",3);near(h,t.account().value(),.7,"expansion generated energy");t.send("grant",2);t.send("resize",1);
            near(h,t.account().value(),1,"shrink did not clip");near(h,t.owner.getHealth(),15.7,"capacity and discarded results did not drive native healing");
            t.send("resize",1);h.assertValueEqual(t.heals,3,"same capacity repeated side effects");t.send("resize",3);near(h,t.account().value(),1,"discarded overflow restored");
            h.assertValueEqual(t.runtime.state().engine().domain().resources().size(),1,"resize changed account identity");t.healthy();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:resource_capacity_ticks",maxTicks=30)
    public void realTicksSettleEachCeilingAndDoNotBankTimeSpentAtTheSmallerCapacity(GameTestHelper h) {
        var t=new Harness(h);
        try{
            t.send("grant",.9);long[] boundary=new long[2];double[] balance=new double[1];
            t.at(3,()->{near(h,t.account().value(),1,"old ceiling");t.send("resize",3);boundary[0]=t.runtime.nowMicros();});
            t.at(9,()->{near(h,t.account().value(),1+(t.runtime.nowMicros()-boundary[0])/1_000_000.0,"expanded interval");t.send("resize",.25);});
            t.at(15,()->{near(h,t.account().value(),.25,"smaller full capacity banked recovery");t.send("resize",3);balance[0]=t.account().value();boundary[1]=t.runtime.nowMicros();});
            t.at(21,()->{try(t){near(h,t.account().value(),balance[0]+(t.runtime.nowMicros()-boundary[1])/1_000_000.0,"regrowth restored discarded energy or elapsed time");h.succeed();}});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase public void unknownNativeFollowupRetainsClippedEnergyAndActualHealingWithoutReplay(GameTestHelper h) {
        try(var t=new Harness(h)){
            t.send("resize",3);t.send("grant",2.5);t.fail=true;
            try{t.send("resize",1);}catch(IllegalStateException expected){/* retain committed domain and world writes */}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown native result did not stop runtime");near(h,t.account().value(),1,"clipped energy rolled back");near(h,t.account().capacity(),1,"capacity rolled back");
            near(h,t.owner.getHealth(),14,"actual followup lost or discarded-energy reaction ran after failure");t.runtime.prepare();h.assertValueEqual(t.heals,2,"unknown followup replayed");
        }h.succeed();
    }
}
