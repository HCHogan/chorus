package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.HealthPayment;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealthPaymentTest {
    static HealthPayment.Command command(double amount,double floor,HealthPayment.Mode mode){return new HealthPayment.Command("player",source("test:health_payment").origin(),amount,floor,mode,Set.of("test:cost"));}
    static EffectEvent input(double amount){var s=source("test:health_payment");return new EffectEvent("player","player",s.origin(),Set.of(),Map.of("amount",new Measure(amount,Unit.DAMAGE),"minimum",new Measure(1,Unit.DAMAGE)));}
    @Test void exactAffordabilityAndCappedPaymentRespectTheFloorWithoutHealing(){
        var exact=command(5,1,HealthPayment.Mode.EXACT);var paid=HealthPayment.plan("a",exact,6);assertTrue(paid.paid());assertEquals(5,paid.effective());assertEquals(1,paid.balance().orElseThrow().after());
        var denied=HealthPayment.plan("b",exact,5);assertEquals(HealthPayment.Outcome.INSUFFICIENT,denied.outcome());assertEquals(0,denied.effective());assertEquals(5,denied.balance().orElseThrow().after());
        var capped=command(5,1,HealthPayment.Mode.UP_TO);assertEquals(3,HealthPayment.plan("c",capped,4).effective());assertTrue(HealthPayment.plan("d",capped,.5).paid());assertEquals(.5,HealthPayment.plan("d",capped,.5).balance().orElseThrow().after());assertEquals(HealthPayment.Outcome.DEAD,HealthPayment.plan("e",capped,0).outcome());
    }
    @Test void fractionalAndTinyCostsNeverOverchargeThroughDoubleSubtraction(){
        for(double hp:List.of(.1,1.0,20.0,1000.0,1e15))for(double amount:List.of(0.0,1e-20,.1,.3,5.0)){
            var r=HealthPayment.plan("x",command(amount,.01,HealthPayment.Mode.UP_TO),hp);assertTrue(r.effective()<=amount);assertTrue(r.balance().orElseThrow().after()>=Math.min(hp,.01));
        }
    }
    @Test void invalidCostsAndContradictoryWorldBalancesAreRejected(){
        for(double amount:List.of(-1.0,Double.NaN,Double.POSITIVE_INFINITY))assertThrows(IllegalArgumentException.class,()->command(amount,1,HealthPayment.Mode.EXACT));
        for(double floor:List.of(0.0,-1.0,Double.NaN))assertThrows(IllegalArgumentException.class,()->command(1,floor,HealthPayment.Mode.EXACT));
        var c=command(5,1,HealthPayment.Mode.EXACT);
        for(var b:List.of(new HealthPayment.Balance(5,1),new HealthPayment.Balance(10,0),new HealthPayment.Balance(10,4)))assertThrows(IllegalArgumentException.class,()->new HealthPayment.Receipt("bad",c,HealthPayment.Outcome.PAID,Optional.of(b)));
        assertThrows(IllegalArgumentException.class,()->HealthPayment.Receipt.unavailable("bad",c,HealthPayment.Outcome.PAID));
        assertThrows(IllegalArgumentException.class,()->new HealthPayment.Receipt("bad",c,HealthPayment.Outcome.INSUFFICIENT,Optional.of(new HealthPayment.Balance(10,10))));
    }
    @Test void OnlyActualPositiveExpenditureProducesItsOwnFactAndKeepsSourceAndIdentity(){
        var receipt=HealthPayment.plan("payment-1",command(5,1,HealthPayment.Mode.UP_TO),3);var facts=HealthPayment.facts(receipt);assertEquals(List.of("chorus:health_spent"),facts.stream().map(RuleEngine.Signal::type).toList());var event=(EffectEvent)facts.getFirst().payload();assertEquals(receipt.command().source(),event.source());assertEquals("payment-1",event.references().get("payment_id"));assertEquals(2,event.numbers().get("effective").value());assertEquals(Set.of("test:cost"),event.tags());
        assertTrue(HealthPayment.facts(HealthPayment.plan("zero",command(5,1,HealthPayment.Mode.UP_TO),1)).isEmpty());assertTrue(HealthPayment.facts(HealthPayment.plan("no",command(5,1,HealthPayment.Mode.EXACT),1)).isEmpty());
    }
    @Test void missingAndDeadObservationsCannotBeMistakenForZeroHealth(){
        for(var o:List.of(HealthPayment.Outcome.MISSING,HealthPayment.Outcome.DEAD)){var r=HealthPayment.Receipt.unavailable("x",command(5,1,HealthPayment.Mode.EXACT),o);assertFalse(ResultShape.HEALTH_PAYMENT.flag("observed",r));assertFalse(ResultShape.HEALTH_PAYMENT.flag("paid",r));assertThrows(NoSuchElementException.class,()->ResultShape.HEALTH_PAYMENT.read("health_before",r));}
    }
    @Test void jsonBranchesAfterConfirmedPaymentAndDuplicateCompletionDoesNotRepeatFacts()throws Exception{
        var p=load("health_payment");var e=engine(p);var waiting=send(e,e.initial(EffectState.empty().withSource(source("test:health_payment"))),0,"test:up_to",input(5));var request=waiting.actions().getFirst();var result=HealthPayment.plan("once",(HealthPayment.Command)request.command(),3);var done=complete(e,waiting,result);
        assertEquals(2,buff(done.state(),"test:payment_meter","player").components().numbers().get("effective"));assertEquals(1,buff(done.state(),"test:paid","player").count());assertEquals(done.state(),e.transition(done.state(),new RuleEngine.Completed(request.id(),result)).state());
        var encoded=EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow();assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,encoded).getOrThrow().program());
    }
    @Test void insufficientAndMissingPaymentsCannotRunThePaidBranch()throws Exception{
        for(boolean missing:List.of(false,true)){var p=load("health_payment");var e=engine(p);var waiting=send(e,e.initial(EffectState.empty().withSource(source("test:health_payment"))),0,"test:exact",input(5));var c=(HealthPayment.Command)waiting.actions().getFirst().command();var r=missing?HealthPayment.Receipt.unavailable("x",c,HealthPayment.Outcome.MISSING):HealthPayment.plan("x",c,3);assertTrue(complete(e,waiting,r).state().engine().domain().buffs().instances().isEmpty());}
    }
    @Test void commandMismatchAndInvalidJsonCannotInventAValidPayment()throws Exception{
        var p=load("health_payment");var e=engine(p);var waiting=send(e,e.initial(EffectState.empty().withSource(source("test:health_payment"))),0,"test:exact",input(5));var wrong=HealthPayment.plan("wrong",command(1,1,HealthPayment.Mode.EXACT),10);var done=e.transition(waiting.state(),new RuleEngine.Completed(waiting.actions().getFirst().id(),wrong));while(done.needsPump())done=e.transition(done.state(),RuleEngine.Pump.INSTANCE);assertTrue(done.state().engine().failure().isPresent());assertTrue(done.state().engine().domain().buffs().instances().isEmpty());
        var data=json("health_payment");var a=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");a.remove("mode");assertThrows(IllegalStateException.class,()->compile(data));
    }
}
