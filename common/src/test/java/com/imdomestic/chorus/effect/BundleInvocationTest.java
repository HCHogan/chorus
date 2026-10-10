package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class BundleInvocationTest {
    static final class Harness {
        final CompiledEffects p;final EffectSession session;final EffectSource caller=source("test:caller");
        final List<Object> calls=new ArrayList<>();boolean fail;
        Harness(CompiledEffects p){this.p=p;session=new EffectSession(engine(p),EffectState.empty().withSource(caller),r->{
            calls.add(r.command());if(r.command() instanceof DamageCommand d){if(fail)throw new IllegalStateException("unknown after damage");return new DamageReceipt("d"+calls.size(),DamageReceipt.Outcome.APPLIED,0,0,d.amount(),Optional.empty(),false);}return RuleEngine.Empty.INSTANCE;
        });}
        Harness()throws Exception{this(load("bundle_invocation"));}
        EffectState state(){return session.state().engine().domain();}
        void send(String event){send(event,7);}
        void send(String event,double amount){session.start(state().buffs().timeMicros(),new RuleEngine.Signal("test:"+event,new EffectEvent("player","target",caller.origin(),Set.of("test:original"),Map.of("amount",new Measure(amount,Unit.DAMAGE)))));}
        List<DamageCommand> damage(){return calls.stream().filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).toList();}
    }
    @Test void queuedDispatchKeepsCallerCreditAndParametersWithoutInstallingAContributor()throws Exception {
        var h=new Harness();h.session.start(0,SourceChange.bind(new EffectSource("observer","test:observer","ally",new BuffInstance.Origin("ally","observer","",""),Set.of())));h.send("start");
        assertEquals("test:caller_done",((Action.CueCommand)h.calls.getFirst()).cue());assertEquals(7,h.damage().getFirst().amount());assertEquals("target",h.damage().getFirst().target());assertEquals(h.caller.origin(),h.damage().getFirst().source());
        var mark=buff(h.session.state(),"test:mark","target");assertEquals(1_000_000,mark.deadline());assertEquals(h.caller.origin(),mark.origin());assertEquals(2,h.state().sources().size());
        assertEquals(1,h.calls.stream().filter(x->x instanceof Action.CueCommand c&&c.cue().equals("test:observed")).count());
        assertEquals(5,h.p.calculate(h.state(),"player",event(h.caller),"test:power",new Measure(5,Unit.DAMAGE),List.of()).output().value(),"invocation must not activate callee modifiers");
    }
    @Test void detachedCallerAfterUnequipCanInvokeAndSeparateCallsKeepIndependentArguments()throws Exception {
        var h=new Harness();h.send("later");h.session.start(0,SourceChange.remove(h.caller.instance()));h.session.observe(100_000,List.of());assertEquals(7,h.damage().getFirst().amount());assertEquals(h.caller.origin(),h.damage().getFirst().source());assertTrue(h.state().sources().isEmpty());assertEquals(1_100_000,buff(h.session.state(),"test:mark","target").deadline());
        var live=new EffectSource("live","test:callee","player",h.caller.origin(),Set.of(),Map.of("amount",new Measure(99,Unit.DAMAGE),"duration",new Measure(9,Unit.SECOND)));
        h.session.start(100_000,SourceChange.bind(live));h.session.start(100_000,SourceChange.bind(h.caller));h.send("start",3);assertEquals(List.of(7d,3d),h.damage().stream().map(DamageCommand::amount).toList());
    }
    @Test void inheritedEmittedEventsKeepInvocationContextButWorldFailureStopsLaterEffectsWithoutReplay()throws Exception {
        var h=new Harness();h.fail=true;assertThrows(IllegalStateException.class,()->h.send("start"));assertEquals(1,h.damage().size());assertTrue(h.state().buffs().instances().isEmpty());
        assertThrows(IllegalStateException.class,()->h.session.observe(100_000,List.of()));assertEquals(1,h.damage().size());assertFalse(h.session.state().idle());
    }
    @Test void runtimeFeedbackCanInvokeTheSameBundleUntilItsContentConditionStopsIt()throws Exception {
        var h=new Harness();var source=new EffectSource("loop","test:callee","player",h.caller.origin(),Set.of(),Map.of("amount",new Measure(1,Unit.DAMAGE),"duration",new Measure(1,Unit.SECOND)));
        var event=new EffectEvent("player","target",source.origin(),Set.of(),Map.of("remaining",new Measure(256,Unit.COUNT)),Map.of(),Map.of("source_instance","loop","bundle","test:callee"));
        h.session.start(0,new RuleEngine.Signal("test:loop",new BundleInvocation("test",source,event)));assertEquals(256,h.calls.size());assertTrue(h.session.state().idle());assertEquals(1,h.state().sources().size());
    }
    @Test void invalidBundlesEventsParametersAndUnitsFailCompilationAndRoundtripKeepsTheDeclaration()throws Exception {
        var p=load("bundle_invocation");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String change:List.of("missing_bundle","buff_bundle","missing_event","internal","missing_parameter","extra_parameter","wrong_unit")){
            var data=json("bundle_invocation");var call=data.getAsJsonArray("bundles").get(3).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject();
            switch(change){case "missing_bundle"->call.addProperty("bundle","test:missing");case "buff_bundle"->call.addProperty("bundle","test:mark_active");case "missing_event"->call.addProperty("event","test:missing");case "internal"->call.addProperty("event","chorus:internal/x");case "missing_parameter"->call.getAsJsonObject("parameters").remove("duration");case "extra_parameter"->call.getAsJsonObject("parameters").add("extra",call.getAsJsonObject("parameters").get("duration"));case "wrong_unit"->call.getAsJsonObject("parameters").getAsJsonObject("duration").addProperty("unit","damage");default->throw new AssertionError();}
            assertThrows(RuntimeException.class,()->compile(data),change);
        }
    }
    @Test void incompatibleInvocationVersionAndForgedSourceReferencesAreRejected()throws Exception {
        var h=new Harness();var s=new EffectSource("invoked","test:callee","player",h.caller.origin(),Set.of(),Map.of("amount",new Measure(7,Unit.DAMAGE),"duration",new Measure(1,Unit.SECOND)));
        var e=new EffectEvent("player","target",s.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",s.instance(),"bundle",s.bundle()));
        assertThrows(IllegalArgumentException.class,()->new BundleInvocation("test",s,event(h.caller)));
        assertThrows(IllegalStateException.class,()->h.session.start(0,new RuleEngine.Signal("test:apply",new BundleInvocation("wrong",s,e))));assertTrue(h.damage().isEmpty());
    }
    @Test void explicitEventOriginDoesNotRebindTheCallerAndInvocationRetainsDeclaredProcExclusions()throws Exception {
        for(boolean deny:List.of(false,true)) {
            var data=json("bundle_invocation");var callee=data.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();callee.addProperty("proc_key","test:apply");
            var call=data.getAsJsonArray("bundles").get(3).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject();call.addProperty("origin","event");call.addProperty("holder","event_actor");
            var h=new Harness(compile(data));var origin=new BuffInstance.Origin("attacker","shot","gun","skill",Set.of("test:attack"));
            var input=new EffectEvent("attacker","target",origin,Set.of("test:trigger"),Map.of("amount",new Measure(9,Unit.DAMAGE)),Map.of(),Map.of(),ImpactData.EMPTY,Optional.empty(),new ProcPolicy(deny?Set.of("test:apply"):Set.of()));
            h.session.start(0,new RuleEngine.Signal("test:start",input));assertEquals(deny?0:1,h.damage().size());
            if(!deny){assertEquals(origin,h.damage().getFirst().source());assertEquals(9,h.damage().getFirst().amount());assertEquals(origin,buff(h.session.state(),"test:mark","target").origin());}
            assertEquals("player",((Action.CueCommand)h.calls.getFirst()).target(),"origin must not change caller self");
        }
    }
    @Test void calleeDetachedTimersRetainParametersWhileSourceLifetimeIsRejectedForATransientSource()throws Exception {
        for(String lifetime:List.of("source","detached")) {
            var data=json("bundle_invocation");var body=data.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
            body.remove(1);var damage=body.remove(0);var after=new com.google.gson.JsonObject();after.add("after",com.google.gson.JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"}"));after.addProperty("lifetime",lifetime);var actions=new com.google.gson.JsonArray();actions.add(damage);after.add("do",actions);body.add(after);
            var h=new Harness(compile(data));
            if(lifetime.equals("source")){assertThrows(IllegalStateException.class,()->h.send("start"));assertTrue(h.damage().isEmpty());assertTrue(h.state().timers().isEmpty());}
            else{h.send("start");assertTrue(h.damage().isEmpty());h.session.start(0,SourceChange.remove(h.caller.instance()));h.session.observe(100_000,List.of());assertEquals(1,h.damage().size());assertEquals(7,h.damage().getFirst().amount());assertTrue(h.state().timers().isEmpty());}
        }
    }
}
