package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceSessionAuthorityTest.session;
import static com.imdomestic.chorus.effect.ResourceSessionAuthorityTest.state;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResourceEnrollmentTest {
    record Fixture(CompiledEffects program,EffectSession a,EffectSession b,ResourceSessionAuthority host) {}
    static Fixture fixture(EffectState left,EffectState right) {
        var p=program().withResourceRouting();var a=session(p,left);var b=session(p,right);
        return new Fixture(p,a,b,new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(
                A,new ResourceSessionAuthority.Binding(p,a),B,new ResourceSessionAuthority.Binding(p,b))));
    }
    @Test void batchAdoptsExistingBalancesAndEmptyPartitionsWithoutCreatingResourcesOrAdvancingClocks() {
        var f=fixture(initial(5_000_000),account(empty(50_000),"friend","energy",.7,2));var a=f.a.state();var b=f.b.state();
        var input=new HashMap<>(Map.of(HOLDER,A,"friend",B,"empty",A));var owners=f.host.enroll(input);input.clear();
        assertEquals(Set.of(HOLDER,"friend","empty"),owners.keySet());assertEquals(.4,owners.get(HOLDER).account("test:energy").value());
        assertEquals(3,owners.get(HOLDER).account("test:energy").capacity());assertEquals(.7,owners.get("friend").account("test:energy").value());
        assertTrue(owners.get("empty").accounts().isEmpty());assertSame(a,f.a.state());assertSame(b,f.b.state());
        assertThrows(UnsupportedOperationException.class,owners::clear);assertEquals(4,f.host.snapshot().directory().nextGeneration());
    }
    @Test void repeatedObservationPreservesRemoteLeasesEmptyPartitionsAndPreparedHandoffs() {
        var f=fixture(initial(0),empty(20_000));f.host.enroll(Map.of(HOLDER,A,"empty",A));
        f.host.commit(f.host.prepareTransfer(HOLDER,B));f.host.commit(f.host.prepareTransfer("empty",B));
        var before=f.host.checkpoint();var plan=f.host.prepareTransfer(HOLDER,A);
        f.host.enroll(Map.of());var observed=f.host.enroll(Map.of(HOLDER,A,"empty",A));
        assertEquals(B,observed.get(HOLDER).lease().domain());assertEquals(B,observed.get("empty").lease().domain());
        assertEquals(before,f.host.checkpoint());f.host.commit(plan);assertEquals(.4,state(f.a).resources().get(ENERGY).value());
    }
    @Test void aReplicaRejectsTheWholeBatchWithoutClaimingEarlierHoldersOrChangingSessions() {
        var f=fixture(initial(0),initial(0));var before=f.host.checkpoint();
        assertThrows(IllegalStateException.class,()->f.host.enroll(Map.of("aaa",A,HOLDER,A)));
        assertEquals(before,f.host.checkpoint());assertTrue(f.host.snapshot().directory().holders().isEmpty());
        assertThrows(IllegalArgumentException.class,()->f.host.enroll(Map.of("aaa",A,"bad",C)));
        assertThrows(IllegalArgumentException.class,()->f.host.enroll(Map.of(" ",A)));assertEquals(before,f.host.checkpoint());
    }
    @Test void incompatibleObservationRejectsNewClaimsButDoesNotRevokeTheOriginalOwner() {
        var f=fixture(initial(0),empty(0));f.host.enroll(Map.of(HOLDER,A));
        var data=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,f.program.program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate",2);
        var different=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,data).getOrThrow().withResourceRouting();
        f.host.enlist(Map.of(C,new ResourceSessionAuthority.Binding(different,session(different,empty(0)))));var before=f.host.checkpoint();
        assertThrows(IllegalArgumentException.class,()->f.host.enroll(Map.of("aaa",C,HOLDER,C)));assertEquals(before,f.host.checkpoint());
        var plan=f.host.prepareTransfer(HOLDER,B);f.host.enroll(Map.of("new",A));assertThrows(IllegalStateException.class,()->f.host.commit(plan));
    }
    @Test void pendingRecipientFactsRemainQueuedWhileNewPlayersAreAdmitted() {
        var f=fixture(initial(0),empty(0));var original=f.b.state();
        EffectSession.commitDomains(List.of(f.b.prepareInboxDomain(original,state(f.b),List.of(new RuleEngine.Signal("test:queued",RuleEngine.Empty.INSTANCE)))));
        var queued=f.b.state();f.host.enroll(Map.of(HOLDER,A,"joining",B));
        assertSame(queued,f.b.state());assertTrue(f.b.inboxPending());assertTrue(f.host.snapshot().directory().holders().containsKey("joining"));
        assertThrows(IllegalStateException.class,f.host::checkpoint);f.host.drain(10);assertFalse(f.b.inboxPending());f.host.checkpoint();
    }
    @Test void restoredEnrollmentDoesNotRefillOrRepeatClockProgressAndOffThreadCallsFail() throws Exception {
        var f=fixture(initial(0),empty(0));f.host.enroll(Map.of(HOLDER,A));f.a.observe(1_000_000,List.of());var saved=f.host.checkpoint();
        var restored=ResourceSessionAuthority.restore(saved,Map.of(
                A,new ResourceSessionAuthority.RestoreBinding(f.program,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE),
                B,new ResourceSessionAuthority.RestoreBinding(f.program,EffectCheckpointTest.clock(),1,_ -> RuleEngine.Empty.INSTANCE)));
        restored.authority().enroll(Map.of(HOLDER,B));assertEquals(saved,restored.authority().checkpoint());
        var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{try{restored.authority().enroll(Map.of("other",A));}catch(Throwable e){failure.set(e);}});
        thread.start();thread.join();assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(saved,restored.authority().checkpoint());
        restored.bindings().get(A).session().observe(2_000_000,List.of());assertEquals(.9,restored.authority().readView(B).account(ENERGY).value(),1e-12);
    }
}
