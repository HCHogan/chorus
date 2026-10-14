package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRequestSessionTest.ORIGIN;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ResourceMutationSessionTest {
    static final ResourceState.Key PROGRESS = new ResourceState.Key(HOLDER, "test:progress"), USES = new ResourceState.Key(HOLDER, "test:uses");
    record Input(ResourceRequests.Request request) implements RuleEngine.Payload {}
    static final class Fixture {
        final Map<String, EffectSession> sessions = new TreeMap<>();
        final List<ResourceRequests.Receipt> replies = new ArrayList<>();
        final List<RuleEngine.Event> observed = new ArrayList<>();
        final ResourceSessionAuthority host;
        boolean unknown;
        Fixture(EffectState a, List<EffectProgram.Step> following) {
            var steps = new ArrayList<EffectProgram.Step>();
            steps.add(new EffectProgram.Instruction(action(e -> ((Input) e.context().event().signal().payload()).request()), "paid"));
            steps.addAll(following);
            var rules = new ArrayList<EffectProgram.Rule>();
            rules.add(new EffectProgram.Rule("request", "test:request", new Condition.Constant(true), steps));
            var observe = new Action() {
                public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
                    observed.add(e.context().event()); return new RuleEngine.Local<>(e.state(), RuleEngine.Empty.INSTANCE, List.of());
                }
            };
            for (String type : List.of("initialized", "spent", "granted", "refunded", "changed", "capacity_changed"))
                rules.add(new EffectProgram.Rule(type, "chorus:resource_" + type, new Condition.Constant(true), List.of(new EffectProgram.Instruction(observe, ""))));
            var bundle = new EffectProgram.Bundle("test:mutations", EffectProgram.Scope.SOURCE, rules, List.of());
            var program = new CompiledEffects(new EffectProgram("mutations", List.of(), List.of(bundle), List.of(), Optional.empty(), program().program().resources()));
            var source = new EffectSource("requester", bundle.id(), HOLDER, ORIGIN, Set.of());
            for (var entry : Map.of(A, a, B, empty(50_000_000)).entrySet()) {
                String domain = entry.getKey();
                sessions.put(domain, new EffectSession(program, EffectCheckpointTest.clock(), 1, entry.getValue().withSource(source), request -> {
                    var result = execute(domain, request);
                    assertSame(result, execute(domain, request)); // A callback retry must not double-pay or duplicate recipient facts.
                    if (unknown) throw new IllegalStateException("unknown after resource commit");
                    return result;
                }));
            }
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>();
            sessions.forEach((domain, session) -> bindings.put(domain, new ResourceSessionAuthority.Binding(program, session)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings); host.claim(HOLDER, A);
        }
        Fixture(EffectState a) { this(a, List.of()); }
        ResourceRequests.Receipt execute(String caller, RuleEngine.WorldRequest request) { return host.execute(caller, request); }
        Action action(Function<Evaluation, ResourceRequests.Request> request) {
            return new Action() {
                public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(request.apply(e)); }
                public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult result) {
                    var receipt = (ResourceRequests.Receipt) result; replies.add(receipt);
                    assertEquals(e.context().command(ResourceRequests.Request.class), receipt.request());
                    return new RuleEngine.Local<>(e.state(), receipt, List.of());
                }
            };
        }
        EffectState state(String domain) { return sessions.get(domain).state().engine().domain(); }
        void move(String domain) { host.commit(host.prepareTransfer(HOLDER, domain)); }
        void request(String caller, ResourceRequests.Request command) {
            sessions.get(caller).start(state(caller).buffs().timeMicros(), new RuleEngine.Signal("test:request", new Input(command)));
        }
        double value(String domain, ResourceState.Key key) { return state(domain).resources().get(key).value(); }
    }
    static EffectProgram.Instruction refund(String bind, double fraction) {
        return new EffectProgram.Instruction(new Action() {
            public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
            public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
                var original = (Resources.CostResult) e.context().bindings().get("paid");
                return new RuleEngine.Await<>(new ResourceMutations.RefundPayment(original.receipt().operation(), fraction, ORIGIN));
            }
        }, bind);
    }
    @Test void initializeFollowsAnEmptyMigratedPartitionAndNeverRefillsItsExistingAccount() {
        var f = new Fixture(empty(0)); f.move(B);
        f.request(A, new ResourceMutations.Initialize(ENERGY, ORIGIN));
        var first = (ResourceMutations.Initialized) f.replies.getFirst(); assertTrue(first.created());
        assertEquals(2, f.value(B, ENERGY)); assertEquals(50_000_000, first.account().timeMicros());
        assertTrue(f.state(A).resources().isEmpty()); assertTrue(f.observed.isEmpty());
        assertEquals(1, f.sessions.get(B).state().engine().facts().size());
        f.request(A, new ResourceMutations.Spend(ENERGY, .75, ORIGIN));
        f.request(A, new ResourceMutations.Initialize(ENERGY, ORIGIN));
        assertFalse(((ResourceMutations.Initialized) f.replies.getLast()).created()); assertEquals(1.25, f.value(B, ENERGY));
        assertEquals(3, f.sessions.get(B).state().engine().facts().size());
        f.host.drain(4); assertEquals(3, f.observed.size());
        assertTrue(f.observed.stream().allMatch(e -> e.timeMicros() == 50_000_000));
        f.move(A); f.request(B, new ResourceMutations.Initialize(ENERGY, ORIGIN));
        assertEquals(1.25, f.value(A, ENERGY)); assertFalse(((ResourceMutations.Initialized) f.replies.getLast()).created());
    }
    @Test void spendingFullChargesAndCapacityChangesReportOnlyTheActuallyCommittedResults() {
        var f = new Fixture(initial(0)); f.move(B);
        f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)); assertEquals(.1, f.value(B, ENERGY));
        var spent = (ResourceMutations.Spent) f.replies.getLast(); assertEquals(.3, spent.receipt().paid());
        f.request(A, new ResourceMutations.FullCharges(ENERGY, 4, ORIGIN));
        var full = (ResourceMutations.FullGranted) f.replies.getLast(); assertEquals(2.9, full.grant().credited()); assertEquals(1.1, full.grant().overflow());
        f.request(A, new ResourceMutations.Resize(ENERGY, .75, ORIGIN));
        var resized = (ResourceMutations.Resized) f.replies.getLast(); assertEquals(2.25, resized.resize().discarded()); assertEquals(.75, f.value(B, ENERGY));
        f.request(A, new ResourceMutations.Resize(ENERGY, .75, ORIGIN)); assertTrue(ResourceRequests.facts(f.replies.getLast()).isEmpty());
        assertTrue(f.observed.isEmpty()); f.host.drain(2); assertEquals(6, f.observed.size());
        var fact = (EffectEvent) f.observed.get(4).signal().payload();
        assertEquals("chorus:resource_capacity_changed", f.observed.get(4).signal().type());
        assertEquals(ORIGIN, fact.source()); assertEquals(A, fact.references().get("resource_caller_domain")); assertEquals(B, fact.references().get("resource_recipient_domain"));
        assertEquals(.75, fact.numbers().get("capacity").value());
    }
    @Test void failedAndFreeSpendsCreateNoRecipientFactsAndDifferentCallersNeverSharePaymentIdentity() {
        var f = new Fixture(initial(0)); f.move(B);
        f.request(A, new ResourceMutations.Spend(ENERGY, 9, ORIGIN));
        var failed = (ResourceMutations.Spent) f.replies.getLast(); assertFalse(failed.spent().succeeded()); assertEquals(0, failed.receipt().paid());
        f.request(A, new ResourceMutations.Spend(ENERGY, 0, ORIGIN)); assertTrue(((ResourceMutations.Spent) f.replies.getLast()).spent().succeeded());
        assertEquals(.4, f.value(B, ENERGY)); assertFalse(f.sessions.get(B).inboxPending());
        var a = new Fixture(initial(0)); var b = new Fixture(initial(0)); a.move(B); b.move(B);
        a.request(A, new ResourceMutations.Spend(ENERGY, .1, ORIGIN)); b.request(B, new ResourceMutations.Spend(ENERGY, .1, ORIGIN));
        var left = (ResourceMutations.Spent) a.replies.getFirst(); var right = (ResourceMutations.Spent) b.replies.getFirst();
        assertEquals(left.operation(), right.operation()); assertNotEquals(left.receipt().operation(), right.receipt().operation());
    }
    @Test void remoteRefundUsesTheFramesLatestClaimAndCannotBeReusedByTheNextEvent() {
        var f = new Fixture(initial(0), List.of(refund("one", .5), refund("two", .5), refund("three", 1))); f.move(B);
        f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)); assertEquals(.4, f.value(B, ENERGY));
        // Recipient events preserve each refund independently, including the exhausted claim's zero-credit result.
        var facts = f.sessions.get(B).state().engine().facts(); assertEquals(7, facts.size());
        var refunded = facts.stream().filter(e -> e.signal().type().equals("chorus:resource_refunded")).map(e -> (EffectEvent) e.signal().payload()).toList();
        assertEquals(List.of(.15, .15, 0.0), refunded.stream().map(e -> e.numbers().get("credited").value()).toList());
        var cost = ((Resources.CostResult) f.replies.getFirst()).receipt();
        var before = f.state(B);
        assertThrows(IllegalArgumentException.class, () -> f.request(A, new ResourceMutations.RefundPayment(cost.operation(), 1, ORIGIN)));
        assertSame(before, f.state(B)); assertThrows(IllegalStateException.class, () -> f.sessions.get(A).checkpoint());
    }
    @Test void sealingALiveRemotePaymentMovesItsQuotaIntoTheIssuersRetainedLedger() {
        var handle = new ArrayList<RetainedCosts.Handle>();
        var seal = new Action() {
            public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
            public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
                var paid = ((Resources.CostResult) e.context().bindings().get("paid")).receipt();
                var result = RetainedCosts.retain(e.state(), "remote", paid, 2_000_000); handle.add((RetainedCosts.Handle) result.result()); return result;
            }
        };
        var f = new Fixture(initial(0), List.of(new EffectProgram.Instruction(seal, "seal"), refund("closed", 1))); f.move(B);
        f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)); assertEquals(.1, f.value(B, ENERGY));
        assertEquals(0, f.state(A).retainedCosts().get("remote").receipt().refundClaimed());
        assertEquals(.3, handle.getFirst().receipt().refundClaimed());
        var result = f.host.refund(A, handle.getFirst(), 1); assertEquals(.3, result.result().orElseThrow().grant().credited()); assertEquals(.4, f.value(B, ENERGY));
    }
    @Test void refundOverflowConsumesItsEntitlementEvenWhenCapacityBecomesAvailableAgain() {
        var fill = new Action() {
            public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
            public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new ResourceMutations.FullCharges(ENERGY, 4, ORIGIN)); }
        };
        var spendAgain = new Action() {
            public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
            public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new ResourceMutations.Spend(ENERGY, .2, ORIGIN)); }
        };
        var f = new Fixture(initial(0), List.of(new EffectProgram.Instruction(fill, "full"), refund("overflow", 1),
                new EffectProgram.Instruction(spendAgain, "later"), refund("exhausted", 1))); f.move(B);
        f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)); assertEquals(2.8, f.value(B, ENERGY));
        var refunds = f.sessions.get(B).state().engine().facts().stream().filter(e -> e.signal().type().equals("chorus:resource_refunded"))
                .map(e -> (EffectEvent) e.signal().payload()).toList();
        assertEquals(2, refunds.size()); assertEquals(.3, refunds.getFirst().numbers().get("overflow").value());
        assertTrue(refunds.stream().allMatch(e -> e.numbers().get("claimed").value() == .3 && e.numbers().get("credited").value() == 0));
    }
    @Test void completionCannotEraseOrRewindAnAlreadyCommittedWorldRefund() {
        for (boolean returnOldReceipt : List.of(false, true)) {
            var lossy = new Action() {
                public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
                    var paid = (Resources.CostResult) e.context().bindings().get("paid");
                    return new RuleEngine.Await<>(new ResourceMutations.RefundPayment(paid.receipt().operation(), 1, ORIGIN));
                }
                public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult ignored) {
                    return new RuleEngine.Local<>(e.state(), returnOldReceipt ? e.context().bindings().get("paid") : RuleEngine.Empty.INSTANCE, List.of());
                }
            };
            var f = new Fixture(initial(0), List.of(new EffectProgram.Instruction(lossy, "discarded"), refund("again", 1))); f.move(B);
            f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)); assertEquals(.4, f.value(B, ENERGY));
            var facts = f.sessions.get(B).state().engine().facts().stream().filter(e -> e.signal().type().equals("chorus:resource_refunded"))
                    .map(e -> ((EffectEvent) e.signal().payload()).numbers().get("credited").value()).toList();
            assertEquals(List.of(.3, 0.0), facts);
        }
    }
    @Test void linkedCompletionAndCyclicPacketsPublishBothAccountsBeforeAnyRecipientRule() {
        var start = account(initial(0), HOLDER, "progress", 1, 1); var f = new Fixture(start); f.move(B);
        f.request(A, new ResourceMutations.CompleteRecharge(PROGRESS, USES, Optional.of(1.5), ORIGIN));
        assertEquals(0, f.value(B, PROGRESS)); assertEquals(2.5, f.value(B, USES)); assertTrue(f.observed.isEmpty());
        var linked = (ResourceMutations.Recharged) f.replies.getLast(); assertEquals(3, ResourceRequests.facts(linked).size());
        f.request(A, new ResourceMutations.CompleteRecharge(PROGRESS, USES, Optional.empty(), ORIGIN));
        assertFalse(((ResourceMutations.Recharged) f.replies.getLast()).recharge().completed());
        f.request(A, new ResourceMutations.GrantCyclic(PROGRESS, USES, 50, 1_000_000, 2, ORIGIN));
        var cyclic = (ResourceMutations.CyclicGranted) f.replies.getLast(); assertEquals(1, cyclic.recharge().cycles());
        assertEquals(999_999, cyclic.recharge().overflow()); assertEquals(1.5, cyclic.recharge().uses().overflow());
        assertEquals(0, f.value(B, PROGRESS)); assertEquals(3, f.value(B, USES));
        f.host.drain(1); assertFalse(f.sessions.get(B).inboxPending());
    }
    @Test void invalidSecondAccountOrDefinitionFailsBeforeEitherAccountIsWritten() {
        var start = account(initial(0), HOLDER, "progress", 1, 1); var worlds = move(worlds(start, empty(90)), B);
        var id = new RuleEngine.OperationId(1, 0, 0);
        var absent = new ResourceState.Key(HOLDER, "test:missing");
        assertThrows(IllegalArgumentException.class, () -> ResourceMutations.apply(worlds, A, id, new ResourceMutations.CompleteRecharge(PROGRESS, absent, Optional.empty(), ORIGIN), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceMutations.apply(worlds, A, id, new ResourceMutations.Resize(USES, 4, ORIGIN), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceMutations.apply(worlds, A, id, new ResourceMutations.Initialize(absent, ORIGIN), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceMutations.apply(worlds, A, id, new ResourceMutations.RefundPayment("forged", 1, ORIGIN), Map.of()));
        assertEquals(1, worlds.domain(B).resources().get(PROGRESS).value()); assertEquals(1, worlds.domain(B).resources().get(USES).value());
        assertThrows(IllegalArgumentException.class, () -> new ResourceMutations.GrantCyclic(PROGRESS, new ResourceState.Key("other", USES.resource()), 1, 1, 1, ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> new ResourceMutations.Resize(ENERGY, 0, ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> new ResourceMutations.Spend(ENERGY, Double.NaN, ORIGIN));
    }
    @Test void unknownCallerOutcomeKeepsPaidBalanceAndRecipientFactsWithoutPermittingReplay() {
        var f = new Fixture(initial(0)); f.move(B); f.unknown = true;
        assertThrows(IllegalStateException.class, () -> f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)));
        assertEquals(.1, f.value(B, ENERGY)); assertEquals(2, f.sessions.get(B).state().engine().facts().size());
        assertThrows(IllegalStateException.class, () -> f.host.drain(5));
        assertThrows(IllegalStateException.class, () -> f.request(A, new ResourceMutations.Spend(ENERGY, .3, ORIGIN)));
        assertEquals(.1, f.value(B, ENERGY)); assertThrows(IllegalStateException.class, () -> f.sessions.get(B).checkpoint());
    }
    @Test void everyNewCommandAndReceiptRoundTripsAndRejectsConflictingPaymentEvidence() {
        var worlds = worlds(account(initial(0), HOLDER, "progress", 1, 1), empty(0)); var id = new RuleEngine.OperationId(9, 2, 1);
        var requests = List.<ResourceMutations.Request>of(new ResourceMutations.Initialize(ENERGY, ORIGIN), new ResourceMutations.Spend(ENERGY, .2, ORIGIN),
                new ResourceMutations.FullCharges(ENERGY, 2, ORIGIN), new ResourceMutations.Resize(ENERGY, .5, ORIGIN),
                new ResourceMutations.CompleteRecharge(PROGRESS, USES, Optional.of(.5), ORIGIN), new ResourceMutations.GrantCyclic(PROGRESS, USES, 2, 3, 1, ORIGIN));
        for (var request : requests) {
            var receipt = ResourceMutations.apply(worlds, B, id, request, Map.of()).receipt();
            assertEquals(request, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(request), request.getClass()));
            assertEquals(receipt, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(receipt), receipt.getClass()));
        }
        var paid = ResourceMutations.apply(worlds, B, id, requests.get(1), Map.of()); var spent = (ResourceMutations.Spent) paid.receipt();
        var refund = new ResourceMutations.RefundPayment(spent.receipt().operation(), 1, ORIGIN);
        var refunded = ResourceMutations.apply(paid.worlds(), B, new RuleEngine.OperationId(9, 3, 1), refund, Map.of(spent.retentionKey(), spent)).receipt();
        assertEquals(refund, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(refund), refund.getClass()));
        assertEquals(refunded, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(refunded), refunded.getClass()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceMutations.Spent(spent.request(), spent.before(), spent.spent(), spent.lease(), A, spent.operation()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceMutations.Spent(new ResourceMutations.Spend(ENERGY, .1, ORIGIN), spent.before(), spent.spent(), spent.lease(), B, spent.operation()));
    }
}
