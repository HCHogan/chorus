package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceSessionAuthorityTest.session;
import static com.imdomestic.chorus.effect.ResourceSessionAuthorityTest.state;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonPrimitive;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ResourceEnlistmentTest {
    static ResourceSessionAuthority.Binding binding(CompiledEffects program,EffectSession session) {
        return new ResourceSessionAuthority.Binding(program,session);
    }
    record Fixture(CompiledEffects program,EffectSession a,ResourceSessionAuthority host) {}
    static Fixture fixture() {
        var program=program().withResourceRouting();var a=session(program,initial(0));
        var host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(A,binding(program,a)));host.claim(HOLDER,A);
        return new Fixture(program,a,host);
    }
    @Test void emptyAuthorityAcceptsLateMixedDomainsWithoutAdvancingOrExecutingAnything() {
        var local=program();var routed=local.withResourceRouting();var a=session(local,initial(5_000_000));var b=session(routed,empty(50_000));
        var host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of());var beforeA=a.checkpoint();var beforeB=b.checkpoint();
        var additions=new HashMap<>(Map.of(A,binding(local,a),B,binding(routed,b)));host.enlist(additions);additions.clear();
        assertEquals(beforeA,a.checkpoint());assertEquals(beforeB,b.checkpoint());assertEquals(Set.of(A,B),host.checkpoint().domains().keySet());assertTrue(host.snapshot().directory().holders().isEmpty());
        host.claim(HOLDER,A);host.commit(host.prepareTransfer(HOLDER,B));b.observe(1_050_000,List.of());
        assertEquals(.65,state(b).resources().get(ENERGY).value(),1e-12);assertEquals(5_000_000,state(a).buffs().timeMicros());
        assertFalse(host.checkpoint().domains().get(A).resourceRouting());assertTrue(host.checkpoint().domains().get(B).resourceRouting());
    }
    @Test void existingAndNewClockBindingsObserveOneAuthorityAcrossHandoffs() {
        var f=fixture();f.a.observe(1_000_000,List.of());var before=f.a.checkpoint();var b=session(f.program,empty(50_000_000));
        var old=f.host.readView(A);f.host.enlist(Map.of(B,binding(f.program,b)));assertEquals(before,f.a.checkpoint());
        f.host.commit(f.host.prepareTransfer(HOLDER,B));f.a.observe(2_000_000,List.of());b.observe(51_000_000,List.of());
        assertTrue(state(f.a).resources().isEmpty());assertEquals(.9,state(b).resources().get(ENERGY).value(),1e-12);
        assertEquals(.65,old.account(ENERGY).value(),1e-12);assertEquals(A,old.owner(HOLDER).domain());
        assertEquals(B,f.host.readView(A).owner(HOLDER).domain());assertEquals(.9,f.host.readView(A).account(ENERGY).value(),1e-12);
        assertThrows(IllegalStateException.class,f.a::requireClockUnbound);assertThrows(IllegalStateException.class,b::requireClockUnbound);
        f.host.commit(f.host.prepareTransfer(HOLDER,A));f.a.observe(3_000_000,List.of());assertEquals(1.15,state(f.a).resources().get(ENERGY).value(),1e-12);
    }
    @Test void enlistmentPublishesAlreadyCommittedAccountProgressWithoutAdvancingEitherDomain() {
        var f=fixture();var old=f.host.snapshot().directory();f.a.observe(1_000_000,List.of());var before=f.a.state();
        var b=session(f.program,empty(50_000_000));var beforeB=b.state();f.host.enlist(Map.of(B,binding(f.program,b)));
        var next=f.host.checkpoint();assertEquals(.65,next.directory().owner(ENERGY).account("test:energy").value(),1e-12);
        assertEquals(1_000_000,next.directory().owner(HOLDER).timeMicros());assertEquals(old.owner(HOLDER).revision()+1,next.directory().owner(HOLDER).revision());
        assertSame(before,f.a.state());assertSame(beforeB,b.state());assertEquals(.4,old.owner(ENERGY).account("test:energy").value());
    }
    @Test void newlyEnlistedDomainCanIssueCompiledRequestsToTheExistingRemoteOwner() {
        var d=ResourceRoutingTest.data(ResourceRoutingTest.rule("go",ResourceRoutingTest.array(ResourceRoutingTest.pool("grant_resource","requested",ResourceRoutingTest.charge(.1)))));
        var f=new ResourceRoutingTest.Fixture(d);var source=ResourceReadViewTest.source(HOLDER);
        var c=new EffectSession(f.program,EffectCheckpointTest.clock(),1,empty(0).withSource(source),request->f.host.execute(C,request));
        f.host.enlist(Map.of(C,binding(f.program,c)));c.start(0,new RuleEngine.Signal("test:go",new EffectEvent(HOLDER,HOLDER,source.origin(),Set.of(),Map.of())));
        assertTrue(state(c).resources().isEmpty());assertEquals(.5,f.energy(),1e-12);assertTrue(f.sessions.get(B).inboxPending());
        assertFalse(f.host.drain(10).pending());assertEquals(Set.of(A,B,C),f.host.checkpoint().domains().keySet());
    }
    @Test void successfulMembershipChangeInvalidatesPreparedHandoffsEvenWithAnUnchangedDirectory() {
        var f=fixture();var b=session(f.program,empty(0));f.host.enlist(Map.of(B,binding(f.program,b)));
        var handoff=f.host.prepareTransfer(HOLDER,B);var directory=f.host.snapshot().directory();var a=f.a.state();var oldB=b.state();
        f.host.enlist(Map.of());assertSame(directory,f.host.snapshot().directory());
        var c=session(f.program,empty(0));f.host.enlist(Map.of(C,binding(f.program,c)));assertSame(directory,f.host.snapshot().directory());
        assertThrows(IllegalStateException.class,()->f.host.commit(handoff));assertSame(a,f.a.state());assertSame(oldB,b.state());
        f.host.commit(f.host.prepareTransfer(HOLDER,B));assertEquals(B,f.host.snapshot().directory().owner(HOLDER).lease().domain());
    }
    @Test void rejectedBatchAndEmptyBatchLeaveAnEarlierHandoffUsableAndAllNewClocksUnbound() {
        var f=fixture();var b=session(f.program,empty(0));f.host.enlist(Map.of(B,binding(f.program,b)));var handoff=f.host.prepareTransfer(HOLDER,B);
        var c=session(f.program,empty(0));var replica=session(f.program,initial(0));
        assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(C,binding(f.program,c),"test:z",binding(f.program,replica))));
        c.requireClockUnbound();replica.requireClockUnbound();assertEquals(Set.of(A,B),f.host.checkpoint().domains().keySet());
        f.host.enlist(Map.of());f.host.commit(handoff);assertEquals(B,f.host.snapshot().directory().owner(HOLDER).lease().domain());
    }
    @Test void identifiersExistingDomainsAndSessionAliasesRejectBeforeBindingAnyNewClock() {
        var f=fixture();var b=session(f.program,empty(0));var candidate=binding(f.program,b);var before=f.host.checkpoint();
        assertThrows(IllegalArgumentException.class,()->f.host.enlist(Map.of(A,candidate)));
        assertThrows(IllegalArgumentException.class,()->f.host.enlist(Map.of("not a domain",candidate)));
        assertThrows(IllegalArgumentException.class,()->f.host.enlist(Map.of(B,binding(f.program,f.a))));
        assertThrows(IllegalArgumentException.class,()->f.host.enlist(Map.of(B,candidate,C,candidate)));
        b.requireClockUnbound();assertEquals(before,f.host.checkpoint());f.host.enlist(Map.of(B,candidate));
    }
    @Test void fullCatalogueAndCompilationModeMustMatchEachNewSession() {
        var f=fixture();var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,f.program.program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate",2);
        var other=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow().withResourceRouting();var b=session(f.program,empty(0));
        assertEquals(f.program.program().version(),other.program().version());
        assertThrows(IllegalArgumentException.class,()->f.host.enlist(Map.of(B,binding(other,b))));
        assertThrows(IllegalArgumentException.class,()->f.host.enlist(Map.of(B,binding(program(),b))));b.requireClockUnbound();
        // Different independent catalogues may coexist; an incompatible handoff still fails.
        var independent=session(other,empty(0));f.host.enlist(Map.of(B,binding(other,independent)));
        assertThrows(IllegalArgumentException.class,()->f.host.prepareTransfer(HOLDER,B));assertEquals(.4,state(f.a).resources().get(ENERGY).value());
    }
    @Test void alreadyBoundCandidateRejectsWholeBatchWithoutTakingEarlierCandidates() {
        var f=fixture();var b=session(f.program,empty(0));var c=session(f.program,empty(0));
        var other=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(C,binding(f.program,c)));var saved=f.host.checkpoint();
        assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(B,binding(f.program,b),C,binding(f.program,c))));
        assertEquals(saved,f.host.checkpoint());b.requireClockUnbound();f.host.enlist(Map.of(B,binding(f.program,b)));
        c.observe(1_000_000,List.of());assertEquals(1_000_000,other.checkpoint().domains().get(C).timeline().timeMicros());
    }
    @Test void pendingRecipientFactsPreventEnlistmentWithoutBeingDrained() {
        var d=ResourceRoutingTest.data(ResourceRoutingTest.rule("go",ResourceRoutingTest.array(ResourceRoutingTest.pool("grant_resource","requested",ResourceRoutingTest.charge(.1)))));
        var f=new ResourceRoutingTest.Fixture(d);f.start("go");var c=session(f.program,empty(0));
        var beforeA=f.sessions.get(A).state();var beforeB=f.sessions.get(B).state();assertTrue(f.sessions.get(B).inboxPending());
        assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(C,binding(f.program,c))));
        assertSame(beforeA,f.sessions.get(A).state());assertSame(beforeB,f.sessions.get(B).state());assertTrue(f.sessions.get(B).inboxPending());c.requireClockUnbound();
        assertFalse(f.host.drain(10).pending());f.host.enlist(Map.of(C,binding(f.program,c)));assertEquals(Set.of(A,B,C),f.host.checkpoint().domains().keySet());
    }
    @Test void activeWorldCallbacksAndUnknownOutcomesCannotAddDomainsOrReplayWrites() {
        var d=ResourceRoutingTest.data(ResourceRoutingTest.rule("go",ResourceRoutingTest.array(ResourceRoutingTest.action("play_cue","cue",new JsonPrimitive("test:cue")))));
        var f=new ResourceRoutingTest.Fixture(d);var c=session(f.program,empty(0));var calls=new AtomicInteger();
        f.external=_->{calls.incrementAndGet();assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(C,binding(f.program,c))));throw new IllegalStateException("Unknown actual world result");};
        assertThrows(IllegalStateException.class,()->f.start("go"));c.requireClockUnbound();
        assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(C,binding(f.program,c))));assertThrows(IllegalStateException.class,()->f.start("go"));assertEquals(1,calls.get());
    }
    @Test void ownerThreadAndEveryTimelineAreCheckedBeforePublishingTheBatch() throws Exception {
        var f=fixture();var b=session(f.program,empty(0));var error=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{f.host.enlist(Map.of(B,binding(f.program,b)));}catch(Throwable failure){error.set(failure);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,error.get());b.requireClockUnbound();
        var local=program();var armed=new AtomicBoolean();var clock=new EffectClock((_,_)->{
            if(armed.getAndSet(false))f.a.observe(0,List.of());return new EffectClock.Rate(0,List.of());
        });
        var c=new EffectSession(local,clock,1,account(empty(0),"unmanaged","custom",1,1),_->{throw new AssertionError();});
        var before=f.a.state();armed.set(true);assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(B,binding(f.program,b),C,binding(local,c))));
        assertNotSame(before,f.a.state());assertEquals(Set.of(A),f.host.checkpoint().domains().keySet());b.requireClockUnbound();
        f.host.enlist(Map.of(B,binding(f.program,b),C,binding(local,c)));assertEquals(Set.of(A,B,C),f.host.checkpoint().domains().keySet());
    }
    @Test void policyReentryAndForeignThreadSessionsCannotPartiallyBindTheBatch() throws Exception {
        var f=fixture();var b=session(f.program,empty(0));var foreign=new AtomicReference<EffectSession>();
        var thread=new Thread(()->foreign.set(session(f.program,empty(0))));thread.start();thread.join();
        assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(B,binding(f.program,b),C,binding(f.program,foreign.get()))));b.requireClockUnbound();
        var armed=new AtomicBoolean();var local=program();var clock=new EffectClock((_,_)->{
            if(armed.getAndSet(false))f.host.enlist(Map.of());return new EffectClock.Rate(0,List.of());
        });
        var c=new EffectSession(local,clock,1,account(empty(0),"unmanaged","custom",1,1),_->{throw new AssertionError();});armed.set(true);
        assertThrows(IllegalStateException.class,()->f.host.enlist(Map.of(B,binding(f.program,b),C,binding(local,c))));b.requireClockUnbound();assertEquals(Set.of(A),f.host.checkpoint().domains().keySet());
    }
    @Test void jointRestoreKeepsAddedDomainsAndCanEnlistAnotherWithoutRebindingRestoredClocks() {
        var f=fixture();var b=session(f.program,empty(50_000));f.host.enlist(Map.of(B,binding(f.program,b)));f.host.commit(f.host.prepareTransfer(HOLDER,B));
        var saved=f.host.checkpoint();var encoded=ResourceCheckpointTest.archive(saved).encode();var decoded=CheckpointArchive.decode(encoded).resourceCheckpoint().orElseThrow();
        var restored=ResourceSessionAuthority.restore(decoded,ResourceCheckpointTest.policies(decoded));assertEquals(saved,restored.authority().checkpoint());
        var c=session(f.program,empty(5_000_000));restored.authority().enlist(Map.of(C,binding(f.program,c)));restored.authority().commit(restored.authority().prepareTransfer(HOLDER,C));
        c.observe(6_000_000,List.of());restored.bindings().get(B).session().observe(1_050_000,List.of());
        assertEquals(.65,state(c).resources().get(ENERGY).value(),1e-12);assertTrue(state(restored.bindings().get(B).session()).resources().isEmpty());
        assertEquals(Set.of(A,B,C),restored.authority().checkpoint().domains().keySet());assertEquals(Set.of(A,B),restored.bindings().keySet());
        assertEquals(saved,f.host.checkpoint());
    }
}
