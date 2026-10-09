package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.math.BigDecimal;
import java.util.*;

/** Restores the same capacity used by damage; neither overflow nor maximum reductions create stored credit. */
public final class ShieldRestoration {
    private ShieldRestoration() {}
    public record Receipt(BuffInstance instance, double requested, double maximum, double before, double after) implements RuleEngine.ActionResult {
        public Receipt {
            Objects.requireNonNull(instance); Numbers.nonnegative(requested, "shield restoration"); Numbers.nonnegative(maximum, "shield maximum");
            Numbers.nonnegative(before, "shield before"); Numbers.nonnegative(after, "shield after");
            if (after < before || after > Math.max(before, maximum)
                    || BigDecimal.valueOf(after).subtract(BigDecimal.valueOf(before)).compareTo(BigDecimal.valueOf(requested)) > 0) throw new IllegalArgumentException("Invalid restored shield capacity");
        }
        public double effective() { return BigDecimal.valueOf(after).subtract(BigDecimal.valueOf(before)).doubleValue(); }
        public double overflow() { return Math.max(0, BigDecimal.valueOf(requested).subtract(BigDecimal.valueOf(effective())).doubleValue()); }
    }
    public record Result(BuffStore store, Receipt receipt) {}
    public static Result restore(BuffStore store, BuffInstance.Key key, String component, double requested, double maximum) {
        Numbers.nonnegative(requested, "shield restoration"); Numbers.nonnegative(maximum, "shield maximum");
        var instance = store.active(key).orElseThrow(() -> new IllegalArgumentException("Cannot restore a missing shield buff"));
        Double capacity = instance.components().numbers().get(component);
        if (capacity == null) throw new IllegalArgumentException("Missing shield capacity component");
        Numbers.nonnegative(capacity, "shield capacity");
        var before = BigDecimal.valueOf(capacity); var room = BigDecimal.valueOf(maximum).subtract(before).max(BigDecimal.ZERO);
        double after = before.add(BigDecimal.valueOf(requested).min(room)).doubleValue();
        // Rounding to a representable capacity must never create more shield than was requested.
        if (BigDecimal.valueOf(after).subtract(before).compareTo(BigDecimal.valueOf(requested)) > 0) after = Math.max(capacity, Math.nextDown(after));
        var receipt = new Receipt(instance, requested, maximum, capacity, after);
        return new Result(after == capacity ? store : Buffs.components(store, key, instance.components().number(component, BuffComponents.Update.SET, after)), receipt);
    }
    public static List<RuleEngine.Signal> facts(Receipt receipt, BuffInstance.Origin source) {
        if (receipt.effective() == 0) return List.of();
        var instance = receipt.instance();
        var event = new EffectEvent(source.owner(), instance.key().holder(), source, instance.definition().tags(),
                Map.of("requested", new Measure(receipt.requested(), Unit.DAMAGE), "effective", new Measure(receipt.effective(), Unit.DAMAGE),
                        "overflow", new Measure(receipt.overflow(), Unit.DAMAGE), "layer_before", new Measure(receipt.before(), Unit.DAMAGE),
                        "layer_remaining", new Measure(receipt.after(), Unit.DAMAGE), "maximum", new Measure(receipt.maximum(), Unit.DAMAGE)),
                Map.of("full", receipt.after() >= receipt.maximum()), Map.of("shield_definition", instance.definition().id(), "shield_generation", Long.toString(instance.generation())));
        return List.of(new RuleEngine.Signal("chorus:shield_restored", event));
    }
}
