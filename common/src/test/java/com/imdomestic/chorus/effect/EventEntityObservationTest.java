package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffObservation;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventEntityObservationTest {
    static final EffectSource SOURCE=source("test:driver");
    static EntityQuery.View dead(){return new EntityQuery.View(false,false,0,100,0,Set.of("test:tier"),Set.of("test:type"));}
    static EntityObservation observation(){return new EntityObservation(0,Map.of("target",Optional.of(dead())));}
    static Evaluation evaluation(CompiledEffects p,EffectEvent event){
        var state=EffectState.empty().withSource(SOURCE).withResource(new ResourceState(new ResourceState.Key("player","test:energy"),0,1,0));
        return new Evaluation(state,new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal("chorus:kill",event)),"test",SOURCE,Map.of()),
                Map.of(),Map.of(),Map.of("test:energy",p.program().resources().getFirst()),Map.of(),Optional.of(p));
    }
    static EntityQuery.Result read(Evaluation e,Evaluation.Target target){return (EntityQuery.Result)((RuleEngine.Local<EffectState>)new Action.ReadEventEntity(target).execute(e)).result();}
    @Test void observationCopiesTagsAndIdentitiesAndRejectsInvalidTimeOrKeys(){
        var tags=new HashSet<>(Set.of("test:tier"));var map=new HashMap<String,Optional<EntityQuery.View>>();
        map.put("victim",Optional.of(new EntityQuery.View(false,false,0,100,0,tags,Set.of("test:type"))));map.put("missing",Optional.empty());
        var observed=new EntityObservation(20,map);tags.clear();map.clear();
        assertTrue(observed.require("victim").orElseThrow().hasTag(EntityQuery.TagSource.ENTITY,"test:tier"));
        assertFalse(observed.require("victim").orElseThrow().hasTag(EntityQuery.TagSource.TYPE,"test:tier"));
        assertTrue(observed.observed("missing"));assertTrue(observed.require("missing").isEmpty());assertFalse(observed.observed("unknown"));
        assertThrows(IllegalArgumentException.class,()->observed.require("unknown"));assertThrows(UnsupportedOperationException.class,()->observed.entities().clear());
        for(long t:List.of(-1L,Long.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->new EntityObservation(t,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new EntityObservation(0,Map.of(" ",Optional.empty())));
    }
    @Test void unobservedUnavailableAndDeadRemainThreeDifferentStates()throws Exception{
        var p=load("event_entities");var unknown=evaluation(p,event(SOURCE));var check=new Condition.EventEntityObserved(Evaluation.Target.VICTIM);
        assertFalse(check.test(unknown));assertThrows(IllegalArgumentException.class,()->read(unknown,Evaluation.Target.VICTIM));
        var missing=evaluation(p,event(SOURCE).withObservedEntities(Optional.of(new EntityObservation(0,Map.of("target",Optional.empty())))));
        assertTrue(check.test(missing));var missingResult=read(missing,Evaluation.Target.VICTIM);assertFalse(ResultShape.ENTITY.flag("available",missingResult));
        assertThrows(IllegalStateException.class,()->ResultShape.ENTITY.read("health",missingResult));
        var e=evaluation(p,event(SOURCE).withObservedEntities(Optional.of(observation())));var result=read(e,Evaluation.Target.VICTIM);
        assertTrue(ResultShape.ENTITY.flag("available",result));assertFalse(ResultShape.ENTITY.flag("alive",result));assertEquals(0,ResultShape.ENTITY.read("health",result).value());
        assertThrows(IllegalArgumentException.class,()->read(e,Evaluation.Target.SELF));
    }
    @Test void receiptCopiesAndAllDerivedCombatFactsKeepBothObservationKinds(){
        var entities=observation();var buffs=BuffObservation.capture(EffectState.empty().buffs(),"player","target");
        var receipt=new DamageReceipt("hit",DamageReceipt.Outcome.APPLIED,0,0,1,Optional.of("death"),false).withObservedEntities(entities).withObservedBuffs(buffs).withConsumptionFacts(List.of());
        assertEquals(Optional.of(entities),receipt.observedEntities());assertEquals(Optional.of(buffs),receipt.observedBuffs());
        var attack=new DamageCommand("target",SOURCE.origin(),1,"minecraft:generic",Set.of(),Set.of(),false);
        var facts=DamageFacts.from(attack,receipt);assertEquals(List.of("chorus:hit","chorus:damage_taken","chorus:death","chorus:kill"),facts.stream().map(RuleEngine.Signal::type).toList());
        for(var fact:facts){var event=(EffectEvent)fact.payload();assertEquals(Optional.of(entities),event.observedEntities());assertEquals(Optional.of(buffs),event.observedBuffs());}
        assertEquals(receipt,receipt.withObservedEntities(entities));assertThrows(IllegalArgumentException.class,()->receipt.withObservedEntities(new EntityObservation(0,Map.of())));
        assertTrue(new DamageReceipt("unknown",DamageReceipt.Outcome.IMMUNE,0,0,0,Optional.empty(),false).observedEntities().isEmpty());
        var event=event(SOURCE).withObservedEntities(Optional.of(entities)).withObservedBuffs(Optional.of(buffs));assertEquals(Optional.of(entities),event.observedEntities());
    }
    @Test void corpseRemovalCannotRewriteKillEmitOrDetachedContinuationAndNoWorldQueryRuns()throws Exception{
        var p=load("event_entities");var live=new HashMap<>(Map.of("target",dead()));var cues=new ArrayList<String>();
        var session=new EffectSession(engine(p),EffectState.empty().withSource(SOURCE),request->switch(request.command()){
            case DamageCommand d -> new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,10,Optional.of("death"),false)
                    .withObservedEntities(new EntityObservation(0,Map.of(d.target(),Optional.of(live.get(d.target())))));
            case Action.CueCommand c -> {cues.add(c.cue());if(c.cue().equals("test:discard"))live.clear();yield RuleEngine.Empty.INSTANCE;}
            default -> throw new AssertionError("Historical reads must not query the world: "+request.command());
        });
        session.start(0,new RuleEngine.Signal("test:attack",event(SOURCE)));assertTrue(live.isEmpty());assertEquals(List.of("test:discard","test:classified","test:copied"),cues);
        session.start(0,SourceChange.remove(SOURCE.instance()));session.observe(100_000,List.of());
        assertEquals(List.of("test:discard","test:classified","test:copied","test:delayed"),cues);assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty());
    }
    @Test void coreNeverInventsWorldEvidenceWhenAdapterOmitsIt()throws Exception{
        for(boolean settled:List.of(false,true)){
            var p=load("event_entities");var cues=new ArrayList<String>();
            var session=new EffectSession(engine(p),EffectState.empty().withSource(SOURCE),request->switch(request.command()){
                case DamageCommand d -> {var receipt=new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,10,Optional.of("death"),false);yield settled?receipt.withConsumptionFacts(List.of()):receipt;}
                case Action.CueCommand c -> {cues.add(c.cue());yield RuleEngine.Empty.INSTANCE;}
                default -> throw new AssertionError(request.command());
            });
            session.start(0,new RuleEngine.Signal("test:attack",event(SOURCE)));assertEquals(List.of("test:discard","test:unobserved"),cues);assertTrue(session.state().engine().failure().isEmpty());
        }
    }
    @Test void calculationsEnergyAndSnapshotsRetainHistoryButNewFactsDoNotBorrowIt()throws Exception{
        var p=load("event_entities");var e=evaluation(p,event(SOURCE).withObservedEntities(Optional.of(observation())));
        for(boolean pipeline:List.of(false,true)){
            Action action=pipeline?new CalculationActions.CalculatePipeline(List.of("test:observed"),Evaluation.Target.SELF,new Value.Constant(0,Unit.COUNT),ActionOrigin.BOUND,Set.of(),Map.of(),Optional.empty())
                    :new CalculationActions.Calculate("test:observed",Evaluation.Target.SELF,new Value.Constant(0,Unit.COUNT),ActionOrigin.BOUND,Set.of(),Map.of());
            var result=((RuleEngine.Local<EffectState>)action.execute(e)).result();assertEquals(1,result instanceof CalculationActions.Result c?c.calculation().output().value():((CalculationActions.PipelineResult)result).calculation().output().value());
        }
        var changedVictim=new CalculationActions.Calculate("test:observed",Evaluation.Target.SELF,new Value.Constant(0,Unit.COUNT),ActionOrigin.BOUND,Set.of(),Map.of(),Optional.of(Evaluation.Target.SELF));
        var changed=(CalculationActions.Result)((RuleEngine.Local<EffectState>)changedVictim.execute(e)).result();assertEquals(0,changed.calculation().output().value());
        var energy=(RuleEngine.Local<EffectState>)new EnergyActions.Grant("test:energy",Evaluation.Target.SELF,new Value.Constant(.2,Unit.CHARGE),EnergyGains.Basis.BASE,Map.of(),Set.of(),Map.of()).execute(e);
        assertEquals(.3,((EnergyActions.Result)energy.result()).grant().credited(),1e-12);
        for(var fact:energy.emitted())assertTrue(((EffectEvent.Carrier)fact.payload()).event().observedEntities().isEmpty());
        assertEquals(new Condition.Constant(true),new Condition.EventEntityObserved(Evaluation.Target.VICTIM).snapshot(e));
    }
    @Test void codecRoundTripsAndRejectsUnboundTargets()throws Exception{
        var p=load("event_entities");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        var data=json("event_entities");var condition=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("if");
        condition.add("target",com.google.gson.JsonParser.parseString("{\"binding\":\"missing\"}"));assertThrows(RuntimeException.class,()->compile(data));
    }
}
