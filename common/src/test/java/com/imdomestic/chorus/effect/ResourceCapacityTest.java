package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceCapacityTest {
    static final String ENERGY="test:energy";
    static EffectSource input(String holder) { return new EffectSource(holder+"/input","test:capacity_inputs",holder,new BuffInstance.Origin(holder,"input","",""),Set.of()); }
    static EffectSource bonus(String id,String holder) { return new EffectSource(id,"test:capacity_bonus",holder,new BuffInstance.Origin(holder,id,"",""),Set.of()); }
    record Heal(long time,HealingCommand command) {}
    static final class Harness {
        final CompiledEffects p; final EffectSession session; final List<Heal> heals=new ArrayList<>(); boolean fail;
        Harness() throws Exception { this(load("resource_capacity")); }
        Harness(CompiledEffects program) {
            p=program;session=new EffectSession(engine(p),EffectState.empty(),request->{
                var c=(HealingCommand)request.command();heals.add(new Heal(now(),c));
                if(fail)throw new IllegalStateException("Unknown resize healing outcome");
                return new HealingReceipt("heal/"+heals.size(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);
            });
            send(SourceChange.bind(input("player")));
        }
        EffectState state(){return session.state().engine().domain();} long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal signal){session.start(now(),signal);} void until(long at){session.observe(at,List.of());}
        void send(String kind,String target,double amount){send(new RuleEngine.Signal("test:"+kind,new EffectEvent("player",target,input("player").origin(),Set.of(),Map.of("amount",new Measure(amount,Unit.CHARGE)))));}
        void resize(double size){send("resize","player",size);} ResourceState account(String holder){return state().resources().get(new ResourceState.Key(holder,ENERGY));}
        void grant(double value){send("grant","player",value);}
    }
    @Test void primitivePreservesUnitsClipsOnlyOverflowAndValidatesReceiptIdentity() {
        var before=new ResourceState(new ResourceState.Key("player",ENERGY),1.7,2,7);
        var grow=Resources.resize(before,3);assertEquals(1.7,grow.after().value());assertEquals(0,grow.discarded());assertTrue(grow.changed());
        var shrink=Resources.resize(grow.after(),1);assertEquals(1,shrink.after().value());assertEquals(.7,shrink.discarded());
        var repeat=Resources.resize(shrink.after(),1);assertFalse(repeat.changed());assertSame(shrink.after(),repeat.after());
        assertEquals(1,Resources.resize(shrink.after(),3).after().value());assertEquals(.4,Resources.resize(before,.4).after().value());
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->Resources.resize(before,invalid));
        assertThrows(IllegalArgumentException.class,()->new Resources.ResizeResult(before,grow.after(),1));
        assertThrows(IllegalArgumentException.class,()->new Resources.ResizeResult(before,new ResourceState(new ResourceState.Key("other",ENERGY),1,1,7),.7));
    }
    @Test void dslRetainsBalanceAndTimeAndResizeFactsAreNotPaymentsOrGrants() throws Exception {
        var h=new Harness();h.grant(.7);h.resize(3);assertEquals(.7,h.account("player").value());h.grant(2);h.resize(1);
        assertEquals(1,h.account("player").value());assertEquals(List.of(3.0,1.0,1.7),h.heals.stream().map(x->x.command().amount()).toList());
        assertTrue(h.heals.getLast().command().tags().contains("test:discard"));var before=h.state();h.resize(1);assertEquals(before,h.state());
        var r=Resources.resize(new ResourceState(new ResourceState.Key("player",ENERGY),2.7,3,0),1);var fact=ResourceFacts.resized(r,input("player").origin());
        assertEquals("resize",fact.references().get("reason"));assertEquals(3,fact.numbers().get("before_capacity").value());assertEquals(-1.7,fact.numbers().get("delta").value());
        assertTrue(fact.flags().get("capacity_changed"));assertFalse(fact.numbers().containsKey("paid"));assertFalse(fact.numbers().containsKey("credited"));
    }
    @Test void timelineSettlesOldCeilingThenActivatesPreviouslyUnreachableThresholds() throws Exception {
        var h=new Harness();h.grant(.5);h.until(500_000);assertEquals(1,h.account("player").value());h.resize(3);h.until(2_500_000);
        assertEquals(3,h.account("player").value());var boundary=h.heals.stream().filter(x->x.command().tags().contains("test:threshold")).findFirst().orElseThrow();assertEquals(2_500_000,boundary.time());
        h.resize(1);h.resize(3);h.until(3_000_000);assertEquals(1.5,h.account("player").value());assertEquals(1,h.heals.stream().filter(x->x.command().tags().contains("test:threshold")).count());
    }
    @Test void independentCapacityContributionsRecomputeForTheRecipientWithoutFreeEnergy() throws Exception {
        var h=new Harness();h.send(SourceChange.bind(input("other")));h.send(SourceChange.bind(bonus("a","player")));h.send(SourceChange.bind(bonus("b","player")));
        h.send("recompute","player",0);assertEquals(3,h.account("player").capacity());assertEquals(0,h.account("player").value());
        h.send("recompute","other",0);assertEquals(1,h.account("other").capacity());h.grant(2.5);
        h.send(SourceChange.remove("a"));h.send("recompute","player",0);assertEquals(2,h.account("player").capacity());assertEquals(2,h.account("player").value());
        h.send(SourceChange.remove("b"));h.send("recompute","player",0);assertEquals(1,h.account("player").capacity());
        h.send(SourceChange.remove("player/input"));h.send(SourceChange.bind(input("player")));assertEquals(1,h.account("player").value(),"initialize must not reset a resized account");
    }
    @Test void retainedRefundUsesTheCurrentCeilingAndOverflowCannotBeRecoveredAfterGrowingAgain() throws Exception {
        var h=new Harness();h.resize(3);h.grant(2.5);h.send(new AbilityChange("player",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:grenade","test:grenade"))).signal());
        h.send(new AbilityUse.Request("player","test:grenade","cast",new EffectEvent("player","player",input("player").origin(),Set.of(),Map.of())).signal());
        assertEquals(1.5,h.account("player").value());h.resize(1);h.until(100_000);assertEquals(1,h.account("player").value());
        assertEquals(0,h.heals.stream().filter(x->x.command().tags().contains("test:refund")).findFirst().orElseThrow().command().amount());
        h.resize(3);h.until(200_000);assertEquals(1.1,h.account("player").value());
    }
    @Test void unknownWorldFollowupKeepsCommittedResizeAndDoesNotReplay() throws Exception {
        var h=new Harness();h.resize(3);h.grant(2.5);h.fail=true;assertThrows(IllegalStateException.class,()->h.resize(1));
        assertEquals(1,h.account("player").value());assertEquals(1,h.account("player").capacity());assertEquals(2,h.heals.size());
        assertFalse(h.session.state().idle());assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(2,h.heals.size());
    }
    @Test void declarationsAndDslRejectFixedAccountsBadUnitsInvalidValuesAndUnknownFields() throws Exception {
        var p=load("resource_capacity");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String bad:List.of("fixed","unit","zero","negative","typo")) {
            var d=json("resource_capacity");var resize=d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");
            switch(bad){
                case "fixed" -> d.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("resizable",false);
                case "unit" -> resize.getAsJsonObject("capacity").addProperty("unit","damage");
                case "zero","negative" -> {var value=new com.google.gson.JsonObject();value.addProperty("type","chorus:constant");value.addProperty("unit","charge_fraction");value.addProperty("value",bad.equals("zero")?0:-1);resize.add("capacity",value);}
                case "typo" -> resize.addProperty("refill",true);
            }
            assertThrows(RuntimeException.class,()->compile(d),bad);
        }
        var h=new Harness();var before=h.state();assertThrows(RuntimeException.class,()->h.resize(-1));assertEquals(before,h.state());
        var legacy=load("resource_regeneration").program().resources().getFirst();assertFalse(legacy.resizable());assertThrows(IllegalArgumentException.class,()->legacy.validate(new ResourceState(new ResourceState.Key("p",legacy.id()),0,3,0)));
        assertThrows(IllegalArgumentException.class,()->p.program().resources().getFirst().validate(new ResourceState(new ResourceState.Key("p",ENERGY),0,0,0)));
    }
}
