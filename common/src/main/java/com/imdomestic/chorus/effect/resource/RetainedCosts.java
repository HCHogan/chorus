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
    public record Handle(String id, Resources.CostReceipt receipt, long startedAt, long dueAt)
            implements Resources.CostResult {
        public Handle {
            Objects.requireNonNull(id); Objects.requireNonNull(receipt);
            if (id.isBlank() || startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE
                    || receipt.refundClaimed() != receipt.paid()) throw new IllegalArgumentException("Invalid retained cost handle");
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
        if (state.retainedCosts().containsKey(id)) throw new IllegalArgumentException("Duplicate retained cost identity");
        var handle = new Handle(id, new Resources.CostReceipt(cost.operation(), cost.account(), cost.paid(), cost.paid()), state.buffs().timeMicros(), dueAt);
        return new RuleEngine.Local<>(state.withRetainedCost(new Entry(handle, cost)), handle, List.of());
    }
    private static Optional<Entry> live(EffectState state, Handle handle) {
        var entry = state.retainedCosts().get(handle.id());
        if (entry != null && !entry.handle().equals(handle)) throw new IllegalArgumentException("Conflicting retained cost handle");
        return entry == null || state.buffs().timeMicros() >= handle.dueAt() ? Optional.empty() : Optional.of(entry);
    }
    public static RuleEngine.Local<EffectState> refund(EffectState state, Handle handle, double fraction) {
        Numbers.fraction(fraction, "refund fraction");
        var entry = live(state, handle);
        if (entry.isEmpty()) return new RuleEngine.Local<>(state, new Refunded(Optional.empty()), List.of());
        var cost = entry.orElseThrow().receipt(); var account = state.resources().get(cost.account());
        if (account == null) throw new IllegalArgumentException("Missing paid resource account: " + cost.account());
        var result = Resources.refund(account, cost, fraction);
        var next = state.withResource(result.grant().after()).withRetainedCost(new Entry(handle, result.receipt()));
        return new RuleEngine.Local<>(next, new Refunded(Optional.of(result)), List.of());
    }
    public static RuleEngine.Local<EffectState> close(EffectState state, Handle handle) {
        boolean removed = live(state, handle).isPresent();
        return new RuleEngine.Local<>(state.withoutRetainedCost(handle.id()), new Closed(removed), List.of());
    }
}
