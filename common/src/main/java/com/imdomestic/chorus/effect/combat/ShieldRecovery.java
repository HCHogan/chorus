package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Integrates each layer's pre-boundary rate into its real capacity before expiry or world callbacks. */
public final class ShieldRecovery {
    private ShieldRecovery() {}
    public record Offer(BuffInstance instance, String capacity, double maximum, double perSecond) {
        public Offer {
            Objects.requireNonNull(instance); Objects.requireNonNull(capacity);
            Numbers.nonnegative(maximum, "shield recovery maximum"); Numbers.nonnegative(perSecond, "shield recovery rate");
            Double before = instance.components().numbers().get(capacity);
            if (before == null) throw new IllegalArgumentException("Missing shield capacity");
            Numbers.nonnegative(before, "shield capacity");
        }
        public double before() { return instance.components().numbers().get(capacity); }
    }
    public record Allocation(Offer offer, long from, long until, ShieldRestoration.Receipt receipt) {}
    public record Batch(BuffStore store, List<Allocation> allocations, List<RuleEngine.Signal> facts) {
        public Batch { allocations = List.copyOf(allocations); facts = List.copyOf(facts); }
    }
    /** No sampling is required for a full, disabled or zero-rate layer. Never stores elapsed credit. */
    public static long nextDeadline(List<Offer> offers, long now) {
        long next = Long.MAX_VALUE;
        for (var offer : offers) {
            if (offer.perSecond() == 0 || offer.before() >= offer.maximum()) continue;
            long quantum = Recovery.QUANTUM_MICROS - now % Recovery.QUANTUM_MICROS;
            var micros = BigDecimal.valueOf(offer.maximum()).subtract(BigDecimal.valueOf(offer.before()))
                    .multiply(BigDecimal.valueOf(1_000_000)).divide(BigDecimal.valueOf(offer.perSecond()), 0, RoundingMode.CEILING);
            long remaining = micros.compareTo(BigDecimal.valueOf(quantum)) < 0 ? micros.longValueExact() : quantum;
            if (remaining < Long.MAX_VALUE - now) next = Math.min(next, now + remaining);
        }
        return next;
    }
    public static Batch integrate(BuffStore store, List<Offer> offers, long until) {
        long from = store.timeMicros();
        if (until <= from || until == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid shield recovery interval");
        var allocations = new ArrayList<Allocation>(); var facts = new ArrayList<RuleEngine.Signal>();
        var generations = new HashSet<Long>(); var updated = store;
        for (var offer : offers.stream().sorted(Comparator.comparingLong(o -> o.instance().generation())).toList()) {
            var instance = offer.instance();
            if (!generations.add(instance.generation()) || instance.pausedAt().isPresent()
                    || !store.active(instance.key()).filter(instance::equals).isPresent()) {
                throw new IllegalArgumentException("Duplicate, paused or stale shield recovery offer");
            }
            if (offer.perSecond() == 0 || offer.before() >= offer.maximum()) continue;
            double amount = BigDecimal.valueOf(offer.perSecond()).multiply(BigDecimal.valueOf(until - from)).movePointLeft(6).doubleValue();
            var result = ShieldRestoration.restore(updated, instance.key(), offer.capacity(), amount, offer.maximum());
            updated = result.store(); allocations.add(new Allocation(offer, from, until, result.receipt()));
            for (var fact : ShieldRestoration.facts(result.receipt(), instance.origin())) {
                var event = (EffectEvent) fact.payload(); var tags = new HashSet<>(event.tags()); tags.add("chorus:continuous_shield_recovery");
                var numbers = new HashMap<>(event.numbers());
                numbers.put("interval_start", new Measure(from / 1_000_000.0, Unit.SECOND));
                numbers.put("interval_end", new Measure(until / 1_000_000.0, Unit.SECOND));
                numbers.put("rate", new Measure(offer.perSecond(), Unit.DAMAGE_PER_SECOND));
                facts.add(new RuleEngine.Signal(fact.type(), new EffectEvent(event.actor(), event.victim(), event.source(), tags, numbers, event.flags(), event.references())));
            }
        }
        return new Batch(updated, allocations, facts);
    }
}
