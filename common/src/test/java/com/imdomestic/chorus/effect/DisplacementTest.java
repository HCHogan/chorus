package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DisplacementTest {
    static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin("player","caster","weapon","ability",Set.of("test:original"));
    static final class Harness {
        final CompiledEffects p;final EffectSession session;
        final List<Displacement.Receipt> receipts=new ArrayList<>();final List<HealingCommand> heals=new ArrayList<>();
        WorldPosition point=new WorldPosition("world",2,3,4);double ceiling=100;boolean missing,mismatch,fail;Displacement.Outcome reject;int reads;
        Harness()throws Exception{
            p=load("displacement");session=new EffectSession(engine(p),EffectState.empty(),r->switch(r.command()){
                case PositionQuery q->{reads++;yield new PositionQuery.Result(q,missing?Optional.empty():Optional.of(point));}
                case Displacement.Command c->{
                    var actual=mismatch?new Displacement.Command("wrong",c.direction(),c.distance(),c.origin(),c.tags()):c;
                    Displacement.Receipt receipt;
                    if(reject!=null)receipt=Displacement.Receipt.rejected(actual,reject);
                    else if(c.direction().isEmpty())receipt=Displacement.Receipt.rejected(actual,Displacement.Outcome.MISSING_DIRECTION);
                    else if(!c.direction().get().dimension().equals(point.dimension()))receipt=Displacement.Receipt.rejected(actual,Displacement.Outcome.WRONG_DIMENSION);
                    else{var d=c.requested().orElseThrow();var resolved=new Displacement.Offset(d.x(),Math.min(d.y(),ceiling-point.y()),d.z());var next=new WorldPosition(point.dimension(),point.x()+resolved.x(),point.y()+resolved.y(),point.z()+resolved.z());var change=new Displacement.Change(point,next,resolved);point=next;receipt=new Displacement.Receipt(actual,change.changed()?Displacement.Outcome.APPLIED:Displacement.Outcome.UNCHANGED,Optional.of(change));}
                    receipts.add(receipt);if(fail)throw new IllegalStateException("unknown relocation outcome");yield receipt;
                }
                case HealingCommand c->{heals.add(c);yield new HealingReceipt(r.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);}
                default->throw new AssertionError(r.command());
            });
            send(SourceChange.bind(new EffectSource("source","test:displacement","player",ORIGIN,Set.of())));
        }
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);}
        void event(String type,double x,double y,double z,double distance){send(new RuleEngine.Signal(type,new EffectEvent("player","target",ORIGIN,Set.of(),Map.of("x",new Measure(x,Unit.MULTIPLIER),"y",new Measure(y,Unit.MULTIPLIER),"z",new Measure(z,Unit.MULTIPLIER),"distance",new Measure(distance,Unit.METER)))));}
        void until(long time){session.observe(time,List.of());}
        boolean locked(){return !p.checkAction(state(),ActionGate.Kind.VERTICAL_MOTION,ActionGate.Phase.CONTINUE,new EffectEvent("target","",ORIGIN,Set.of(),Map.of())).allowed();}
    }
    @Test void clippedDistanceAndActualDestinationAreTypedAndKeepTheCaster()throws Exception{
        var h=new Harness();h.ceiling=3.4;h.event("test:move",0,2,0,1);var r=h.receipts.getFirst();
        assertEquals(Displacement.Outcome.APPLIED,r.outcome());assertTrue(r.clipped());assertEquals(1,r.command().distance());assertEquals(ORIGIN,r.command().origin());assertEquals("target",r.command().target());
        assertEquals(Unit.METER,DisplacementActions.RESULT.fields().get("delta_y").unit());assertEquals(.4,DisplacementActions.RESULT.fields().get("delta_y").read().applyAsDouble(r),1e-10);assertEquals(Optional.of(h.point),r.position());
        assertEquals(2,h.heals.size());assertEquals(.4,h.heals.getFirst().amount(),1e-10);assertEquals("target",h.heals.getLast().target());assertEquals(1,h.heals.getLast().amount());
    }
    @Test void blockedAndZeroMovesAreObservedButEmitNoAppliedFact()throws Exception{
        var zero=new Displacement.Command("target",Optional.of(new WorldDirection("world",0,-1,0)),0,ORIGIN,Set.of());var point=new WorldPosition("world",0,0,0);assertFalse(new Displacement.Receipt(zero,Displacement.Outcome.UNCHANGED,Optional.of(new Displacement.Change(point,point,new Displacement.Offset(0,0,0)))).clipped());
        for(double distance:List.of(0.,1.)){
            var h=new Harness();h.ceiling=h.point.y();h.event("test:move",0,1,0,distance);var r=h.receipts.getFirst();
            assertEquals(Displacement.Outcome.UNCHANGED,r.outcome());assertEquals(distance>0,r.clipped());assertEquals(0,r.requireChange().delta().length());assertEquals(1,h.heals.size());assertEquals(0,h.heals.getFirst().amount());
        }
    }
    @Test void missingDirectionAndRejectedTargetsHaveNoInventedMeasurements()throws Exception{
        for(String reason:List.of("position","zero_axis","dead")){
            var h=new Harness();h.missing=reason.equals("position");if(reason.equals("dead"))h.reject=Displacement.Outcome.DEAD;
            h.event("test:move",0,reason.equals("zero_axis")?0:1,0,1);var r=h.receipts.getFirst();assertTrue(r.change().isEmpty());assertTrue(r.position().isEmpty());assertTrue(h.heals.isEmpty());
            assertThrows(IllegalArgumentException.class,()->DisplacementActions.RESULT.fields().get("delta_y").read().applyAsDouble(r));
        }
    }
    @Test void delayedDirectionKeepsItsDimensionAndDetachedAttribution()throws Exception{
        var h=new Harness();h.event("test:delayed",0,1,0,1);h.send(SourceChange.remove("source"));h.until(100_000);assertEquals(ORIGIN,h.receipts.getFirst().command().origin());assertEquals(4,h.point.y());assertEquals(1,h.reads);
        var cross=new Harness();cross.event("test:delayed",0,1,0,1);cross.point=new WorldPosition("other",2,3,4);cross.until(100_000);assertEquals(Displacement.Outcome.WRONG_DIMENSION,cross.receipts.getFirst().outcome());assertTrue(cross.heals.isEmpty());
    }
    @Test void periodicLiftStopsAtItsActualHeightAndRetainsHoldUntilExpiry()throws Exception{
        var h=new Harness();h.event("test:lift",0,0,0,0);assertTrue(h.locked());h.until(50_000);assertEquals(3.25,h.point.y());h.until(250_000);
        assertEquals(4.05,h.point.y(),1e-10);assertEquals(5,h.receipts.size());assertEquals(.05,h.receipts.getLast().command().distance(),1e-10);assertTrue(h.receipts.stream().allMatch(r->r.command().origin().equals(ORIGIN)));
        h.until(4_999_999);assertEquals(5,h.receipts.size());assertTrue(h.locked());h.until(5_000_000);assertFalse(h.locked());
    }
    @Test void clippingOrRejectionCancelsFutureStepsAndCleansingCancelsOwnedTimers()throws Exception{
        var clipped=new Harness();clipped.ceiling=3.6;clipped.event("test:lift",0,0,0,0);clipped.until(250_000);assertEquals(3,clipped.receipts.size());assertTrue(clipped.receipts.getLast().clipped());assertTrue(clipped.locked());clipped.until(1_000_000);assertEquals(3,clipped.receipts.size());
        var rejected=new Harness();rejected.reject=Displacement.Outcome.UNLOADED;rejected.event("test:lift",0,0,0,0);rejected.until(1_000_000);assertEquals(1,rejected.receipts.size());
        var clean=new Harness();clean.event("test:lift",0,0,0,0);clean.until(50_000);clean.event("test:release",0,0,0,0);assertFalse(clean.locked());clean.until(1_000_000);assertEquals(1,clean.receipts.size());
    }
    @Test void unknownAndMismatchedWorldOutcomesStopWithoutRepeatingDisplacement()throws Exception{
        for(boolean mismatch:List.of(false,true)){
            var h=new Harness();h.mismatch=mismatch;h.fail=!mismatch;assertThrows(IllegalStateException.class,()->h.event("test:move",0,1,0,1));assertEquals(4,h.point.y());assertTrue(h.heals.isEmpty());
            assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.receipts.size());
        }
    }
    @Test void codecRequiresDistanceAndAxisUnitsAndTheCorrectCapturedBinding()throws Exception{
        var p=load("displacement");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String variant:List.of("distance","axis","binding","negative")){
            var data=json("displacement");var steps=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");var axis=steps.get(1).getAsJsonObject().getAsJsonObject("action");var action=steps.get(2).getAsJsonObject().getAsJsonObject("action");
            switch(variant){case "distance"->action.getAsJsonObject("distance").addProperty("unit","meter_per_second");case "axis"->axis.getAsJsonObject("y").addProperty("unit","meter");case "binding"->action.addProperty("direction","point");case "negative"->{var d=action.getAsJsonObject("distance");d.addProperty("type","chorus:constant");d.remove("name");d.addProperty("value",-1);}}
            assertThrows(RuntimeException.class,()->compile(data));
        }
    }
}
