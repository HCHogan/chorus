package com.imdomestic.chorus.effect;

import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.combat.Recovery;
import com.imdomestic.chorus.effect.combat.ShieldRecovery;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.resource.Resources;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Integrates the pre-boundary rate, commits expirations, then emits due facts in stable order. */
public final class EffectClock implements TimelineEngine.Clock<EffectState> {
    public record Rate(double perSecond, List<Double> thresholds) {
        public Rate {
            Numbers.finite(perSecond, "resource rate"); thresholds = List.copyOf(thresholds);
            thresholds.forEach(value -> Numbers.nonnegative(value, "resource threshold"));
        }
    }
    @FunctionalInterface public interface Rates { Rate evaluate(EffectState state, ResourceState account); }
    public record ResourceChanged(ResourceState before, ResourceState after) implements EffectEvent.Carrier {
        @Override public EffectEvent event() { return com.imdomestic.chorus.effect.resource.ResourceFacts.passive(before, after); }
    }
    private final Rates rates;
    private final java.util.function.Function<EffectState, List<Recovery.Offer>> recovery;
    private final java.util.function.Function<EffectState, List<ShieldRecovery.Offer>> shieldRecovery;
    public EffectClock(Rates rates) { this(rates, _ -> List.of(), _ -> List.of()); }
    private EffectClock(Rates rates, java.util.function.Function<EffectState, List<Recovery.Offer>> recovery,
            java.util.function.Function<EffectState, List<ShieldRecovery.Offer>> shieldRecovery) {
        this.rates = Objects.requireNonNull(rates); this.recovery = Objects.requireNonNull(recovery);
        this.shieldRecovery = Objects.requireNonNull(shieldRecovery);
    }
    public EffectClock withRecovery(java.util.function.Function<EffectState, List<Recovery.Offer>> value) { return new EffectClock(rates, value, shieldRecovery); }
    public EffectClock withShieldRecovery(java.util.function.Function<EffectState, List<ShieldRecovery.Offer>> value) { return new EffectClock(rates, recovery, value); }
    public EffectClock withRates(Rates value) { return new EffectClock(value, recovery, shieldRecovery); }
    public Rate resourceRate(EffectState state, ResourceState account) { return rates.evaluate(state, account); }
    @Override public long time(EffectState state) { return state.buffs().timeMicros(); }

    @Override public long nextDeadline(EffectState state) {
        long next = state.buffs().nextDeadline();
        for (var timer : state.timers().values()) next = Math.min(next, timer.dueAt());
        for (var cost : state.retainedCosts().values()) next = Math.min(next, cost.handle().dueAt());
        next = Math.min(next, ShieldRecovery.nextDeadline(shieldRecovery.apply(state), time(state)));
        if (recovery.apply(state).stream().anyMatch(offer -> offer.perSecond() > 0)) {
            long remaining = Recovery.QUANTUM_MICROS - time(state) % Recovery.QUANTUM_MICROS;
            if (remaining < Long.MAX_VALUE - time(state)) next = Math.min(next, time(state) + remaining);
        }
        for (var account : state.resources().values()) {
            Rate rate = rates.evaluate(state, account);
            var thresholds = new ArrayList<>(rate.thresholds()); thresholds.add(0.0); thresholds.add(account.capacity());
            for (double target : thresholds) {
                long micros = Resources.microsToThreshold(account, target, rate.perSecond());
                // An unreachable distant threshold is not a deadline in the finite logical clock.
                if (micros >= Long.MAX_VALUE - time(state)) continue;
                next = Math.min(next, Math.addExact(time(state), micros));
            }
        }
        return next;
    }

    @Override public RuleEngine.Local<EffectState> advance(EffectState state, long until) {
        if (until < time(state) || until == TimelineEngine.NEVER || until > nextDeadline(state)) throw new IllegalArgumentException("Clock crossed an unsettled boundary");
        var resources = new HashMap<ResourceState.Key, ResourceState>();
        var resourceSignals = new ArrayList<RuleEngine.Signal>();
        var ordered = state.resources().values().stream().sorted(Comparator.comparing((ResourceState value) -> value.key().holder())
                .thenComparing(value -> value.key().resource())).toList();
        for (var account : ordered) {
            var integrated = Resources.integrate(account, until, rates.evaluate(state, account).perSecond(), List.of());
            resources.put(account.key(), integrated);
            if (integrated.value() != account.value()) resourceSignals.add(new RuleEngine.Signal("chorus:resource_changed", new ResourceChanged(account, integrated)));
        }
        var recovered = until > time(state) ? ShieldRecovery.integrate(state.buffs(), shieldRecovery.apply(state), until)
                : new ShieldRecovery.Batch(state.buffs(), List.of(), List.of());
        var buffs = Buffs.advanceStep(recovered.store(), until);
        if (buffs.store().timeMicros() != until) throw new IllegalStateException("Skipped buff deadline");
        var timers = new TreeMap<>(state.timers());
        var dueSignals = new ArrayList<RuleEngine.Signal>();
        for (var timer : state.timers().values()) {
            if (timer.lifetime().isPresent() && !timer.lifetime().orElseThrow().active(buffs.store())
                    || !EffectTimers.active(timer, state.sources(), buffs.store())) {
                timers.remove(timer.id()); continue;
            }
            if (timer.dueAt() != until) continue;
            timers.remove(timer.id()); dueSignals.add(timer.signal());
            if (timer.remaining() != 1) {
                long next = Math.addExact(timer.dueAt(), timer.intervalMicros());
                timers.put(timer.id(), new EffectState.Timer(timer.id(), next, timer.intervalMicros(),
                        timer.remaining() == -1 ? -1 : timer.remaining() - 1, timer.signal(), timer.lifetime()));
            }
        }
        var signals = new ArrayList<>(recovered.facts()); signals.addAll(buffs.signals()); signals.addAll(resourceSignals); signals.addAll(dueSignals);
        // The old snapshot owns [from, until), even when its buff expires at until. The new domain is
        // settled first; allocated world commands finish before expiry/timer reactions can run.
        if (until > time(state)) {
            var batch = Recovery.integrate(recovery.apply(state), time(state), until, signals);
            if (!batch.allocations().isEmpty()) signals = new ArrayList<>(List.of(new RuleEngine.Signal(Recovery.EVENT, batch)));
        }
        var retainedCosts = new TreeMap<>(state.retainedCosts());
        retainedCosts.values().removeIf(cost -> cost.handle().dueAt() <= until);
        return new RuleEngine.Local<>(new EffectState(buffs.store(), resources, timers, state.sources(), state.mode(), state.equipment(), state.abilities(), state.ammunition(), state.reloads(), state.shots(), state.random(), state.shotGroups(), state.damageGroups(), retainedCosts), RuleEngine.Empty.INSTANCE, signals);
    }
}
