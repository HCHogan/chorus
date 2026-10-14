package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Common event measurements for continuous and explicit account changes. */
public final class ResourceFacts {
    private ResourceFacts() {}
    public static List<RuleEngine.Signal> completed(LinkedRecharge.Result result, BuffInstance.Origin origin) {
        if (!result.completed()) return List.of();
        var facts = new ArrayList<RuleEngine.Signal>();
        facts.add(new RuleEngine.Signal("chorus:resource_changed", change(result.progressBefore(), result.progressAfter(), origin, "recharge_completed")));
        var grant = granted(result.charges(), origin, "linked_recharge");
        facts.add(new RuleEngine.Signal("chorus:resource_granted", grant));
        if (result.charges().credited() > 0) facts.add(new RuleEngine.Signal("chorus:resource_changed", grant));
        return List.copyOf(facts);
    }
    public static EffectEvent cyclic(CyclicRecharge.Result result, double requested, boolean yieldObserved, BuffInstance.Origin origin) {
        var base = change(result.progressBefore(), result.progressAfter(), origin, "cyclic_recharge");
        var numbers = new HashMap<>(base.numbers());
        numbers.put("requested", new Measure(requested, Unit.CHARGE)); numbers.put("scaled", new Measure(result.energy(), Unit.CHARGE));
        numbers.put("credited", new Measure(result.credited(), Unit.CHARGE)); numbers.put("overflow", new Measure(result.overflow(), Unit.CHARGE));
        numbers.put("cycles", new Measure(result.cycles(), Unit.COUNT));
        if (yieldObserved) numbers.put("yield", new Measure(result.yield(), Unit.CHARGE));
        numbers.put("uses_credited", new Measure(result.uses().credited(), Unit.CHARGE)); numbers.put("uses_after", new Measure(result.uses().after().value(), Unit.CHARGE));
        var refs = new HashMap<>(base.references()); refs.put("uses_resource", result.uses().after().key().resource());
        var flags = new HashMap<>(base.flags()); flags.put("yield_observed", yieldObserved);
        return new EffectEvent(base.actor(), base.victim(), origin, base.tags(), numbers, flags, refs);
    }
    public static List<RuleEngine.Signal> cyclicSignals(CyclicRecharge.Result result, double requested, boolean yieldObserved, BuffInstance.Origin origin) {
        var facts = new ArrayList<RuleEngine.Signal>(); var fact = cyclic(result, requested, yieldObserved, origin);
        facts.add(new RuleEngine.Signal("chorus:resource_granted", fact));
        if (result.progressBefore().value() != result.progressAfter().value()) facts.add(new RuleEngine.Signal("chorus:resource_changed", fact));
        if (result.cycles() > 0) {
            facts.add(new RuleEngine.Signal("chorus:recharge_completed", fact));
            var uses = granted(result.uses(), origin, "cyclic_recharge"); facts.add(new RuleEngine.Signal("chorus:resource_granted", uses));
            if (result.uses().credited() > 0) facts.add(new RuleEngine.Signal("chorus:resource_changed", uses));
        }
        return List.copyOf(facts);
    }
    public static EffectEvent change(ResourceState before, ResourceState after, BuffInstance.Origin origin, String reason) {
        return new EffectEvent(origin.owner(), after.key().holder(), origin, Set.of("chorus:resource_" + reason),
                Map.of("before", new Measure(before.value(), Unit.CHARGE), "after", new Measure(after.value(), Unit.CHARGE),
                        "delta", new Measure(java.math.BigDecimal.valueOf(after.value()).subtract(java.math.BigDecimal.valueOf(before.value())).doubleValue(), Unit.CHARGE), "capacity", new Measure(after.capacity(), Unit.CHARGE)),
                Map.of("changed", before.value() != after.value()), Map.of("resource", after.key().resource(), "reason", reason));
    }
    public static EffectEvent passive(ResourceState before, ResourceState after) {
        // Regeneration is account-owned; a sum of modifiers is not attributed to one arbitrary contributor.
        return change(before, after, new BuffInstance.Origin(after.key().holder(), after.key().resource(), "", ""), "regeneration");
    }
    public static EffectEvent resized(Resources.ResizeResult result, BuffInstance.Origin origin) {
        var base = change(result.before(), result.after(), origin, "resize"); var numbers = new HashMap<>(base.numbers());
        numbers.put("before_capacity", new Measure(result.before().capacity(), Unit.CHARGE));
        numbers.put("discarded", new Measure(result.discarded(), Unit.CHARGE));
        var flags = new HashMap<>(base.flags()); flags.put("capacity_changed", result.changed());
        return new EffectEvent(base.actor(), base.victim(), base.source(), base.tags(), numbers, flags, base.references());
    }
    public static EffectEvent granted(ResourceResult result, BuffInstance.Origin origin) {
        return granted(result, origin, "grant");
    }
    public static EffectEvent granted(ResourceResult result, BuffInstance.Origin origin, String reason) {
        var base = change(result.before(), result.after(), origin, reason); var numbers = new HashMap<>(base.numbers());
        numbers.put("requested", new Measure(result.requested(), Unit.CHARGE)); numbers.put("scaled", new Measure(result.scaled(), Unit.CHARGE));
        numbers.put("credited", new Measure(result.credited(), Unit.CHARGE)); numbers.put("overflow", new Measure(result.overflow(), Unit.CHARGE));
        return new EffectEvent(base.actor(), base.victim(), base.source(), base.tags(), numbers, base.flags(), base.references());
    }
    public static EffectEvent refunded(Resources.RefundResult result, BuffInstance.Origin origin) {
        var base = granted(result.grant(), origin, "refund"); var numbers = new HashMap<>(base.numbers());
        numbers.put("paid", new Measure(result.receipt().paid(), Unit.CHARGE));
        numbers.put("allowed", new Measure(result.grant().scaled(), Unit.CHARGE));
        numbers.put("claimed", new Measure(result.receipt().refundClaimed(), Unit.CHARGE));
        numbers.put("remaining", new Measure(Resources.remaining(result.receipt()), Unit.CHARGE));
        var references = new HashMap<>(base.references()); references.put("payment", result.receipt().operation());
        return new EffectEvent(base.actor(), base.victim(), base.source(), base.tags(), numbers, base.flags(), references);
    }
}
