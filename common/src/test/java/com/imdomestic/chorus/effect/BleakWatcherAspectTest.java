package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class BleakWatcherAspectTest {
    static final String SLOT="chorus_d2:grenade", DUSK="chorus_d2:duskfield", BLEAK="chorus_d2:bleak_watcher", ARC="test:arcbolt", FUTURE="test:future";
    static CompiledEffects program() throws Exception {
        var parts=new ArrayList<EffectProgram>();parts.add(BleakWatcherTest.program(true).program());
        var dusk=json("duskfield");json("duskfield_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->dusk.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        for(String id:List.of(ARC,FUTURE)) {
            var a=dusk.getAsJsonArray("abilities").get(0).getAsJsonObject().deepCopy();a.addProperty("id",id);a.getAsJsonObject("effects").getAsJsonObject("energy").addProperty("bundle",id.equals(ARC)?"chorus_d2:arcbolt_energy_scaling":"test:future_scaling");
            if(id.equals(FUTURE)) {
                a.remove("parameters");a.add("on_use",com.google.gson.JsonParser.parseString("""
                    [{"action":{"type":"chorus:retain_cost","cost":"cast_cost","duration":{"type":"chorus:constant","value":1,"unit":"second"}},"as":"right"},
                     {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[
                       {"type":"chorus:refund_retained_cost","cost":"right","fraction":{"type":"chorus:constant","value":0.25,"unit":"multiplier"}},
                       {"type":"chorus:close_retained_cost","cost":"right"}]}]
                    """));
            }
            dusk.getAsJsonArray("abilities").add(a);
        }
        var energy=json("duskfield_energy");var futureBundle=energy.getAsJsonArray("bundles").get(0).getAsJsonObject().deepCopy();futureBundle.addProperty("id","test:future_scaling");
        futureBundle.getAsJsonArray("modifiers").forEach(m -> { var modifier=m.getAsJsonObject();modifier.getAsJsonObject("value").addProperty("value",modifier.get("id").getAsString().equals("base_rate")?.02:.9);modifier.addProperty("reference","Synthetic future grenade: 50 second cooldown and .9 recipient scalar");modifier.addProperty("confidence","assumed"); });energy.getAsJsonArray("bundles").add(futureBundle);
        parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,dusk).getOrThrow());parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,energy).getOrThrow());
        for(String name:List.of("arcbolt_energy","duskfield_damage_test_calibration","bleak_watcher_conversion","bleak_watcher_aspect"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static EffectSource aspect(String instance,String holder) { return new EffectSource(instance,"chorus_d2:bleak_watcher_aspect",holder,new BuffInstance.Origin(holder,instance,"",""),Set.of(),Map.of("hold_time",new Measure(.3,Unit.SECOND))); }
    static EffectSource stats(String holder,double points) {return new EffectSource(holder+"/stats","test:bleak_stats",holder,new BuffInstance.Origin(holder,"stats","",""),Set.of(),Map.of("points",new Measure(points,Unit.STAT_POINT)));}
    static String pool(String ability) {return "chorus_d2:grenade_energy";}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;
        Harness()throws Exception {this(null);}
        Harness(EffectState initial)throws Exception {p=program();var s=initial;if(s==null){s=EffectState.empty();for(var r:p.program().resources())for(String h:List.of("player","other"))s=s.withResource(new ResourceState(new ResourceState.Key(h,r.id()),0,r.capacity(),0));}session=new EffectSession(engine(p),s,_ -> RuleEngine.Empty.INSTANCE);}
        EffectState state(){return session.state().engine().domain();} long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal s){session.start(now(),s);} void until(long at){session.observe(at,List.of());}
        void select(String h,String id){send(new AbilityChange(h,state().abilities().getOrDefault(h,AbilityLoadout.EMPTY),id.isEmpty()?AbilityLoadout.EMPTY:new AbilityLoadout(Map.of(SLOT,id))).signal());}
        ResourceState account(String holder,String id){return state().resources().get(new ResourceState.Key(holder,pool(id)));}
        double rate(String holder,String id){return p.resourceRate(state(),account(holder,id)).perSecond();}
    }
    static EnergyActions.Result gain(CompiledEffects p,EffectState s,String holder,String resource,double amount,EnergyGains.Basis basis,Map<String,Value> factors) {
        var origin=new BuffInstance.Origin("other","external","","");var source=new EffectSource("query","chorus_d2:bleak_watcher_energy_scaling","other",origin,Set.of());
        var event=new EffectEvent("other",holder,origin,Set.of(),Map.of());var resources=new HashMap<String,ResourceDefinition>();p.program().resources().forEach(r->resources.put(r.id(),r));
        var e=new Evaluation(s,new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),s.buffs().timeMicros(),new RuleEngine.Signal("test:gain",event)),"query",source,Map.of()),Map.of(),Map.of(),resources,Map.of(),Optional.of(p));
        return (EnergyActions.Result)((RuleEngine.Local<EffectState>)new EnergyActions.Grant(resource,Evaluation.Target.VICTIM,new Value.Constant(amount,Unit.CHARGE),basis,factors,Set.of(),Map.of()).execute(e)).result();
    }
    static EnergyActions.Result gain(CompiledEffects p,EffectState s,String holder,String resource,double amount){return gain(p,s,holder,resource,amount,EnergyGains.Basis.BASE,Map.of());}
    static AbilityUse.Request request(int sequence) {return new AbilityUse.Request("player",SLOT,"cast-"+sequence,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of(),Map.of("on_ground",true,"sprinting",false,"crouching",false),Map.of()));}
    @Test void allSelectionsShareTheSpentBalanceWithoutHiddenCandidateBanks()throws Exception {
        var h=new Harness(EffectState.empty());h.select("player",FUTURE);assertEquals(1,h.account("player",FUTURE).value());
        h.send(request(1).signal());assertEquals(0,h.account("player",FUTURE).value());
        int sequence=1;
        for(String id:List.of(DUSK,ARC,BLEAK,FUTURE,DUSK)) {
            h.select("player",id);assertEquals(0,h.account("player",id).value());
            assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,((AbilityUse.Receipt)h.p.useAbility(h.state(),request(++sequence)).result()).outcome());
            assertEquals(1,h.state().resources().size());
        }
    }
    @Test void switchingSplitsRecoveryAndUsesTheCurrentSelectionsScalarWithoutDuplicatedStats()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.select("other",BLEAK);h.send(SourceChange.bind(stats("player",100)));h.until(1_000_000);
        double expected=2.75/131.7;assertEquals(expected,h.account("player",DUSK).value(),1e-12);
        h.select("player",ARC);assertEquals(expected,h.account("player",ARC).value(),1e-12);h.until(2_000_000);expected+=2.75/151.5;
        assertEquals(expected,h.account("player",ARC).value(),1e-12);assertEquals(.04*.75*2.25,gain(h.p,h.state(),"player",pool(ARC),.04).grant().scaled(),1e-12);
        h.select("player",FUTURE);h.until(3_000_000);expected+=2.75/50;
        assertEquals(expected,h.account("player",FUTURE).value(),1e-12);assertEquals(.04*.9*2.25,gain(h.p,h.state(),"player",pool(FUTURE),.04).grant().scaled(),1e-12);
        assertEquals(3/175.6,h.account("other",BLEAK).value(),1e-12);
        assertEquals(1,h.state().sources().values().stream().filter(s->s.holder().equals("player")&&s.origin().ability().equals(FUTURE)).count());
    }
    @Test void clearingSelectionPausesBaselineAndAspectWithoutErasingOrRefillingThePool()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.send(SourceChange.bind(aspect("a","player")));h.until(1_000_000);double balance=h.account("player",DUSK).value();
        h.select("player","");h.until(10_000_000);assertEquals(balance,h.account("player",DUSK).value());assertEquals(0,h.rate("player",DUSK));
        assertEquals(0,gain(h.p,h.state(),"player",pool(DUSK),.04).grant().scaled());
        assertEquals(.04,gain(h.p,h.state(),"player",pool(DUSK),.04,EnergyGains.Basis.FIXED,Map.of()).grant().scaled());
        h.select("player",ARC);assertEquals(balance,h.account("player",ARC).value());assertEquals(1/175.6,h.rate("player",ARC),1e-12);
        h.send(SourceChange.remove("a"));assertEquals(1/151.5,h.rate("player",ARC),1e-12);
    }
    @Test void delayedRefundAfterChangingGrenadeCreditsTheSamePoolWithoutCurrentCes()throws Exception {
        var h=new Harness(EffectState.empty());h.select("player",FUTURE);h.send(request(1).signal());h.select("player",ARC);
        h.until(100_000);assertEquals(.25+.1/151.5,h.account("player",ARC).value(),1e-12);assertEquals(1,h.state().resources().size());
        h.until(200_000);assertEquals(.25+.2/151.5,h.account("player",ARC).value(),1e-12);
    }
    @Test void aspectOverridesEveryParticipatingGrenadeIncludingAnUnlistedFutureDefinition()throws Exception {
        for(String id:List.of(DUSK,BLEAK,ARC,FUTURE)) {
            var h=new Harness();h.select("player",id);h.select("other",id);h.send(SourceChange.bind(stats("player",100)));
            var original=h.rate("player",id);var other=h.rate("other",id);h.send(SourceChange.bind(aspect("aspect","player")));
            assertEquals(2.75/175.6,h.rate("player",id),1e-12);assertEquals(other,h.rate("other",id),1e-12);
            var r=gain(h.p,h.state(),"player",pool(id),.04);assertEquals(.04*.625*2.25,r.grant().scaled(),1e-12);assertEquals(.625,r.recipient().orElseThrow().value());
            h.send(SourceChange.remove("aspect"));assertEquals(original,h.rate("player",id),1e-12);
            double intrinsic=id.equals(DUSK)?.875:id.equals(BLEAK)?.625:id.equals(ARC)?.75:.9;
            assertEquals(.04*intrinsic*2.25,gain(h.p,h.state(),"player",pool(id),.04).grant().scaled(),1e-12);
        }
    }
    @Test void equipAndUnequipSplitRealTimeAtTheBoundaryWithoutResettingEnergy()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.until(1_000_000);assertEquals(1/131.7,h.account("player",DUSK).value(),1e-12);
        h.send(SourceChange.bind(aspect("aspect","player")));h.until(2_000_000);double partial=1/131.7+1/175.6;assertEquals(partial,h.account("player",DUSK).value(),1e-12);
        h.send(SourceChange.remove("aspect"));assertEquals(partial,h.account("player",DUSK).value(),1e-12);h.until(3_000_000);assertEquals(partial+1/131.7,h.account("player",DUSK).value(),1e-12);
    }
    @Test void duplicateAspectsDoNotStackAndChangingSelectionDoesNotRestoreItsOriginalCooldown()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.send(SourceChange.bind(aspect("a","player")));h.send(SourceChange.bind(aspect("b","player")));
        assertEquals(1/175.6,h.rate("player",DUSK),1e-12);assertEquals(.025,gain(h.p,h.state(),"player",pool(DUSK),.04).grant().scaled(),1e-12);
        h.select("player",FUTURE);assertEquals(1/175.6,h.rate("player",FUTURE),1e-12);h.send(SourceChange.remove("a"));assertEquals(1/175.6,h.rate("player",FUTURE),1e-12);
        h.send(SourceChange.remove("b"));assertEquals(.02,h.rate("player",FUTURE),1e-12);
    }
    @Test void fixedGainsSkipAspectAndReferenceGainsNormalizeBeforeTheCurrentScalar()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.send(SourceChange.bind(stats("player",100)));h.send(SourceChange.bind(aspect("a","player")));
        assertEquals(.04,gain(h.p,h.state(),"player",pool(DUSK),.04,EnergyGains.Basis.FIXED,Map.of()).grant().scaled(),1e-12);
        var r=gain(h.p,h.state(),"player",pool(DUSK),.04*.875*2.25,EnergyGains.Basis.REFERENCE,Map.of("test:old_ces",new Value.Constant(.875,Unit.MULTIPLIER),"test:old_stat",new Value.Constant(2.25,Unit.MULTIPLIER)));
        assertEquals(.04,r.normalized().base(),1e-12);assertEquals(.05625,r.grant().scaled(),1e-12);
        assertEquals(0,r.recipient().orElseThrow().calculation().orElseThrow().inputs().base().value());assertEquals(.025,r.calculation().orElseThrow().inputs().base().value(),1e-12);
    }
    @Test void combinedAspectKeepsHeldConversionAndBaseSelectionPaymentWithSeparateTraces()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.send(SourceChange.bind(aspect("a","player")));var s=h.state().withResource(new ResourceState(new ResourceState.Key("player",pool(DUSK)),1,1,0));
        var event=new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of("chorus:ability_input_release"),Map.of("input_hold_time",new Measure(.3,Unit.SECOND)),Map.of("on_ground",true,"sprinting",false,"crouching",false),Map.of());
        var result=h.p.useAbility(s,new AbilityUse.Request("player",SLOT,"cast",event));var receipt=(AbilityUse.Receipt)result.result();
        assertEquals(AbilityUse.Outcome.ACCEPTED,receipt.outcome());assertEquals(BLEAK,receipt.resolved());assertEquals(pool(DUSK),receipt.cost().orElseThrow().after().key().resource());assertEquals(0,receipt.cost().orElseThrow().after().value());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
}
