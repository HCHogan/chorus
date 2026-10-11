package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public class CyclicRechargeGameTest {
    static class Harness implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer player;final MinecraftEffectRuntime runtime;final List<HealingCommand> heals=new ArrayList<>();final List<List<Double>> seen=new ArrayList<>();boolean fail;
        Harness(GameTestHelper h){
            this.h=h;player=NativeMeleeGameTest.player(h);player.setPos(h.absoluteVec(new Vec3(2.5,40,2.5)));player.setNoGravity(true);player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);player.setHealth(10);player.getFoodData().setFoodLevel(0);
            var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("cyclic_recharge")).getOrThrow();
            var world=new MinecraftWorldActions(h.getLevel(),ref->{try{return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e?e:null;}catch(IllegalArgumentException e){return null;}},_ -> h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),r->{var result=world.apply(r);if(r.command() instanceof HealingCommand c){heals.add(c);seen.add(List.of(account("uses").value(),account("meter").value()));if(fail)throw new IllegalStateException("unknown world result after multi-cycle grant");}return result;},MinecraftEffectRuntime::nativeSource);
            bind("input","test:input",Map.of());runtime.abilities(new AbilityChange(id(),AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:melee","test:cyclic"))));
        }
        String id(){return player.getUUID().toString();}ResourceState account(String name){return runtime.state().engine().domain().resources().get(new ResourceState.Key(id(),"test:"+name));}
        void bind(String id,String bundle,Map<String,Measure> params){runtime.bind(new EffectSource(id,bundle,id(),new BuffInstance.Origin(id(),id,"",""),Set.of(),params));}
        void grant(double amount){runtime.start(new RuleEngine.Signal("test:fixed",new EffectEvent(id(),id(),new BuffInstance.Origin(id(),"input","",""),Set.of(),Map.of("amount",new Measure(amount,Unit.CHARGE)))));}
        void healthy(){runtime.prepare();h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"cyclic recharge runtime failed: "+runtime.failure());}
        @Override public void close(){runtime.close();player.discard();}
    }
    @GameCase public void onePacketPermitsTwoActualServerAbilityUsesAndExposesFinalBalancesToHealing(GameTestHelper h){
        try(var t=new Harness(h)){
            t.grant(2.5);near(h,t.account("uses").value(),2,"two charges");near(h,t.account("meter").value(),.5,"next partial cycle");near(h,t.player.getHealth(),14,"packet and aggregate completion observations");
            h.assertValueEqual(t.seen,List.of(List.of(2.0,.5),List.of(2.0,.5)),"world observer saw a partial transaction");
            h.assertValueEqual(t.runtime.useAbility(t.player,"test:melee").outcome(),AbilityUse.Outcome.ACCEPTED,"first restored charge");h.assertValueEqual(t.runtime.useAbility(t.player,"test:melee").outcome(),AbilityUse.Outcome.ACCEPTED,"second restored charge");h.assertValueEqual(t.runtime.useAbility(t.player,"test:melee").outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"partial meter was spendable");t.healthy();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:cyclic_ticks",maxTicks=35)
    public void realTicksResumeTheRemainingFractionAndStopAfterFillingTheUsableAccount(GameTestHelper h){
        var t=new Harness(h);try{
            t.grant(2.5);h.runAfterDelay(14,()->{try{t.healthy();near(h,t.account("uses").value(),3,"natural completion after packet");near(h,t.account("meter").value(),0,"full account banked another cycle");h.assertValueEqual(t.heals.size(),2,"old threshold rules replayed packet completions");h.succeed();}finally{t.close();}});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase public void currentRecipientPolicyAndDuplicateCompletionSourcesDoNotMultiplyThePacket(GameTestHelper h){
        try(var t=new Harness(h)){
            t.bind("duplicate","test:cycle",Map.of());t.bind("yield","test:yield_override",Map.of("amount",new Measure(2,Unit.CHARGE)));t.grant(1.25);near(h,t.account("uses").value(),2,"current per-cycle yield");near(h,t.account("meter").value(),.25,"partial second cycle");t.runtime.unbind("yield");t.grant(.75);near(h,t.account("uses").value(),3,"subsequent packet ignored source removal");near(h,t.account("meter").value(),0,"full meter residue");near(h,t.player.getHealth(),16,"duplicate completion source multiplied observations");t.healthy();
        }h.succeed();
    }
    @GameCase public void unknownWorldObservationRetainsActualHealingAndBothResourceBalances(GameTestHelper h){
        try(var t=new Harness(h)){
            t.fail=true;try{t.grant(2.5);}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown observation did not stop runtime");near(h,t.account("uses").value(),2,"uses rolled back");near(h,t.account("meter").value(),.5,"partial progress rolled back");near(h,t.player.getHealth(),12,"actual healing rolled back or aggregate callback ran after failure");t.runtime.prepare();h.assertValueEqual(t.heals.size(),1,"unknown healing replayed");
        }h.succeed();
    }
}
