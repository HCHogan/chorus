package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventBuffObservationTest {
    static final EffectSource SOURCE=source("test:driver");
    static EffectState marked(CompiledEffects p,String source,int stacks){
        var origin=new BuffInstance.Origin("someone",source,"","");
        return EffectState.empty().withBuffs(Buffs.grant(EffectState.empty().buffs(),p.buff("test:marked"),"target","target",origin,stacks,1,2_000_000).store()).withSource(SOURCE);
    }
    static Evaluation evaluation(CompiledEffects p,EffectState state,EffectEvent event){
        return new Evaluation(state,new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),state.buffs().timeMicros(),new RuleEngine.Signal("chorus:kill",event)),"test",SOURCE,Map.of()),Map.of("test:marked",p.buff("test:marked")),Map.of(),Map.of(),Map.of(),Optional.of(p));
    }
    @Test void deathCleanupCannotChangeKillEmitOrDetachedContinuationObservations() throws Exception {
        var p=load("event_buffs");var cues=new ArrayList<Action.CueCommand>();
        var session=new EffectSession(engine(p),marked(p,"other",2),request->switch(request.command()){
            case DamageCommand d -> new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,10,Optional.of("death"),false);
            case Action.CueCommand c -> {cues.add(c);yield RuleEngine.Empty.INSTANCE;}
            default -> throw new AssertionError(request.command());
        });
        session.start(0,new RuleEngine.Signal("test:attack",event(SOURCE)));
        assertTrue(session.state().engine().domain().buffs().instances().isEmpty());
        assertEquals(List.of("test:cleaned","test:copied"),cues.stream().map(Action.CueCommand::cue).toList());
        session.start(0,SourceChange.remove(SOURCE.instance()));session.observe(100_000,List.of());
        assertEquals(List.of("test:cleaned","test:copied","test:delayed"),cues.stream().map(Action.CueCommand::cue).toList());assertTrue(session.state().idle());
    }
    @Test void unknownHolderIsNotKnownAbsenceAndNegationCannotTurnItIntoProof() throws Exception {
        var p=load("event_buffs");var empty=EffectState.empty();var absent=event(SOURCE);
        var known=absent.withObservedBuffs(Optional.of(BuffObservation.capture(empty.buffs(),"target")));
        var check=new Condition.EventHasBuff("test:marked",Evaluation.Target.VICTIM,1,Condition.BuffMatch.ANY);
        assertThrows(IllegalArgumentException.class,()->check.test(evaluation(p,empty,absent)));
        assertThrows(IllegalArgumentException.class,()->new Condition.Not(check).test(evaluation(p,empty,absent)));
        assertFalse(new Condition.EventBuffsAvailable(Evaluation.Target.VICTIM).test(evaluation(p,empty,absent)));
        assertFalse(check.test(evaluation(p,empty,known)));assertTrue(new Condition.EventBuffsAvailable(Evaluation.Target.VICTIM).test(evaluation(p,empty,known)));
        assertFalse(new Condition.EventBuffsAvailable(Evaluation.Target.SELF).test(evaluation(p,empty,known)));
        assertThrows(IllegalArgumentException.class,()->new Condition.EventHasBuffTag("test:debuff",Evaluation.Target.SELF).test(evaluation(p,empty,known)));
    }
    @Test void minimumAppliesPerObservedInstanceAndBoundMatchingDoesNotBorrowOtherSources() throws Exception {
        var p=load("event_buffs");var s=marked(p,"other",2);
        s=s.withBuffs(Buffs.grant(s.buffs(),p.buff("test:marked"),"target","target",SOURCE.origin(),1,1,2_000_000).store());
        var observation=BuffObservation.capture(s.buffs(),"target","player");var e=evaluation(p,EffectState.empty(),event(SOURCE).withObservedBuffs(Optional.of(observation)));
        assertTrue(new Condition.EventHasBuff("test:marked",Evaluation.Target.VICTIM,2,Condition.BuffMatch.ANY).test(e));
        assertFalse(new Condition.EventHasBuff("test:marked",Evaluation.Target.VICTIM,3,Condition.BuffMatch.ANY).test(e),"do not sum separate instances");
        assertFalse(new Condition.EventHasBuff("test:marked",Evaluation.Target.VICTIM,2,Condition.BuffMatch.BOUND).test(e));
        assertTrue(new Condition.EventHasBuff("test:marked",Evaluation.Target.VICTIM,1,Condition.BuffMatch.BOUND).test(e));
        assertEquals(2,observation.require("target").size());assertEquals(List.of(),observation.require("player"));assertThrows(UnsupportedOperationException.class,()->observation.holders().clear());
    }
    @Test void captureFiltersExpiredStacksButRetainsPausedPresenceAndRejectsContradictions() throws Exception {
        var p=load("event_buffs");var s=marked(p,"other",2);var b=s.buffs().instances().values().iterator().next();
        var expired=new BuffStore(2_000_000,s.buffs().nextGeneration(),s.buffs().instances());assertTrue(BuffObservation.capture(expired,"target").require("target").isEmpty());
        var paused=new BuffInstance(b.generation(),b.key(),b.definition(),b.origin(),b.stacks(),b.nextStackId(),b.tier(),b.originalDurationMicros(),b.longestDurationMicros(),OptionalLong.of(1_000_000),b.components());
        var observation=BuffObservation.capture(new BuffStore(3_000_000,s.buffs().nextGeneration(),Map.of(paused.key(),paused)),"target");
        assertEquals(2,observation.require("target").getFirst().stacks());assertTrue(observation.require("target").getFirst().paused());
        assertThrows(IllegalArgumentException.class,()->new BuffObservation(0,Map.of("wrong",observation.require("target"))));
        assertThrows(IllegalArgumentException.class,()->new BuffObservation(0,Map.of("target",Collections.nCopies(2,observation.require("target").getFirst()))));
    }
    @Test void allFactsAndConsumptionReceiptCopiesRetainSameObservationWithoutInventingMissingOnes() throws Exception {
        var p=load("event_buffs");var observation=BuffObservation.capture(marked(p,"other",2).buffs(),"target","player");
        var receipt=new DamageReceipt("hit",DamageReceipt.Outcome.APPLIED,0,0,1,Optional.of("death"),false).withObservedBuffs(observation);
        assertEquals(Optional.of(observation),receipt.withConsumptionFacts(List.of()).observedBuffs());
        var attack=new DamageCommand("target",SOURCE.origin(),1,"minecraft:generic",Set.of(),Set.of(),false);
        assertTrue(DamageFacts.from(attack,receipt).stream().map(s->(EffectEvent)s.payload()).allMatch(e->e.observedBuffs().equals(Optional.of(observation))));
        assertTrue(new DamageReceipt("unknown",DamageReceipt.Outcome.IMMUNE,0,0,0,Optional.empty(),false).observedBuffs().isEmpty());
        assertThrows(IllegalArgumentException.class,()->receipt.withObservedBuffs(BuffObservation.capture(EffectState.empty().buffs(),"target")));
    }
    @Test void codecsValidateBindingsAndMinimumAndSnapshotsFreezeTheRecordedFact() throws Exception {
        var p=load("event_buffs");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String change:List.of("minimum","buff","target")){
            var data=json("event_buffs");var condition=data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonObject("if");
            if(change.equals("minimum"))condition.addProperty("minimum",0);else if(change.equals("buff"))condition.addProperty("buff","test:missing");else condition.add("target",com.google.gson.JsonParser.parseString("{\"binding\":\"missing\"}"));
            assertThrows(RuntimeException.class,()->compile(data));
        }
        var observation=BuffObservation.capture(marked(p,"other",2).buffs(),"target");var e=evaluation(p,EffectState.empty(),event(SOURCE).withObservedBuffs(Optional.of(observation)));
        assertEquals(new Condition.Constant(true),new Condition.EventHasBuff("test:marked",Evaluation.Target.VICTIM,2,Condition.BuffMatch.ANY).snapshot(e));
    }
}
