package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Source-calibrated Slow, successful conversion, actual attributes, jump impulse and ability gates. */
public class SlowGameTest {
    public static CompiledEffects program(){return CompiledEffects.link(List.of("slow","freeze","freeze_test_falloff","freeze_inputs","combat_damage","movement_attributes","weapon_stats","slow_inputs","amplified","amplified_movement","amplified_inputs").stream().map(name->EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(name)).getOrThrow()).toList());}
    public static EffectSource source(LivingEntity actor,double duration){
        var freeze=FreezeGameTest.source(actor);var params=new HashMap<>(freeze.parameters());var calibration=ThreadedSpikeGameTest.json("slow_test_calibration");
        params.put("slow_duration",new Measure(duration,Unit.SECOND));params.put("slow_jump_delta",new Measure(calibration.getAsJsonObject("slow_jump_delta").get("value").getAsDouble(),Unit.DELTA));
        return new EffectSource(actor.getUUID()+"/slow","chorus_d2:slow_application",freeze.holder(),freeze.origin(),Set.of(),params);
    }
    public static void event(MinecraftEffectRuntime runtime,EffectSource source,LivingEntity target,String type,double stacks){runtime.start(new RuleEngine.Signal("chorus_d2:"+type,new EffectEvent(source.holder(),target.getUUID().toString(),source.origin(),Set.of(),Map.of("stacks",new Measure(stacks,Unit.COUNT)),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));}
    static Optional<BuffInstance> slow(FreezeGameTest.Harness t,LivingEntity actor){return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:slow")&&b.key().holder().equals(actor.getUUID().toString())).findFirst();}
    static EffectSource caster(FreezeGameTest.Harness t,double duration){var s=source(t.mob(EntityTypes.COW,12,2,""),duration);t.runtime.bind(s);return s;}
    static void apply(FreezeGameTest.Harness t,EffectSource source,LivingEntity target,double stacks){event(t.runtime,source,target,"apply_slow",stacks);t.settled();}
    static void clear(FreezeGameTest.Harness t,EffectSource source,LivingEntity target){event(t.runtime,source,target,"clear_slow",0);t.settled();}
    @GameCase(environment="chorus_gametest:slow_attributes")
    public void actualGuardianAndCombatantAttributesJumpImpulseAndMovementAbilityHaveSeparatePenalties(GameTestHelper h){
        try(var t=new FreezeGameTest.Harness(h,program(),EffectState.empty())){
            var s=caster(t,2);var player=t.player(2);var npc=t.mob(EntityTypes.COW,4,2,"elite");
            for(var actor:List.of(player,npc)){
                double speed=actor.getAttributeValue(Attributes.MOVEMENT_SPEED),jump=actor.getAttributeValue(Attributes.JUMP_STRENGTH);actor.setDeltaMovement(Vec3.ZERO);actor.jumpFromGround();double impulse=actor.getDeltaMovement().y;
                apply(t,s,actor,40);near(h,actor.getAttributeValue(Attributes.MOVEMENT_SPEED),speed*.5,"native ground speed");near(h,actor.getAttributeValue(Attributes.JUMP_STRENGTH),jump*(actor==player?.7:1),"calibrated jump strength");actor.setDeltaMovement(Vec3.ZERO);actor.jumpFromGround();near(h,actor.getDeltaMovement().y,impulse*(actor==player?.7:1),"actual native jump impulse");h.assertValueEqual(NativeMovementGameTest.mask(actor),0,"Slow must permit movement and jump");
                if(actor==player){String holder=player.getUUID().toString();var move=new AbilityLoadout(Map.of("test:slow_slot","test:slow_move"));var plain=new AbilityLoadout(Map.of("test:slow_slot","test:slow_plain"));t.runtime.abilities(new AbilityChange(holder,AbilityLoadout.EMPTY,move));h.assertValueEqual(t.runtime.useAbility(player,"test:slow_slot").outcome(),AbilityUse.Outcome.RESTRICTED,"movement ability gate");t.runtime.abilities(new AbilityChange(holder,move,plain));h.assertValueEqual(t.runtime.useAbility(player,"test:slow_slot").outcome(),AbilityUse.Outcome.ACCEPTED,"ordinary ability");}
                clear(t,s,actor);near(h,actor.getAttributeValue(Attributes.MOVEMENT_SPEED),speed,"speed restored");near(h,actor.getAttributeValue(Attributes.JUMP_STRENGTH),jump,"jump restored");
            }t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:slow_conversion")
    public void successfulHundredStackConversionRemovesSlowAndThawNeverRestoresConsumedStacks(GameTestHelper h){
        try(var t=new FreezeGameTest.Harness(h,program(),EffectState.empty())){
            var s=caster(t,2);var player=t.player(2);double speed=player.getAttributeValue(Attributes.MOVEMENT_SPEED);apply(t,s,player,40);h.assertTrue(t.status(player).isEmpty(),"early freeze");apply(t,s,player,60);h.assertTrue(slow(t,player).isEmpty()&&t.status(player).isPresent(),"conversion failed");near(h,player.getAttributeValue(Attributes.MOVEMENT_SPEED),speed,"Slow attribute retained during Freeze");h.assertValueEqual(NativeMovementGameTest.mask(player),15,"Freeze controls missing");
            t.clear(s,player);h.assertValueEqual(NativeMovementGameTest.mask(player),0,"thaw failed");apply(t,s,player,1);h.assertValueEqual(slow(t,player).orElseThrow().count(),1,"consumed stacks restored");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:slow_rejection")
    public void deniedFreezeRetainsCommittedSlowAndCanRetryAfterHostAuthorizationChanges(GameTestHelper h){
        try(var t=new FreezeGameTest.Harness(h,program(),EffectState.empty())){
            var s=caster(t,2);var npc=t.mob(EntityTypes.COW,2,2,"elite");double speed=npc.getAttributeValue(Attributes.MOVEMENT_SPEED);t.deniedStatus="chorus_d2:freeze";apply(t,s,npc,150);h.assertValueEqual(slow(t,npc).orElseThrow().count(),100,"denied Freeze consumed Slow");near(h,npc.getAttributeValue(Attributes.MOVEMENT_SPEED),speed*.5,"retained Slow penalty");h.assertTrue(t.status(npc).isEmpty(),"denied Freeze applied");t.deniedStatus="";apply(t,s,npc,1);h.assertTrue(slow(t,npc).isEmpty()&&t.status(npc).isPresent(),"retry failed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:slow_expiry",maxTicks=65)
    public void shorterRefreshKeepsOriginalExpiryAndAnotherCasterOwnsThresholdConversion(GameTestHelper h){
        var t=new FreezeGameTest.Harness(h,program(),EffectState.empty());try{
            var a=caster(t,2);var b=caster(t,.5);var npc=t.mob(EntityTypes.COW,2,2,"elite");var frozen=t.mob(EntityTypes.COW,4,2,"elite");double speed=npc.getAttributeValue(Attributes.MOVEMENT_SPEED);apply(t,a,npc,40);long deadline=slow(t,npc).orElseThrow().deadline();apply(t,a,frozen,40);apply(t,b,frozen,60);h.assertValueEqual(t.status(frozen).orElseThrow().origin(),b.origin(),"threshold source credit");
            h.runAfterDelay(10,()->{try{apply(t,b,npc,30);h.assertValueEqual(slow(t,npc).orElseThrow().deadline(),deadline,"shorter source shortened deadline");h.assertValueEqual(slow(t,npc).orElseThrow().origin(),a.origin(),"refresh changed Slow credit");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(30,()->{try{t.runtime.prepare();h.assertTrue(slow(t,npc).isPresent(),"shorter refresh expired shared Slow");near(h,npc.getAttributeValue(Attributes.MOVEMENT_SPEED),speed*.5,"active penalty missing");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(45,()->{try(t){t.runtime.prepare();h.assertTrue(slow(t,npc).isEmpty()&&t.status(npc).isEmpty(),"Slow expiry froze target");near(h,npc.getAttributeValue(Attributes.MOVEMENT_SPEED),speed,"expiry failed to restore movement");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:slow_amplified")
    public void slowMultipliesAmplifiedSpeedAndCleansingPreservesTheIndependentArcContribution(GameTestHelper h){
        var p=program();try(var t=new FreezeGameTest.Harness(h,p,EffectState.empty())){
            var s=caster(t,2);var player=t.player(2);String id=player.getUUID().toString();double base=player.getAttributeValue(Attributes.MOVEMENT_SPEED);t.runtime.bind(AmplifiedGameTest.source("inputs","test:amplified_inputs",id));t.runtime.bind(AmplifiedGameTest.calibration(id));t.runtime.start(AmplifiedGameTest.grant(id,id,10));near(h,player.getAttributeValue(Attributes.MOVEMENT_SPEED),base*1.2,"Amplified speed");apply(t,s,player,1);near(h,player.getAttributeValue(Attributes.MOVEMENT_SPEED),base*.6,"independent Slow factor");var query=new EffectEvent(id,id,s.origin(),Set.of(),Map.of());near(h,p.calculate(t.runtime.state().engine().domain(),id,query,"chorus_d2:weapon_handling",new Measure(80,Unit.STAT_POINT),List.of()).output().value(),30,"Handling perks precede Slow and cap");clear(t,s,player);near(h,player.getAttributeValue(Attributes.MOVEMENT_SPEED),base*1.2,"cleanse erased Amplified");t.settled();
        }h.succeed();
    }
}
