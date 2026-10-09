package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import com.imdomestic.chorus.stat.CalculationProfile;
import com.imdomestic.chorus.stat.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure shield proposal and compare-before-write receipt. No vanilla health is represented here. */
public final class ShieldDamage {
    private ShieldDamage() {}
    public record Bound(BuffInstance instance, String capacity, double multiplier, Optional<CalculationProfile.Result> attackScaling, Optional<DamageBasis.Suppression> factorSuppression) {
        public Bound {
            Objects.requireNonNull(instance); Objects.requireNonNull(capacity);
            Numbers.nonnegative(instance.components().numbers().get(capacity), "shield capacity");
            Numbers.nonnegative(multiplier, "shield multiplier");
            Objects.requireNonNull(attackScaling);
            Objects.requireNonNull(factorSuppression);
            attackScaling.ifPresent(result -> {
                if (!result.output().unit().equals(Unit.MULTIPLIER)) throw new IllegalArgumentException("Shield attack profile must output multiplier");
                Numbers.nonnegative(result.output().value(), "shield attack multiplier");
            });
        }
        public Bound(BuffInstance instance, String capacity, double multiplier, Optional<CalculationProfile.Result> attackScaling) { this(instance, capacity, multiplier, attackScaling, Optional.empty()); }
        public Bound(BuffInstance instance, String capacity, double multiplier) { this(instance, capacity, multiplier, Optional.empty()); }
    }
    public record LayerHit(BuffInstance before, String capacity, ShieldPlan.Hit trace, Optional<CalculationProfile.Result> attackScaling, Optional<DamageBasis.Suppression> factorSuppression) {
        public LayerHit { Objects.requireNonNull(before); Objects.requireNonNull(capacity); Objects.requireNonNull(trace); Objects.requireNonNull(attackScaling); Objects.requireNonNull(factorSuppression); }
        public LayerHit(BuffInstance before, String capacity, ShieldPlan.Hit trace, Optional<CalculationProfile.Result> attackScaling) { this(before, capacity, trace, attackScaling, Optional.empty()); }
        public LayerHit(BuffInstance before, String capacity, ShieldPlan.Hit trace) { this(before, capacity, trace, Optional.empty()); }
        public double after() { return Math.max(0, before.components().numbers().get(capacity) - trace.capacityLoss()); }
    }
    public record Write(BuffInstance before, String capacity, double after) {
        public Write {
            Objects.requireNonNull(before); Objects.requireNonNull(capacity); Numbers.nonnegative(after, "shield capacity after damage");
            Double previous = before.components().numbers().get(capacity);
            if (previous == null || after > previous) throw new IllegalArgumentException("Invalid shield damage write");
        }
    }
    public record Commit(List<Write> writes) implements RuleEngine.Payload {
        public Commit { writes = List.copyOf(writes); }
        public EffectState apply(EffectState state) {
            var store = state.buffs();
            for (var write : writes) {
                var actual = store.active(write.before().key()).orElseThrow(() -> new IllegalStateException("Shield instance disappeared before commit"));
                if (!actual.equals(write.before())) throw new IllegalStateException("Stale shield write: " + actual.key());
                store = Buffs.components(store, actual.key(), actual.components().number(write.capacity(), BuffComponents.Update.SET, write.after()));
            }
            return state.withBuffs(store);
        }
    }
    public record Planned(ShieldPlan budget, List<LayerHit> layers, Commit commit) {
        public Planned { layers = List.copyOf(layers); }
    }
    public static Planned plan(double input, List<Bound> layers) {
        var budget = ShieldPlan.calculate(input, layers.stream().map(layer -> new ShieldPlan.Layer(Long.toString(layer.instance().generation()),
                layer.instance().components().numbers().get(layer.capacity()), layer.multiplier())).toList());
        var results = new ArrayList<LayerHit>(); var writes = new ArrayList<Write>();
        for (var hit : budget.hits()) {
            var bound = layers.stream().filter(layer -> Long.toString(layer.instance().generation()).equals(hit.layer())).findFirst().orElseThrow();
            var result = new LayerHit(bound.instance(), bound.capacity(), hit, bound.attackScaling(), bound.factorSuppression()); results.add(result);
            if (hit.capacityLoss() > 0) writes.add(new Write(bound.instance(), bound.capacity(), result.after()));
        }
        return new Planned(budget, results, new Commit(writes));
    }
    public static EffectState reconcile(EffectState state, RuleEngine.Payload committed) {
        if (committed == RuleEngine.Empty.INSTANCE) return state;
        if (committed instanceof Commit commit) return commit.apply(state);
        throw new IllegalArgumentException("Unknown committed effect writes: " + committed.getClass().getName());
    }
}
