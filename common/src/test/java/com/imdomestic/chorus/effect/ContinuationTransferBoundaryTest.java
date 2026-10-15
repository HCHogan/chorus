package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.compile;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A transfer cannot turn a lexical reference to one world's ledger into authority over another. */
class ContinuationTransferBoundaryTest {
    static final String A="test:a",B="test:b",H="player",OTHER="other";
    static EffectSource source(String holder) {
        var id="root/"+holder;
        return new EffectSource(id,"test:input",holder,new BuffInstance.Origin(holder,id,"",""),Set.of());
    }
    static EntityAttachments.Handle attachment() {
        var point=new WorldPosition(A,1,2,3);
        var observation=new EntityAnchor.Observation(new EntityAnchor.Reference(H,"life",A),point,TargetQuery.Anchor.BODY,true);
        return new EntityAttachments.Handle("shared",0,1_000_000,observation,point,
                new EntityAttachments.Policy(EntityAttachments.DeathPolicy.FIX,EntityAttachments.RemovalPolicy.END,
                        EntityAttachments.RemovalPolicy.WAIT,EntityAttachments.RemovalPolicy.WAIT,EntityAttachments.RemovalPolicy.FIX));
    }
    static EffectContinuations.Pending pending(Map<String,RuleEngine.ActionResult> bindings) {
        var scope=source(H);
        var cause=new RuleEngine.Event(1,1,Optional.empty(),0,
                new RuleEngine.Signal("test:start",new EffectEvent(H,H,scope.origin(),Set.of(),Map.of())));
        return new EffectContinuations.Pending("pending","test:body","transfer-boundary",scope,cause,bindings,Optional.of(EffectTimers.Owner.of(scope)));
    }
    static EffectContinuations.Pending relocate(EffectContinuations.Pending pending,boolean resourceRouting) {
        return pending.relocated("moved",pending.scope(),pending.owner().orElseThrow(),50,Optional.of(A),resourceRouting);
    }

    @Test void remainingUnroutedLiveHandleKindsRejectBeforeChangingTheCapturedBindings() {
        var handles=List.<RuleEngine.ActionResult>of(new DamageGroups.Handle("shared",H,0,1_000_000),
                new ShotGroups.Handle("shared",source(H).origin(),2,0,1_000_000),attachment());
        for(var handle:handles) {
            var original=pending(Map.of("live",handle));
            var error=assertThrows(IllegalArgumentException.class,()->relocate(original,true));
            assertTrue(error.getMessage().contains("issuer routing"));
            assertTrue(error.getMessage().contains(handle.getClass().getName()));
            assertEquals(Map.of("live",handle),original.bindings());
            assertTrue(original.relocatedAt().isEmpty());
        }
    }

    @Test void legacyTalliesAcquireTheirFirstIssuerAndRequireACoordinatedDestination() {
        var handle=new DamageTallies.Handle("shared",0,1_000_000);var original=pending(Map.of("tally",handle));
        var moved=relocate(original,true);var qualified=handle.issuedBy(A);
        assertEquals(qualified,moved.bindings().get("tally"));assertEquals(Map.of("tally",handle),original.bindings());
        assertEquals(original.cause(),moved.cause());assertEquals(50,moved.causalTime());
        var again=moved.relocated("twice",moved.scope(),moved.owner().orElseThrow(),70,Optional.of(B),true);
        assertEquals(qualified,again.bindings().get("tally"));
        assertThrows(IllegalArgumentException.class,()->relocate(original,false));
        assertThrows(IllegalArgumentException.class,()->relocate(moved,false));
        assertThrows(IllegalArgumentException.class,()->original.relocated("missing",original.scope(),original.owner().orElseThrow(),50,Optional.empty(),true));
    }

    @Test void historicalSnapshotsAndLogicalBatchIdsRemainCapturableWhileRefundsKeepTheirIssuer() {
        var attached=attachment();
        var entity=new EntityAnchor.Result(attached.query(),EntityAnchor.Outcome.MISSING,Optional.empty());
        var snapshot=new EntityAttachments.Resolution(attached,EntityAttachments.Outcome.CLOSED,Optional.empty(),entity);
        var voucher=new RetainedCosts.Handle("refund",new Resources.CostReceipt("paid",new ResourceState.Key(H,"test:energy"),.3,.3),0,1_000_000);
        var original=pending(Map.of("position",snapshot,"summary",new DamageTallies.Result(Optional.of(DamageTallies.Summary.ZERO),false),
                "batch",new DamageBatch("batch",H),"refund",voucher));
        var moved=relocate(original,true);
        assertSame(snapshot,moved.bindings().get("position"));
        assertSame(original.bindings().get("summary"),moved.bindings().get("summary"));
        assertSame(original.bindings().get("batch"),moved.bindings().get("batch"));
        assertEquals(voucher.issuedBy(A),moved.bindings().get("refund"));
        assertEquals(original.cause(),moved.cause());
        assertThrows(IllegalArgumentException.class,()->relocate(original,false));
        assertEquals(voucher.issuedBy(A),relocate(moved,true).bindings().get("refund"));
    }

    static JsonObject data(boolean buff,boolean detached) {
        var data=JsonParser.parseString("""
            {"version":"transfer-boundary","resources":[{"id":"test:energy","capacity":1,"initial":1}],
             "buffs":[{"definition":{"id":"test:carry","version":"transfer-boundary","duration":5},"bundle":"test:active"}],
             "bundles":[{"id":"test:input","rules":[{"id":"start","on":"test:start","do":[{"type":"chorus:grant_buff","buff":"test:carry"}]}]},
              {"id":"test:active","scope":"buff","rules":[{"id":"gained","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[]}]}]}
            """).getAsJsonObject();
        var rules=data.getAsJsonArray("bundles").get(buff?1:0).getAsJsonObject().getAsJsonArray("rules");
        var body=rules.get(0).getAsJsonObject().getAsJsonArray("do");
        body.add(JsonParser.parseString("""
            {"action":{"type":"chorus:begin_damage_tally","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"tally"}
            """));
        var after=JsonParser.parseString("""
            {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"do":[
              {"action":{"type":"chorus:close_damage_tally","tally":"tally"},"as":"summary"},
              {"type":"chorus:play_cue","cue":"test:closed"}]}
            """).getAsJsonObject();
        if(detached)after.addProperty("lifetime","detached");
        body.add(after);return data;
    }
    static final class Fixture {
        final CompiledEffects program;final Map<String,EffectSession> sessions=new TreeMap<>();final ResourceSessionAuthority host;
        final List<String> cues=new ArrayList<>();
        Fixture(boolean buff,boolean detached) {
            program=compile(data(buff,detached)).withResourceRouting();
            for(var domain:List.of(A,B)) {
                var holder=domain.equals(A)?H:OTHER;
                var initial=EffectState.empty().withSource(source(holder)).withResource(new ResourceState(new ResourceState.Key(holder,"test:energy"),domain.equals(A)?.8:.2,1,0));
                sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,initial,r->execute(domain,r)));
            }
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(A,new ResourceSessionAuthority.Binding(program,sessions.get(A)),B,new ResourceSessionAuthority.Binding(program,sessions.get(B))));
            host.claim(H,A);host.claim(OTHER,B);
            for(var domain:List.of(A,B)) {
                var holder=domain.equals(A)?H:OTHER;var scope=source(holder);
                sessions.get(domain).start(0,new RuleEngine.Signal("test:start",new EffectEvent(holder,holder,scope.origin(),Set.of(),Map.of())));
                var handle=handle(domain);
                var receipt=new DamageReceipt("record",DamageReceipt.Outcome.APPLIED,0,0,domain.equals(A)?3:9,Optional.empty(),false);
                var recorded=DamageTallies.record(state(domain),handle,receipt).state();
                EffectSession.commitDomains(List.of(sessions.get(domain).prepareDomain(sessions.get(domain).state(),recorded)));
            }
            // Identical operation coordinates and clocks are legal in independent sessions.
            assertEquals(handle(A).id(),handle(B).id());assertEquals(handle(A).startedAt(),handle(B).startedAt());
            assertEquals(handle(A).dueAt(),handle(B).dueAt());
            assertEquals(Optional.of(A),handle(A).issuer());assertEquals(Optional.of(B),handle(B).issuer());
            sessions.get(A).observe(50_000,List.of());
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request) {
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            if(request.command() instanceof Action.CueCommand){cues.add(domain);return RuleEngine.Empty.INSTANCE;}
            throw new AssertionError(request.command());
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        DamageTallies.Handle handle(String domain){return state(domain).damageTallies().values().iterator().next().handle();}
        DamageTallies.Handle captured(String domain) {
            return state(domain).timers().values().stream().map(EffectState.Timer::signal).map(RuleEngine.Signal::payload)
                    .filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast)
                    .filter(p->switch(p.scope()) {
                        case EffectSource source->source.holder().equals(H);
                        case BuffRules.Scope buff->buff.snapshot().key().holder().equals(H);
                        default->false;
                    }).flatMap(p->p.bindings().values().stream())
                    .filter(DamageTallies.Handle.class::isInstance).map(DamageTallies.Handle.class::cast).findFirst().orElseThrow();
        }
        void completeMoved() {
            assertEquals(B,host.snapshot().directory().owner(H).lease().domain());
            assertEquals(Optional.of(A),captured(B).issuer());
            assertEquals(3,DamageTallies.read(state(A),handle(A)).numbers().healthLoss());
            sessions.get(B).observe(50_000,List.of());assertFalse(host.drain(20).pending());
            assertTrue(state(A).damageTallies().isEmpty());assertEquals(List.of(B),cues);
            assertEquals(9,DamageTallies.read(state(B),handle(B)).numbers().healthLoss());
        }
        void unchanged(ResourceSessionAuthority.Checkpoint before) {
            assertEquals(before,host.checkpoint());
            assertEquals(A,host.snapshot().directory().owner(H).lease().domain());
            assertEquals(3,DamageTallies.read(state(A),handle(A)).numbers().healthLoss());
            assertEquals(9,DamageTallies.read(state(B),handle(B)).numbers().healthLoss());
            assertTrue(cues.isEmpty());
        }
    }

    @Test void sourceOwnedTallyWithSameIdAtDestinationClosesTheOriginalLedgerAfterCharacterTransfer() {
        var f=new Fixture(false,false);var before=f.host.checkpoint();
        CharacterTransfer.prepare(H,Set.of(),f.program,f.state(A),f.program,f.state(B),Optional.of(A));
        f.unchanged(before);
        var prepared=f.host.prepareCharacterTransfer(H,A,B,Set.of());f.unchanged(before);
        f.host.commit(prepared);f.completeMoved();
    }

    @Test void buffOwnedTallyRoutesBackAfterBothBuffAndWholeCharacterHandoffs() {
        for(boolean character:List.of(false,true)) {
            var f=new Fixture(true,false);var before=f.host.checkpoint();
            BuffTransfer.prepare(H,f.program,f.state(A),f.program,f.state(B),Optional.of(A));f.unchanged(before);
            if(character) {
                var prepared=f.host.prepareCharacterTransfer(H,A,B,Set.of());f.unchanged(before);f.host.commit(prepared);
            } else {
                var prepared=f.host.prepareTransferWithBuffs(H,A,B);f.unchanged(before);f.host.commit(prepared);
            }
            assertTrue(f.state(A).buffs().instances().isEmpty());
            assertEquals(2,f.state(B).buffs().instances().size());f.completeMoved();
        }
    }

    @Test void liveTalliesCannotMoveIntoANoncoordinatedProgram() {
        for(boolean buff:List.of(false,true)) {
            var f=new Fixture(buff,false);var before=f.host.checkpoint();var local=new CompiledEffects(f.program.program());
            if(buff)assertThrows(IllegalArgumentException.class,()->BuffTransfer.prepare(H,f.program,f.state(A),local,f.state(B),Optional.of(A)));
            else assertThrows(IllegalArgumentException.class,()->CharacterTransfer.prepare(H,Set.of(),f.program,f.state(A),local,f.state(B),Optional.of(A)));
            f.unchanged(before);
        }
    }

    @Test void detachedWorkAndItsLedgerRemainInTheOriginalDomainAndDoNotBlockCharacterTransfer() {
        for(boolean buff:List.of(false,true)) {
            var f=new Fixture(buff,true);var timer=f.state(A).timers().values().iterator().next();
            f.host.commit(f.host.prepareCharacterTransfer(H,A,B,Set.of()));
            assertEquals(timer,f.state(A).timers().get(timer.id()));
            assertEquals(B,f.host.snapshot().directory().owner(H).lease().domain());
            f.sessions.get(A).observe(100_000,List.of());
            assertTrue(f.state(A).damageTallies().isEmpty());assertEquals(List.of(A),f.cues);
            assertEquals(9,DamageTallies.read(f.state(B),f.handle(B)).numbers().healthLoss());
        }
    }
}
