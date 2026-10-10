package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttributeQueryTest {
    static EffectSource source(String instance,String bundle,String holder){return new EffectSource(instance,"test:"+bundle,holder,new BuffInstance.Origin(holder,instance,"weapon",""),Set.of());}
    static EffectEvent event(){return new EffectEvent("actor","victim",new BuffInstance.Origin("actor","outer","weapon",""),Set.of("test:contamination"),Map.of("outer",new Measure(99,Unit.COUNT)));}
    static Evaluation evaluation(CompiledEffects p,EffectState s){return new Evaluation(s,new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),s.buffs().timeMicros(),new RuleEngine.Signal("test:query",event())),"query",source("driver","driver","owner"),Map.of()),Map.of(),Map.of(),Map.of(),Map.of(),Optional.of(p));}
    static Value.Attribute attribute(String profile,Evaluation.Target target,double base){return new Value.Attribute("test:"+profile,new Value.Constant(base,Unit.STAT_POINT),target);}
    static double points(CompiledEffects p,EffectState s,String holder,double base){return p.attribute(s,holder,"test:points",new Measure(base,Unit.STAT_POINT),NumericQuery.Path.empty()).output().value();}
    static JsonObject boost(JsonObject data){return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject();}
    static JsonElement encoded(Value v){return EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE,v).getOrThrow();}

    @Test void isolatedSubjectQueryUsesCurrentModifiersAndClampsWithoutChangingBaseOrTriggerFacts()throws Exception {
        var p=load("attribute_queries");var s=EffectState.empty().withSource(source("owner-boost","boost","owner")).withSource(source("victim-boost","boost","victim"));
        var e=evaluation(p,s);assertEquals(60,attribute("points",Evaluation.Target.SELF,50).evaluate(e).value());assertEquals(20,attribute("points",Evaluation.Target.VICTIM,10).evaluate(e).value());
        assertEquals(200,points(p,s,"owner",199));assertEquals(0,points(p,s.withSource(source("penalty","penalty","owner")),"owner",50));
        assertEquals(50,points(p,EffectState.empty(),"owner",50));assertEquals(event(),e.event());assertSame(s,e.state());
        var trace=p.attribute(s,"owner","test:points",new Measure(50,Unit.STAT_POINT),NumericQuery.Path.empty());assertEquals(50,trace.inputs().base().value());assertEquals(1,trace.trace().contributions().size());
    }
    @Test void pureQueriesCanFeedRealActionSequencesAndCapturedResultsOutliveChangedSources()throws Exception {
        var p=load("attribute_queries");var state=EffectState.empty().withSource(source("driver","driver","owner")).withSource(source("boost","boost","owner"));var amounts=new ArrayList<Double>();
        var session=new EffectSession(engine(p),state,request->{var c=(HealingCommand)request.command();amounts.add(c.amount());return new HealingReceipt(request.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);});
        session.start(0,new RuleEngine.Signal("test:later",event()));assertTrue(amounts.isEmpty());
        session.start(0,SourceChange.remove("boost"));session.start(0,SourceChange.remove("driver"));session.observe(100_000,List.of());assertEquals(List.of(6.0,5.0),amounts);assertTrue(session.state().idle());
    }
    @Test void capturedOwnerAttributeFreezesWhileVictimAttributeIsResolvedAtEachImpact()throws Exception {
        var p=load("attribute_queries");var owner=source("attack","attack","owner");
        var state=EffectState.empty().withSource(owner).withSource(source("boost","boost","owner"));
        var command=new DamageCommand("unknown",owner.origin(),10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:damage"));
        var snapshot=p.captureDamage(state,command);
        var later=EffectState.empty().withSource(source("victim-boost","boost","victim"));
        assertEquals(17,p.outgoing(later,snapshot.command("victim"),10).orElseThrow().output().value(),1e-12);
        assertEquals(16,p.outgoing(later,snapshot.command("other"),10).orElseThrow().output().value(),1e-12);
        assertEquals(16,p.outgoing(EffectState.empty(),snapshot.command("victim"),10).orElseThrow().output().value(),1e-12);
    }
    @Test void directAndMutualAttributeDependenciesFailClearlyButIndependentRepeatedQueriesRemainValid()throws Exception {
        for(boolean mutual:List.of(false,true)) {
            var data=json("attribute_queries");boost(data).add("value",encoded(attribute(mutual?"other":"points",Evaluation.Target.SELF,0)));
            if(mutual){var m=boost(data).deepCopy();m.addProperty("id","other");m.addProperty("profile","test:other");m.add("value",encoded(attribute("points",Evaluation.Target.SELF,0)));data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").add(m);}
            var p=compile(data);var state=EffectState.empty().withSource(source("boost","boost","owner"));
            assertTrue(assertThrows(IllegalArgumentException.class,()->points(p,state,"owner",0)).getMessage().contains("Circular attribute"));
            assertEquals(50,points(p,EffectState.empty(),"owner",50));assertEquals(60,points(p,EffectState.empty(),"owner",60));
        }
    }
    @Test void attributeAndAmmunitionQueriesShareDependencyPathsInBothDirections()throws Exception {
        var data=json("attribute_queries");
        boost(data).add("value",encoded(new Value.Scale(new Value.Ammo(Evaluation.Target.THIS_WEAPON,AmmoState.Field.CAPACITY),1,Unit.ROUND,Unit.STAT_POINT)));
        var m=boost(data).deepCopy();m.addProperty("id","capacity");m.addProperty("profile","test:capacity");m.add("value",encoded(new Value.Scale(attribute("points",Evaluation.Target.SELF,0),1,Unit.STAT_POINT,Unit.ROUND)));
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").add(m);
        var p=compile(data);var state=EffectState.empty().withSource(source("boost","boost","owner")).withAmmo(new AmmoState("weapon",1,5,Optional.empty(),Optional.of(new AmmoState.CapacityProfile("owner","test:capacity"))));
        assertTrue(assertThrows(IllegalArgumentException.class,()->points(p,state,"owner",0)).getMessage().contains("Circular attribute"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->p.ammoCapacity(state,"weapon")).getMessage().contains("Circular ammunition"));
    }
    @Test void sameProfileOnAnotherHolderIsAValidDependencyUntilItRefersBack()throws Exception {
        var data=json("attribute_queries");var fixed=data.getAsJsonArray("bundles").get(0).deepCopy().getAsJsonObject();fixed.addProperty("id","test:fixed");data.getAsJsonArray("bundles").add(fixed);
        boost(data).add("value",encoded(attribute("points",Evaluation.Target.SOURCE_OWNER,0)));var p=compile(data);
        var state=EffectState.empty().withSource(new EffectSource("dependent","test:boost","owner",new BuffInstance.Origin("other","dependent","",""),Set.of())).withSource(source("fixed","fixed","other"));
        assertEquals(60,points(p,state,"owner",50));assertEquals(10,points(p,state,"other",0));
        var cyclic=state.withSource(new EffectSource("back","test:boost","other",new BuffInstance.Origin("owner","back","",""),Set.of()));
        assertThrows(IllegalArgumentException.class,()->points(p,cyclic,"owner",0));
    }
    @Test void schemasRejectUnknownProfilesWrongUnitsAndUnknownFieldsAndRoundTrip()throws Exception {
        var data=json("attribute_queries");var p=compile(data);assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String invalid:List.of("missing","output","input","field","target")){
            var d=data.deepCopy();var a=d.getAsJsonArray("bundles").get(3).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("value");
            switch(invalid){case "missing"->a.addProperty("profile","test:absent");case "output"->a.addProperty("profile","test:damage");case "input"->a.getAsJsonObject("input").addProperty("unit","damage");case "field"->a.addProperty("inherit_tags",true);case "target"->a.add("target",JsonParser.parseString("{\"binding\":\"missing\"}"));}
            assertThrows(RuntimeException.class,()->compile(d),invalid);
        }
    }
}
