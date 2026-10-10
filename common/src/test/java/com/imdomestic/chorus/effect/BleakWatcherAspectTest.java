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
        var futureResource=dusk.getAsJsonArray("resources").get(0).getAsJsonObject().deepCopy();futureResource.addProperty("id",FUTURE+"_energy");futureResource.addProperty("base_rate",.02);futureResource.addProperty("gain_scalar",.9);dusk.getAsJsonArray("resources").add(futureResource);
        for(String id:List.of(ARC,FUTURE)) { var a=dusk.getAsJsonArray("abilities").get(0).getAsJsonObject().deepCopy();a.addProperty("id",id);a.getAsJsonObject("cost").addProperty("resource",id.equals(ARC)?"chorus_d2:arcbolt_energy":FUTURE+"_energy");a.getAsJsonObject("effects").getAsJsonObject("energy").addProperty("bundle",id.equals(ARC)?"chorus_d2:arcbolt_energy_scaling":"test:future_scaling");dusk.getAsJsonArray("abilities").add(a); }
        var energy=json("duskfield_energy");var futureBundle=energy.getAsJsonArray("bundles").get(0).getAsJsonObject().deepCopy();futureBundle.addProperty("id","test:future_scaling");futureBundle.getAsJsonArray("modifiers").forEach(m->m.getAsJsonObject().getAsJsonObject("if").addProperty("is",FUTURE+"_energy"));energy.getAsJsonArray("bundles").add(futureBundle);
        parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,dusk).getOrThrow());parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,energy).getOrThrow());
        for(String name:List.of("arcbolt_energy","duskfield_damage_test_calibration","bleak_watcher_conversion","bleak_watcher_aspect"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static EffectSource aspect(String instance,String holder) { return new EffectSource(instance,"chorus_d2:bleak_watcher_aspect",holder,new BuffInstance.Origin(holder,instance,"",""),Set.of(),Map.of("hold_time",new Measure(.3,Unit.SECOND))); }
    static EffectSource stats(String holder,double points) {return new EffectSource(holder+"/stats","test:bleak_stats",holder,new BuffInstance.Origin(holder,"stats","",""),Set.of(),Map.of("points",new Measure(points,Unit.STAT_POINT)));}
    static String pool(String ability) {return ability.equals(ARC)?"chorus_d2:arcbolt_energy":ability+"_energy";}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;
        Harness()throws Exception {p=program();var s=EffectState.empty();for(var r:p.program().resources())for(String h:List.of("player","other"))s=s.withResource(new ResourceState(new ResourceState.Key(h,r.id()),0,r.capacity(),0));session=new EffectSession(engine(p),s,_ -> RuleEngine.Empty.INSTANCE);}
        EffectState state(){return session.state().engine().domain();} long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal s){session.start(now(),s);} void until(long at){session.observe(at,List.of());}
        void select(String h,String id){send(new AbilityChange(h,state().abilities().getOrDefault(h,AbilityLoadout.EMPTY),new AbilityLoadout(Map.of(SLOT,id))).signal());}
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
    @Test void aspectOverridesEveryParticipatingGrenadeIncludingAnUnlistedFutureDefinition()throws Exception {
        for(String id:List.of(DUSK,BLEAK,ARC,FUTURE)) {
            var h=new Harness();h.select("player",id);h.select("other",id);h.send(SourceChange.bind(stats("player",100)));
            var original=h.rate("player",id);var other=h.rate("other",id);h.send(SourceChange.bind(aspect("aspect","player")));
            assertEquals(2.75/175.6,h.rate("player",id),1e-12);assertEquals(other,h.rate("other",id),1e-12);
            var r=gain(h.p,h.state(),"player",pool(id),.04);assertEquals(.04*.625*2.25,r.grant().scaled(),1e-12);assertEquals(.625,r.recipient().orElseThrow().value());
            h.send(SourceChange.remove("aspect"));assertEquals(original,h.rate("player",id),1e-12);
            var definition=h.p.program().resources().stream().filter(x->x.id().equals(pool(id))).findFirst().orElseThrow();
            assertEquals(.04*definition.gainScalar()*2.25,gain(h.p,h.state(),"player",pool(id),.04).grant().scaled(),1e-12);
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
        assertEquals(.875,r.recipient().orElseThrow().calculation().orElseThrow().inputs().base().value());assertEquals(.025,r.calculation().orElseThrow().inputs().base().value(),1e-12);
    }
    @Test void combinedAspectKeepsHeldConversionAndBaseSelectionPaymentWithSeparateTraces()throws Exception {
        var h=new Harness();h.select("player",DUSK);h.send(SourceChange.bind(aspect("a","player")));var s=h.state().withResource(new ResourceState(new ResourceState.Key("player",pool(DUSK)),1,1,0));
        var event=new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of("chorus:ability_input_release"),Map.of("input_hold_time",new Measure(.3,Unit.SECOND)),Map.of("on_ground",true,"sprinting",false,"crouching",false),Map.of());
        var result=h.p.useAbility(s,new AbilityUse.Request("player",SLOT,"cast",event));var receipt=(AbilityUse.Receipt)result.result();
        assertEquals(AbilityUse.Outcome.ACCEPTED,receipt.outcome());assertEquals(BLEAK,receipt.resolved());assertEquals(pool(DUSK),receipt.cost().orElseThrow().after().key().resource());assertEquals(0,receipt.cost().orElseThrow().after().value());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
}
