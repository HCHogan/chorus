package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeAttributeTest {
    static EffectSource source(String id,String bundle,String holder){return new EffectSource(id,"test:"+bundle,holder,new BuffInstance.Origin(holder,id,"",""),Set.of());}
    static RuleEngine.Signal slow(String target){return new RuleEngine.Signal("test:slow",new EffectEvent("caster",target,new BuffInstance.Origin("caster","cast","weapon","ability"),Set.of("test:incoming_kill"),Map.of()));}
    static double amount(CompiledEffects p,EffectState state,String holder,String binding){return p.nativeAttributes(state,holder).stream().filter(c->c.binding().id().equals("test:"+binding)).findFirst().orElseThrow().amount();}
    @Test void isolatedContributionQueriesCombineOnlyTheRecipientSourcesAndBuffsWithoutWritingState() throws Exception {
        var p=load("native_attributes");var initial=EffectState.empty().withSource(source("input","inputs","caster")).withSource(source("fast","fast","player"));
        var session=new EffectSession(engine(p),initial,r->{throw new AssertionError("Attribute query issued world command: "+r.command());});session.start(0,slow("player"));
        var state=session.state().engine().domain();assertEquals(.25,amount(p,state,"player","a_speed"));assertEquals(-.5,amount(p,state,"player","z_gravity"));
        assertEquals(0,amount(p,state,"caster","a_speed"));assertEquals(0,amount(p,state,"other","z_gravity"));
        assertSame(state,session.state().engine().domain());assertTrue(state.ammunition().isEmpty());
        var saved=p.nativeAttributes(state,"player");session.observe(200_000,List.of());
        assertEquals(.5,amount(p,session.state().engine().domain(),"player","a_speed"));assertEquals(0,amount(p,session.state().engine().domain(),"player","z_gravity"));
        assertEquals(.25,saved.stream().filter(c->c.binding().id().equals("test:a_speed")).findFirst().orElseThrow().calculation().output().value());
    }
    @Test void absentSourcesYieldNeutralContributionsAndRequiredObservationsAreNeverFabricated() throws Exception {
        var p=load("native_attributes");assertTrue(p.nativeAttributes(EffectState.empty(),"player").stream().allMatch(c->c.amount()==0));
        var state=EffectState.empty().withSource(source("bad","bad","player"));assertThrows(IllegalArgumentException.class,()->p.nativeAttributes(state,"player"));
        assertEquals(0,amount(p,state,"other","z_gravity"));
    }
    @Test void nativeAttributeBindingsLinkAcrossFragmentsAndRoundtripWithoutChangingOlderPrograms() throws Exception {
        var data=json("native_attributes");var declarations=new com.google.gson.JsonObject();declarations.addProperty("version","test-1");declarations.add("native_attributes",data.remove("native_attributes"));
        var p=CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,declarations).getOrThrow()));
        assertEquals(5,p.program().nativeAttributes().size());assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        assertTrue(load("impulse").program().nativeAttributes().isEmpty());
    }
    @Test void duplicateOperationsUnknownProfilesAndAbsoluteMultiplierOutputsAreRejectedBeforeExecution() throws Exception {
        for(String variant:List.of("identity","operation","profile","input","output","absolute","typo")){
            var data=json("native_attributes");var bindings=data.getAsJsonArray("native_attributes");var first=bindings.get(0).getAsJsonObject();
            switch(variant){
                case "identity"->bindings.get(1).getAsJsonObject().addProperty("id",first.get("id").getAsString());
                case "operation"->bindings.get(1).getAsJsonObject().addProperty("operation","add_multiplied_total");
                case "profile"->first.addProperty("profile","test:missing");
                case "input"->first.getAsJsonObject("input").addProperty("unit","second");
                case "output"->bindings.get(2).getAsJsonObject().addProperty("output_unit","meter");
                case "absolute"->first.addProperty("output_unit","multiplier");
                case "typo"->first.addProperty("scale",2);
            }
            assertThrows(RuntimeException.class,()->compile(data),variant);
        }
    }
}
