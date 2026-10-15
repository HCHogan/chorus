package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** A bounded transfer of a frame's remaining refund entitlement into the domain state. */
public final class RetainedCosts {
    private RetainedCosts() {}
    // The sealed receipt revokes the original frame-local entitlement. The handle itself is
    // capturable, but refunds always consult the one live entry, never its captured snapshot.
    public record Handle(String id, Resources.CostReceipt receipt, long startedAt, long dueAt, Optional<String> issuer)
            implements Resources.CostResult {
        public Handle {
            Objects.requireNonNull(id); Objects.requireNonNull(receipt);
            Objects.requireNonNull(issuer);issuer.ifPresent(value->new ResourceAuthority.Lease(value,1));
            if (id.isBlank() || startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE
                    || receipt.refundClaimed() != receipt.paid()) throw new IllegalArgumentException("Invalid retained cost handle");
        }
        public Handle(String id,Resources.CostReceipt receipt,long startedAt,long dueAt) { this(id,receipt,startedAt,dueAt,Optional.empty()); }
        public Handle issuedBy(String domain) { return issuer.isPresent()?this:new Handle(id,receipt,startedAt,dueAt,Optional.of(domain)); }
        public void requireIssuer(String domain) {
            if(issuer.isPresent()&&!issuer.orElseThrow().equals(domain))throw new IllegalArgumentException("Retained cost belongs to another issuing domain");
        }
    }
    public record Entry(Handle handle, Resources.CostReceipt receipt) {
        public Entry {
            Objects.requireNonNull(handle); Objects.requireNonNull(receipt);
            var sealed = handle.receipt();
            if (!sealed.operation().equals(receipt.operation()) || !sealed.account().equals(receipt.account()) || sealed.paid() != receipt.paid())
                throw new IllegalArgumentException("Retained cost differs from its payment");
        }
    }
    public record Refunded(Optional<Resources.RefundResult> refund) implements RuleEngine.ActionResult {
        public Refunded { Objects.requireNonNull(refund); }
    }
    public record Closed(boolean removed) implements RuleEngine.ActionResult {}
    public static RuleEngine.Local<EffectState> retain(EffectState state, String id, Resources.CostReceipt cost, long dueAt) {
        return retain(state,id,cost,dueAt,Optional.empty());
    }
    public static RuleEngine.Local<EffectState> retain(EffectState state,String id,Resources.CostReceipt cost,long dueAt,Optional<String> issuer) {
        if (state.retainedCosts().containsKey(id)) throw new IllegalArgumentException("Duplicate retained cost identity");
        var handle = new Handle(id, new Resources.CostReceipt(cost.operation(), cost.account(), cost.paid(), cost.paid()), state.buffs().timeMicros(), dueAt,issuer);
        return new RuleEngine.Local<>(state.withRetainedCost(new Entry(handle, cost)), handle, List.of());
    }
    private static Optional<Entry> live(EffectState state, Handle handle) {
        var entry = state.retainedCosts().get(handle.id());
        if (entry != null) {
            var expected=entry.handle();
            if(!expected.id().equals(handle.id())||!expected.receipt().equals(handle.receipt())||expected.startedAt()!=handle.startedAt()||expected.dueAt()!=handle.dueAt()
                    ||expected.issuer().isPresent()&&handle.issuer().isPresent()&&!expected.issuer().equals(handle.issuer()))
                throw new IllegalArgumentException("Conflicting retained cost handle");
        }
        return entry == null || state.buffs().timeMicros() >= handle.dueAt() ? Optional.empty() : Optional.of(entry);
    }
    public static RuleEngine.Local<EffectState> refund(EffectState state, Handle handle, double fraction) {
        var authorization = authorize(state, handle, fraction);
        if (authorization.claim().isEmpty()) return new RuleEngine.Local<>(state, new Refunded(Optional.empty()), List.of());
        var claim = authorization.claim().orElseThrow(); var account = state.resources().get(claim.before().account());
        if (account == null) throw new IllegalArgumentException("Missing paid resource account: " + claim.before().account());
        var result = Resources.applyRefund(account, claim);
        return new RuleEngine.Local<>(authorization.state().withResource(result.grant().after()), new Refunded(Optional.of(result)), List.of());
    }
    /** The issuer's ledger consumes the claim; the supervisor can credit its stable account in another domain. */
    public record Authorization(EffectState state, Optional<Resources.RefundClaim> claim) {
        public Authorization { Objects.requireNonNull(state); Objects.requireNonNull(claim); }
    }
    public static Authorization authorize(EffectState state, Handle handle, double fraction) {
        Numbers.fraction(fraction, "refund fraction");
        var entry = live(state, handle);
        if (entry.isEmpty()) return new Authorization(state, Optional.empty());
        var claim = Resources.claimRefund(entry.orElseThrow().receipt(), fraction);
        var canonical = entry.orElseThrow().handle();
        if (canonical.issuer().isEmpty() && handle.issuer().isPresent()) canonical = handle;
        return new Authorization(state.withRetainedCost(new Entry(canonical, claim.after())), Optional.of(claim));
    }
    public static RuleEngine.Local<EffectState> close(EffectState state, Handle handle) {
        boolean removed = live(state, handle).isPresent();
        return new RuleEngine.Local<>(state.withoutRetainedCost(handle.id()), new Closed(removed), List.of());
    }
}
