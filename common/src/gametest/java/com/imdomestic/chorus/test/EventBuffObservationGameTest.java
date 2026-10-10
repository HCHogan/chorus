package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.Action;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class EventBuffObservationGameTest {
    static void prepare(JsonObject data,boolean lateMark){
        data.getAsJsonArray("bundles").add(JsonParser.parseString("{\"id\":\"test:projectile\"}"));data.getAsJsonArray("bundles").add(JsonParser.parseString("{\"id\":\"test:power\"}"));
        var rules=data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        rules.add(JsonParser.parseString("""
            {"id":"mark","on":"test:mark","do":[{"type":"chorus:grant_buff","buff":"test:marked","target":"victim","stacks":{"type":"chorus:constant","value":2,"unit":"count"}}]}
            """));
        if(lateMark){var late=rules.get(rules.size()-1).deepCopy().getAsJsonObject();late.addProperty("id","late_mark");late.addProperty("on","chorus:hit");rules.add(late);}
        rules.add(JsonParser.parseString("""
            {"id":"replace","on":"test:replace","do":[{"type":"chorus:remove_buff","buff":"test:marked","target":"victim"},{"type":"chorus:grant_buff","buff":"test:marked","target":"victim","stacks":{"type":"chorus:constant","value":2,"unit":"count"}}]}
            """));
    }
    static ProjectileGameTest.Harness harness(GameTestHelper h,boolean lateMark)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"event_buffs",data->prepare(data,lateMark),true);
        IncandescentGameTest.bind(t,"driver","test:driver","");return t;
    }
    static void event(ProjectileGameTest.Harness t,LivingEntity victim,String type){
        var owner=t.owner.getUUID().toString();t.runtime.start(new RuleEngine.Signal("test:"+type,new EffectEvent(owner,victim.getUUID().toString(),new BuffInstance.Origin(owner,"driver","",""),Set.of(),Map.of())));
    }
    @GameCase public void managedAndOrdinaryNativeDeathPreserveMarkedVictimAfterCleanup(GameTestHelper h)throws Exception{
        for(boolean managed:List.of(false,true))try(var t=harness(h,false)){
            var victim=t.cow(2.5,46,3.5);victim.setHealth(5);event(t,victim,"mark");
            if(managed)event(t,victim,"attack");else {
                var receipt=MinecraftDamageExecutor.execute("observation/"+UUID.randomUUID(),victim,h.getLevel().damageSources().playerAttack(t.owner),10,false);
                h.assertValueEqual(receipt.observedBuffs().orElseThrow().require(victim.getUUID().toString()).getFirst().stacks(),2,"native receipt before death reactions");
            }
            h.assertTrue(victim.isDeadOrDying(),"real death");h.assertTrue(t.runtime.state().engine().domain().buffs().instances().isEmpty(),"death cleanup retained live mark");
            h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(),List.of("test:cleaned","test:copied"),"kill and copied event saw original observation");
            if(managed)h.assertValueEqual(t.receipts.getFirst().observedBuffs().orElseThrow().require(victim.getUUID().toString()).getFirst().stacks(),2,"managed receipt observed the same state");
            h.assertTrue(t.runtime.failure().isEmpty(),"observation runtime failed");
        }h.succeed();
    }
    @GameCase public void earlierHitReactionCannotInventABuffForTheSameDeathsKillCondition(GameTestHelper h)throws Exception{
        try(var t=harness(h,true)){
            var victim=t.cow(2.5,46,3.5);victim.setHealth(5);
            var receipt=MinecraftDamageExecutor.execute("observation/"+UUID.randomUUID(),victim,h.getLevel().damageSources().playerAttack(t.owner),10,false);
            h.assertTrue(receipt.observedBuffs().orElseThrow().require(victim.getUUID().toString()).isEmpty(),"known absence at receipt");
            h.assertTrue(t.cues.isEmpty()&&t.runtime.state().engine().domain().buffs().instances().isEmpty(),"later reaction rewrote history");
            h.assertTrue(t.runtime.failure().isEmpty(),"observation runtime failed");
        }h.succeed();
    }
    @GameCase public void oldNativeHitDoesNotBelongToARecreatedBuffWithTheSameLogicalKey(GameTestHelper h)throws Exception{
        try(var t=harness(h,false)){
            var victim=t.cow(2.5,46,3.5);victim.setHealth(20);event(t,victim,"mark");var old=t.runtime.state().engine().domain().buffs().instances().values().iterator().next();
            var before=MinecraftDamageExecutor.execute("generation/old",victim,h.getLevel().damageSources().playerAttack(t.owner),1,false);var observed=before.observedBuffs().orElseThrow().require(victim.getUUID().toString()).getFirst();h.assertValueEqual(observed.generation(),old.generation(),"actual native hit captured wrong generation");
            event(t,victim,"replace");var fresh=t.runtime.state().engine().domain().buffs().instances().get(old.key());h.assertTrue(fresh!=null&&fresh.generation()!=old.generation(),"fixture failed to replace the generation");
            var command=new com.imdomestic.chorus.effect.combat.DamageCommand(victim.getUUID().toString(),new BuffInstance.Origin(t.owner.getUUID().toString(),"native","",""),1,"minecraft:generic",Set.of(),Set.of(),false);
            var oldFact=com.imdomestic.chorus.effect.combat.DamageFacts.from(command,before).getFirst();t.runtime.start(new RuleEngine.Signal("test:probe",oldFact.payload()));h.assertTrue(t.cues.isEmpty(),"old hit counted against new buff instance");
            var after=MinecraftDamageExecutor.execute("generation/new",victim,h.getLevel().damageSources().playerAttack(t.owner),2,false);h.assertValueEqual(after.outcome(),com.imdomestic.chorus.effect.combat.DamageReceipt.Outcome.APPLIED,"larger hit during native cooldown must produce a receipt");var newFact=com.imdomestic.chorus.effect.combat.DamageFacts.from(command,after).getFirst();t.runtime.start(new RuleEngine.Signal("test:probe",newFact.payload()));h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(),List.of("test:instance"),"fresh native hit did not match current instance");
            h.assertTrue(t.runtime.failure().isEmpty(),"instance matching runtime failed");
        }h.succeed();
    }

}
