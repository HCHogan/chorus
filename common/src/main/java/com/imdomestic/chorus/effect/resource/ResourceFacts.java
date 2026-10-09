package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Common event measurements for continuous and explicit account changes. */
public final class ResourceFacts {
    private ResourceFacts() {}
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
