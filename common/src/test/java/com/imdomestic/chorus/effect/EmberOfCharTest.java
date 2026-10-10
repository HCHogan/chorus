package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.effect.SolarTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Char's 40/60 stacks are from Compendium; all windup values here are synthetic. */
class EmberOfCharTest {
    static CompiledEffects program(double windup) throws Exception {
        var calibration=json("solar_test_calibration");
        for(var entry:calibration.getAsJsonArray("profiles")) {
            var p=entry.getAsJsonObject();
            if(p.get("id").getAsString().equals("chorus_d2:ignition_delay")) {
                var coefficients=new com.google.gson.JsonArray(); coefficients.add(windup);
                p.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("curve").add("coefficients",coefficients);
            }
        }
        var fragments=new ArrayList<EffectProgram>();
        for(String name:List.of("solar","solar_test_source","ember_of_char","character_stats")) fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());
        fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,calibration).getOrThrow());
        return CompiledEffects.link(fragments);
    }
    static class Harness extends SolarTest.Harness {
        DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;
        boolean zero, absorption, kill;
        Harness(double windup) throws Exception { this(program(windup)); }
        Harness(CompiledEffects program) { super(program,EffectState.Mode.PVE); }
        void fragment(EffectSource owner,String name) {
            session.start(state().buffs().timeMicros(),SourceChange.bind(new EffectSource(owner.holder()+"-"+name,"chorus_d2:ember_of_"+name,owner.holder(),owner.origin(),Set.of()))); healthy();
        }
        void removeChar() { session.start(state().buffs().timeMicros(),SourceChange.remove(FIRST.holder()+"-char")); healthy(); }
        Optional<BuffInstance> scorch(String target) { return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(SCORCH)&&b.key().holder().equals(target)).findFirst(); }
        @Override RuleEngine.ActionResult execute(RuleEngine.WorldRequest request) {
            if(request.command() instanceof StatusResult.Check q && !views.get(q.target()).alive()) return new StatusResult.Checked(q,StatusResult.Decision.DEAD);
            var result=super.execute(request);
            if(result instanceof DamageReceipt receipt) {
                if(outcome!=DamageReceipt.Outcome.APPLIED||zero) return new DamageReceipt(receipt.damageId(),outcome,0,0,0,Optional.empty(),false);
                if(kill) {
                    var target=((DamageCommand)request.command()).target(); views.put(target,new EntityQuery.View(false,false,0,1000,0));
                    return new DamageReceipt(receipt.damageId(),outcome,0,0,receipt.healthLoss(),Optional.of("death/"+receipt.damageId()),false);
                }
                if(absorption) return new DamageReceipt(receipt.damageId(),outcome,0,receipt.healthLoss(),0,Optional.empty(),false);
            }
            return result;
        }
    }
    @Test void charSpreadsFortyOrSixtyAndNeverUsesAnotherOwnersFragments() throws Exception {
        for(boolean charEquipped:List.of(false,true)) for(boolean ashes:List.of(false,true)) {
            var h=new Harness(0); h.fragment(SECOND,"char"); h.fragment(SECOND,"ashes");
            if(charEquipped) h.fragment(FIRST,"char"); if(ashes) h.fragment(FIRST,"ashes");
            h.apply(0,FIRST,100);
            assertTrue(h.scorch("target").isEmpty());
            assertEquals(charEquipped?(ashes?60:40):0,h.scorch("neighbor").map(BuffInstance::count).orElse(0));
            h.scorch("neighbor").ifPresent(b->assertEquals(FIRST.origin(),b.origin()));
        }
    }
    @Test void centerRemainsExcludedAfterItsLockoutExpiresWhileDetachedExplosionKeepsCredit() throws Exception {
        var h=new Harness(2); h.fragment(FIRST,"char"); h.apply(0,FIRST,100); h.detach(FIRST);
        h.until(1_999_999); assertTrue(h.damage.isEmpty()); assertTrue(h.buff(LOCKOUT).isEmpty());
        h.until(2_000_000); assertEquals(2,h.damage.size()); assertTrue(h.scorch("target").isEmpty());
        assertEquals(40,h.scorch("neighbor").orElseThrow().count()); assertEquals(FIRST.origin(),h.scorch("neighbor").orElseThrow().origin());
        assertTrue(h.damage.stream().allMatch(d->d.source().equals(FIRST.origin())&&d.killTags().equals(Set.of("chorus:weapon_kill"))));
        h.amounts.forEach(v->assertEquals(67.6,v,1e-9));
    }
    @Test void cancelledImmuneZeroLossAndDeadVictimsDoNotReceiveScorchButAbsorptionCounts() throws Exception {
        for(var outcome:List.of(DamageReceipt.Outcome.CANCELLED,DamageReceipt.Outcome.IMMUNE,DamageReceipt.Outcome.BLOCKED,DamageReceipt.Outcome.APPLIED)) {
            var h=new Harness(0); h.fragment(FIRST,"char"); h.outcome=outcome; h.zero=true; h.apply(0,FIRST,100);
            assertTrue(h.buff(SCORCH).isEmpty());
        }
        var dead=new Harness(0); dead.fragment(FIRST,"char"); dead.kill=true; dead.apply(0,FIRST,100); assertTrue(dead.buff(SCORCH).isEmpty());
        var shield=new Harness(0); shield.fragment(FIRST,"char"); shield.absorption=true; shield.apply(0,FIRST,100);
        assertEquals(40,shield.scorch("neighbor").orElseThrow().count());
    }
    @Test void fourTargetsSustainAlternatingIgnitionsAcrossRepeatedLocalLockoutsThenUnequippingCharStopsNewWaves() throws Exception {
        var h=new Harness(1); h.fragment(FIRST,"char"); h.fragment(FIRST,"ashes");
        var ids=List.of("a","b","c","d"); h.targets=ids.stream().map(id->new TargetQuery.Target(id,2)).toList();
        ids.forEach(id->h.views.put(id,view(false,false)));
        h.apply(0,FIRST,"a",100); h.apply(0,FIRST,"b",100);
        for(int wave=1;wave<=8;wave++) {
            h.until(wave*1_000_000L-1); assertEquals((wave-1)*8,h.damage.size());
            h.until(wave*1_000_000L); assertEquals(wave*8,h.damage.size()); assertEquals(wave*2,h.queries.size());
            assertTrue(h.buff(SCORCH).isEmpty(),"two real 60-stack applications must prime the next pair");
            assertEquals(4,h.state().buffs().instances().values().stream().filter(b->b.definition().id().equals(LOCKOUT)).count());
        }
        assertTrue(h.damage.stream().allMatch(d->d.source().equals(FIRST.origin())&&d.proc().deny().isEmpty()));
        assertEquals(4,h.damage.stream().map(DamageCommand::target).distinct().count());
        h.removeChar(); h.until(9_000_000); assertEquals(72,h.damage.size());
        h.until(12_000_000); assertEquals(72,h.damage.size()); assertTrue(h.state().timers().isEmpty());
    }
    @Test void fortyStacksOrMissingSecondSeedDoesNotInventEnoughScorchForAChain() throws Exception {
        for(boolean ashes:List.of(false,true)) {
            var h=new Harness(1); h.fragment(FIRST,"char"); if(ashes)h.fragment(FIRST,"ashes");
            var ids=List.of("a","b","c","d"); h.targets=ids.stream().map(id->new TargetQuery.Target(id,2)).toList(); ids.forEach(id->h.views.put(id,view(false,false)));
            h.apply(0,FIRST,"a",100); if(!ashes)h.apply(0,FIRST,"b",100);
            h.until(1_000_000); assertEquals(ashes?60:80,h.scorch("c").orElseThrow().count());
            h.until(2_000_000); assertEquals(ashes?1:2,h.queries.size());
        }
    }
    @Test void restrictingNewActionsAfterPrimingCannotCutTheExistingCharFeedbackChain() throws Exception {
        var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,program(1).program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("bundles").add(com.google.gson.JsonParser.parseString("""
                {"id":"test:restricted_inputs","action_gates":[
                  {"id":"ability","action":"ability_use"},
                  {"id":"fire","action":"weapon_fire"},
                  {"id":"reload","action":"weapon_reload"}
                ]}
                """));
        var h=new Harness(compile(data));h.fragment(FIRST,"char");h.fragment(FIRST,"ashes");
        var ids=List.of("a","b","c","d");h.targets=ids.stream().map(id->new TargetQuery.Target(id,2)).toList();ids.forEach(id->h.views.put(id,view(false,false)));
        h.apply(0,FIRST,"a",100);h.apply(0,FIRST,"b",100);h.until(500_000);
        var restriction=new EffectSource("disabled","test:restricted_inputs",FIRST.holder(),new BuffInstance.Origin(FIRST.holder(),"disabled","",""),Set.of());
        h.session.start(500_000,SourceChange.bind(restriction));h.healthy();
        var input=new EffectEvent(FIRST.holder(),"",FIRST.origin(),Set.of(),Map.of());
        for(int wave=1;wave<=8;wave++){
            h.until(wave*1_000_000L);
            for(var action:List.of(ActionGate.Kind.ABILITY_USE,ActionGate.Kind.WEAPON_FIRE,ActionGate.Kind.WEAPON_RELOAD)){
                var decision=h.program.checkAction(h.state(),action,ActionGate.Phase.START,input);
                assertFalse(decision.allowed());assertEquals("disabled",decision.denials().getFirst().origin().source());
            }
            assertEquals(wave*8,h.damage.size(),"input restrictions cannot disable subsequent ignition generations");
        }
        assertTrue(h.damage.stream().allMatch(d->d.source().equals(FIRST.origin())&&d.proc().deny().isEmpty()));
        assertEquals(4,h.damage.stream().map(DamageCommand::target).distinct().count());
    }
    @Test void fragmentSelectionUsesDetonationTimeAndNegativeCalibrationFails() throws Exception {
        var h=new Harness(1); h.apply(0,FIRST,100); h.until(500_000); h.fragment(FIRST,"char"); h.fragment(FIRST,"ashes");
        h.until(1_000_000); assertEquals(60,h.scorch("neighbor").orElseThrow().count());
        var invalid=new Harness(-1); assertThrows(IllegalStateException.class,()->invalid.apply(0,FIRST,100));
        assertTrue(invalid.damage.isEmpty()); assertTrue(invalid.session.state().engine().failure().isPresent());
    }
}
