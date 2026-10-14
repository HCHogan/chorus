package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.ResourceAuthorityTest.A;
import static com.imdomestic.chorus.effect.ResourceAuthorityTest.B;
import static com.imdomestic.chorus.effect.ResourceAuthorityTest.C;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceRecipientFactsTest {
    static final String ENERGY = "test:energy";
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("attacker", "old-projectile", "gun", "skill", Set.of("test:credit"));
    record Input(ResourceRequests.Request request) implements RuleEngine.Payload {}
    record Probe(String domain) implements RuleEngine.WorldCommand {}
    record Seen(String domain, RuleEngine.Event event, EffectEvent fact) {}
    static ResourceState.Key key(String holder) { return new ResourceState.Key(holder, ENERGY); }
    static final class Fixture {
        final Map<String, EffectSession> sessions = new TreeMap<>();
        final List<Seen> seen = new ArrayList<>();
        final Map<String, String> feedback = new HashMap<>();
        final ResourceSessionAuthority host;
        int feedbackLimit = Integer.MAX_VALUE, generated, worldWrites;
        boolean repeatRequest, failAfterCredit;
        String failRecipient = "";
        Fixture() {
            var send = new Action() {
                public ResultShape validate(Validation ignored) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(((Input) e.context().event().signal().payload()).request()); }
            };
            var observe = new Action() {
                public ResultShape validate(Validation ignored) { return ResultShape.EMPTY; }
                public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
                    String domain = e.origin().source(); var event = e.context().event(); var fact = e.event(); seen.add(new Seen(domain, event, fact));
                    if (domain.equals(failRecipient)) return new RuleEngine.Await<>(new Probe(domain));
                    if (event.signal().type().equals("chorus:resource_granted") && feedback.containsKey(domain) && generated < feedbackLimit) {
                        generated++; return new RuleEngine.Await<>(new ResourceRequests.Grant(key(feedback.get(domain)), 1, 1, fact.source()));
                    }
                    return new RuleEngine.Local<>(e.state(), RuleEngine.Empty.INSTANCE, List.of());
                }
            };
            var rules = new ArrayList<EffectProgram.Rule>();
            rules.add(new EffectProgram.Rule("send", "test:send", new Condition.Constant(true), List.of(new EffectProgram.Instruction(send, ""))));
            for (String type : List.of("chorus:resource_granted", "chorus:resource_refunded", "chorus:resource_changed"))
                rules.add(new EffectProgram.Rule(type.substring(7), type, new Condition.Constant(true), List.of(new EffectProgram.Instruction(observe, ""))));
            var resource = new ResourceDefinition(ENERGY, 10, 0, 0, List.of(), Optional.empty());
            var program = new CompiledEffects(new EffectProgram("recipient-facts", List.of(), List.of(new EffectProgram.Bundle("test:rules", EffectProgram.Scope.SOURCE, rules, List.of())), List.of(), Optional.empty(), List.of(resource)));
            for (var entry : Map.of(A, 1000L, B, 5000L, C, 0L).entrySet()) {
                String domain = entry.getKey();
                var state = ResourceAuthorityTest.empty(entry.getValue()).withResource(new ResourceState(key(domain), 0, 10, entry.getValue()))
                        .withSource(new EffectSource("rules", "test:rules", domain, new BuffInstance.Origin(domain, domain, "", ""), Set.of()));
                sessions.put(domain, new EffectSession(program, EffectCheckpointTest.clock(), 1, state, request -> dispatch(domain, request)));
            }
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>();
            sessions.forEach((domain, session) -> bindings.put(domain, new ResourceSessionAuthority.Binding(program, session)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings);
            for (String domain : List.of(A, B, C)) host.claim(domain, domain);
        }
        RuleEngine.ActionResult dispatch(String domain, RuleEngine.WorldRequest request) {
            if (request.command() instanceof Probe) { worldWrites++; throw new IllegalStateException("recipient write outcome unknown"); }
            var result = host.execute(domain, request);
            if (repeatRequest) assertSame(result, host.execute(domain, request));
            if (failAfterCredit) throw new IllegalStateException("caller receipt delivery failed");
            return result;
        }
        EffectState state(String domain) { return sessions.get(domain).state().engine().domain(); }
        void request(String caller, ResourceRequests.Request command) { sessions.get(caller).start(state(caller).buffs().timeMicros(), new RuleEngine.Signal("test:send", new Input(command))); }
        void grant(String caller, String holder, double value) { request(caller, new ResourceRequests.Grant(key(holder), value, value, ORIGIN)); }
        List<Seen> grants() { return seen.stream().filter(value -> value.event().signal().type().equals("chorus:resource_granted")).toList(); }
    }
    @Test void remoteFactsAreCommittedOnceInOrderWithOriginalCreditAndRecipientClock() {
        var f = new Fixture(); f.repeatRequest = true; f.grant(A, B, 1); f.grant(A, B, 2);
        assertTrue(f.seen.isEmpty()); assertEquals(3, f.state(B).resources().get(key(B)).value());
        var inbox = f.sessions.get(B).state(); assertEquals(4, inbox.engine().facts().size());
        assertEquals(5, inbox.engine().nextEvent()); assertTrue(f.sessions.get(B).inboxPending());
        assertThrows(IllegalStateException.class, f.sessions.get(B)::checkpoint);
        assertThrows(IllegalStateException.class, () -> f.host.prepareTransfer(B, C));
        assertEquals(new ResourceSessionAuthority.Drained(1, false), f.host.drain(8));
        assertEquals(List.of(1.0, 3.0), f.grants().stream().map(value -> value.fact().numbers().get("after").value()).toList());
        f.seen.forEach(value -> { assertEquals(B, value.domain()); assertEquals(5000, value.event().timeMicros()); assertEquals(ORIGIN, value.fact().source());
            assertEquals(A, value.fact().references().get("resource_caller_domain")); assertEquals(B, value.fact().references().get("resource_recipient_domain")); });
        assertNotEquals(f.grants().getFirst().fact().references().get("resource_operation"), f.grants().getLast().fact().references().get("resource_operation"));
        assertEquals(new ResourceSessionAuthority.Drained(0, false), f.host.drain(8)); assertEquals(4, f.seen.size()); f.sessions.get(B).checkpoint();
    }
    @Test void recipientProcessesFactsBeforeItsNextInputAndAuthorityMove() {
        var f = new Fixture(); f.grant(A, B, 1);
        f.sessions.get(B).observe(5100, List.of()); assertEquals(2, f.seen.size()); assertTrue(f.seen.stream().allMatch(value -> value.event().timeMicros() == 5000));
        assertEquals(5100, f.state(B).buffs().timeMicros()); f.host.commit(f.host.prepareTransfer(B, C));
        f.grant(A, B, 1); assertEquals(2, f.seen.size()); assertTrue(f.sessions.get(C).inboxPending());
        f.host.drain(4); assertEquals(C, f.grants().getLast().domain()); assertEquals(0, f.grants().getLast().event().timeMicros());
        assertEquals(2, f.state(C).resources().get(key(B)).value());
    }
    @Test void sameDomainFactsKeepTheActiveFramesCausalRootAndCanProduceFurtherCredits() {
        var f = new Fixture(); f.feedback.put(A, A); f.feedbackLimit = 4; f.grant(A, A, 1);
        assertEquals(5, f.grants().size()); assertEquals(5, f.state(A).resources().get(key(A)).value());
        assertTrue(f.seen.stream().allMatch(value -> value.domain().equals(A) && value.event().root() == 1 && value.event().parent().isPresent()));
        assertFalse(f.sessions.get(A).inboxPending()); assertTrue(f.sessions.get(A).state().idle());
        assertEquals(new ResourceSessionAuthority.Drained(0, false), f.host.drain(1));
    }
    @Test void feedbackYieldsWithoutDroppingWorkAndDoesNotStarveAThirdRecipient() {
        var f = new Fixture(); f.feedback.put(A, B); f.feedback.put(B, A);
        f.grant(A, B, 1); f.grant(A, C, 1);
        assertEquals(new ResourceSessionAuthority.Drained(2, true), f.host.drain(2));
        assertEquals(List.of(B, C), f.grants().stream().map(Seen::domain).toList());
        for (int i = 0; i < 10; i++) assertEquals(new ResourceSessionAuthority.Drained(4, true), f.host.drain(4));
        assertEquals(42, f.grants().size()); assertTrue(f.sessions.values().stream().anyMatch(EffectSession::inboxPending));
        assertEquals(41, f.generated); assertEquals(1, f.state(C).resources().get(key(C)).value());
        f.feedback.clear(); assertFalse(f.host.drain(4).pending());
        f.sessions.values().forEach(EffectSession::checkpoint);
    }
    @Test void unknownCallerOutcomeRetainsRecipientFactsAndBlocksAutomaticDrain() {
        var f = new Fixture(); f.failAfterCredit = true;
        assertThrows(IllegalStateException.class, () -> f.grant(A, B, 1));
        assertEquals(1, f.state(B).resources().get(key(B)).value()); assertEquals(2, f.sessions.get(B).state().engine().facts().size());
        assertTrue(f.seen.isEmpty()); assertThrows(IllegalStateException.class, () -> f.host.drain(1)); assertTrue(f.seen.isEmpty());
        assertThrows(IllegalStateException.class, f.sessions.get(A)::checkpoint); assertThrows(IllegalStateException.class, f.sessions.get(B)::checkpoint);
    }
    @Test void unknownRecipientWriteIsNeverRepeatedAndOtherInboxesRemainQueued() {
        var f = new Fixture(); f.grant(A, B, 1); f.grant(A, C, 1); f.failRecipient = B;
        assertThrows(IllegalStateException.class, () -> f.host.drain(4)); assertEquals(1, f.worldWrites);
        assertEquals(1, f.state(B).resources().get(key(B)).value()); assertTrue(f.sessions.get(B).state().engine().pending().isPresent());
        assertEquals(2, f.sessions.get(C).state().engine().facts().size()); assertTrue(f.sessions.get(C).inboxPending());
        assertThrows(IllegalStateException.class, () -> f.host.drain(4)); assertEquals(1, f.worldWrites);
        assertThrows(IllegalStateException.class, f.sessions.get(B)::drain); assertThrows(IllegalStateException.class, f.sessions.get(C)::checkpoint);
    }
    @Test void unavailableRefundAndReadProduceNoInboxWhileARealRefundRetainsItsPayment() {
        var f = new Fixture(); f.host.grant(key(A), 2, 2);
        var before = f.sessions.get(A).state(); var paid = Resources.spend(f.state(A).resources().get(key(A)), "payment", 1);
        var retained = RetainedCosts.retain(f.state(A).withResource(paid.after()), "refund", paid.receipt(), 100_000);
        var handle = (RetainedCosts.Handle) retained.result(); EffectSession.commitDomains(List.of(f.sessions.get(A).prepareDomain(before, retained.state())));
        f.host.commit(f.host.prepareTransfer(A, B));
        f.request(C, new ResourceRequests.Read(key(A))); assertFalse(f.sessions.get(B).inboxPending());
        f.request(C, new ResourceRequests.Refund(A, handle, 1, ORIGIN)); f.host.drain(4);
        var refund = f.seen.stream().filter(value -> value.event().signal().type().equals("chorus:resource_refunded")).findFirst().orElseThrow();
        assertEquals(B, refund.domain()); assertEquals("payment", refund.fact().references().get("payment")); assertEquals(1, refund.fact().numbers().get("credited").value());
        f.sessions.get(A).observe(100_000, List.of()); f.request(C, new ResourceRequests.Refund(A, handle, 1, ORIGIN));
        assertFalse(f.sessions.values().stream().anyMatch(EffectSession::inboxPending));
    }
}
