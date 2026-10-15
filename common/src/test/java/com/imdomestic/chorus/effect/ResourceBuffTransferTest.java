package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceBuffTransferTest {
    static final String A="test:a",B="test:b",C="test:c",H="player";
    static final ResourceState.Key KEY=new ResourceState.Key(H,"test:energy");
    static final class Fixture {
        final CompiledEffects p;final Map<String,EffectSession> sessions=new TreeMap<>();final ResourceSessionAuthority host;
        int cues;boolean fail;
        Fixture() {
            var data=BuffTransferTest.data();data.add("resources",JsonParser.parseString("[{\"id\":\"test:energy\",\"capacity\":1,\"initial\":1}]"));
            data.getAsJsonArray("bundles").get(1).getAsJsonObject().add("rules",JsonParser.parseString("""
                [{"id":"paid","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[
                  {"action":{"type":"chorus:spend_resource","resource":"test:energy","payment":"cast","amount":{"type":"chorus:constant","value":0.6,"unit":"charge_fraction"}},"as":"paid"},
                  {"action":{"type":"chorus:retain_cost","cost":"paid","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"voucher"},
                  {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"do":[
                    {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":0.5,"unit":"multiplier"}},
                    {"type":"chorus:play_cue","cue":"test:refunded"},
                    {"type":"chorus:close_retained_cost","cost":"voucher"},
                    {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":1,"unit":"multiplier"}}
                  ]}
                ]}]
                """));
            p=EffectTestSupport.compile(data).withResourceRouting();var source=EffectTestSupport.source("test:input");
            var initial=BuffTransferTest.empty(0).withSource(source).withResource(new ResourceState(KEY,1,1,0));
            for(var domain:List.of(A,B,C))sessions.put(domain,new EffectSession(p,EffectCheckpointTest.clock(),1,domain.equals(A)?initial:BuffTransferTest.empty(0),r->execute(domain,r)));
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((d,s)->bindings.put(d,new ResourceSessionAuthority.Binding(p,s)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);host.claim(H,A);
            sessions.get(A).start(0,new RuleEngine.Signal("test:grant",new EffectEvent(H,H,source.origin(),Set.of(),Map.of())));
            sessions.get(A).observe(50_000,List.of());
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request) {
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            assertInstanceOf(Action.CueCommand.class,request.command());cues++;if(fail)throw new IllegalStateException("lost relocated world receipt");return RuleEngine.Empty.INSTANCE;
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        double energy(){return host.snapshot().directory().owner(KEY).account(KEY.resource()).value();}
        RetainedCosts.Handle handle(){return state(A).retainedCosts().values().iterator().next().handle();}
    }
    @Test void accountsBuffsAndCapturedRefundRightsMoveTogetherButTheIssuingLedgerStaysPut() {
        var f=new Fixture();var handle=f.handle();assertEquals(Optional.of(A),handle.issuer());
        var before=f.host.checkpoint();var move=f.host.prepareTransferWithBuffs(H,A,B);assertEquals(before,f.host.checkpoint());f.host.commit(move);
        assertTrue(f.state(A).buffs().instances().isEmpty());assertTrue(f.state(A).resources().isEmpty());assertEquals(handle,f.handle());assertTrue(f.state(B).retainedCosts().isEmpty());
        assertEquals(.4,f.energy(),1e-12);assertEquals(1,f.state(B).buffs().instances().size());
        f.sessions.get(B).observe(50_000,List.of());f.host.drain(20);assertEquals(.7,f.energy(),1e-12);assertEquals(1,f.cues);assertTrue(f.state(A).retainedCosts().isEmpty());
        assertEquals(0,f.host.refund(A,handle,1).result().stream().count());assertThrows(IllegalStateException.class,()->f.host.commit(move));
    }
    @Test void threeDomainHandoffAndAnAlreadyResidentResourceLeaseUseTheSameAtomicProtocol() {
        for(var accountDestination:List.of(B,C)) {
            var f=new Fixture();f.host.commit(f.host.prepareTransfer(H,accountDestination));var lease=f.host.snapshot().directory().owner(H).lease();
            var move=f.host.prepareTransferWithBuffs(H,A,B);f.host.commit(move);
            if(accountDestination.equals(B))assertEquals(lease,f.host.snapshot().directory().owner(H).lease());
            assertTrue(f.state(A).resources().isEmpty()&&f.state(C).resources().isEmpty());assertTrue(f.state(A).buffs().instances().isEmpty());
            f.sessions.get(B).observe(50_000,List.of());f.host.drain(20);assertEquals(.7,f.energy(),1e-12);assertTrue(f.state(A).retainedCosts().isEmpty());
        }
    }
    @Test void staleOrConflictingPlansNeverPublishOnlyTheEnergyHalf() {
        var f=new Fixture();var before=f.host.checkpoint();var plan=f.host.prepareTransferWithBuffs(H,A,B);f.sessions.get(C).observe(0,List.of());
        assertThrows(IllegalStateException.class,()->f.host.commit(plan));assertEquals(before.directory(),f.host.checkpoint().directory());assertEquals(1,f.state(A).buffs().instances().size());assertTrue(f.state(B).resources().isEmpty());
        var duplicate=BuffTransferTest.grant(f.p,f.state(B),H,"test:live",1,100_000);EffectSession.commitDomains(List.of(f.sessions.get(B).prepareDomain(f.sessions.get(B).state(),duplicate)));
        assertThrows(IllegalStateException.class,()->f.host.prepareTransferWithBuffs(H,A,B));assertEquals(A,f.host.snapshot().directory().owner(H).lease().domain());assertEquals(.4,f.energy(),1e-12);
    }
    @Test void issuerClockExpiryRemainsAuthoritativeAfterTheContinuationChangesDomains() {
        var f=new Fixture();f.host.commit(f.host.prepareTransferWithBuffs(H,A,B));f.sessions.get(A).observe(5_000_000,List.of());
        f.sessions.get(B).observe(50_000,List.of());f.host.drain(20);assertEquals(.4,f.energy(),1e-12);assertEquals(1,f.cues);assertTrue(f.state(A).retainedCosts().isEmpty());
    }
    @Test void unknownWorldOutcomeRetainsTheRefundAndOriginalClaimWithoutRetryOrClosure() {
        var f=new Fixture();f.host.commit(f.host.prepareTransferWithBuffs(H,A,B));f.fail=true;
        assertThrows(IllegalStateException.class,()->f.sessions.get(B).observe(50_000,List.of()));
        assertEquals(.7,f.state(B).resources().get(KEY).value(),1e-12);assertEquals(.3,f.state(A).retainedCosts().values().iterator().next().receipt().refundClaimed(),1e-12);
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,()->f.sessions.get(B).observe(60_000,List.of()));assertEquals(1,f.cues);
    }
    @Test void legacyUnqualifiedHandlesAreQualifiedAtHandoffWithoutRecreatingTheirLedger() {
        var f=new Fixture();var original=f.handle();var old=new RetainedCosts.Handle(original.id(),original.receipt(),original.startedAt(),original.dueAt());
        var state=f.state(A).withRetainedCost(new RetainedCosts.Entry(old,f.state(A).retainedCosts().get(old.id()).receipt()));
        for(var timer:List.copyOf(state.timers().values()))if(timer.signal().payload() instanceof EffectContinuations.Pending pending) {
            var bindings=new TreeMap<>(pending.bindings());bindings.replaceAll((_,v)->v instanceof RetainedCosts.Handle?old:v);
            var next=new EffectContinuations.Pending(pending.id(),pending.definition(),pending.version(),pending.scope(),pending.cause(),bindings,pending.owner(),pending.relocatedAt());
            state=state.cancel(timer.id()).schedule(new EffectState.Timer(timer.id(),timer.dueAt(),timer.intervalMicros(),timer.remaining(),new RuleEngine.Signal(timer.signal().type(),next),timer.lifetime(),timer.pausedRemaining()));
        }
        EffectSession.commitDomains(List.of(f.sessions.get(A).prepareDomain(f.sessions.get(A).state(),state)));
        assertThrows(IllegalArgumentException.class,()->BuffTransfer.prepare(H,f.p,f.state(A),f.p,f.state(B)));
        var move=f.host.prepareTransferWithBuffs(H,A,B);f.host.commit(move);assertEquals(old,f.handle());
        f.sessions.get(B).observe(50_000,List.of());f.host.drain(20);assertEquals(.7,f.energy(),1e-12);assertTrue(f.state(A).retainedCosts().isEmpty());
    }
    @Test void qualifiedRefundsRejectWrongIssuersAndBothNewAndOldStorageShapesRoundTrip() {
        var f=new Fixture();var handle=f.handle();assertThrows(IllegalArgumentException.class,()->f.host.refund(B,handle,1));
        assertThrows(IllegalArgumentException.class,()->new ResourceRequests.Close(B,handle));
        var codec=EffectCheckpointCodec.DATA;var request=new ResourceRequests.Close(A,handle);var receipt=new ResourceRequests.Closed(request,new RetainedCosts.Closed(true));
        assertEquals(receipt,codec.decode(codec.encode(receipt),ResourceRequests.Closed.class));
        var legacy=new RetainedCosts.Handle(handle.id(),handle.receipt(),handle.startedAt(),handle.dueAt());var wire=codec.encode(legacy).getAsJsonObject();wire.addProperty("$","chorus:effect/resource/retained_costs/handle");wire.getAsJsonObject("v").remove("issuer");
        assertEquals(legacy,codec.decode(wire,RetainedCosts.Handle.class));assertEquals(handle,codec.decode(codec.encode(handle),RetainedCosts.Handle.class));
        var saved=f.host.checkpoint();var restored=ResourceSessionAuthority.restore(saved,Map.of(
                A,new ResourceSessionAuthority.RestoreBinding(f.p,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE),
                B,new ResourceSessionAuthority.RestoreBinding(f.p,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE),
                C,new ResourceSessionAuthority.RestoreBinding(f.p,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE)));
        assertEquals(saved,restored.authority().checkpoint());
    }
    @Test void aLegacyAliasCannotEraseAnAlreadyQualifiedIssuingLedger() {
        var f=new Fixture();var handle=f.handle();
        var legacy=new RetainedCosts.Handle(handle.id(),handle.receipt(),handle.startedAt(),handle.dueAt());
        var result=RetainedCosts.authorize(f.state(A),legacy,.5);
        assertEquals(handle,result.state().retainedCosts().get(handle.id()).handle());
        assertEquals(.3,result.state().retainedCosts().get(handle.id()).receipt().refundClaimed(),1e-12);
        assertTrue(((RetainedCosts.Closed)RetainedCosts.close(result.state(),legacy).result()).removed());
    }
    @Test void aLocalOnlyDestinationCannotSilentlyLoseTheCapturedRemoteRefund() {
        var f=new Fixture();var local=new CompiledEffects(f.p.program());var saved=f.host.checkpoint();
        var domains=new TreeMap<>(saved.domains());var before=domains.get(B);
        domains.put(B,new EffectSession.Checkpoint(before.program(),before.timeline(),false));
        var mixed=new ResourceSessionAuthority.Checkpoint(saved.directory(),domains);
        var restored=ResourceSessionAuthority.restore(mixed,Map.of(
                A,new ResourceSessionAuthority.RestoreBinding(f.p,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE),
                B,new ResourceSessionAuthority.RestoreBinding(local,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE),
                C,new ResourceSessionAuthority.RestoreBinding(f.p,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE)));
        assertAll(
                ()->assertThrows(IllegalArgumentException.class,()->BuffTransfer.prepare(H,f.p,f.state(A),local,f.state(B),Optional.of(A))),
                ()->assertThrows(IllegalArgumentException.class,()->restored.authority().prepareTransferWithBuffs(H,A,B)));
        assertEquals(mixed,restored.authority().checkpoint());
    }
}
