package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceAuthorityTest {
    static final String A="test:world_a", B="test:world_b", C="test:world_c", HOLDER="player";
    static final ResourceState.Key ENERGY=new ResourceState.Key(HOLDER,"test:energy");
    static CompiledEffects program(){return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,JsonParser.parseString("""
        {"version":"authority-test","resources":[
          {"id":"test:energy","capacity":2,"initial":2,"base_rate":0.25,"resizable":true},
          {"id":"test:melee","capacity":1,"initial":1},
          {"id":"test:progress","capacity":1,"initial":0,"base_rate":0.5},
          {"id":"test:uses","capacity":3,"initial":3}
        ]}
        """)).getOrThrow();}
    static EffectState empty(long time){return EffectState.empty().withBuffs(new BuffStore(time,1,Map.of()));}
    static EffectState account(EffectState state,String holder,String resource,double value,double capacity){return state.withResource(new ResourceState(new ResourceState.Key(holder,"test:"+resource),value,capacity,state.buffs().timeMicros()));}
    static EffectState initial(long time){var state=account(empty(time),HOLDER,"energy",0.4,3);state=account(state,HOLDER,"melee",0,1);state=account(state,HOLDER,"progress",0.4,1);return account(state,HOLDER,"uses",1,3);}
    static ResourceAuthority.Worlds worlds(EffectState a,EffectState b){return new ResourceAuthority.Worlds(ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,HOLDER,A,program(),a),Map.of(A,a,B,b));}
    static ResourceAuthority.Transfer plan(ResourceAuthority.Worlds worlds,String destination){var owner=worlds.directory().owner(HOLDER);return ResourceAuthority.transfer(worlds.directory(),owner,worlds.domain(owner.lease().domain()),destination,program(),worlds.domain(destination));}
    static ResourceAuthority.Worlds move(ResourceAuthority.Worlds worlds,String destination){return ResourceAuthority.commit(worlds,plan(worlds,destination),program());}
    static EffectState advance(EffectState state,long time){var session=new EffectSession(program(),EffectCheckpointTest.clock(),1,state,_->{throw new AssertionError("unexpected world action");});session.observe(time,List.of());return session.state().engine().domain();}

    @Test void allPoolsMoveTogetherWithTheirCapacityAndFractionalBalanceWithoutInitialization(){
        var a=account(initial(1_000_000),"other","energy",0.9,2).withMode(EffectState.Mode.PVP);var b=account(empty(50_000),"neighbor","energy",1.3,2);
        var before=worlds(a,b);var plan=plan(before,B);assertEquals(4,ResourceProjection.accounts(before.domain(A),HOLDER).size());
        var moved=ResourceAuthority.commit(before,plan,program());assertTrue(ResourceProjection.accounts(moved.domain(A),HOLDER).isEmpty());
        var owner=moved.directory().owner(HOLDER);assertEquals(B,owner.lease().domain());assertEquals(50_000,owner.timeMicros());assertEquals(0.4,owner.account("test:energy").value());assertEquals(3,owner.account("test:energy").capacity());
        assertEquals(0,owner.account("test:melee").value());assertEquals(0.4,owner.account("test:progress").value());assertEquals(1,owner.account("test:uses").value());
        assertEquals(a.resources().get(new ResourceState.Key("other","test:energy")),moved.domain(A).resources().get(new ResourceState.Key("other","test:energy")));
        assertEquals(b.resources().get(new ResourceState.Key("neighbor","test:energy")),moved.domain(B).resources().get(new ResourceState.Key("neighbor","test:energy")));assertEquals(EffectState.Mode.PVP,moved.domain(A).mode());
        assertEquals(a,before.domain(A));assertEquals(b,before.domain(B));
    }
    @Test void onlyDestinationRecoversFromItsRebasedClockAndOldOwnerCannotPublish(){
        var before=worlds(initial(5_000_000),empty(50_000));var old=before.directory().owner(HOLDER);var moved=move(before,B);
        var source=advance(moved.domain(A),6_000_000);var target=advance(moved.domain(B),1_050_000);assertTrue(ResourceProjection.accounts(source,HOLDER).isEmpty());
        assertEquals(0.65,target.resources().get(ENERGY).value());assertEquals(0.9,target.resources().get(new ResourceState.Key(HOLDER,"test:progress")).value());
        var directory=ResourceAuthority.publish(moved.directory(),moved.directory().owner(HOLDER),target);assertEquals(0.65,directory.owner(ENERGY).account(ENERGY.resource()).value());
        assertThrows(IllegalStateException.class,()->ResourceAuthority.publish(directory,old,before.domain(A)));assertThrows(IllegalStateException.class,()->ResourceAuthority.publish(directory,moved.directory().owner(HOLDER),moved.domain(B)));
    }
    @Test void roundTripsChangeAuthorityEvenWhenBalancesAndWorldClockAreIdentical(){
        var original=worlds(initial(0),empty(0));var plan=plan(original,B);var b=ResourceAuthority.commit(original,plan,program());var a=move(b,A);
        assertEquals(original.domain(A).resources(),a.domain(A).resources());assertNotEquals(original.directory().owner(HOLDER).lease(),a.directory().owner(HOLDER).lease());
        assertThrows(IllegalStateException.class,()->ResourceAuthority.commit(a,plan,program()));assertThrows(IllegalStateException.class,()->ResourceAuthority.claim(a.directory(),HOLDER,A,program(),a.domain(A)));
        assertEquals(A,a.directory().owner(ENERGY).lease().domain());assertTrue(ResourceProjection.accounts(a.domain(B),HOLDER).isEmpty());
    }
    @Test void publishingADomainClockUpdatesEveryOwnedHolderAndRejectsStaleDomainWrites(){
        var state=account(initial(0),"friend","energy",0.1,2);var directory=ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,HOLDER,A,program(),state);directory=ResourceAuthority.claim(directory,"friend",A,program(),state);
        var worlds=new ResourceAuthority.Worlds(directory,Map.of(A,state,B,empty(0)));var advanced=advance(state,100_000);var published=ResourceAuthority.publish(worlds,A,state,advanced);
        assertEquals(100_000,published.directory().owner(HOLDER).timeMicros());assertEquals(100_000,published.directory().owner("friend").timeMicros());assertEquals(0.125,published.directory().owner("friend").account("test:energy").value());
        var moved=move(published,B);var next=advance(moved.domain(A),200_000);var settled=ResourceAuthority.publish(moved,A,moved.domain(A),next);
        assertEquals(0.15,settled.directory().owner("friend").account("test:energy").value());assertEquals(0,settled.directory().owner(HOLDER).timeMicros());assertEquals(0.425,settled.directory().owner(HOLDER).account("test:energy").value());
        assertThrows(IllegalStateException.class,()->ResourceAuthority.publish(settled,A,state,advanced));assertThrows(IllegalArgumentException.class,()->ResourceAuthority.publish(settled,A,next,moved.domain(A)));
    }
    @Test void staleSourcesAndDestinationBalancesCannotBeMergedOrSilentlyOverwritten(){
        var before=worlds(initial(0),empty(0));var owner=before.directory().owner(HOLDER);
        var spent=before.domain(A).withResource(Resources.spend(owner.account(ENERGY.resource()),"payment",0.1).after());
        assertThrows(IllegalStateException.class,()->ResourceAuthority.transfer(before.directory(),owner,spent,B,program(),before.domain(B)));
        assertThrows(IllegalStateException.class,()->ResourceAuthority.transfer(before.directory(),owner,before.domain(A),B,program(),initial(0)));
        assertThrows(IllegalStateException.class,()->new ResourceAuthority.Worlds(before.directory(),Map.of(A,before.domain(A),B,initial(0))));
        var planned=plan(before,B);var changedTarget=new ResourceAuthority.Worlds(before.directory(),Map.of(A,before.domain(A),B,empty(1)));
        assertThrows(IllegalStateException.class,()->ResourceAuthority.commit(changedTarget,planned,program()));assertEquals(0.4,before.domain(A).resources().get(ENERGY).value());
    }
    @Test void catalogueIdentityClockAndResourceShapesAreCheckedBeforePlanning(){
        var before=worlds(initial(0),empty(0));var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,program().program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate",2);var incompatible=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow();
        assertThrows(IllegalArgumentException.class,()->ResourceAuthority.transfer(before.directory(),before.directory().owner(HOLDER),before.domain(A),B,incompatible,before.domain(B)));
        assertThrows(IllegalArgumentException.class,()->new ResourceAuthority.Partition(HOLDER,new ResourceAuthority.Lease(A,1),0,1,program().program(),ResourceProjection.accounts(initial(0),HOLDER)));
        assertThrows(IllegalArgumentException.class,()->ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,HOLDER,A,program(),account(empty(0),HOLDER,"missing",1,1)));
        assertThrows(IllegalArgumentException.class,()->ResourceAuthority.publish(before.directory(),before.directory().owner(HOLDER),empty(0)));
        var later=worlds(initial(1),empty(0));assertThrows(IllegalArgumentException.class,()->ResourceAuthority.publish(later.directory(),later.directory().owner(HOLDER),initial(0)));
    }
    @Test void emptyEnrollmentSurvivesTransferAndLaterInitializationOnlyHasOneOwner(){
        var before=worlds(empty(0),empty(20));var moved=move(before,B);assertTrue(moved.directory().owner(HOLDER).accounts().isEmpty());
        var initialized=account(moved.domain(B),HOLDER,"energy",2,2);var directory=ResourceAuthority.publish(moved.directory(),moved.directory().owner(HOLDER),initialized);
        assertEquals(B,directory.owner(ENERGY).lease().domain());assertEquals(2,directory.owner(ENERGY).account(ENERGY.resource()).value());
        assertThrows(IllegalStateException.class,()->ResourceAuthority.publish(directory,before.directory().owner(HOLDER),account(before.domain(A),HOLDER,"energy",2,2)));
    }
    @Test void stableAddressesRouteLaterCreditsToCurrentOwnerAndPreserveOverflow(){
        var original=worlds(initial(0),empty(0));var moved=move(original,B);var credited=ResourceAuthority.grant(moved,ENERGY,4,4);
        assertEquals(B,credited.recipient().domain());assertEquals(3,credited.result().after().value());assertEquals(2.6,credited.result().credited());assertEquals(1.4,credited.result().overflow());
        assertTrue(ResourceProjection.accounts(credited.worlds().domain(A),HOLDER).isEmpty());var returned=move(credited.worlds(),A);var full=ResourceAuthority.grant(returned,ENERGY,1,1);assertEquals(0,full.result().credited());assertEquals(A,full.recipient().domain());
    }
    static record Paid(ResourceAuthority.Worlds worlds,RetainedCosts.Handle handle) {}
    static Paid paid(){
        var source=account(empty(0),HOLDER,"energy",2,2);var cost=Resources.spend(source.resources().get(ENERGY),"cast/original",1);source=source.withResource(cost.after());
        var retained=RetainedCosts.retain(source,"handle/original",cost.receipt(),2_000_000);return new Paid(worlds(retained.state(),empty(50_000_000)),(RetainedCosts.Handle)retained.result());
    }
    @Test void oldIssuerRefundsCurrentOwnerOnceAcrossMultipleMovesAndKeepsOriginalPayment(){
        var paid=paid();var moved=move(paid.worlds(),B);var first=ResourceAuthority.refund(moved,A,paid.handle(),0.6);assertEquals(1.6,first.result().orElseThrow().grant().after().value());assertEquals(B,first.recipient().orElseThrow().domain());
        assertEquals(50_000_000,first.result().orElseThrow().grant().after().timeMicros(),"destination clock must not prematurely expire the issuer's claim");
        assertEquals("cast/original",first.result().orElseThrow().receipt().operation());assertTrue(ResourceProjection.accounts(first.worlds().domain(A),HOLDER).isEmpty());
        var returned=move(first.worlds(),A);var last=ResourceAuthority.refund(returned,A,paid.handle(),0.6);assertEquals(0.4,last.result().orElseThrow().grant().credited());assertEquals(2,last.worlds().domain(A).resources().get(ENERGY).value());
        var replay=ResourceAuthority.refund(last.worlds(),A,paid.handle(),1);assertEquals(0,replay.result().orElseThrow().grant().credited());assertEquals(1,replay.result().orElseThrow().receipt().refundClaimed());assertEquals(A,replay.recipient().orElseThrow().domain());
    }
    @Test void expiredOrClosedIssuerClaimsCannotCreditADestinationAndOverflowStillConsumesAllowance(){
        var paid=paid();var moved=move(paid.worlds(),B);var full=ResourceAuthority.grant(moved,ENERGY,1,1).worlds();var refunded=ResourceAuthority.refund(full,A,paid.handle(),1);
        assertEquals(0,refunded.result().orElseThrow().grant().credited());assertEquals(1,refunded.result().orElseThrow().grant().overflow());assertEquals(1,refunded.result().orElseThrow().receipt().refundClaimed());
        var closed=RetainedCosts.close(moved.domain(A),paid.handle()).state();var worlds=new ResourceAuthority.Worlds(moved.directory(),Map.of(A,closed,B,moved.domain(B)));assertTrue(ResourceAuthority.refund(worlds,A,paid.handle(),1).result().isEmpty());
        var expired=advance(moved.domain(A),2_000_000);worlds=new ResourceAuthority.Worlds(moved.directory(),Map.of(A,expired,B,moved.domain(B)));assertTrue(ResourceAuthority.refund(worlds,A,paid.handle(),1).result().isEmpty());
        var conflict=new RetainedCosts.Handle(paid.handle().id(),new Resources.CostReceipt("another",ENERGY,1,1),0,2_000_000);assertThrows(IllegalArgumentException.class,()->ResourceAuthority.refund(moved,A,conflict,1));
    }
    @Test void projectionsAreCheckpointableAndSessionsDoNotInventResourceEvents(){
        var worlds=worlds(initial(0),empty(50_000));var plan=plan(worlds,B);var encoded=EffectCheckpointCodec.DATA.encode(plan.arrival());assertEquals(plan.arrival(),EffectCheckpointCodec.DATA.decode(encoded,ResourceProjection.Change.class));
        var source=new EffectSession(program(),EffectCheckpointTest.clock(),1,worlds.domain(A),_-> {throw new AssertionError();});var target=new EffectSession(program(),EffectCheckpointTest.clock(),1,worlds.domain(B),_-> {throw new AssertionError();});
        var control=new EffectSession(program(),EffectCheckpointTest.clock(),1,worlds.domain(B),_-> {throw new AssertionError();});control.observe(50_000,List.of());source.observe(0,List.of(),plan.departure());target.observe(50_000,List.of(),plan.arrival());assertEquals(control.checkpoint().timeline().nextEvent(),target.checkpoint().timeline().nextEvent(),"projection added gameplay facts beyond the normal observation boundary");
        var restored=EffectSession.restore(program(),EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(target.checkpoint())),_-> {throw new AssertionError();});assertEquals(0.4,restored.state().engine().domain().resources().get(ENERGY).value());assertTrue(ResourceProjection.accounts(source.state().engine().domain(),HOLDER).isEmpty());
    }
    @Test void supervisorEncodingKeepsExactLongsAndRejectsDuplicateOwnersAndMalformedPartitions(){
        long time=9_007_199_254_740_993L;var before=worlds(initial(time),empty(0));var original=before.directory().owner(HOLDER);var partition=new ResourceAuthority.Partition(HOLDER,new ResourceAuthority.Lease(A,time),time,time,original.program(),original.accounts());
        var directory=new ResourceAuthority.Directory(time+1,Map.of(HOLDER,partition));var encoded=ResourceAuthorityCodec.encode(directory);assertEquals(directory,ResourceAuthorityCodec.decode(JsonParser.parseString(encoded.toString())));
        var duplicate=encoded.deepCopy();duplicate.getAsJsonArray("holders").add(duplicate.getAsJsonArray("holders").get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->ResourceAuthorityCodec.decode(duplicate));
        var number=encoded.deepCopy();number.addProperty("next_generation",time+1);assertThrows(IllegalArgumentException.class,()->ResourceAuthorityCodec.decode(number));
        var stale=encoded.deepCopy();stale.addProperty("next_generation",Long.toString(time));assertThrows(IllegalArgumentException.class,()->ResourceAuthorityCodec.decode(stale));
        var unknown=encoded.deepCopy();unknown.addProperty("balances",true);assertThrows(IllegalArgumentException.class,()->ResourceAuthorityCodec.decode(unknown));
        var duplicateAccount=encoded.deepCopy();var accounts=duplicateAccount.getAsJsonArray("holders").get(0).getAsJsonObject().getAsJsonArray("accounts");accounts.add(accounts.get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->ResourceAuthorityCodec.decode(duplicateAccount));
        var badClock=encoded.deepCopy();badClock.getAsJsonArray("holders").get(0).getAsJsonObject().addProperty("time_micros","0");assertThrows(IllegalArgumentException.class,()->ResourceAuthorityCodec.decode(badClock));
    }
    @Test void generationOrRevisionExhaustionCannotPartiallyMoveOrChangeTheDirectory(){
        var state=initial(0);var exhausted=new ResourceAuthority.Directory(Long.MAX_VALUE,Map.of());assertThrows(ArithmeticException.class,()->ResourceAuthority.claim(exhausted,HOLDER,A,program(),state));assertTrue(exhausted.holders().isEmpty());
        var before=worlds(state,empty(0));var owner=before.directory().owner(HOLDER);var limit=new ResourceAuthority.Partition(HOLDER,owner.lease(),Long.MAX_VALUE,0,owner.program(),owner.accounts());var directory=new ResourceAuthority.Directory(2,Map.of(HOLDER,limit));
        assertThrows(ArithmeticException.class,()->ResourceAuthority.publish(directory,limit,state.withResource(Resources.spend(state.resources().get(ENERGY),"payment",0.1).after())));assertEquals(0.4,directory.owner(ENERGY).account(ENERGY.resource()).value());
    }
}
