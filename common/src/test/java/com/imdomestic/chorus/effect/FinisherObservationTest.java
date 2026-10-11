package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinisherObservationTest {
    static RuleEngine.Local<EffectState> observe(FinisherTest.Harness h,String target,boolean match,Map<String,String> refs){
        var s=FinisherTest.source("a");var e=new EffectEvent("a",target,s.origin(),Set.of(),Map.of(),Map.of(),refs);
        var context=new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),h.state().buffs().timeMicros(),new RuleEngine.Signal("test:query",e)),"observe",s,Map.of());
        return (RuleEngine.Local<EffectState>)new FinisherActions.Observe(Evaluation.Target.VICTIM,match).execute(new Evaluation(h.state(),context,Map.of(),Map.of()));
    }
    @Test void absentVictimAndUnrelatedActorAreNotActiveExecutorsAndNoTimeIsInvented()throws Exception{
        var h=new FinisherTest.Harness();h.event(0,"begin","a","victim");for(String target:List.of("victim","b","missing")){var before=h.state();var result=observe(h,target,false,Map.of());assertSame(before,result.state());assertTrue(result.emitted().isEmpty());assertFalse(FinisherActions.OBSERVATION.flag("active",result.result()));for(String key:List.of("remaining","elapsed","duration"))assertThrows(IllegalArgumentException.class,()->FinisherActions.OBSERVATION.read(key,result.result()));}
    }
    @Test void exactRemainingAndImmutableObservationSurviveSubsequentTimeAndCompletion()throws Exception{
        var h=new FinisherTest.Harness();h.event(0,"begin","a","victim");h.advance(125_000);var result=observe(h,"a",false,Map.of());assertSame(h.state(),result.state());assertTrue(result.emitted().isEmpty());var r=result.result();assertEquals(.175,FinisherActions.OBSERVATION.read("remaining",r).value(),1e-12);assertEquals(.125,FinisherActions.OBSERVATION.read("elapsed",r).value(),1e-12);assertEquals(.3,FinisherActions.OBSERVATION.read("duration",r).value(),1e-12);h.advance(300_000);assertTrue(FinisherActions.OBSERVATION.flag("active",r));assertFalse(FinisherActions.OBSERVATION.flag("active",observe(h,"a",false,Map.of()).result()));assertEquals(.175,FinisherActions.OBSERVATION.read("remaining",r).value(),1e-12);
    }
    @Test void optionalIdentityRejectsMissingOldOrAnotherActorsExecution()throws Exception{
        var h=new FinisherTest.Harness();h.event(0,"begin","a","victim");var p=Finisher.observe(h.state(),"a",Optional.empty()).active();assertTrue(FinisherActions.OBSERVATION.flag("active",observe(h,"a",true,Map.of("finisher_id",p.id())).result()));for(Map<String,String> refs:List.of(Map.<String,String>of(),Map.of("finisher_id",""),Map.of("finisher_id","old")))assertFalse(FinisherActions.OBSERVATION.flag("active",observe(h,"a",true,refs).result()));assertFalse(FinisherActions.OBSERVATION.flag("active",observe(h,"b",true,Map.of("finisher_id",p.id())).result()));h.event(1,"cancel","a","victim");h.event(2,"begin","a","next");assertFalse(FinisherActions.OBSERVATION.flag("active",observe(h,"a",true,Map.of("finisher_id",p.id())).result()));
    }
    @Test void interruptedGenerationCannotRemainActiveUntilTheNextWatchdog()throws Exception{
        var h=new FinisherTest.Harness();h.event(0,"begin","a","victim");h.event(1,"interrupt","a","victim");assertFalse(FinisherActions.OBSERVATION.flag("active",observe(h,"a",false,Map.of()).result()));assertFalse(h.state().timers().isEmpty(),"watchdog still pending but one lock is already gone");
    }
    @Test void observationAndActionRoundTripWithoutChangingExistingCheckpointShapes()throws Exception{
        var h=new FinisherTest.Harness();h.event(0,"begin","a","victim");var o=(Finisher.Observation)observe(h,"a",false,Map.of()).result();assertEquals(o,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(o),Finisher.Observation.class));h.restore();assertEquals(o,observe(h,"a",false,Map.of()).result());var empty=new Finisher.Observation("a",0,Optional.empty());assertEquals(empty,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(empty),Finisher.Observation.class));
        var json=JsonParser.parseString("{\"type\":\"chorus:observe_finisher\"}").getAsJsonObject();var action=EffectCodecs.ACTION.parse(JsonOps.INSTANCE,json).getOrThrow();assertEquals(new FinisherActions.Observe(Evaluation.Target.SELF,false),action);assertEquals(action,EffectCodecs.ACTION.parse(JsonOps.INSTANCE,EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE,action).getOrThrow()).getOrThrow());json.addProperty("duration",20);assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE,json).error().isPresent());assertThrows(IllegalArgumentException.class,()->new Finisher.Observation("b",0,Optional.of(o.active())));assertThrows(IllegalArgumentException.class,()->new Finisher.Observation("a",300_000,Optional.of(o.active())));
    }
}
