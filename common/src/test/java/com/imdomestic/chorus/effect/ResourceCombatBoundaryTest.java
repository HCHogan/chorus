package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.combat.DamageGroups;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.ParticipantPlacement;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.effect.resource.ResourceSessionAuthority;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.EntityAttachments;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ResourceCombatBoundaryTest {
    private static final String A="test:a", B="test:b", PLAYER="player";
    private static final Signal CALL=new Signal("test:call",Empty.INSTANCE);
    private static final Signal OLDER=new Signal("test:older",Empty.INSTANCE);
    private static final Signal CONFIRMED=new Signal("test:confirmed_damage",Empty.INSTANCE);
    private static final DamageGroups.Handle GROUP=new DamageGroups.Handle("attack",PLAYER,0,1_000_000).issuedBy(A);

    private static CompiledEffects program(){
        return EffectTestSupport.compile(JsonParser.parseString("""
            {"version":"resource-combat-boundary",
             "buffs":[{"definition":{"id":"test:charge","version":"resource-combat-boundary","duration":1},
               "consume_on_damage":{"when":"hit","sharing":"group"}}],
             "bundles":[{"id":"test:caller","rules":[{"id":"call","on":"test:call",
               "do":[{"type":"chorus:play_cue","cue":"test:world"}]}]}]}
            """).getAsJsonObject());
    }
    private static final class Fixture {
        final CompiledEffects program=program();
        final EffectSession a,b;
        final ResourceSessionAuthority host;
        int worldCalls;
        Function<WorldRequest,ActionResult> world=_->{throw new AssertionError("Unexpected world callback");};
        Fixture(){this(EffectCheckpointTest.clock(),UnaryOperator.identity());}
        Fixture(EffectClock remoteClock,UnaryOperator<EffectState> local){
            this(remoteClock,local,UnaryOperator.identity());
        }
        Fixture(EffectClock remoteClock,UnaryOperator<EffectState> local,UnaryOperator<EffectState> remote){
            var source=EffectTestSupport.source("test:caller");
            var left=local.apply(DamageGroups.begin(EffectState.empty().withSource(source),GROUP).state());
            var right=EffectState.empty();
            right=right.withBuffs(Buffs.grant(right.buffs(),program.buff("test:charge"),PLAYER,PLAYER,source.origin(),1,1,1_000_000).store());
            right=remote.apply(right);
            a=new EffectSession(program,EffectCheckpointTest.clock(),1,left,request->{worldCalls++;return world.apply(request);});
            b=new EffectSession(program,remoteClock,1,right,_->{throw new AssertionError("Publication ran remote world work");});
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(
                    A,new ResourceSessionAuthority.Binding(program,a),B,new ResourceSessionAuthority.Binding(program,b)));
            host.enrollParticipants(Map.of(PLAYER,A),Map.of(PLAYER,B));
        }
        void queueOlder(){
            var before=b.coordinationState();
            EffectSession.commitDomains(List.of(b.prepareInboxDomain(before,state(b),List.of(OLDER))));
        }
    }
    private record Observed(ResourceAuthority.Worlds worlds,Map<String,List<Signal>> facts) {}
    private static EffectState state(EffectSession session){return session.state().engine().domain();}
    private static List<Signal> facts(EffectSession session){return session.state().engine().facts().stream().map(Event::signal).toList();}
    private static ResourceAuthority.Worlds replace(ResourceAuthority.Worlds before,String domain,EffectState state){
        var domains=new TreeMap<>(before.domains());domains.put(domain,state);
        return new ResourceAuthority.Worlds(before.directory(),domains,before.timelines());
    }
    /** A confirmed hit spends the Buff in B and retains its eligibility in the issuer's ledger in A. */
    private static Observed observed(ResourceSessionAuthority.CombatBoundary ticket){
        var before=ticket.worlds();var buff=before.domain(B).buffs().instances().values().iterator().next();
        var spent=Buffs.consume(before.domain(B).buffs(),buff.key(),1);
        var ledger=before.domain(A).withDamageGroup(DamageGroups.require(before.domain(A),GROUP).retain(buff));
        var next=replace(replace(before,A,ledger),B,before.domain(B).withBuffs(spent.store()));
        return new Observed(next,Map.of(A,List.of(CONFIRMED),B,spent.signals()));
    }
    private static void assertObserved(Fixture f){
        assertTrue(state(f.b).buffs().instances().isEmpty());
        assertEquals(1,DamageGroups.require(state(f.a),GROUP).grants().size());
    }

    @Test void unmanagedCombatPublishesBothDomainsAndPreservesTheOlderRecipientInbox(){
        var f=new Fixture();f.queueOlder();var beforeA=f.a.state();var beforeB=f.b.state();
        var ticket=f.host.beginCombat(A,Optional.empty());var observed=observed(ticket);
        assertFalse(ticket.managed());assertEquals(A,ticket.caller());
        assertEquals(Map.of(A,f.program.program(),B,f.program.program()),ticket.programs());
        assertEquals(A,ticket.placement().require(ParticipantPlacement.Facet.CHARACTER,PLAYER,A).domain());
        var view=ticket.view(ticket.worlds(),A);
        assertSame(state(f.a),view.characterState(PLAYER));assertSame(state(f.b),view.buffState(PLAYER));
        assertSame(beforeA,f.a.state());assertSame(beforeB,f.b.state());
        f.host.commitCombat(ticket,observed.worlds(),Optional.empty(),observed.facts());
        assertObserved(f);assertEquals(List.of(CONFIRMED),facts(f.a));
        var expected=new ArrayList<>(List.of(OLDER));expected.addAll(observed.facts().get(B));
        assertEquals(expected,facts(f.b));assertEquals(beforeB.engine().facts().getFirst(),f.b.state().engine().facts().getFirst());
        assertEquals(beforeA.engine().nextFrame(),f.a.state().engine().nextFrame());
        assertEquals(beforeB.engine().nextFrame(),f.b.state().engine().nextFrame());assertEquals(0,f.worldCalls);
        assertThrows(IllegalStateException.class,()->f.host.validateCombat(ticket));
        assertThrows(IllegalStateException.class,f.host::checkpoint);
        f.a.drain();f.b.drain();assertDoesNotThrow(f.host::checkpoint);assertEquals(0,f.worldCalls);
        assertEquals(ticket.placement(),f.host.participants());
    }

    @Test void managedCombatCommitsExactlyOneActualResultBeforeTheInterpreterResumes(){
        var f=new Fixture();f.queueOlder();var captured=new AtomicReference<ResourceSessionAuthority.CombatBoundary>();
        f.world=request->{
            var operation=f.a.operation(request);var before=f.a.state();var remote=f.b.state();
            var ticket=f.host.beginCombat(A,Optional.of(request));captured.set(ticket);var observed=observed(ticket);
            assertTrue(ticket.managed());
            assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,observed.worlds(),Optional.empty(),observed.facts()));
            assertSame(before,f.a.state());assertSame(remote,f.b.state());assertTrue(operation.committed().isEmpty());
            f.host.commitCombat(ticket,observed.worlds(),Optional.of(Empty.INSTANCE),observed.facts());
            assertObserved(f);assertEquals(Optional.of(Empty.INSTANCE),operation.committed());
            assertEquals(before.engine().pending(),f.a.state().engine().pending());
            assertEquals(before.engine().frames(),f.a.state().engine().frames());
            assertEquals(before.engine().receipts(),f.a.state().engine().receipts());return Empty.INSTANCE;
        };
        f.a.start(0,CALL);assertEquals(1,f.worldCalls);assertTrue(f.a.state().idle());
        assertEquals(1,f.a.state().engine().receipts().size());assertTrue(f.b.inboxPending());
        assertThrows(IllegalStateException.class,()->f.host.validateCombat(captured.get()));
        f.b.drain();assertDoesNotThrow(f.host::checkpoint);assertObserved(f);assertEquals(1,f.worldCalls);
    }

    @Test void unknownManagedOutcomeRetainsObservedWritesAndFactsDespiteAStickyViolationWithoutAReceiptOrReplay(){
        var f=new Fixture();f.queueOlder();var token=new AtomicReference<EffectSession.WorldOperation>();
        var unknown=new IllegalStateException("physical outcome unknown after a confirmed child hit");
        var expectedFacts=new AtomicReference<List<Signal>>();
        f.world=request->{
            var operation=f.a.operation(request);token.set(operation);var before=f.a.state();var remote=f.b.state();
            var ticket=f.host.beginCombat(A,Optional.of(request));var observed=observed(ticket);
            assertThrows(IllegalStateException.class,f.host::snapshot);
            assertThrows(IllegalStateException.class,()->f.host.validateCombat(ticket));
            assertThrows(IllegalStateException.class,()->f.host.commitCombat(ticket,observed.worlds(),Optional.of(Empty.INSTANCE),observed.facts()));
            assertSame(before,f.a.state());assertSame(remote,f.b.state());
            f.host.retainCombat(ticket,observed.worlds(),observed.facts());
            var expected=new ArrayList<>(List.of(OLDER));expected.addAll(observed.facts().get(B));expectedFacts.set(expected);
            assertTrue(operation.committed().isEmpty());assertObserved(f);throw unknown;
        };
        assertSame(unknown,assertThrows(IllegalStateException.class,()->f.a.start(0,CALL)));
        assertObserved(f);assertTrue(token.get().committed().isEmpty());
        assertEquals(Optional.of(token.get().request().id()),f.a.state().engine().pending());
        assertTrue(f.a.state().engine().receipts().isEmpty());assertEquals(List.of(CONFIRMED),facts(f.a));
        assertEquals(expectedFacts.get(),facts(f.b));assertThrows(IllegalStateException.class,f.host::checkpoint);
        assertThrows(IllegalStateException.class,()->f.a.start(0,CALL));assertThrows(IllegalStateException.class,f.a::drain);
        f.b.drain();assertDoesNotThrow(f.b::checkpoint);assertEquals(1,f.worldCalls);
    }

    @Test void staleRecipientTimelineRejectsTheWholeBatchEvenWhenItsDomainValueDidNotChange(){
        var f=new Fixture();var ticket=f.host.beginCombat(A,Optional.empty());var observed=observed(ticket);
        var beforeA=f.a.state();var beforeB=f.b.coordinationState();
        EffectSession.commitDomains(List.of(f.b.prepareInboxDomain(beforeB,state(f.b),List.of(OLDER))));
        var changed=f.b.state();assertSame(beforeB.engine().domain(),changed.engine().domain());
        assertThrows(IllegalStateException.class,()->f.host.validateCombat(ticket));
        assertThrows(IllegalStateException.class,()->f.host.commitCombat(ticket,observed.worlds(),Optional.empty(),observed.facts()));
        assertThrows(IllegalStateException.class,()->f.host.retainCombat(ticket,observed.worlds(),observed.facts()));
        assertSame(beforeA,f.a.state());assertSame(changed,f.b.state());
        assertTrue(DamageGroups.require(state(f.a),GROUP).grants().isEmpty());assertEquals(1,state(f.b).buffs().instances().size());
        f.host.abandonCombat(ticket);f.b.drain();assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void foreignAndReleasedTicketsCannotPublishOrReleaseAnActiveCapture(){
        var f=new Fixture();var other=new Fixture();var ticket=f.host.beginCombat(A,Optional.empty());
        var foreign=other.host.beginCombat(A,Optional.empty());
        assertThrows(IllegalStateException.class,()->f.host.validateCombat(foreign));
        assertThrows(IllegalStateException.class,()->f.host.commitCombat(foreign,foreign.worlds(),Optional.empty(),Map.of()));
        assertThrows(IllegalStateException.class,()->f.host.abandonCombat(foreign));
        assertDoesNotThrow(()->f.host.validateCombat(ticket));f.host.abandonCombat(ticket);
        var next=f.host.beginCombat(A,Optional.empty());
        assertThrows(IllegalStateException.class,()->f.host.commitCombat(ticket,ticket.worlds(),Optional.empty(),Map.of()));
        assertThrows(IllegalStateException.class,()->f.host.abandonCombat(ticket));
        assertDoesNotThrow(()->f.host.validateCombat(next));f.host.abandonCombat(next);other.host.abandonCombat(foreign);
        assertDoesNotThrow(f.host::checkpoint);assertDoesNotThrow(other.host::checkpoint);
    }

    @Test void combatCannotChangeResourcesTimeConfigurationTimersOrGroupIdentity(){
        List<UnaryOperator<EffectState>> forbidden=List.of(
                state->state.withMode(EffectState.Mode.PVP),
                state->state.withResource(new ResourceState(new ResourceState.Key("unmanaged","test:energy"),1,2,0)),
                state->state.withBuffs(new BuffStore(1,state.buffs().nextGeneration(),state.buffs().instances())),
                state->state.withoutSource("perk"),
                state->state.schedule(new EffectState.Timer("unrelated",50,0,1,CONFIRMED,Optional.empty())),
                state->DamageGroups.close(state,GROUP).state(),
                state->DamageGroups.begin(state,new DamageGroups.Handle("extra",PLAYER,0,1_000_000).issuedBy(A)).state(),
                state->state.withDamageGroup(new DamageGroups.Group(new DamageGroups.Handle(GROUP.id(),PLAYER,0,2_000_000).issuedBy(A),Map.of())));
        for(var change:forbidden){
            var f=new Fixture();var ticket=f.host.beginCombat(A,Optional.empty());var beforeA=f.a.state();var beforeB=f.b.state();
            var observed=observed(ticket);var invalid=replace(observed.worlds(),A,change.apply(observed.worlds().domain(A)));
            assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,invalid,Optional.empty(),observed.facts()));
            assertThrows(IllegalArgumentException.class,()->f.host.retainCombat(ticket,invalid,observed.facts()));
            assertSame(beforeA,f.a.state());assertSame(beforeB,f.b.state());
            f.host.abandonCombat(ticket);assertDoesNotThrow(f.host::checkpoint);
        }
    }

    @Test void publicationRequiresCapturedDirectoryMembershipAndCorrectResultArity(){
        var f=new Fixture();var ticket=f.host.beginCombat(A,Optional.empty());var beforeA=f.a.state();var beforeB=f.b.state();
        var cloned=new ResourceAuthority.Worlds(new ResourceAuthority.Directory(ticket.worlds().directory().nextGeneration(),
                ticket.worlds().directory().holders()),ticket.worlds().domains(),ticket.worlds().timelines());
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,cloned,Optional.empty(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->ticket.view(cloned,A));
        var missing=new ResourceAuthority.Worlds(ticket.worlds().directory(),Map.of(A,ticket.worlds().domain(A)),Map.of(A,ticket.worlds().timelines().get(A)));
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,missing,Optional.empty(),Map.of()));
        var changedIdentities=new TreeMap<>(ticket.worlds().timelines());changedIdentities.put(A,TimelineIdentity.fresh());
        for(var identities:List.of(Map.<String,TimelineIdentity>of(),changedIdentities)){
            var changed=new ResourceAuthority.Worlds(ticket.worlds().directory(),ticket.worlds().domains(),identities);
            assertThrows(IllegalArgumentException.class,()->ticket.view(changed,A));
            assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,changed,Optional.empty(),Map.of()));
            assertThrows(IllegalArgumentException.class,()->f.host.retainCombat(ticket,changed,Map.of()));
        }
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,ticket.worlds(),Optional.of(Empty.INSTANCE),Map.of()));
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,ticket.worlds(),Optional.empty(),Map.of("test:unknown",List.of(CONFIRMED))));
        assertSame(beforeA,f.a.state());assertSame(beforeB,f.b.state());
        f.host.commitCombat(ticket,ticket.worlds(),Optional.empty(),Map.of());assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void attachmentWritesNeedMatchingLifecycleFactsAndCommitTogetherWithRemoteConsumption(){
        var policy=EntityAttachmentTest.policy(EntityAttachments.DeathPolicy.END,EntityAttachments.RemovalPolicy.END);
        var reference=new com.imdomestic.chorus.effect.target.EntityAnchor.Reference("target","life",A);
        var point=new com.imdomestic.chorus.effect.target.WorldPosition(A,2,2,3);
        var anchor=new com.imdomestic.chorus.effect.target.EntityAnchor.Observation(reference,point,
                com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET,true);
        var attachment=new EntityAttachments.Handle("contact",0,1_000_000,anchor,point,policy);
        var f=new Fixture(EffectCheckpointTest.clock(),state->EntityAttachments.begin(state,attachment).state());
        var ticket=f.host.beginCombat(A,Optional.empty());var observed=observed(ticket);
        var oldObservation=EntityAttachmentTest.observation(true,false,10);
        var positions=new HashMap<com.imdomestic.chorus.effect.target.PositionQuery,Optional<com.imdomestic.chorus.effect.target.WorldPosition>>();
        oldObservation.positions().keySet().forEach(query->positions.put(query,Optional.of(point)));
        var death=new com.imdomestic.chorus.effect.target.EntityLifecycle.Died(reference,"damage/death",
                new com.imdomestic.chorus.effect.target.EntityObservation(0,oldObservation.entities(),positions)).signal();
        var shadow=replace(observed.worlds(),A,EntityAttachmentTest.observe(observed.worlds().domain(A),death));
        var beforeA=f.a.state();var beforeB=f.b.state();
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,shadow,Optional.empty(),observed.facts()));
        assertSame(beforeA,f.a.state());assertSame(beforeB,f.b.state());
        var proven=new TreeMap<>(observed.facts());proven.put(A,List.of(CONFIRMED,death));
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,shadow,Optional.empty(),proven));
        var token=f.host.captureCombatLifecycle(ticket,observed.worlds(),A,death);
        var settled=f.host.settleCombatLifecycle(ticket,token,observed.worlds());assertEquals(shadow,settled.worlds());
        f.host.commitCombat(ticket,shadow,Optional.empty(),proven);assertObserved(f);
        assertTrue(state(f.a).attachments().isEmpty());assertEquals(List.of(CONFIRMED,death),facts(f.a));
        f.a.drain();f.b.drain();assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void aCaughtAuthorityCallFromPublicationClockPolicyStillPoisonsSuccessfulCommit(){
        var host=new AtomicReference<ResourceSessionAuthority>();var armed=new AtomicBoolean();var calls=new AtomicInteger();
        var clock=EffectCheckpointTest.clock().withRates((_,account)->{
            assertEquals("test:external_rate",account.key().resource());
            if(armed.get()){
                calls.incrementAndGet();assertThrows(IllegalStateException.class,()->host.get().snapshot());
            }
            return new EffectClock.Rate(0,List.of());
        });
        var f=new Fixture(clock,UnaryOperator.identity(),ResourceCombatBoundaryTest::withPublicationClockProbe);host.set(f.host);
        var ticket=f.host.beginCombat(A,Optional.empty());var observed=observed(ticket);var beforeA=f.a.state();var beforeB=f.b.state();
        armed.set(true);
        assertThrows(IllegalStateException.class,()->f.host.commitCombat(ticket,observed.worlds(),Optional.empty(),observed.facts()));
        armed.set(false);assertTrue(calls.get()>0);assertSame(beforeA,f.a.state());assertSame(beforeB,f.b.state());
        assertThrows(IllegalStateException.class,()->f.host.validateCombat(ticket));
        f.host.retainCombat(ticket,observed.worlds(),observed.facts());assertObserved(f);
        f.a.drain();f.b.drain();assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void aViolationReportedDuringPreparationIsCheckedAgainBeforeAnyDomainPublishes(){
        var ticketRef=new AtomicReference<ResourceSessionAuthority.CombatBoundary>();var armed=new AtomicBoolean();var calls=new AtomicInteger();
        var failure=new IllegalStateException("physical participant changed during final preparation");
        var clock=EffectCheckpointTest.clock().withRates((_,account)->{
            assertEquals("test:external_rate",account.key().resource());
            if(armed.get()){calls.incrementAndGet();ticketRef.get().violated(failure);}
            return new EffectClock.Rate(0,List.of());
        });
        var f=new Fixture(clock,UnaryOperator.identity(),ResourceCombatBoundaryTest::withPublicationClockProbe);
        var ticket=f.host.beginCombat(A,Optional.empty());ticketRef.set(ticket);
        var observed=observed(ticket);var beforeA=f.a.state();var beforeB=f.b.state();armed.set(true);
        var rejected=assertThrows(IllegalStateException.class,()->f.host.commitCombat(ticket,observed.worlds(),Optional.empty(),observed.facts()));
        armed.set(false);assertTrue(calls.get()>0);assertSame(failure,rejected.getCause());assertSame(beforeA,f.a.state());assertSame(beforeB,f.b.state());
        f.host.retainCombat(ticket,observed.worlds(),observed.facts());assertObserved(f);
    }

    /** Compiled recovery replaces the supplied policy; an undeclared resource retains its host rate callback. */
    private static EffectState withPublicationClockProbe(EffectState state){
        return state.withResource(new ResourceState(new ResourceState.Key("clock-probe","test:external_rate"),1,2,0));
    }
}
