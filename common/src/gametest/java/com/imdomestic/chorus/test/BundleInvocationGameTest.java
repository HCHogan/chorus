package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;

/** Synthetic projectile producer invokes the actual Slow/Freeze bundle after its source is removed. */
public class BundleInvocationGameTest {
    static CompiledEffects program(){return CompiledEffects.link(List.of(DuranceGameTest.program().program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("bundle_invocation_slow")).getOrThrow()));}
    static EffectSource source(String id,LivingEntity owner){String holder=owner.getUUID().toString();return new EffectSource(id,"test:slow_invocation_host",holder,new BuffInstance.Origin(holder,id,"","test:projectile"),Set.of());}
    static void send(FreezeGameTest.Harness t,EffectSource source,LivingEntity target,String type,double stacks){
        t.runtime.start(new RuleEngine.Signal("test:"+type,new EffectEvent(source.holder(),target.getUUID().toString(),source.origin(),Set.of(),Map.of("requested",new Measure(stacks,Unit.COUNT),"stacks",new Measure(99,Unit.COUNT)),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));t.settled();
    }
    @GameCase(environment="chorus_gametest:invocation_flight",maxTicks=60)
    public void realProjectileAfterUnequipInvokesSlowWithCapturedCreditAndCurrentDurance(GameTestHelper h){
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try {
            var caster=t.player(2);caster.setYRot(-90);caster.setXRot(0);var victim=t.mob(EntityTypes.SKELETON,8,2,"elite");var s=source("projectile",caster);t.runtime.bind(s);
            send(t,s,victim,"throw_slow",40);t.runtime.unbind(s.instance());t.runtime.bind(DuranceGameTest.fragment("durance",caster));
            h.runAfterDelay(30,()->{try(t){t.runtime.prepare();var slow=SlowGameTest.slow(t,victim).orElseThrow();h.assertValueEqual(slow.count(),40,"invocation failed to override incoming measurement");h.assertValueEqual(slow.origin(),s.origin(),"unequipping lost original projectile attribution");h.assertValueEqual(t.checks.getLast().duration(),4_000_000L,"Durance must be queried at actual contact");
                h.assertTrue(t.runtime.state().engine().domain().sources().values().stream().noneMatch(v->v.bundle().equals("chorus_d2:slow_application")||v.instance().equals(s.instance())),"invocation leaked a live source");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:invocation_freeze")
    public void invokedSlowReusesInheritedFreezeAuthorizationAndFinalApplierCredit(GameTestHelper h){
        try(var t=new FreezeGameTest.Harness(h,program(),EffectState.empty())){
            var caster=t.player(12);var other=t.player(14);var victim=t.player(2);var a=source("first",caster);var b=source("last",other);t.runtime.bind(a);t.runtime.bind(b);
            send(t,a,victim,"invoke_slow",60);h.assertValueEqual(SlowGameTest.slow(t,victim).orElseThrow().origin(),a.origin(),"initial Slow source");
            t.deniedStatus="chorus_d2:freeze";send(t,b,victim,"invoke_slow",40);h.assertValueEqual(SlowGameTest.slow(t,victim).orElseThrow().count(),100,"denied Freeze consumed committed Slow");h.assertTrue(t.status(victim).isEmpty(),"denied Freeze applied");
            t.deniedStatus="";send(t,b,victim,"invoke_slow",1);h.assertTrue(SlowGameTest.slow(t,victim).isEmpty(),"successful Freeze did not clear Slow");h.assertValueEqual(t.status(victim).orElseThrow().origin(),b.origin(),"threshold source attribution");h.assertValueEqual(t.checks.getLast().duration(),1_350_000L,"inherited Freeze timing");h.assertValueEqual(NativeMovementGameTest.mask(victim),15,"inherited Freeze movement restriction");t.settled();
        }h.succeed();
    }
}
