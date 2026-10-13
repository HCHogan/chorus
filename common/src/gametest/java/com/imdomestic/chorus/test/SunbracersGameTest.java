package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Actual powered-knife receipt exercises the shared qualification; no Warlock subclass assembly is implied. */
public class SunbracersGameTest {
    static final String D="chorus_d2:",EXOTIC=D+"sunbracers";
    static void prepare(JsonObject data){
        SolarGrenadeGameTest.prepare(data);
        for(String name:List.of("sunbracers","lightweight_knife","lightweight_knife_precision_test_calibration")){
            var part=ThreadedSpikeGameTest.json(name);if(name.equals("lightweight_knife"))AscensionGameTest.calibrate(name,part);GamblersDodgeGameTest.merge(data,part);
        }
        AscensionGameTest.version(data);
    }
    static final class Scenario extends SolarGrenadeGameTest.Scenario {
        Scenario(GameTestHelper h)throws Exception{super(h,SunbracersGameTest::prepare);t.runtime.bind(new EffectSource("sun",EXOTIC,owner(),new BuffInstance.Origin(owner(),"sun","",""),Set.of(EXOTIC),Map.of("fastball_speed_delta",new Measure(.25,Unit.DELTA))));AscensionGameTest.choose(t,D+"melee",D+"lightweight_knife");}
        Optional<BuffInstance> readyBuff(){return buff("sunbracers_ready",t.owner);} Optional<BuffInstance> active(){return buff("sunbracers_active",t.owner);}
        LivingEntity knife(){var target=enemy(3);target.setHealth(1);target.setPos(target.getX(),t.owner.getEyeY()-target.getBbHeight()*.5,target.getZ());t.owner.setYRot(-90);t.owner.setXRot(0);h.assertValueEqual(t.runtime.useAbility(t.owner,D+"melee").outcome(),AbilityUse.Outcome.ACCEPTED,"paid powered knife");var knife=t.projectiles.getLast();for(int i=0;i<12&&!knife.isRemoved();i++)knife.tick();h.assertTrue(target.isDeadOrDying(),"physical knife did not kill target");return target;}
    }
    static void scenario(GameTestHelper h,java.util.function.Consumer<Scenario> body){ready(h,()->{var s=new Scenario(h);try{body.accept(s);}catch(RuntimeException|Error e){s.close();throw e;}});}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_cycle",maxTicks=120)
    public void actualPoweredKillArmsAndEveryPhysicalThrowPaysFromTheTimedRegeneration(GameTestHelper h){scenario(h,s->{
        s.knife();h.assertTrue(s.readyBuff().isPresent()&&s.active().isEmpty(),"actual kill did not arm");s.impact();h.assertTrue(s.readyBuff().isEmpty()&&s.active().isPresent(),"first paid throw did not consume Ready");
        for(int delay:List.of(20,40,60,80))s.later(delay,()->{near(h,s.energy(),1,"natural burst regeneration must fund next throw");s.impact();});
        s.finish(85,()->{h.assertValueEqual(s.fields().size(),5,"five independent paid fields under explicit calibration");h.assertTrue(s.active().isEmpty(),"burst did not expire");near(h,s.energy(),.25/199.5,"post-expiry baseline recharge");h.assertValueEqual(s.t.projectiles.size(),6,"one real melee and five real grenades");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_passive",maxTicks=200)
    public void capturedFastballAndTouchLifetimePersistAfterUnequippingBothSources(GameTestHelper h){scenario(h,s->{
        s.touch();s.impact();near(h,s.t.projectiles.getFirst().launch().orElseThrow().parameters().speed(),25,"intrinsic calibrated Fastball");s.t.runtime.unbind("sun");s.t.runtime.unbind("touch");AscensionGameTest.choose(s.t,D+"grenade",null);
        s.later(130,()->h.assertValueEqual(s.fields().size(),1,"eight-second source lifetime plus calibration lost after unequip"));s.finish(167,()->h.assertTrue(s.fields().isEmpty(),"extended field did not expire"));
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_heat",maxTicks=70)
    public void heatRisesUsesTheSelectedSolarCostAndActivatesTheSameRegenerationWithoutThrowing(GameTestHelper h){scenario(h,s->{
        s.knife();s.t.runtime.bind(new EffectSource("heat",D+"heat_rises_aspect",s.owner(),new BuffInstance.Origin(s.owner(),"heat","",""),Set.of(),Map.of("hold_time",new Measure(.3,Unit.SECOND),"receipt_seconds",new Measure(60,Unit.SECOND))));s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.PRESS,1);
        s.later(7,()->{var result=s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.RELEASE,1);h.assertValueEqual(result.cast().orElseThrow().resolved(),D+"heat_rises_consume","held consumption");s.finish(3,()->{h.assertTrue(s.active().isPresent()&&s.readyBuff().isEmpty(),"consume did not activate");h.assertValueEqual(s.t.projectiles.size(),1,"consume threw a grenade");h.assertTrue(s.fields().isEmpty()&&s.buff("heat_rises",s.t.owner).isPresent(),"consume created a field or lost Heat Rises");near(h,s.energy(),.15*201/199.5,"shared timed recharge");});});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_grenade_kill",maxTicks=60)
    public void actualSolarGrenadeKillCannotSubstituteForAPoweredMeleeKill(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(3);target.setHealth(1);s.impact();h.assertTrue(target.isDeadOrDying(),"impact must actually kill");s.finish(2,()->h.assertTrue(s.readyBuff().isEmpty()&&s.active().isEmpty(),"grenade kill armed the exotic"));
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_detach",maxTicks=70)
    public void removingTheExoticSplitsActualRechargeWithoutDeletingAlreadyAcceptedFields(GameTestHelper h){scenario(h,s->{
        s.knife();s.impact();s.later(10,()->{near(h,s.energy(),.5*201/199.5,"burst before unbind");s.t.runtime.unbind("sun");h.assertTrue(s.active().isEmpty()&&s.readyBuff().isEmpty(),"unbound exotic retained active state");});s.finish(20,()->{near(h,s.energy(),101/199.5,"exact baseline handoff");h.assertValueEqual(s.fields().size(),1,"unbind deleted accepted field");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:sunbracers_unknown",maxTicks=60)
    public void unknownNativePoweredKillKeepsItsWorldDeathButCannotInventReadyOrReplay(GameTestHelper h){scenario(h,s->{
        try(s){s.t.failAfterDamage=true;var dead=s.knife();h.assertTrue(s.t.runtime.failure().isPresent()&&dead.isDeadOrDying(),"unknown receipt must preserve actual death");h.assertTrue(s.readyBuff().isEmpty(),"unknown kill invented Ready");int before=s.t.hits.size();MinecraftEffectRuntime.tick(h.getLevel());h.assertValueEqual(s.t.hits.size(),before,"unknown kill replayed");h.succeed();}
    });}
}
