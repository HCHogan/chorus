package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttachmentLifecycleAccountingTest {
    private static final String A="test:a", B="test:b";
    private static EntityAttachments.Policy policy(EntityAttachments.DeathPolicy death,EntityAttachments.RemovalPolicy removed) {
        return EntityAttachmentTest.policy(death,removed);
    }
    private static final EntityAnchor.Reference REF=new EntityAnchor.Reference("target","life",B);
    private static WorldPosition point(double x) { return new WorldPosition(B,x,2,3); }
    private static EntityAnchor.Observation anchor(double x,boolean alive) { return new EntityAnchor.Observation(REF,point(x),TargetQuery.Anchor.FEET,alive); }
    private static EntityObservation observation(boolean present,boolean alive,double x) {
        var positions=new HashMap<PositionQuery,Optional<WorldPosition>>();
        for(var anchor:TargetQuery.Anchor.values())positions.put(new PositionQuery("target",anchor),Optional.of(point(x)));
        return new EntityObservation(0,Map.of("target",present?Optional.of(new EntityQuery.View(alive,false,alive?10:0,10,0,Set.of(),Set.of())):Optional.empty()),positions);
    }
    private static RuleEngine.Signal died(double x) { return new EntityLifecycle.Died(REF,"damage/death",observation(true,false,x)).signal(); }
    private static RuleEngine.Signal removed(double x,EntityLifecycle.Kind kind) { return new EntityLifecycle.Removed(REF,kind,"test:remove",true,observation(false,false,x)).signal(); }
    private static EntityAttachments.Observed observed(EntityAttachments.Query query,double x,boolean alive) {
        return new EntityAttachments.Observed(query,new EntityAnchor.Result(query.expected().handle().query(),EntityAnchor.Outcome.AVAILABLE,Optional.of(anchor(x,alive))));
    }
    private static EffectState state(long now) { return EffectState.empty().withBuffs(new BuffStore(now,1,Map.of())); }
    private static EntityAttachments.Handle handle(String id,String issuer,long now,EntityAttachments.Policy policy) {
        return new EntityAttachments.Handle(id,now,now+1_000_000,anchor(1,true),point(2),policy).issuedBy(issuer);
    }
    private static ResourceAuthority.Worlds worlds(EntityAttachments.Policy policy) {
        var a=EntityAttachments.begin(state(90_000),handle("same",A,90_000,policy)).state();
        var b=EntityAttachments.begin(state(7),handle("same",B,7,policy)).state();
        return new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(A,a,B,b),Map.of(A,TimelineIdentity.fresh(),B,TimelineIdentity.fresh()));
    }
    private static ResourceAuthority.Worlds replace(ResourceAuthority.Worlds worlds,String domain,EffectState value) {
        var domains=new TreeMap<>(worlds.domains());domains.put(domain,value);
        return new ResourceAuthority.Worlds(worlds.directory(),domains,worlds.timelines());
    }
    @Test void differentIssuerClocksKeepTheOriginalPhysicalObservationAndEachOffset() {
        var before=worlds(policy(EntityAttachments.DeathPolicy.FIX,EntityAttachments.RemovalPolicy.WAIT));
        var fact=died(20);var admission=AttachmentLifecycleAccounting.capture(before,B,fact);
        var applied=AttachmentLifecycleAccounting.settle(before,admission);
        for(var domain:List.of(A,B)) {
            assertEquals(before.domain(domain).buffs().timeMicros(),applied.worlds().domain(domain).buffs().timeMicros());
            assertEquals(EntityAttachments.Phase.FIXED,applied.worlds().domain(domain).attachments().get("same").phase());
            assertEquals(point(21),((RuleEngine.Local<EffectState>)EntityAttachments.resolveIssued(
                    applied.worlds().domain(domain),domain,before.domain(domain).attachments().get("same").handle())).result() instanceof EntityAttachments.Resolution r?r.position().orElseThrow():null);
            AttachmentLifecycleAccounting.validateLedger(domain,before.domain(domain),applied.worlds().domain(domain),List.of(applied.settled()));
        }
        assertEquals(0,EntityAttachments.time(fact));assertSame(fact,admission.fact());
        var facts=AttachmentLifecycleAccounting.facts(admission,ParticipantPlacement.Directory.EMPTY);
        assertEquals(Set.of(A,B),facts.keySet());assertSame(fact,facts.get(A).getFirst());assertEquals(1,facts.get(B).size());
    }
    @Test void earlierQueuedRetirementCannotTouchAnAttachmentCreatedAfterAdmission() {
        var before=worlds(policy(EntityAttachments.DeathPolicy.END,EntityAttachments.RemovalPolicy.END));
        var admitted=AttachmentLifecycleAccounting.capture(before,B,removed(4,EntityLifecycle.Kind.REMOVED));
        var old=before.domain(A).attachments().get("same").handle();
        var updated=EntityAttachments.close(before.domain(A),old).state();
        updated=EntityAttachments.begin(updated,handle("same",A,90_000,policy(EntityAttachments.DeathPolicy.FIX,EntityAttachments.RemovalPolicy.FIX))).state();
        updated=EntityAttachments.begin(updated,handle("later",A,90_000,old.policy())).state();
        var result=AttachmentLifecycleAccounting.settle(replace(before,A,updated),admitted);
        assertSame(updated,result.worlds().domain(A));assertTrue(result.worlds().domain(B).attachments().isEmpty());
    }
    @Test void replacementLifeWrongIssuerAndReplacedTimelineCannotAcquireEligibility() {
        var before=worlds(policy(EntityAttachments.DeathPolicy.END,EntityAttachments.RemovalPolicy.END));
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.capture(before,A,died(3)));
        var different=new EntityLifecycle.Died(new EntityAnchor.Reference("target","replacement",B),"new-death",observation(true,false,4)).signal();
        assertTrue(AttachmentLifecycleAccounting.capture(before,B,different).eligible().isEmpty());
        var entry=before.domain(B).attachments().get("same");var h=entry.handle();
        var wrongHandle=new EntityAttachments.Handle(h.id(),h.startedAt(),h.expiresAt(),h.initial(),h.contact(),h.policy(),Optional.of(A));
        var wrong=new EntityAttachments.Entry(wrongHandle,entry.phase(),entry.last(),entry.revision(),entry.reason());
        var foreign=replace(before,B,before.domain(B).withAttachments(Map.of("same",wrong)));
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.capture(foreign,B,died(3)));
        var admitted=AttachmentLifecycleAccounting.capture(before,B,died(3));
        var timelines=new TreeMap<>(before.timelines());timelines.put(A,TimelineIdentity.fresh());
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.settle(new ResourceAuthority.Worlds(before.directory(),before.domains(),timelines),admitted));
    }
    @Test void exactEvidenceRejectsInventedDeletionRevisionAndDuplicateSettlement() {
        var before=worlds(policy(EntityAttachments.DeathPolicy.FOLLOW,EntityAttachments.RemovalPolicy.WAIT));
        var applied=AttachmentLifecycleAccounting.settle(before,AttachmentLifecycleAccounting.capture(before,B,died(3)));
        var after=applied.worlds().domain(A);var evidence=List.of(applied.settled());
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.validateLedger(A,before.domain(A),after,List.of()));
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.validateLedger(A,before.domain(A),after,List.of(applied.settled(),applied.settled())));
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.validateLedger(A,before.domain(A),after.withAttachments(Map.of()),evidence));
        var entry=after.attachments().get("same");var forged=new EntityAttachments.Entry(entry.handle(),entry.phase(),entry.last(),entry.revision()+1,entry.reason());
        assertThrows(IllegalArgumentException.class,()->AttachmentLifecycleAccounting.validateLedger(A,before.domain(A),after.withAttachments(Map.of("same",forged)),evidence));
    }
    @Test void admittedInstanceAllowsNewQueryRevisionAndOrderedDeathThenRemoval() {
        var before=worlds(policy(EntityAttachments.DeathPolicy.FOLLOW,EntityAttachments.RemovalPolicy.WAIT));
        var death=AttachmentLifecycleAccounting.capture(before,B,died(5));
        var removal=AttachmentLifecycleAccounting.capture(before,B,removed(8,EntityLifecycle.Kind.UNLOADED));
        var handle=before.domain(A).attachments().get("same").handle();
        var query=(EntityAttachments.Query)((RuleEngine.Await<EffectState>)EntityAttachments.resolveIssued(before.domain(A),A,handle)).command();
        var queried=EntityAttachments.resolvedIssued(before.domain(A),A,query,observed(query,4,true)).state();
        var died=AttachmentLifecycleAccounting.settle(replace(before,A,queried),death);
        var removed=AttachmentLifecycleAccounting.settle(died.worlds(),removal);
        assertEquals(3,removed.worlds().domain(A).attachments().get("same").revision());
        assertEquals(2,removed.worlds().domain(B).attachments().get("same").revision());
        assertEquals(EntityAttachments.Phase.WAITING,removed.worlds().domain(A).attachments().get("same").phase());
        AttachmentLifecycleAccounting.validateLedger(A,queried,removed.worlds().domain(A),List.of(died.settled(),removed.settled()));
    }
    @Test void qualifiedLegacyQueryChecksTheWholeRevisionAndKeepsCanonicalResults() {
        var old=new EntityAttachments.Handle("old",0,1_000_000,anchor(1,true),point(2),policy(EntityAttachments.DeathPolicy.FOLLOW,EntityAttachments.RemovalPolicy.WAIT));
        var state=EntityAttachments.begin(EffectState.empty(),old).state();
        var query=(EntityAttachments.Query)((RuleEngine.Await<EffectState>)EntityAttachments.resolveIssued(state,A,old.issuedBy(A))).command();
        assertEquals(Optional.of(A),query.expected().handle().issuer());assertEquals(Optional.empty(),state.attachments().get("old").handle().issuer());
        var result=EntityAttachments.resolvedIssued(state,A,query,observed(query,4,true));
        assertEquals(Optional.of(A),result.state().attachments().get("old").handle().issuer());
        assertEquals(1,result.state().attachments().get("old").revision());
        var stale=EntityAttachments.resolvedIssued(result.state(),A,query,observed(query,8,true));
        assertSame(result.state(),stale.state());
        assertThrows(IllegalArgumentException.class,()->EntityAttachments.resolvedIssued(state,B,query,observed(query,4,true)));
    }

    private static final class Fixture {
        final Map<String,EffectSession> sessions=new TreeMap<>();final ResourceSessionAuthority host;
        Fixture(ResourceAuthority.Worlds worlds) {
            var program=EffectTestSupport.compile(JsonParser.parseString("{\"version\":\"attachment-lifecycle\",\"bundles\":[]}").getAsJsonObject()).withResourceRouting();
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();
            worlds.domains().forEach((domain,state)->{
                var session=new EffectSession(program,EffectCheckpointTest.clock(),1,state,_->{throw new AssertionError("Publishing lifecycle ran a rule");});
                sessions.put(domain,session);bindings.put(domain,new ResourceSessionAuthority.Binding(program,session));
            });
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        List<RuleEngine.Signal> facts(String domain){return sessions.get(domain).state().engine().facts().stream().map(RuleEngine.Event::signal).toList();}
    }
    @Test void queuedTokensPublishJointlyOnceAndPreventCheckpointUntilSettled() {
        var f=new Fixture(worlds(policy(EntityAttachments.DeathPolicy.END,EntityAttachments.RemovalPolicy.END)));var fact=died(3);
        var token=f.host.captureLifecycle(B,fact);assertThrows(IllegalStateException.class,f.host::checkpoint);
        assertEquals(1,f.state(A).attachments().size());assertTrue(f.facts(A).isEmpty());
        f.host.publishLifecycle(B,List.of(token));
        assertTrue(f.state(A).attachments().isEmpty());assertTrue(f.state(B).attachments().isEmpty());
        assertEquals(List.of(fact),f.facts(A));assertEquals(List.of(fact),f.facts(B));
        assertThrows(IllegalArgumentException.class,()->f.host.publishLifecycle(B,List.of(token)));
        assertFalse(f.host.drain(20).pending());assertDoesNotThrow(f.host::checkpoint);
    }
    @Test void activeTokenSettlementIsIdempotentAndRetainsConfirmedWritesAfterViolation() {
        var f=new Fixture(worlds(policy(EntityAttachments.DeathPolicy.FIX,EntityAttachments.RemovalPolicy.WAIT)));
        var ticket=f.host.beginCombat(B,Optional.empty());var token=f.host.captureCombatLifecycle(ticket,ticket.worlds(),B,died(5));
        var applied=f.host.settleCombatLifecycle(ticket,token,ticket.worlds());
        var duplicate=f.host.settleCombatLifecycle(ticket,token,applied.worlds());assertSame(applied.worlds(),duplicate.worlds());assertTrue(duplicate.facts().isEmpty());
        assertThrows(IllegalArgumentException.class,()->f.host.commitCombat(ticket,applied.worlds(),Optional.empty(),Map.of()));
        ticket.violated(new IllegalStateException("unknown after native retirement"));
        assertThrows(IllegalStateException.class,()->f.host.commitCombat(ticket,applied.worlds(),Optional.empty(),applied.facts()));
        f.host.retainCombat(ticket,applied.worlds(),applied.facts());
        assertEquals(EntityAttachments.Phase.FIXED,f.state(A).attachments().get("same").phase());assertEquals(1,f.facts(A).size());
        assertEquals(1,ticket.lifecycleEvidence().size());
    }
}
