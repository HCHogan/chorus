package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProximityWardTest {
    static EffectSource ward(String id,String actor){return new EffectSource(id,"chorus_d2:proximity_ward",actor,new BuffInstance.Origin(actor,id,"",""),Set.of(),Map.of("receipt_seconds",new Measure(60,Unit.SECOND)));}
    static class Harness extends ArmorFinisherTest.Harness {
        Harness()throws Exception{super("proximity_ward");equip(ward("ward","player"));}
        RuleEngine.Signal start(){return new RuleEngine.Signal("chorus:finisher_started",plan.event());}
        RuleEngine.Signal cancel(){return new RuleEngine.Signal("chorus:finisher_cancelled",plan.event());}
        boolean protectedNow(){return buff("proximity_ward").isPresent();}
        double capacity(){return buff("proximity_ward").orElseThrow().components().numbers().get("capacity");}
    }
    @Test void acceptedStartGrantsTwoHundredUntilTheExactPlanDeadlineWithoutChargeCost()throws Exception{
        var h=new Harness();assertFalse(h.protectedNow());h.begin();assertEquals(200,h.capacity());assertEquals(h.plan.dueAt(),h.buff("proximity_ward").orElseThrow().deadline());assertEquals(0,h.charges());h.until(299_999);assertTrue(h.protectedNow());h.until(300_000);assertFalse(h.protectedNow());assertEquals(0,h.health.get("enemy"));
    }
    @Test void duplicateCopiesAndReplayedStartsCannotReplaceOrRefillTheAcceptedShield()throws Exception{
        var h=new Harness();h.equip(ward("copy","player"));h.begin();var original=h.buff("proximity_ward").orElseThrow();h.until(100_000);h.replay(List.of(h.start()));assertEquals(original,h.buff("proximity_ward").orElseThrow());h.restore();h.replay(List.of(h.start()));assertEquals(original,h.buff("proximity_ward").orElseThrow());
    }
    @Test void lateFirstDeliveryUsesObservedRemainingDurationNotTheOldFullDuration()throws Exception{
        var h=new Harness();h.remove("ward");h.begin();h.until(200_000);h.equip(ward("late","player"));assertFalse(h.protectedNow(),"equipping alone does not synthesize a start");h.replay(List.of(h.start()));assertEquals(300_000,h.buff("proximity_ward").orElseThrow().deadline());assertEquals(100_000,h.buff("proximity_ward").orElseThrow().originalDurationMicros());h.until(300_000);assertFalse(h.protectedNow());
    }
    @Test void staleStartOutsideItsExecutionCannotCreateOrReplaceProtectionEvenAfterReceiptExpiry()throws Exception{
        var h=new Harness();h.complete();var old=h.start();h.until(61_000_000);h.replay(List.of(old));assertFalse(h.protectedNow());h.begin();var active=h.buff("proximity_ward").orElseThrow();h.replay(List.of(old));assertEquals(active,h.buff("proximity_ward").orElseThrow());
    }
    @Test void earlyCancellationRemovesOnlyItsOwnShieldAndLateOldCancellationCannotRemoveANewOne()throws Exception{
        var h=new Harness();h.begin();var old=h.cancel();h.input("cancel");assertFalse(h.protectedNow());h.begin();var next=h.buff("proximity_ward").orElseThrow();h.replay(List.of(old));assertEquals(next,h.buff("proximity_ward").orElseThrow());h.decision=Finisher.Decision.RANGE;h.until(50_000);assertFalse(h.protectedNow());
    }
    @Test void acceptedShieldCleanupOutlivesModRemovalAndRebindingDoesNotRegenerateIt()throws Exception{
        var h=new Harness();h.begin();var first=h.buff("proximity_ward").orElseThrow();h.remove("ward");assertEquals(first,h.buff("proximity_ward").orElseThrow());h.equip(ward("ward","player"));h.replay(List.of(h.start()));assertEquals(first,h.buff("proximity_ward").orElseThrow());h.remove("ward");h.input("cancel");assertFalse(h.protectedNow());
    }
    @Test void deniedStartVictimSideAndOrdinaryKillCannotInventAnActiveWard()throws Exception{
        var h=new Harness();h.equip(ward("victim_ward","enemy"));h.decision=Finisher.Decision.HEALTH;h.input("begin");assertFalse(h.protectedNow());h.decision=Finisher.Decision.ALLOWED;h.begin();assertTrue(h.protectedNow());assertTrue(h.buff("proximity_ward","enemy").isEmpty());h.input("cancel");h.kill();assertFalse(h.protectedNow());
    }
    @Test void deathAndCheckpointedCancellationClearProtectionWithoutGrantingAnotherShield()throws Exception{
        var h=new Harness();h.begin();h.restore();h.input("cancel");assertFalse(h.protectedNow());var dead=new Harness();dead.begin();dead.damage("giver","player",2000);assertFalse(dead.protectedNow());
    }
}
