package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class AmplifiedGameTest {
    private static final java.util.concurrent.atomic.AtomicLong DAMAGE_IDS=new java.util.concurrent.atomic.AtomicLong(1000);
    public static final String A="chorus_d2:amplified",S="chorus_d2:speed_booster",W="chorus_d2:speed_booster_windup",C="chorus_d2:arc_kill_progress";
    public static JsonObject data(String name){
        try(var reader=new InputStreamReader(Objects.requireNonNull(AmplifiedGameTest.class.getResourceAsStream("/effects/"+name+".json")),StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
        catch(Exception failure){throw new RuntimeException(failure);}
    }
    public static void merge(JsonObject data){
        for(String name:List.of("amplified","amplified_movement","movement_attributes","weapon_stats"))for(var entry:data(name).entrySet())if(!entry.getKey().equals("version")){
            if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());data.getAsJsonArray(entry.getKey()).addAll(entry.getValue().getAsJsonArray());
        }
    }
    public static CompiledEffects program(){var data=data("amplified_inputs");merge(data);return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();}
    public static EffectSource source(String name,String bundle,String holder){return new EffectSource(name,bundle,holder,new BuffInstance.Origin(holder,name,"",""),Set.of());}
    public static EffectSource calibration(String holder){return new EffectSource("calibration","chorus_d2:arc_movement_calibration",holder,new BuffInstance.Origin(holder,"calibration","",""),Set.of(),Map.of("amplified_speed",new Measure(.2,Unit.DELTA),"speed_booster_speed",new Measure(.5,Unit.DELTA),"speed_booster_jump",new Measure(.1,Unit.DELTA)));}
    public static RuleEngine.Signal grant(String owner,String target,double duration){return new RuleEngine.Signal("test:amplified",new EffectEvent(owner,target,new BuffInstance.Origin(owner,"inputs","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND))));}
    public static int count(com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime runtime,String id,String holder){return runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.definition().id().equals(id)&&b.key().holder().equals(holder)).mapToInt(BuffInstance::count).sum();}
    static String id(LivingEntity e){return e.getUUID().toString();}
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"amplified_inputs",AmplifiedGameTest::merge,true);
        t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        t.runtime.bind(source("inputs","test:amplified_inputs",id(t.owner)));t.runtime.bind(source("intrinsic","chorus_d2:arc_intrinsic",id(t.owner)));t.runtime.bind(calibration(id(t.owner)));return t;
    }
    static void grant(ProjectileGameTest.Harness t,double duration){t.runtime.start(grant(id(t.owner),id(t.owner),duration));}
    static int count(ProjectileGameTest.Harness t,String buff){return count(t.runtime,buff,id(t.owner));}
    static void checked(ProjectileGameTest.Harness t,Runnable body){try{t.runtime.prepare();t.h.assertTrue(t.runtime.failure().isEmpty(),"Amplified runtime failed: "+t.runtime.failure());body.run();}catch(RuntimeException|Error failure){t.close();throw failure;}}
    @GameCase(environment="chorus_gametest:amplified_kills")
    public void actualArcKillsUseObservedVictimWeightsAndProjectOnlyTheKiller(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            double base=t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED);
            for(String tier:List.of("ordinary","elite","ordinary")){
                var victim=t.cow(2.5,46,3.5);if(!tier.equals("ordinary"))victim.addTag("chorus_d2:"+tier);
                t.runtime.start(new RuleEngine.Signal("test:arc_attack",new EffectEvent(id(t.owner),id(victim),new BuffInstance.Origin(id(t.owner),"inputs","",""),Set.of(),Map.of())));
                h.assertTrue(!victim.isAlive(),"real Arc attack did not kill");
            }
            h.assertValueEqual(count(t,A),1,"four quarter-progress stacks activate Amplified");h.assertValueEqual(count(t,C),0,"activation consumes counter");
            near(h,t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED),base*1.2,"calibrated Amplified speed projected");
            var other=t.cow(5,46,3.5);h.assertValueEqual(count(t.runtime,A,id(other)),0,"unrelated target gained Amplified");
            h.assertTrue(t.runtime.failure().isEmpty(),"kill runtime failed: "+t.runtime.failure());h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:amplified_sprint",maxTicks=140)
    public void realTicksMaintainSpeedBoosterAfterAmplifiedThenRestoreSpeedAndJump(GameTestHelper h)throws Exception{
        var t=harness(h);try{
            double speed=t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED),jump=t.owner.getAttributeValue(Attributes.JUMP_STRENGTH);
            t.owner.setSprinting(true); // Include the native sprint modifier in the baseline below.
            speed=t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED);final double sprintSpeed=speed;
            grant(t,3);h.assertValueEqual(count(t,W),1,"sprint began before Amplified");
            h.runAfterDelay(52,()->checked(t,()->{h.assertValueEqual(count(t,S),1,"2.5 second sprint completed");near(h,t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED),sprintSpeed*1.5,"Speed Booster replaces Amplified contribution");near(h,t.owner.getAttributeValue(Attributes.JUMP_STRENGTH),jump*1.1,"explicit jump calibration");}));
            h.runAfterDelay(70,()->checked(t,()->{h.assertValueEqual(count(t,A),0,"Amplified expired");h.assertValueEqual(count(t,S),1,"independent Speed Booster remains");t.owner.setSprinting(false);}));
            t.finish(114,()->{h.assertValueEqual(count(t,S),0,"linger expired after stop");h.assertValueEqual(count(t,W),0,"no detached windup");near(h,t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED),t.owner.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(),"speed restored including native sprint removal");near(h,t.owner.getAttributeValue(Attributes.JUMP_STRENGTH),jump,"jump restored");});
        }catch(Exception|Error failure){t.close();throw failure;}
    }
    @GameCase(environment="chorus_gametest:amplified_resistance",maxTicks=75)
    public void independentCombatantResistancesChangeActualHealthAndExcludeUnclassifiedDamage(GameTestHelper h)throws Exception{
        var t=harness(h);try{
            t.owner.setHealth(100);t.owner.setSprinting(true);grant(t,15);
            damage(t,Set.of("chorus:combatant"));near(h,t.owner.getHealth(),91.5,"Amplified combatant resistance");
            h.runAfterDelay(52,()->checked(t,()->{
                h.assertValueEqual(count(t,S),1,"Speed Booster active for resistance");
                t.owner.setHealth(100);damage(t,Set.of("chorus:combatant"));near(h,t.owner.getHealth(),92.775,"two distinct 15 percent reductions");
                t.owner.setHealth(100);damage(t,Set.of());near(h,t.owner.getHealth(),90,"unclassified damage not assumed combatant");
                t.owner.setHealth(100);damage(t,Set.of("chorus:combatant","chorus:guardian"));near(h,t.owner.getHealth(),90,"Guardian excluded even with conflicting combatant tag");
            }));t.finish(54,()->{});
        }catch(Exception|Error failure){t.close();throw failure;}
    }
    static void damage(ProjectileGameTest.Harness t,Set<String> tags){
        var attacker=t.cow(7,46,3.5);t.owner.damageCooldownTime=0;
        var command=new DamageCommand(id(t.owner),new BuffInstance.Origin(id(attacker),"test:attack","","",tags),10,"minecraft:mob_attack",Set.of(),Set.of(),false);
        var world=new com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions(t.h.getLevel(),t::resolve,_->t.h.getLevel().damageSources().mobAttack(attacker),(_,_) -> true,_ -> {});
        world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(DAMAGE_IDS.incrementAndGet(),0,0),command));
    }
}
