package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class ActorBindingGameTest {
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{return new ProjectileGameTest.Harness(h,"projectile",data->{GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json("actor_binding"));AscensionGameTest.version(data);});}
    static String owner(ProjectileGameTest.Harness t){return t.owner.getUUID().toString();}
    static ActorBinding binding(double power){return new ActorBinding("test:actor",Set.of("test:standing"),Map.of("power",new Measure(power,Unit.DELTA)));}
    static void change(MinecraftEffectRuntime runtime,String holder,ActorBinding after){runtime.actor(new ActorChange(holder,Optional.ofNullable(runtime.state().engine().domain().actors().get(holder)),Optional.ofNullable(after)));}
    static double power(MinecraftEffectRuntime runtime,String holder){var e=new EffectEvent(holder,holder,new BuffInstance.Origin(holder,"query","",""),Set.of(),Map.of());return runtime.program().calculate(runtime.state().engine().domain(),holder,e,"test:actor_damage",new Measure(10,Unit.DAMAGE),List.of()).output().value();}
    static void refused(GameTestHelper h,Runnable action){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}h.fail("Invalid actor request was accepted");}
    @GameCase(environment="chorus_gametest:actor_lifecycle",maxTicks=50)
    public void nativeAttachReplaceAndAbilityChangesKeepOneStandingOwnerSource(GameTestHelper h){ready(h,()->{
        var t=harness(h);try{
            change(t.runtime,owner(t),binding(1));near(h,t.owner.getHealth(),11,"actual attachment heal");near(h,power(t.runtime,owner(t)),20,"composed parameterized modifier");
            var slots=new AbilityLoadout(Map.of("test:grenade","test:bolt"));t.runtime.abilities(new AbilityChange(owner(t),AbilityLoadout.EMPTY,slots));t.runtime.abilities(new AbilityChange(owner(t),slots,AbilityLoadout.EMPTY));
            change(t.runtime,owner(t),binding(1));near(h,t.owner.getHealth(),11,"idempotent enrollment must not heal twice");change(t.runtime,owner(t),binding(2));near(h,t.owner.getHealth(),14,"old detach plus new attach");near(h,power(t.runtime,owner(t)),30,"new complete actor configuration");
            refused(h,()->t.runtime.unbind(ActorSources.instance(owner(t))));refused(h,()->t.runtime.actor(new ActorChange(owner(t),Optional.empty(),Optional.of(binding(1)))));
            h.assertTrue(t.runtime.failure().isEmpty(),"known preflight rejection poisoned runtime");
            t.finish(3,()->{near(h,t.owner.getHealth(),17,"one new source timer");h.assertValueEqual(t.runtime.state().engine().domain().actors(),Map.of(owner(t),binding(2)),"actor persisted through time and selections");change(t.runtime,owner(t),null);near(h,t.owner.getHealth(),19,"confirmed detach");h.assertTrue(t.runtime.state().engine().domain().actors().isEmpty(),"actor was not removed");});
        }catch(RuntimeException|Error e){t.close();throw e;}
    });}
    @GameCase(environment="chorus_gametest:actor_restore",maxTicks=50)
    public void nativeCheckpointRestoresStandingRulesAndTimerWithoutReplayingAttachment(GameTestHelper h){ready(h,()->{
        var t=harness(h);MinecraftEffectRuntime restored=null;try{
            change(t.runtime,owner(t),binding(1));var saved=t.runtime.checkpoint();t.runtime.close();restored=MinecraftEffectRuntime.restore(h.getLevel(),t.runtime.program(),saved,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),t.world::apply,MinecraftEffectRuntime::nativeSource);
            var active=restored;near(h,t.owner.getHealth(),11,"restore cannot repeat attachment heal");h.assertValueEqual(active.checkpoint(),saved,"exact managed actor checkpoint");near(h,power(active,owner(t)),20,"restored composed modifier");
            h.runAfterDelay(3,()->{try{near(h,t.owner.getHealth(),14,"restored timer fired once");change(active,owner(t),null);near(h,t.owner.getHealth(),16,"restored detach lifecycle");h.assertTrue(active.failure().isEmpty(),"restored runtime failure");h.succeed();}finally{active.close();t.close();}});
        }catch(RuntimeException|Error e){if(restored!=null)restored.close();t.close();throw e;}
    });}
    @GameCase(environment="chorus_gametest:actor_unknown",maxTicks=40)
    public void unknownNativeAttachmentHealKeepsEnrollmentAndDoesNotReplayOnRetry(GameTestHelper h){ready(h,()->{try(var t=harness(h)){
        t.failAfterHealing=true;refused(h,()->change(t.runtime,owner(t),binding(1)));near(h,t.owner.getHealth(),11,"already committed native healing");h.assertTrue(t.runtime.failure().isPresent(),"unknown result was acknowledged");
        h.assertValueEqual(t.runtime.state().engine().domain().actors(),Map.of(owner(t),binding(1)),"committed enrollment was rolled back");ActorSources.validate(t.runtime.state().engine().domain());refused(h,()->change(t.runtime,owner(t),binding(1)));MinecraftEffectRuntime.tick(h.getLevel());near(h,t.owner.getHealth(),11,"unknown attachment replayed");h.succeed();
    }});}
}
