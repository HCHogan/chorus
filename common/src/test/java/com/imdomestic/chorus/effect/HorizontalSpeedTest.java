package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HorizontalSpeedTest {
    static EffectSource cap(String id,double speed){return new EffectSource(id,"test:speed","player",new BuffInstance.Origin("player",id,"",""),Set.of(),Map.of("speed",new Measure(speed,Unit.METER_PER_SECOND)));}
    @Test void independentCeilingsTakeMinimumKeepEveryOriginAndDistinguishZeroFromAbsence()throws Exception{
        var p=load("horizontal_speed");var input=event(cap("a",4));var empty=EffectState.empty();
        assertTrue(p.hasHorizontalSpeedLimits());assertTrue(p.horizontalSpeedLimit(empty,input).maximum().isEmpty());
        var state=empty.withSource(cap("z",4)).withSource(cap("a",2));var decision=p.horizontalSpeedLimit(state,input);
        assertEquals(2,decision.maximum().orElseThrow());assertEquals(List.of("a","z"),decision.contributions().stream().map(c->c.origin().source()).toList());
        assertEquals(decision,p.horizontalSpeedLimit(state,decision.query()));assertThrows(UnsupportedOperationException.class,()->decision.contributions().clear());
        assertEquals(0,p.horizontalSpeedLimit(state.withSource(cap("zero",0)),input).maximum().orElseThrow());
        assertEquals(4,p.horizontalSpeedLimit(state.withoutSource("a"),input).maximum().orElseThrow());assertEquals(2,state.sources().size());
    }
    @Test void recipientBuffPausesAndExpiresWithoutRestrictingItsCaster()throws Exception{
        var data=json("horizontal_speed");data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("on_stow","pause");var p=compile(data);
        var origin=new BuffInstance.Origin("caster","debuff","weapon","");var store=Buffs.grant(BuffStore.empty(),p.buff("test:slow"),"player","player",origin,1,1,300_000).store();
        var state=EffectState.empty().withBuffs(store);var input=event(cap("a",4));assertEquals(origin,p.horizontalSpeedLimit(state,input).contributions().getFirst().origin());
        assertTrue(p.horizontalSpeedLimit(state,new EffectEvent("caster","player",origin,Set.of(),Map.of())).maximum().isEmpty());
        var paused=Buffs.weaponState(store,"caster","weapon",true).store();assertTrue(p.horizontalSpeedLimit(state.withBuffs(paused),input).maximum().isEmpty());
        var resumed=Buffs.weaponState(paused,"caster","weapon",false).store();assertEquals(1,p.horizontalSpeedLimit(state.withBuffs(resumed),input).maximum().orElseThrow());
        var session=new EffectSession(engine(p),state.withSource(cap("a",4)),_-> {throw new AssertionError("Pure expiry");});session.observe(300_000,List.of());assertEquals(4,p.horizontalSpeedLimit(session.state().engine().domain(),input).maximum().orElseThrow());
    }
    @Test void conditionsCanSkipUnavailableValuesButSelectedInvalidValuesFailExplicitly()throws Exception{
        var data=json("horizontal_speed");var limit=data.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("horizontal_speed_limits").get(0).getAsJsonObject();
        limit.add("if",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":false}"));var p=compile(data);var state=EffectState.empty().withSource(source("test:broken"));assertTrue(p.horizontalSpeedLimit(state,event(source("test:broken"))).maximum().isEmpty());
        var active=load("horizontal_speed");assertThrows(IllegalArgumentException.class,()->active.horizontalSpeedLimit(state,event(source("test:broken"))));
        assertThrows(IllegalArgumentException.class,()->active.horizontalSpeedLimit(EffectState.empty().withSource(cap("a",-1)),event(cap("a",1))));
        assertThrows(IllegalArgumentException.class,()->active.horizontalSpeedLimit(state,new EffectEvent("","",source("test:broken").origin(),Set.of(),Map.of())));
    }
    @Test void strictTypedDeclarationsRoundtripAndRejectAmbiguousOrUnboundData()throws Exception{
        var p=load("horizontal_speed");assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());assertFalse(load("native_motion").hasHorizontalSpeedLimits());
        for(String fault:List.of("unit","negative","unknown","duplicate","binding")){
            var data=json("horizontal_speed");var limits=data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("horizontal_speed_limits");var limit=limits.get(0).getAsJsonObject();
            switch(fault){
                case "unit"->limit.getAsJsonObject("speed").addProperty("unit","meter");
                case "negative"->limit.getAsJsonObject("speed").addProperty("value",-1);
                case "unknown"->limit.addProperty("priority",1);
                case "duplicate"->limits.add(limit.deepCopy());
                case "binding"->limit.add("speed",JsonParser.parseString("{\"type\":\"chorus:result\",\"binding\":\"missing\",\"field\":\"value\"}"));
            }assertThrows(RuntimeException.class,()->compile(data),fault);
        }
    }
}
