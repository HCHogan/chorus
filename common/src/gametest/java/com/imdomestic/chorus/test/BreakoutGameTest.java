package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class BreakoutGameTest {
    public static CompiledEffects program(){return CompiledEffects.link(List.of(FreezeGameTest.program().program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("breakout_inputs")).getOrThrow()));}
    public static EffectSource calibration(LivingEntity actor,double delay){
        String holder=actor.getUUID().toString();var json=ThreadedSpikeGameTest.json("breakout_test_calibration");var params=new HashMap<String,Measure>();for(String key:List.of("health_cost","health_floor"))params.put(key,new Measure(json.getAsJsonObject(key).get("value").getAsDouble(),Unit.DAMAGE));params.put("breakout_time",new Measure(delay,Unit.SECOND));
        return new EffectSource(holder+"/breakout_calibration","chorus_d2:breakout_calibration",holder,new BuffInstance.Origin(holder,"breakout-calibration","",""),Set.of("chorus_d2:breakout_calibrated"),params);
    }
    public static void select(MinecraftEffectRuntime runtime,LivingEntity actor){runtime.abilities(new AbilityChange(actor.getUUID().toString(),AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("chorus_d2:class","test:class_cost","chorus_d2:super","test:super"))));}
    @GameCase(environment="chorus_gametest:breakout_air",maxTicks=35)
    public void airborneClassInputStartsOnceThenPaysHealthAndRestoresTheOriginalClassAbility(GameTestHelper h){
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try{
            var source=t.caster();var player=t.player(2);player.setHealth(20);select(t.runtime,player);t.runtime.bind(calibration(player,.5));t.apply(source,player);player.setOnGround(false);var used=t.runtime.useAbility(player,"chorus_d2:class");h.assertValueEqual(used.resolved(),"chorus_d2:breakout","replacement");h.assertValueEqual(used.outcome(),AbilityUse.Outcome.ACCEPTED,"airborne Breakout denied");h.assertTrue(used.cost().isEmpty(),"Breakout spent base class energy");h.assertValueEqual(t.runtime.useAbility(player,"chorus_d2:class").outcome(),AbilityUse.Outcome.CONDITION,"duplicate input restarted Breakout");
            h.runAfterDelay(5,()->{try{t.runtime.prepare();h.assertTrue(t.payments.isEmpty()&&t.status(player).isPresent(),"Breakout finished before calibrated time");near(h,player.getHealth(),20,"premature payment");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(12,()->{try(t){t.runtime.prepare();near(h,player.getHealth(),15,"actual Breakout cost");h.assertTrue(t.status(player).isEmpty()&&t.damage.isEmpty(),"payment retained Freeze or caused Shatter");h.assertValueEqual(NativeMovementGameTest.mask(player),0,"Breakout controls not released");h.assertValueEqual(t.payments.size(),1,"payment repeated");h.assertValueEqual(t.payments.getFirst().command().source().owner(),player.getUUID().toString(),"payment attributed to freezer");var original=t.runtime.useAbility(player,"chorus_d2:class");h.assertValueEqual(original.resolved(),"test:class_cost","base class not restored");h.assertValueEqual(original.outcome(),AbilityUse.Outcome.ACCEPTED,"base energy was consumed");near(h,original.cost().orElseThrow().receipt().paid(),1,"original class cost");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:breakout_generation",maxTicks=45)
    public void oldBreakoutCannotChargeOrClearANewFreezeGeneration(GameTestHelper h){
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try{
            var source=t.caster();var player=t.player(2);player.setHealth(20);select(t.runtime,player);t.runtime.bind(calibration(player,.5));t.apply(source,player);t.runtime.useAbility(player,"chorus_d2:class");t.clear(source,player);t.apply(source,player);long generation=t.status(player).orElseThrow().generation();
            h.runAfterDelay(12,()->{try{t.runtime.prepare();h.assertTrue(t.payments.isEmpty(),"old generation charged");h.assertValueEqual(t.status(player).orElseThrow().generation(),generation,"old generation cleared new Freeze");h.assertValueEqual(t.runtime.useAbility(player,"chorus_d2:class").outcome(),AbilityUse.Outcome.ACCEPTED,"new generation cannot break out");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(24,()->{try(t){t.runtime.prepare();h.assertTrue(t.status(player).isEmpty(),"new Breakout failed");near(h,player.getHealth(),15,"generation charged twice");h.assertValueEqual(t.payments.size(),1,"multiple payments");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:breakout_low_health",maxTicks=35)
    public void lowHealthAndDetachedCalibrationStillFinishTheAcceptedBreakout(GameTestHelper h){
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try{
            var source=t.caster();var low=t.player(2);var below=t.player(4);low.setHealth(3);below.setHealth(.5f);
            for(var player:List.of(low,below)){select(t.runtime,player);var cal=calibration(player,.5);t.runtime.bind(cal);t.apply(source,player);t.runtime.useAbility(player,"chorus_d2:class");t.runtime.unbind(cal.instance());}
            h.runAfterDelay(12,()->{try(t){t.runtime.prepare();near(h,low.getHealth(),1,"nonlethal floor");near(h,below.getHealth(),.5,"below floor healed or died");h.assertTrue(low.isAlive()&&below.isAlive()&&t.status(low).isEmpty()&&t.status(below).isEmpty(),"accepted Breakout did not finish");h.assertValueEqual(t.payments.size(),2,"missing accepted payment");h.assertTrue(t.damage.isEmpty(),"health payment produced damage");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:breakout_fault",maxTicks=35)
    public void paymentReceiptFailureKeepsHealthWriteButDoesNotThawOrReplay(GameTestHelper h){
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try{
            var source=t.caster();var player=t.player(2);player.setHealth(20);select(t.runtime,player);t.runtime.bind(calibration(player,.5));t.apply(source,player);t.failPayment=true;t.runtime.useAbility(player,"chorus_d2:class");
            h.runAfterDelay(15,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown receipt did not stop runtime");near(h,player.getHealth(),15,"committed cost rolled back");h.assertTrue(t.status(player).isPresent(),"unknown receipt thawed target");t.runtime.prepare();h.assertValueEqual(t.payments.size(),1,"unknown payment replayed");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
