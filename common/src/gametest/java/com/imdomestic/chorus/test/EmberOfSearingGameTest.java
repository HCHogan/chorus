package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.Action;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class EmberOfSearingGameTest {
    static void prepare(JsonObject data){
        for(String fixture:List.of("ember_of_searing","searing_test_calibration","searing_inputs","solar","solar_test_calibration","solar_test_source","threaded_spike_energy")){
            var fragment=ThreadedSpikeGameTest.json(fixture);
            for(var entry:fragment.entrySet()){
                if(entry.getKey().equals("version"))continue;
                if(!data.has(entry.getKey()))data.add(entry.getKey(),new JsonArray());
                entry.getValue().getAsJsonArray().forEach(e->data.getAsJsonArray(entry.getKey()).add(e));
            }
        }
    }
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{return harness(h,_ -> {});}
    static ProjectileGameTest.Harness harness(GameTestHelper h,java.util.function.Consumer<JsonObject> edit)throws Exception{
        var t=FirespriteGameTest.harness(h,data->{prepare(data);edit.accept(data);});t.runtime.unbind("tempering");
        IncandescentGameTest.bind(t,"searing","chorus_d2:ember_of_searing","");IncandescentGameTest.bind(t,"searing-input","test:searing_inputs","");
        var owner=t.owner.getUUID().toString();var slots=new HashMap<>(t.runtime.state().engine().domain().abilities().get(owner).slots());slots.put("chorus_d2:melee","test:searing_melee");
        t.runtime.abilities(new AbilityChange(owner,t.runtime.state().engine().domain().abilities().get(owner),new AbilityLoadout(slots)));
        t.runtime.start(new RuleEngine.Signal("test:searing_stat",new EffectEvent(owner,owner,new BuffInstance.Origin(owner,"input","",""),Set.of(),Map.of("stat",new Measure(100,Unit.STAT_POINT)))));return t;
    }
    static double energy(ProjectileGameTest.Harness t){return t.runtime.state().engine().domain().resources().get(new ResourceState.Key(t.owner.getUUID().toString(),"test:searing_melee_energy")).value();}
    static LivingEntity target(ProjectileGameTest.Harness t,String tier){var e=FirespriteGameTest.cow(t,0,6);e.setHealth(5);if(tier!=null)e.addTag("chorus_d2:combatant_tier_"+tier);return e;}
    static void scorch(ProjectileGameTest.Harness t,LivingEntity applier,LivingEntity target){
        var owner=applier.getUUID().toString();var origin=new BuffInstance.Origin(owner,"scorch/"+owner,"","test:scorch",Set.of("chorus:grenade_damage"));
        t.runtime.bind(new EffectSource("scorch/"+owner,"test:scorch_source",owner,origin,Set.of()));
        t.runtime.bind(new EffectSource("solar/"+owner,"chorus_d2:solar_scaling",owner,origin,Set.of()));
        t.runtime.start(new RuleEngine.Signal("test:scorch_apply",new EffectEvent(owner,target.getUUID().toString(),origin,Set.of(),Map.of("stacks",new Measure(1,Unit.COUNT)))));
        t.h.assertTrue(IncandescentGameTest.scorch(t,target).isPresent(),"Scorch must exist before actual lethal hit");
    }
    static void kill(ProjectileGameTest.Harness t,LivingEntity target,String slot,boolean nativeHit)throws Exception{
        if(nativeHit)MinecraftDamageExecutor.execute("searing/"+UUID.randomUUID(),target,t.h.getLevel().damageSources().playerAttack(t.owner),10,false);
        else {PugilistGameTest.draw(t,slot);PugilistGameTest.impact(t,PugilistGameTest.fire(t),target);}
        t.h.assertTrue(target.isDeadOrDying()&&t.runtime.failure().isEmpty(),"actual Searing kill failed");
    }
    @GameCase public void nativeAndPhysicalKillsOfAnotherSourcesScorchRestoreMeleeWhileFirespriteCooldownIsIndependent(GameTestHelper h)throws Exception{
        for(boolean nativeHit:List.of(false,true))try(var t=harness(h)){
            var applier=FirespriteGameTest.cow(t,3,0);var first=target(t,"1");scorch(t,applier,first);kill(t,first,"primary",nativeHit);
            h.assertTrue(IncandescentGameTest.scorch(t,first).isEmpty(),"death cleanup already removed Scorch");near(h,energy(t),.08*.8,"tier one, current 100 Melee stat and synthetic reference normalization");h.assertValueEqual(t.pickups.size(),1,"first scorched kill creates Firesprite");
            var second=target(t,"4");scorch(t,applier,second);kill(t,second,"secondary",nativeHit);
            near(h,energy(t),(.08+.25)*.8,"melee gain remains eligible during pickup cooldown");h.assertValueEqual(t.pickups.size(),1,"same owner shares generation cooldown");
            near(h,FirespriteGameTest.stat(t,t.owner,"class_stat"),60,"Searing Class bonus");t.runtime.unbind("searing");near(h,FirespriteGameTest.stat(t,t.owner,"class_stat"),50,"source removal removes bonus");
        }h.succeed();
    }
    @GameCase public void unmarkedKillsDoNothingAndUnknownCombatantTierIsExplicitWithoutGuessingRank(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            kill(t,target(t,"1"),"primary",false);near(h,energy(t),0,"unmarked kill");h.assertTrue(t.pickups.isEmpty(),"unmarked kill generated pickup");
            var unknown=target(t,null);unknown.addTag("chorus_d2:boss");scorch(t,t.owner,unknown);kill(t,unknown,"secondary",false);
            near(h,energy(t),0,"rank label is not a combatant tier");h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(),List.of("test:unclassified"),"explicit missing-classification fact");h.assertValueEqual(t.pickups.size(),1,"known pickup leg still executes");
        }h.succeed();
    }
    static void addCorpseCleanup(JsonObject data){
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
            {"id":"test:corpse_cleanup","rules":[{"id":"remove","on":"chorus:death","do":[{"type":"chorus:play_cue","cue":"test:remove_corpse"}]}]}
            """));
    }
    @GameCase public void corpseCleanupPreservesSearingTierButMissingPositionCannotCreateAPickup(GameTestHelper h)throws Exception{
        for(boolean nativeHit:List.of(false,true))for(boolean removed:List.of(false,true))try(var t=harness(h,EmberOfSearingGameTest::addCorpseCleanup)){
            IncandescentGameTest.bind(t,"cleanup","test:corpse_cleanup","");var victim=target(t,"1");scorch(t,t.owner,victim);
            t.onCue=cue->{if(cue.cue().equals("test:remove_corpse")){victim.removeTag("chorus_d2:combatant_tier_1");victim.addTag("chorus_d2:combatant_tier_4");if(removed)victim.discard();}};
            kill(t,victim,"primary",nativeHit);near(h,energy(t),.08*.8,"original tier one survives earlier corpse mutation or removal");
            h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(),List.of("test:remove_corpse"),"classification is present despite cleanup");
            h.assertValueEqual(t.pickups.size(),removed?0:1,"pickup still requires current world position");
            h.assertTrue(FirespriteGameTest.buff(t,t.owner,"chorus_d2:firesprite_cooldown").isPresent()!=removed,"missing-position spawn must not spend generation cooldown");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:searing_dot",maxTicks=40)
    public void actualScorchTickDeathUsesItsOriginalKillerAndRetainsObservedScorchForSearing(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            var victim=target(t,"2");victim.setHealth(.1f);scorch(t,t.owner,victim);
            t.finish(11,()->{h.assertTrue(victim.isDeadOrDying()&&IncandescentGameTest.scorch(t,victim).isEmpty(),"tick did not confirm and clean death: health="+victim.getHealth()+", receipts="+t.receipts);near(h,energy(t),.15*.8,"native Scorch tick qualifies original killer");h.assertValueEqual(t.pickups.size(),1,"tick kill generated one private pickup");});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
