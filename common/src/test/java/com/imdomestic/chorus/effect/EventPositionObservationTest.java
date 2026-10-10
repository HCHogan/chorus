package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffObservation;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventPositionObservationTest {
    static final EffectSource SOURCE=source("test:position_driver");
    static final WorldPosition POINT=new WorldPosition("test:world",2,40,3);
    static final TargetQuery.Anchor FEET=TargetQuery.Anchor.FEET, EYES=TargetQuery.Anchor.EYES;
    static EntityObservation observation(){return new EntityObservation(0,Map.of("target",Optional.of(EventEntityObservationTest.dead())),Map.of(new PositionQuery("target"),Optional.of(POINT)));}
    static Evaluation evaluation(CompiledEffects program,Optional<EntityObservation> observed){
        return new Evaluation(EffectState.empty().withSource(SOURCE),new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),0,
                new RuleEngine.Signal("chorus:kill",event(SOURCE).withObservedEntities(observed))),"test",SOURCE,Map.of()),Map.of(),Map.of(),Map.of(),Map.of(),Optional.of(program));
    }
    static PositionResult read(Evaluation e,TargetQuery.Anchor anchor){return (PositionResult)((RuleEngine.Local<EffectState>)new Action.ReadEventPosition(Evaluation.Target.VICTIM,anchor).execute(e)).result();}
    @Test void anchorKeysAndMissingEvidenceAreIndependentAndImmutable(){
        var positions=new HashMap<PositionQuery,Optional<WorldPosition>>();positions.put(new PositionQuery("target"),Optional.of(POINT));positions.put(new PositionQuery("target",EYES),Optional.empty());
        var observed=new EntityObservation(0,Map.of(),positions);positions.clear();
        assertEquals(Optional.of(POINT),observed.requirePosition("target",FEET));assertTrue(observed.positionObserved("target",EYES));assertTrue(observed.requirePosition("target",EYES).isEmpty());
        assertFalse(observed.positionObserved("target",TargetQuery.Anchor.BODY));assertFalse(observed.observed("target"),"position evidence does not invent native metadata");
        assertThrows(IllegalArgumentException.class,()->observed.requirePosition("other",FEET));assertThrows(IllegalArgumentException.class,()->observed.requirePosition("target",TargetQuery.Anchor.BODY));
        assertThrows(UnsupportedOperationException.class,()->observed.positions().clear());
        assertFalse(new EntityObservation(0,Map.of("target",Optional.of(EventEntityObservationTest.dead()))).positionObserved("target",FEET));
    }
    @Test void pureReadDistinguishesUnobservedAnchorKnownUnavailableAndCapturedPoint()throws Exception{
        var p=load("event_positions");var guard=new Condition.EventPositionObserved(Evaluation.Target.VICTIM,FEET);
        for(var observation:List.of(Optional.<EntityObservation>empty(),Optional.of(new EntityObservation(0,Map.of())),Optional.of(new EntityObservation(0,Map.of(),Map.of(new PositionQuery("target",EYES),Optional.of(POINT)))))){
            var e=evaluation(p,observation);assertFalse(guard.test(e));assertThrows(IllegalArgumentException.class,()->read(e,FEET));
        }
        var missing=evaluation(p,Optional.of(new EntityObservation(0,Map.of(),Map.of(new PositionQuery("target"),Optional.empty()))));assertTrue(guard.test(missing));assertTrue(ResultShape.POSITION.flag("missing",read(missing,FEET)));
        var e=evaluation(p,Optional.of(observation()));assertTrue(guard.test(e));assertEquals(Optional.of(POINT),ResultShape.POSITION.position(read(e,FEET)));assertEquals(new Condition.Constant(true),guard.snapshot(e));
    }
    @Test void damageFactCopiesDoNotLoseDimensionAnchorOrPositionHistory(){
        var observed=observation();var buffs=BuffObservation.capture(EffectState.empty().buffs(),"target");
        var receipt=new DamageReceipt("hit",DamageReceipt.Outcome.APPLIED,0,0,10,Optional.of("death"),false).withObservedEntities(observed).withObservedBuffs(buffs).withConsumptionFacts(List.of());
        var command=new DamageCommand("target",SOURCE.origin(),10,"minecraft:generic",Set.of(),Set.of(),false);
        for(var fact:DamageFacts.from(command,receipt))assertEquals(Optional.of(POINT),((EffectEvent)fact.payload()).observedEntities().orElseThrow().requirePosition("target",FEET));
        assertThrows(IllegalArgumentException.class,()->receipt.withObservedEntities(new EntityObservation(0,observed.entities())));
    }
    @Test void emittedAndDetachedBlastsUseReceiptPointEvenAfterCorpseAndSourceDisappear()throws Exception{
        var p=load("event_positions");var queries=new ArrayList<TargetQuery>();var damage=new ArrayList<DamageCommand>();var live=new HashMap<>(Map.of("target",POINT));
        var session=new EffectSession(engine(p),EffectState.empty().withSource(SOURCE),request->switch(request.command()){
            case DamageCommand d -> {damage.add(d);yield new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,d.amount(),d.target().equals("target")?Optional.of("death"):Optional.empty(),false)
                    .withObservedEntities(d.target().equals("target")?new EntityObservation(0,Map.of(),Map.of(new PositionQuery("target"),Optional.of(live.get("target")))):new EntityObservation(0,Map.of()));}
            case Action.CueCommand c -> {assertEquals("test:cleanup",c.cue());live.clear();yield RuleEngine.Empty.INSTANCE;}
            case TargetQuery q -> {queries.add(q);assertEquals(Optional.of(POINT),((TargetQuery.PositionCenter)q.center()).position());yield new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,List.of(new TargetQuery.Target("near",1)));}
            default -> throw new AssertionError("Historical position must not cause a world query: "+request.command());
        });
        session.start(0,new RuleEngine.Signal("test:attack",event(SOURCE)));assertTrue(live.isEmpty());assertEquals(1,queries.size());
        session.start(0,SourceChange.remove(SOURCE.instance()));session.observe(100_000,List.of());
        assertEquals(2,queries.size());assertEquals(List.of("target","near","near"),damage.stream().map(DamageCommand::target).toList());assertTrue(damage.stream().allMatch(d->d.source().equals(SOURCE.origin())));assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty());
    }
    @Test void missingHistoryDoesNotFallBackToCurrentPositionOrInventAnOrigin()throws Exception{
        var p=load("event_positions");var cues=new ArrayList<String>();
        var session=new EffectSession(engine(p),EffectState.empty().withSource(SOURCE),request->switch(request.command()){
            case DamageCommand d -> new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,10,Optional.of("death"),false).withObservedEntities(new EntityObservation(0,Map.of("target",Optional.of(EventEntityObservationTest.dead()))));
            case Action.CueCommand c -> {cues.add(c.cue());yield RuleEngine.Empty.INSTANCE;}
            default -> throw new AssertionError(request.command());
        });
        session.start(0,new RuleEngine.Signal("test:attack",event(SOURCE)));assertEquals(List.of("test:cleanup","test:unobserved"),cues);assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty());
    }
    @Test void codecsValidateAnchorsBindingsAndPositionResultTypes()throws Exception{
        var p=load("event_positions");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String type:List.of("chorus:event_position_observed","chorus:read_event_position")){
            var invalid=com.google.gson.JsonParser.parseString("{\"type\":\""+type+"\",\"anchor\":\"head\"}");
            assertTrue((type.endsWith("observed")?EffectCodecs.CONDITION.parse(JsonOps.INSTANCE,invalid):EffectCodecs.ACTION.parse(JsonOps.INSTANCE,invalid)).error().isPresent());
        }
        var data=json("event_positions");var read=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(3).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");
        read.add("target",com.google.gson.JsonParser.parseString("{\"binding\":\"missing\"}"));assertThrows(RuntimeException.class,()->compile(data));
        assertThrows(IllegalArgumentException.class,()->ResultShape.POSITION.requireEntityObservation());
    }
}
