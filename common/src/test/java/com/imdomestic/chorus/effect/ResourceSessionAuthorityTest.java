package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceSessionAuthorityTest {
    static EffectSession session(CompiledEffects program, EffectState state) {
        return new EffectSession(program, EffectCheckpointTest.clock(), 1, state, _ -> { throw new AssertionError("unexpected world command"); });
    }
    record Fixture(CompiledEffects program, EffectSession a, EffectSession b, ResourceSessionAuthority host) {}
    static Fixture fixture(EffectState a, EffectState b) {
        var program = program(); var left = session(program, a); var right = session(program, b);
        var host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, new ResourceSessionAuthority.Binding(program, left), B, new ResourceSessionAuthority.Binding(program, right)));
        host.claim(HOLDER, A); return new Fixture(program, left, right, host);
    }
    static EffectState state(EffectSession session) { return session.state().engine().domain(); }
    @Test void handoffPublishesActualSessionsWithoutReactionsOrCounterChangesAndOnlyNewOwnerRecovers() {
        var f = fixture(initial(5_000_000), empty(50_000)); var a = f.a.checkpoint(); var b = f.b.checkpoint();
        var handoff = f.host.prepareTransfer(HOLDER, B); assertEquals(a, f.a.checkpoint()); assertEquals(b, f.b.checkpoint());
        f.host.commit(handoff); assertTrue(ResourceProjection.accounts(state(f.a), HOLDER).isEmpty());
        assertEquals(0.4, state(f.b).resources().get(ENERGY).value()); assertEquals(50_000, state(f.b).resources().get(ENERGY).timeMicros());
        assertEquals(a.timeline().nextEvent(), f.a.checkpoint().timeline().nextEvent()); assertEquals(b.timeline().nextFrame(), f.b.checkpoint().timeline().nextFrame());
        f.a.observe(6_000_000, List.of()); f.b.observe(1_050_000, List.of());
        assertTrue(state(f.a).resources().isEmpty()); assertEquals(0.65, state(f.b).resources().get(ENERGY).value());
        assertEquals(0.65, f.host.snapshot().directory().owner(ENERGY).account(ENERGY.resource()).value());
        assertThrows(IllegalStateException.class, () -> f.host.commit(handoff));
    }
    @Test void targetProgressAndRoundTripsInvalidateOldHandoffsWithoutPartialWrites() {
        var f = fixture(initial(0), empty(0)); var handoff = f.host.prepareTransfer(HOLDER, B); var beforeA = f.a.state();
        f.b.observe(0, List.of()); assertEquals(empty(0), state(f.b));
        assertThrows(IllegalStateException.class, () -> f.host.commit(handoff)); assertSame(beforeA, f.a.state());
        var next = f.host.prepareTransfer(HOLDER, B); f.host.commit(next); f.host.commit(f.host.prepareTransfer(HOLDER, A));
        assertEquals(initial(0), state(f.a)); assertEquals(empty(0), state(f.b));
        assertThrows(IllegalStateException.class, () -> f.host.commit(next)); assertThrows(IllegalStateException.class, () -> f.host.commit(handoff));
    }
    @Test void grantsAndOldIssuerRefundsCommitBeforeResultsAreReturnedAndRetainTheirQuota() {
        var account = initial(0).resources().get(ENERGY); var payment = Resources.spend(account, "paid", 0.3);
        var retained = RetainedCosts.retain(initial(0).withResource(payment.after()), "old-issuer", payment.receipt(), 2_000_000);
        var handle = (RetainedCosts.Handle) retained.result(); var f = fixture(retained.state(), empty(50_000_000));
        f.host.commit(f.host.prepareTransfer(HOLDER, B)); var beforeA = f.a.checkpoint().timeline(); var beforeB = f.b.checkpoint().timeline();
        var refund = f.host.refund(A, handle, 0.5); assertEquals(B, refund.recipient().orElseThrow().domain());
        assertEquals(0.25, state(f.b).resources().get(ENERGY).value(), 1e-12);
        assertEquals(0.15, state(f.a).retainedCosts().get(handle.id()).receipt().refundClaimed(), 1e-12);
        assertEquals(beforeA.nextEvent(), f.a.checkpoint().timeline().nextEvent()); assertEquals(beforeB.nextEvent(), f.b.checkpoint().timeline().nextEvent());
        f.host.commit(f.host.prepareTransfer(HOLDER, A)); f.host.refund(A, handle, 1); f.host.refund(A, handle, 1);
        assertEquals(0.4, state(f.a).resources().get(ENERGY).value(), 1e-12); assertTrue(state(f.b).resources().isEmpty());
        var grant = f.host.grant(ENERGY, 4, 4); assertEquals(3, state(f.a).resources().get(ENERGY).value()); assertEquals(1.4, grant.result().overflow(), 1e-12);
    }
    @Test void snapshotPublishesAllHoldersAndRejectsAnOldWorldRecreatingAMovedAccount() {
        var f = fixture(account(initial(0), "friend", "energy", 0.1, 2), empty(0)); f.host.claim("friend", A);
        f.a.observe(100_000, List.of()); var snapshot = f.host.snapshot();
        assertEquals(100_000, snapshot.directory().owner(HOLDER).timeMicros()); assertEquals(100_000, snapshot.directory().owner("friend").timeMicros());
        f.host.commit(f.host.prepareTransfer(HOLDER, B)); var original = f.b.state();
        var before = f.a.state(); EffectSession.commitDomains(List.of(f.a.prepareDomain(before, account(state(f.a), HOLDER, "energy", 2, 2))));
        assertThrows(IllegalStateException.class, f.host::snapshot); assertThrows(IllegalStateException.class, () -> f.host.grant(ENERGY, 1, 1));
        assertSame(original, f.b.state());
    }
    @Test void reconstructedBridgeRequiresDirectoryAndBothSessionCheckpointsToAgree() {
        var f = fixture(initial(0), empty(30)); f.host.commit(f.host.prepareTransfer(HOLDER, B));
        var directory = ResourceAuthorityCodec.decode(ResourceAuthorityCodec.encode(f.host.snapshot().directory()));
        var a = EffectSession.restore(f.program, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(f.a.checkpoint())), _ -> RuleEngine.Empty.INSTANCE);
        var b = EffectSession.restore(f.program, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(f.b.checkpoint())), _ -> RuleEngine.Empty.INSTANCE);
        var bindings = Map.of(A, new ResourceSessionAuthority.Binding(f.program, a), B, new ResourceSessionAuthority.Binding(f.program, b));
        var restored = new ResourceSessionAuthority(directory, bindings); restored.commit(restored.prepareTransfer(HOLDER, A));
        assertEquals(initial(0), state(a)); assertTrue(state(b).resources().isEmpty());
        assertThrows(IllegalStateException.class, () -> new ResourceSessionAuthority(directory, bindings));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSessionAuthority(directory, Map.of(A, bindings.get(A))));
    }
    @Test void aliasesAndMismatchedCataloguesAreRejectedBeforeAnySessionChange() {
        var f = fixture(initial(0), empty(0)); var a = f.a.state(); var b = f.b.state();
        var same = new ResourceSessionAuthority.Binding(f.program, f.a);
        assertThrows(IllegalArgumentException.class, () -> new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, same, B, same)));
        var changed = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, f.program.program()).getOrThrow().getAsJsonObject();
        changed.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate", 2);
        var other = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, changed).getOrThrow();
        assertThrows(IllegalArgumentException.class, () -> new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, new ResourceSessionAuthority.Binding(other, f.a))));
        var otherSession = session(other, empty(0));
        var host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, same, B, new ResourceSessionAuthority.Binding(other, otherSession)));
        host.claim(HOLDER, A); assertThrows(IllegalArgumentException.class, () -> host.prepareTransfer(HOLDER, B));
        assertSame(a, f.a.state()); assertSame(b, f.b.state());
        assertThrows(IllegalArgumentException.class, () -> host.commit(f.host.prepareTransfer(HOLDER, B)));
    }
    @Test void aRunningParticipantRejectsRefundBeforeConsumingTheOriginalClaim() {
        var data = JsonParser.parseString("""
            {"version":"native-boundary","resources":[{"id":"test:energy","capacity":2,"initial":1}],
             "bundles":[{"id":"test:caller","scope":"source","rules":[{"id":"call","on":"test:run","do":[
               {"type":"chorus:play_cue","cue":"test:call"}]}]}]}
            """);
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
        var paid = Resources.spend(new ResourceState(ENERGY, 1, 2, 0), "paid", 0.5);
        var retained = RetainedCosts.retain(empty(0).withResource(paid.after()), "cost", paid.receipt(), 1_000_000);
        var handle = (RetainedCosts.Handle) retained.result();
        var source = new EffectSource("test:source", "test:caller", HOLDER,
                new com.imdomestic.chorus.effect.buff.BuffInstance.Origin(HOLDER, "", "", "", Set.of()), Set.of());
        var host = new java.util.concurrent.atomic.AtomicReference<ResourceSessionAuthority>();
        var a = new EffectSession(program, EffectCheckpointTest.clock(), 1, retained.state().withSource(source), _ -> {
            assertThrows(IllegalStateException.class, () -> host.get().refund(A, handle, 1)); return RuleEngine.Empty.INSTANCE;
        });
        var b = session(program, empty(0));
        host.set(new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, new ResourceSessionAuthority.Binding(program, a), B, new ResourceSessionAuthority.Binding(program, b))));
        host.get().claim(HOLDER, A); host.get().commit(host.get().prepareTransfer(HOLDER, B));
        a.start(0, new RuleEngine.Signal("test:run", RuleEngine.Empty.INSTANCE));
        assertEquals(0, state(a).retainedCosts().get(handle.id()).receipt().refundClaimed()); assertEquals(0.5, state(b).resources().get(ENERGY).value());
        host.get().refund(A, handle, 1); assertEquals(1, state(b).resources().get(ENERGY).value());
    }
}
