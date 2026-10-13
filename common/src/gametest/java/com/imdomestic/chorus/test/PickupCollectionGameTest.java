package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.PickupGameTest.id;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;

public class PickupCollectionGameTest {
    static void edit(JsonObject data) {
        PickupGameTest.spec(data).remove("attraction");
        for(var b:data.getAsJsonArray("bundles")) if(b.getAsJsonObject().get("id").getAsString().equals("test:collector"))
            b.getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
                {"id":"remote","on":"test:collect","if":{"type":"chorus:target_is","left":"self","right":"event_actor"},"do":[
                  {"type":"chorus:collect_pickups","collector":"self","radius":{"type":"chorus:constant","value":5,"unit":"meter"},"kinds":["test:pickup"],"line_of_sight":false}
                ]}
                """));
    }
    static PickupGameTest.Harness open(GameTestHelper h, String fixture) throws Exception { return new PickupGameTest.Harness(h,fixture,PickupCollectionGameTest::edit); }
    static void collect(PickupGameTest.Harness t) { t.runtime.start(new RuleEngine.Signal("test:collect",new EffectEvent(id(t.collector),id(t.collector),t.source.origin(),Set.of(),Map.of()))); }
    static PickupCollection.Query query(PickupGameTest.Harness t, double radius, boolean los) { return new PickupCollection.Query(id(t.collector),radius,Set.of("test:pickup"),los); }
    static PickupCollection.Result select(PickupGameTest.Harness t, PickupCollection.Query q) { return (PickupCollection.Result)t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(0,0,0),q)); }
    static PickupCollection.Claimed claim(PickupGameTest.Harness t, PickupCollection.Query q, PickupCollection.Entry e) { return (PickupCollection.Claimed)t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(0,0,0),new PickupCollection.Claim(q,e))); }
    static void ready(PickupGameTest.Harness t) {
        if(t.runtime.failure().isPresent() || !t.runtime.state().idle())throw new AssertionError("Unsettled collection: "+t.runtime.failure());
    }
    @GameCase public void remoteClaimsKeepPrivateOwnershipCapturedRewardsAndOrdinaryPickupFacts(GameTestHelper h) throws Exception {
        try(var t=open(h,"pickup")) {
            t.spawn();t.spawn();
            t.runtime.start(new RuleEngine.Signal("test:spawn",new EffectEvent(id(t.owner),id(t.outsider),t.source.origin(),Set.of(),Map.of())));
            h.assertTrue(select(t,new PickupCollection.Query(id(t.collector),5,Set.of("test:other"),false)).entries().isEmpty(),"kind filter ignored");
            t.runtime.unbind("producer");collect(t);ready(t);
            near(h,t.collector.getHealth(),24,"two remote rewards");near(h,t.owner.getHealth(),10,"producer not collector");near(h,t.outsider.getHealth(),10,"private recipient not stolen");
            h.assertTrue(t.objects.get(0).isRemoved() && t.objects.get(1).isRemoved() && !t.objects.get(2).isRemoved(),"wrong private claim");
            h.assertValueEqual(t.collectedStates.size(),2,"ordinary collector facts missing");
            h.assertTrue(t.heals.stream().allMatch(heal->heal.source().equals(t.source.origin())),"producer source replaced");
            collect(t);t.objects.getFirst().tick();h.assertValueEqual(t.heals.size(),2,"remote/contact replay");
        }h.succeed();
    }
    @GameCase public void firstRewardFillsTheGapBeforeTheSecondPhysicalCandidateChecksEligibility(GameTestHelper h) throws Exception {
        try(var t=open(h,"conditional_pickup")) {
            ConditionalPickupGameTest.equip(t,t.collector,new Loadout(Map.of("test:primary",ConditionalPickupGameTest.gear("a","finite")),Optional.empty()));
            ConditionalPickupGameTest.refill(t,t.collector,"a");t.spawn();t.spawn();collect(t);ready(t);
            h.assertValueEqual(t.objects.stream().filter(o->o.isRemoved()).count(),1L,"two bricks spent one reserve gap");
            h.assertValueEqual(ConditionalPickupGameTest.reserves(ConditionalPickupGameTest.state(t),"a"),10,"first reward not committed");
            h.assertValueEqual(t.collectedStates.size(),1,"rejected candidate emitted pickup fact");
            collect(t);h.assertValueEqual(t.heals.size(),1,"full reserves consumed remaining brick");
        }h.succeed();
    }
    @GameCase public void everyClaimRechecksRangeVisibilityPresenceAndTheExactObject(GameTestHelper h) throws Exception {
        try(var t=open(h,"pickup")) {
            t.spawn();var q=query(t,3,false);var e=select(t,q).entries().getFirst();
            t.objects.getFirst().setPos(t.collector.position().add(3.001,0,0));
            h.assertValueEqual(claim(t,q,e).outcome(),PickupCollection.ClaimOutcome.OUT_OF_RANGE,"stale range accepted");
            t.objects.getFirst().setPos(t.owner.position());
            var wall=BlockPos.containing(t.owner.position().add(1.5,0,0));t.blocks.put(wall,h.getLevel().getBlockState(wall));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
            h.assertTrue(select(t,query(t,3,true)).entries().isEmpty(),"visibility filter ignored");
            h.assertValueEqual(claim(t,query(t,3,true),e).outcome(),PickupCollection.ClaimOutcome.OCCLUDED,"claim forgot visibility");
            t.collector.setHealth(0);h.assertValueEqual(claim(t,q,e).outcome(),PickupCollection.ClaimOutcome.MISSING_COLLECTOR,"dead collector accepted");
            h.assertTrue(!t.objects.getFirst().isRemoved() && t.heals.isEmpty(),"rejection consumed pickup");
        }h.succeed();
    }
    @GameCase public void duplicateCandidatesAcrossRequestsCannotConsumeOnePhysicalUnitTwice(GameTestHelper h) throws Exception {
        try(var t=open(h,"pickup")) {
            t.spawn();var q=query(t,5,false);var first=select(t,q);var batch=new PickupCollection.Batch(q,first.entries());
            t.runtime.start(new RuleEngine.Signal(PickupCollection.EVENT,batch));
            t.runtime.start(new RuleEngine.Signal(PickupCollection.EVENT,batch));ready(t);
            h.assertValueEqual(t.heals.size(),1,"same sampled object rewarded twice");
            h.assertValueEqual(t.collectedStates.size(),1,"duplicate pickup facts");
            h.assertValueEqual(claim(t,q,first.entries().getFirst()).outcome(),PickupCollection.ClaimOutcome.UNAVAILABLE,"removed candidate available");
        }h.succeed();
    }
    @GameCase public void unknownRewardConsumesOnlyTheClaimedUnitAndStopsTheRemainingBatch(GameTestHelper h) throws Exception {
        try(var t=open(h,"conditional_pickup")) {
            ConditionalPickupGameTest.equip(t,t.collector,new Loadout(Map.of("test:primary",ConditionalPickupGameTest.gear("a","finite")),Optional.empty()));
            ConditionalPickupGameTest.refill(t,t.collector,"a");t.spawn();t.spawn();t.failAfterHealing=true;
            boolean failed=false;try{collect(t);}catch(IllegalStateException expected){failed=true;}
            h.assertTrue(failed && t.runtime.failure().isPresent(),"unknown reward reported success");
            h.assertValueEqual(t.objects.stream().filter(o->o.isRemoved()).count(),1L,"unconfirmed remaining claim consumed");
            h.assertValueEqual(t.heals.size(),1,"reward replayed or batch continued");
            h.assertValueEqual(ConditionalPickupGameTest.reserves(ConditionalPickupGameTest.state(t),"a"),10,"committed reserves rolled back");
            h.assertTrue(t.collectedStates.isEmpty(),"failed reward dispatched pickup reactions");
        }h.succeed();
    }
}
