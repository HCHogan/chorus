package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResourceRequestSessionTest {
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin(HOLDER, "projectile", "weapon", "ability", Set.of("test:credit"));
    record Input(ResourceRequests.Request request) implements RuleEngine.Payload {}
    @FunctionalInterface interface Callback { RuleEngine.ActionResult apply(String domain, RuleEngine.WorldRequest request, ResourceRequests.Receipt result); }
    static final class Fixture {
        final Map<String, EffectSession> sessions = new TreeMap<>();
        final List<ResourceRequests.Receipt> completed = new ArrayList<>();
        final ResourceSessionAuthority host;
        Callback callback = (_, _, result) -> result;
        boolean failAfter;
        Fixture(Map<String, EffectState> initial) {
            var request = new Action() {
                public ResultShape validate(Validation ignored) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(((Input) e.context().event().signal().payload()).request()); }
                public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult result) {
                    var receipt = (ResourceRequests.Receipt) result;
                    assertEquals(e.context().command(ResourceRequests.Request.class), receipt.request());
                    completed.add(receipt); return new RuleEngine.Local<>(e.state(), result, List.of());
                }
            };
            var dependent = new Action() {
                public ResultShape validate(Validation ignored) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
                    assertInstanceOf(ResourceRequests.Receipt.class, e.context().bindings().get("reply"));
                    if (failAfter) throw new IllegalStateException("dependent action failed");
                    return new RuleEngine.Local<>(e.state().withMode(EffectState.Mode.PVP), RuleEngine.Empty.INSTANCE, List.of());
                }
            };
            var rules = List.of(new EffectProgram.Rule("request", "test:request", new Condition.Constant(true), List.of(
                    new EffectProgram.Instruction(request, "reply"), new EffectProgram.Instruction(dependent, ""))));
            var bundle = new EffectProgram.Bundle("test:requester", EffectProgram.Scope.SOURCE, rules, List.of());
            var program = new CompiledEffects(new EffectProgram("request-test", List.of(), List.of(bundle), List.of(), Optional.empty(), program().program().resources()));
            var source = new EffectSource("requester", "test:requester", HOLDER, ORIGIN, Set.of());
            for (var entry : initial.entrySet()) {
                String domain = entry.getKey();
                sessions.put(domain, new EffectSession(program, EffectCheckpointTest.clock(), 1, entry.getValue().withSource(source), worldRequest -> {
                    var result = execute(domain, worldRequest); return callback.apply(domain, worldRequest, result);
                }));
            }
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>();
            sessions.forEach((domain, session) -> bindings.put(domain, new ResourceSessionAuthority.Binding(program, session)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings); host.claim(HOLDER, A);
        }
        ResourceRequests.Receipt execute(String caller, RuleEngine.WorldRequest request) { return host.execute(caller, request); }
        EffectSession session(String domain) { return sessions.get(domain); }
        EffectState state(String domain) { return session(domain).state().engine().domain(); }
        void move(String destination) { host.commit(host.prepareTransfer(HOLDER, destination)); }
        void request(String caller, ResourceRequests.Request request) { session(caller).start(state(caller).buffs().timeMicros(), new RuleEngine.Signal("test:request", new Input(request))); }
    }
    static Fixture fixture() { return new Fixture(Map.of(A, initial(0), B, empty(50_000_000))); }
    static ResourceRequests.Grant grant(double amount) { return new ResourceRequests.Grant(ENERGY, amount, amount, ORIGIN); }
    static RetainedCosts.Handle retain(EffectState state, String id, double amount, long due) {
        var paid = Resources.spend(state.resources().get(ENERGY), id, amount);
        return (RetainedCosts.Handle) RetainedCosts.retain(state.withResource(paid.after()), id, paid.receipt(), due).result();
    }
    static EffectState paid(EffectState state, RetainedCosts.Handle handle) {
        var spent = Resources.spend(state.resources().get(ENERGY), handle.receipt().operation(), handle.receipt().paid());
        return RetainedCosts.retain(state.withResource(spent.after()), handle.id(), spent.receipt(), handle.dueAt()).state();
    }
    @Test void runningSourceCreditsCurrentOwnerOnceAndDependentActionUsesTheCommittedReceipt() {
        var f = fixture(); f.move(B); var retained = new AtomicReference<EffectSession.WorldOperation>();
        f.callback = (domain, request, result) -> {
            var session = f.session(domain); var token = session.operation(request); retained.set(token);
            assertEquals(Optional.of(request.id()), session.state().engine().pending()); assertTrue(session.running());
            assertEquals(1.1, f.state(B).resources().get(ENERGY).value()); assertTrue(f.state(A).resources().isEmpty());
            assertSame(result, f.host.execute(domain, request)); assertEquals(1.1, f.state(B).resources().get(ENERGY).value());
            assertThrows(IllegalStateException.class, () -> f.host.execute(B, request));
            assertThrows(IllegalStateException.class, () -> f.host.execute(domain, new RuleEngine.WorldRequest(request.id(), grant(0.2))));
            assertThrows(IllegalStateException.class, () -> session.prepareOperationDomain(token, session.state(), f.state(domain), result));
            return new RuleEngine.WorldReceipt(result, List.of());
        };
        f.request(A, grant(0.7)); assertEquals(1, f.completed.size()); assertEquals(EffectState.Mode.PVP, f.state(A).mode());
        var result = (ResourceRequests.Granted) f.completed.getFirst(); assertEquals(0.7, result.grant().credited()); assertEquals(B, result.lease().domain());
        assertEquals(ORIGIN, ((EffectEvent) ResourceRequests.facts(result).getFirst().payload()).source());
        assertThrows(IllegalStateException.class, () -> f.session(A).operationState(retained.get()));
        assertThrows(IllegalStateException.class, () -> f.host.execute(A, retained.get().request()));
        assertTrue(f.session(A).state().idle()); assertEquals(1.1, f.host.snapshot().directory().owner(ENERGY).account(ENERGY.resource()).value());
    }
    @Test void readsAndSameDomainCreditsUseTheSamePendingRequestProtocolWithoutInventingReadFacts() {
        var f = fixture(); f.request(A, new ResourceRequests.Read(ENERGY));
        var read = (ResourceRequests.ReadResult) f.completed.getFirst(); assertEquals(0.4, read.account().value()); assertEquals(A, read.lease().domain());
        assertTrue(ResourceRequests.facts(read).isEmpty()); f.request(A, grant(0.6));
        assertEquals(1, f.state(A).resources().get(ENERGY).value()); assertTrue(f.state(B).resources().isEmpty());
        assertEquals(2, f.completed.size());
    }
    @Test void requesterIssuerAndRecipientCanBeThreeDifferentSessionsAndShareOneRefundQuota() {
        var state = initial(0); var handle = retain(state, "cost", 0.3, 2_000_000);
        var f = new Fixture(Map.of(A, paid(state, handle), B, empty(20_000_000), C, empty(50_000_000))); f.move(C);
        f.callback = (_, _, result) -> {
            var refund = (ResourceRequests.Refunded) result;
            assertEquals(C, refund.recipient().orElseThrow().domain());
            assertEquals(refund.refund().orElseThrow().receipt(), f.state(A).retainedCosts().get(handle.id()).receipt());
            assertEquals(refund.refund().orElseThrow().grant().after(), f.state(C).resources().get(ENERGY));
            return result;
        };
        f.request(B, new ResourceRequests.Refund(A, handle, 0.5, ORIGIN)); assertEquals(0.25, f.state(C).resources().get(ENERGY).value());
        f.request(B, new ResourceRequests.Refund(A, handle, 1, ORIGIN)); f.request(B, new ResourceRequests.Refund(A, handle, 1, ORIGIN));
        assertEquals(0.4, f.state(C).resources().get(ENERGY).value()); assertEquals(0.3, f.state(A).retainedCosts().get(handle.id()).receipt().refundClaimed());
        assertEquals(0, ((ResourceRequests.Refunded) f.completed.getLast()).refund().orElseThrow().grant().credited());
    }
    @Test void refundCanUpdateThePendingIssuerAndTheRecipientBeforeResumingItsFrame() {
        var state = initial(0); var handle = retain(state, "cost", 0.3, 2_000_000);
        var f = new Fixture(Map.of(A, paid(state, handle), B, empty(50_000_000))); f.move(B);
        f.callback = (domain, request, result) -> {
            assertEquals(0.3, f.state(A).retainedCosts().get(handle.id()).receipt().refundClaimed());
            assertEquals(0.4, f.state(B).resources().get(ENERGY).value());
            assertEquals(Optional.of(request.id()), f.session(domain).state().engine().pending()); return result;
        };
        f.request(A, new ResourceRequests.Refund(A, handle, 1, ORIGIN)); assertEquals(EffectState.Mode.PVP, f.state(A).mode());
        assertTrue(f.session(A).state().idle());
    }
    @Test void expiredIssuerClaimAndOverflowReturnTheActualOutcome() {
        var state = initial(0); var handle = retain(state, "cost", 0.3, 2_000_000);
        var f = new Fixture(Map.of(A, paid(state, handle), B, empty(50_000_000))); f.move(B);
        f.host.grant(ENERGY, 4, 4); f.request(A, new ResourceRequests.Refund(A, handle, 1, ORIGIN));
        var overflow = (ResourceRequests.Refunded) f.completed.getFirst(); assertEquals(0, overflow.refund().orElseThrow().grant().credited());
        assertEquals(0.3, overflow.refund().orElseThrow().grant().overflow()); assertEquals(0.3, f.state(A).retainedCosts().get(handle.id()).receipt().refundClaimed());
        f.session(A).observe(2_000_000, List.of()); f.request(B, new ResourceRequests.Refund(A, handle, 1, ORIGIN));
        var expired = (ResourceRequests.Refunded) f.completed.getLast(); assertTrue(expired.refund().isEmpty()); assertTrue(expired.recipient().isEmpty());
        assertTrue(ResourceRequests.facts(expired).isEmpty());
    }
    @Test void exceptionsAfterCommitPreserveBothWritesAndCannotBeRetriedOrCheckpointed() {
        var state = initial(0); var handle = retain(state, "cost", 0.3, 2_000_000);
        var f = new Fixture(Map.of(A, paid(state, handle), B, empty(50_000_000))); f.move(B);
        var issued = new AtomicReference<RuleEngine.WorldRequest>();
        f.callback = (_, request, _) -> { issued.set(request); throw new IllegalStateException("receipt delivery failed"); };
        assertThrows(IllegalStateException.class, () -> f.request(A, new ResourceRequests.Refund(A, handle, 1, ORIGIN)));
        assertEquals(0.3, f.state(A).retainedCosts().get(handle.id()).receipt().refundClaimed()); assertEquals(0.4, f.state(B).resources().get(ENERGY).value());
        assertEquals(Optional.of(issued.get().id()), f.session(A).state().engine().pending()); assertTrue(f.completed.isEmpty());
        assertThrows(IllegalStateException.class, () -> f.host.execute(A, issued.get()));
        assertThrows(IllegalStateException.class, () -> f.request(A, grant(1))); assertThrows(IllegalStateException.class, f.session(A)::checkpoint);
        assertThrows(IllegalStateException.class, f.host::snapshot);
    }
    @Test void callbackCannotReturnAnotherResultAndLaterRuleFailureDoesNotRollbackAConfirmedCredit() {
        var f = fixture(); f.move(B); f.callback = (_, _, _) -> RuleEngine.Empty.INSTANCE;
        assertThrows(IllegalStateException.class, () -> f.request(A, grant(0.5))); assertEquals(0.9, f.state(B).resources().get(ENERGY).value());
        assertTrue(f.completed.isEmpty()); assertTrue(f.session(A).state().engine().pending().isPresent());
        var g = fixture(); g.move(B); g.failAfter = true;
        assertThrows(IllegalStateException.class, () -> g.request(A, grant(0.5))); assertEquals(0.9, g.state(B).resources().get(ENERGY).value());
        assertEquals(1, g.completed.size()); assertTrue(g.session(A).state().engine().failure().isPresent());
        assertFalse(g.session(A).state().engine().receipts().isEmpty());
    }
    @Test void pendingTimeAdvanceResumesItsOriginalRequestedTimeAfterTheResourceOperation() {
        var request = grant(0.7);
        var timer = new EffectState.Timer("delayed", 5, 0, 1, new RuleEngine.Signal("test:request", new Input(request)), Optional.empty());
        var f = new Fixture(Map.of(A, initial(0).schedule(timer), B, empty(50_000_000))); f.move(B);
        f.callback = (domain, operation, result) -> {
            assertEquals(5, f.state(domain).buffs().timeMicros());
            assertEquals(10, f.session(domain).state().requested().orElseThrow().timeMicros());
            assertEquals(Optional.of(operation.id()), f.session(domain).state().engine().pending()); return result;
        };
        f.session(A).observe(10, List.of()); assertEquals(10, f.state(A).buffs().timeMicros()); assertEquals(1, f.completed.size());
        assertEquals(50_000_000, f.state(B).buffs().timeMicros()); assertEquals(1.1, f.state(B).resources().get(ENERGY).value());
        assertTrue(f.session(A).state().requested().isEmpty());
    }
    @Test void requestAndReceiptCodecsPreserveStableAddressOriginAndLargeLeaseIdentity() {
        var request = grant(0.7); var account = initial(0).resources().get(ENERGY); var lease = new ResourceAuthority.Lease(B, 9_007_199_254_740_999L);
        var read = new ResourceRequests.Read(ENERGY); var handle = retain(initial(0), "payment", 0.3, 2_000_000);
        var refund = new ResourceRequests.Refund(A, handle, 0.5, ORIGIN); var paid = paid(initial(0), handle);
        var result = Resources.refund(paid.resources().get(ENERGY), paid.retainedCosts().get(handle.id()).receipt(), 0.5);
        var values = List.of(request, read, refund, new ResourceRequests.ReadResult(read, account, lease),
                new ResourceRequests.Granted(request, Resources.grant(account, 0.7, 0.7), lease),
                new ResourceRequests.Refunded(refund, Optional.of(result), Optional.of(lease)), new ResourceRequests.Refunded(refund, Optional.empty(), Optional.empty()));
        for (var value : values) assertEquals(value, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(value), value.getClass()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRequests.Granted(request, Resources.grant(account, 0.2, 0.2), lease));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRequests.Refunded(new ResourceRequests.Refund(A, handle, 0.1, ORIGIN), Optional.of(result), Optional.of(lease)));
    }
    @Test void anotherRunningParticipantRejectsTheRequestBeforeCreditingAnyAccount() {
        var f = fixture();
        f.callback = (domain, _, result) -> {
            assertEquals(B, domain);
            assertThrows(IllegalStateException.class, () -> f.request(A, grant(0.7)));
            assertEquals(0.4, f.state(A).resources().get(ENERGY).value()); assertTrue(f.state(B).resources().isEmpty());
            return result;
        };
        f.request(B, new ResourceRequests.Read(ENERGY));
        assertEquals(1, f.completed.size()); assertTrue(f.session(B).state().idle());
        assertTrue(f.session(A).state().engine().pending().isPresent());
    }
    @Test void aGrantBelowStoredPrecisionDoesNotInventABalanceChangeFact() {
        var account = new ResourceState(ENERGY, 1, 2, 0); var request = grant(Double.MIN_VALUE);
        var result = new ResourceRequests.Granted(request, Resources.grant(account, request.requested(), request.scaled()), new ResourceAuthority.Lease(B, 1));
        assertEquals(account.value(), result.grant().after().value());
        assertEquals(List.of("chorus:resource_granted"), ResourceRequests.facts(result).stream().map(RuleEngine.Signal::type).toList());
    }
}
