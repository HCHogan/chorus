package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class MovementObservationTest {
    static final List<String> FIELDS=List.of("on_ground","sprinting","crouching","swimming","fall_flying","passenger","sleeping");
    static EntityQuery.View view(boolean value){return new EntityQuery.View(true,true,10,20,0,Set.of(),Set.of(),Optional.of(new EntityQuery.Movement(value,value,value,value,value,value,value)));}
    static EntityQuery.Result result(Optional<EntityQuery.View> view){return new EntityQuery.Result(new EntityQuery("target"),view);}
    static CompiledEffects program(){return compile(JsonParser.parseString("""
            {"version":"test-1","bundles":[{"id":"test:movement","rules":[
              {"id":"current","on":"test:probe","do":[
                {"action":{"type":"chorus:inspect_entity","target":"victim"},"as":"movement"},
                {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[
                  {"if":{"type":"chorus:all","of":[
                    {"type":"chorus:result_flag","binding":"movement","field":"movement_observed"},
                    {"type":"chorus:result_flag","binding":"movement","field":"sprinting"}]},
                    "then":[{"type":"chorus:play_cue","cue":"test:captured_sprint"}]}]}]},
              {"id":"historical","on":"test:historical","do":[
                {"action":{"type":"chorus:read_event_entity","target":"victim"},"as":"movement"},
                {"if":{"type":"chorus:all","of":[
                  {"type":"chorus:result_flag","binding":"movement","field":"movement_observed"},
                  {"type":"chorus:result_flag","binding":"movement","field":"sprinting"}]},
                  "then":[{"type":"chorus:play_cue","cue":"test:historical_sprint"}]}]}
            ]}]}
            """).getAsJsonObject());}
    @Test void everyFlagDistinguishesObservedFalseFromAnOldAdapterOrMissingEntity(){
        for(boolean value:List.of(false,true)){
            var result=result(Optional.of(view(value)));assertTrue(ResultShape.ENTITY.flag("movement_observed",result));
            for(String field:FIELDS)assertEquals(value,ResultShape.ENTITY.flag(field,result));
        }
        for(var unknown:List.of(result(Optional.empty()),result(Optional.of(new EntityQuery.View(true,true,10,20,0))))){
            assertFalse(ResultShape.ENTITY.flag("movement_observed",unknown));
            for(String field:FIELDS)assertThrows(IllegalStateException.class,()->ResultShape.ENTITY.flag(field,unknown));
        }
        assertThrows(NullPointerException.class,()->new EntityQuery.View(true,true,10,20,0,Set.of(),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->ResultShape.ENTITY.unit("sprinting"));
    }
    @Test void aDetachedRuleKeepsTheObservedSprintAndHistoryNeverRequeriesCurrentState(){
        var p=program();var source=source("test:movement");var live=new java.util.concurrent.atomic.AtomicReference<>(view(true));var cues=new ArrayList<String>();var queries=new ArrayList<EntityQuery>();
        var session=new EffectSession(engine(p),EffectState.empty().withSource(source),request->switch(request.command()){
            case EntityQuery q->{queries.add(q);var old=live.get();live.set(view(false));yield new EntityQuery.Result(q,Optional.of(old));}
            case Action.CueCommand cue->{cues.add(cue.cue());yield RuleEngine.Empty.INSTANCE;}
            default->throw new AssertionError(request.command());
        });
        session.start(0,new RuleEngine.Signal("test:probe",event(source)));
        session.start(0,new RuleEngine.Signal("test:historical",event(source).withObservedEntities(Optional.of(new EntityObservation(0,Map.of("target",Optional.of(view(true))))))));
        session.start(0,SourceChange.remove(source.instance()));session.observe(100_000,List.of());
        assertEquals(List.of("test:historical_sprint","test:captured_sprint"),cues);assertEquals(1,queries.size());assertFalse(live.get().observedMovement().sprinting());
        assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty());
        assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
    }
    @Test void guardedRulesDoNotTurnUnknownMovementIntoAnObservedStop(){
        for(var observed:List.of(Optional.<EntityQuery.View>empty(),Optional.of(new EntityQuery.View(true,true,10,20,0)),Optional.of(view(false)))){
            var source=source("test:movement");var cues=new ArrayList<String>();var session=new EffectSession(engine(program()),EffectState.empty().withSource(source),request->switch(request.command()){
                case EntityQuery q->new EntityQuery.Result(q,observed);
                case Action.CueCommand cue->{cues.add(cue.cue());yield RuleEngine.Empty.INSTANCE;}
                default->throw new AssertionError(request.command());
            });
            session.start(0,new RuleEngine.Signal("test:probe",event(source)));session.observe(100_000,List.of());
            assertTrue(cues.isEmpty());assertTrue(session.state().engine().failure().isEmpty());
        }
    }
    @Test void damageFactsRetainMovementTogetherWithTheRestOfTheirEntityEvidence(){
        var observation=new EntityObservation(0,Map.of("target",Optional.of(view(true))));
        var receipt=new DamageReceipt("movement",DamageReceipt.Outcome.APPLIED,1,1,0,Optional.of("death"),false).withObservedEntities(observation);
        var source=source("test:movement");var attack=new DamageCommand("target",source.origin(),1,"minecraft:generic",Set.of(),Set.of(),false);
        var facts=DamageFacts.from(attack,receipt);assertEquals(4,facts.size());
        for(var fact:facts)assertTrue(((EffectEvent)fact.payload()).observedEntities().orElseThrow().require("target").orElseThrow().observedMovement().sprinting());
    }
}
