package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceGainScalarTest {
    static JsonObject data() { return JsonParser.parseString("""
        {"version":"test", "resources":[
          {"id":"test:a","capacity":1,"initial":0,"gain_scalar":0.875,"gain_scalar_profile":"test:scalar","gain_profile":"test:gain"},
          {"id":"test:b","capacity":1,"initial":0,"gain_scalar":0.5,"gain_scalar_profile":"test:scalar","gain_profile":"test:gain"},
          {"id":"test:legacy","capacity":1,"initial":0,"gain_profile":"test:gain"}],
         "profiles":[
          {"id":"test:scalar","version":"test","input_unit":"multiplier","steps":[{"type":"chorus:apply","id":"override","operation":"replace","group":{"name":"override","reduction":"max"}}]},
          {"id":"test:gain","version":"test","input_unit":"charge_fraction","steps":[{"type":"chorus:apply","id":"stat","operation":"multiply","group":{"name":"stat","reduction":"max"}}]}],
         "bundles":[
          {"id":"test:override","modifiers":[{"id":"scalar","profile":"test:scalar","stage":"override","group":"override","op":"replace","stacking_key":"test:scalar","reference":"synthetic test","confidence":"assumed","value":{"type":"chorus:constant","value":0.625,"unit":"multiplier"}}]},
          {"id":"test:stat","modifiers":[{"id":"stat","profile":"test:gain","stage":"stat","group":"stat","op":"multiply","stacking_key":"test:stat","reference":"synthetic test","confidence":"assumed","value":{"type":"chorus:constant","value":1,"unit":"delta"}}]}]}
        """).getAsJsonObject(); }
    static CompiledEffects compile(JsonObject data) { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow(); }
    static EffectSource source(String holder, String bundle) { return new EffectSource(holder + bundle, "test:" + bundle, holder, new BuffInstance.Origin(holder, bundle, "", ""), Set.of()); }
    static EffectState initial(CompiledEffects p) { var s = EffectState.empty(); for (var r : p.program().resources()) for (String h : List.of("recipient", "grantor")) s = s.withResource(r.initialize(h, 0)); return s; }
    static EnergyActions.Grant action(String resource, EnergyGains.Basis basis, double amount) {
        return new EnergyActions.Grant(resource, Evaluation.Target.VICTIM, new Value.Constant(amount, Unit.CHARGE), basis,
                basis == EnergyGains.Basis.REFERENCE ? Map.of("test:old_scalar", new Value.Constant(.875, Unit.MULTIPLIER), "test:old_stat", new Value.Constant(2, Unit.MULTIPLIER)) : Map.of(), Set.of(), Map.of());
    }
    static RuleEngine.Local<EffectState> grant(CompiledEffects p, EffectState state, String resource, EnergyGains.Basis basis, double amount) {
        var resources = new HashMap<String, ResourceDefinition>(); p.program().resources().forEach(r -> resources.put(r.id(), r));
        var source = source("grantor", "stat"); var event = new EffectEvent("grantor", "recipient", source.origin(), Set.of(), Map.of());
        var e = new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("test:gain", event)), "gain", source, Map.of()), Map.of(), Map.of(), resources, Map.of(), Optional.of(p));
        return (RuleEngine.Local<EffectState>) action(resource, basis, amount).execute(e);
    }
    static EnergyActions.Result result(CompiledEffects p, EffectState s, String resource, EnergyGains.Basis basis, double amount) { return (EnergyActions.Result) grant(p, s, resource, basis, amount).result(); }
    @Test void perAccountScalarAndCurrentRecipientOverridePrecedeGainProfileAndRetainBothTraces() {
        var p = compile(data()); var s = initial(p).withSource(source("grantor", "override")).withSource(source("grantor", "stat"));
        assertEquals(.0875, result(p,s,"test:a",EnergyGains.Basis.BASE,.1).grant().scaled(),1e-12);
        assertEquals(.05, result(p,s,"test:b",EnergyGains.Basis.BASE,.1).grant().scaled(),1e-12);
        s = s.withSource(source("recipient","override")).withSource(source("recipient","stat"));
        var r = result(p,s,"test:a",EnergyGains.Basis.BASE,.1);
        assertEquals(.125,r.grant().scaled(),1e-12); assertEquals(.875,r.recipient().orElseThrow().base()); assertEquals(.625,r.recipient().orElseThrow().value());
        assertEquals(.875,r.recipient().orElseThrow().calculation().orElseThrow().inputs().base().value()); assertEquals(.0625,r.calculation().orElseThrow().inputs().base().value());
        assertEquals(.125,result(p,s,"test:b",EnergyGains.Basis.BASE,.1).grant().scaled(),1e-12);
    }
    @Test void referenceFactorsAreRemovedBeforeCurrentScalarAndFixedGainsBypassBothQueries() {
        var p=compile(data()); var s=initial(p).withSource(source("recipient","override")).withSource(source("recipient","stat"));
        var r=result(p,s,"test:a",EnergyGains.Basis.REFERENCE,.175); assertEquals(.1,r.normalized().base(),1e-12); assertEquals(.125,r.grant().scaled(),1e-12);
        r=result(p,s,"test:a",EnergyGains.Basis.FIXED,2); assertEquals(1,r.grant().credited()); assertEquals(1,r.grant().overflow()); assertTrue(r.recipient().isEmpty()); assertTrue(r.calculation().isEmpty());
        var shape=action("test:a",EnergyGains.Basis.FIXED,2).validate(new Validation(Map.of(),Map.of(),false,Map.of(),Map.of("test:a",p.program().resources().getFirst())));
        var fixed=r; assertThrows(IllegalArgumentException.class,()->shape.read("recipient_scalar",fixed));
    }
    @Test void removingOverrideRestoresIntrinsicScalarWithoutChangingTheExistingBalance() {
        var p=compile(data()); var override=source("recipient","override");
        var session=new EffectSession(EffectTestSupport.engine(p),initial(p), _->RuleEngine.Empty.INSTANCE);
        session.start(0,SourceChange.bind(override));
        var paid=grant(p,session.state().engine().domain(),"test:a",EnergyGains.Basis.BASE,.4);
        var next=new EffectSession(EffectTestSupport.engine(p),paid.state(), _->RuleEngine.Empty.INSTANCE); next.start(0,SourceChange.remove(override.instance()));
        var s=next.state().engine().domain(); assertEquals(.25,s.resources().get(new ResourceState.Key("recipient","test:a")).value());
        assertEquals(.0875,result(p,s,"test:a",EnergyGains.Basis.BASE,.1).grant().scaled(),1e-12);
    }
    @Test void zeroScalarIsARealZeroGainAndLegacyDefinitionsDefaultToOne() {
        var d=data(); d.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("gain_scalar",0);
        var p=compile(d); var s=initial(p); assertEquals(0,result(p,s,"test:a",EnergyGains.Basis.BASE,.4).grant().scaled());
        assertEquals(.4,result(p,s,"test:legacy",EnergyGains.Basis.BASE,.4).grant().scaled());
        var encoded=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow(); assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,encoded).getOrThrow());
    }
    @Test void invalidDeclarationsAndNegativeEffectiveScalarRejectBeforeGranting() {
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) { var d=data(); d.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("gain_scalar",bad); assertThrows(IllegalStateException.class,()->compile(d)); }
        for(String bad:List.of("test:missing","test:gain")) { var d=data(); d.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("gain_scalar_profile",bad); assertThrows(IllegalStateException.class,()->compile(d)); }
        var d=data(); d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().getAsJsonObject("value").addProperty("value",-1);
        var p=compile(d); var s=initial(p).withSource(source("recipient","override")); assertThrows(IllegalArgumentException.class,()->result(p,s,"test:a",EnergyGains.Basis.BASE,.1));
        assertEquals(0,s.resources().get(new ResourceState.Key("recipient","test:a")).value()); assertEquals(.1,result(p,s,"test:a",EnergyGains.Basis.FIXED,.1).grant().scaled());
    }
}
