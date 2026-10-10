package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Content-selected confirmed damage receipts shared across detached callbacks, with explicit expiry. */
public final class DamageTallies {
    private DamageTallies() {}
    public record Handle(String id, long startedAt, long dueAt) implements RuleEngine.ActionResult {
        public Handle {
            Objects.requireNonNull(id);
            if (id.isBlank() || startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid damage tally identity or lifetime");
        }
    }
    /** Keep only accounting facts; traces and queued reactions do not affect receipt identity. */
    public record Observation(DamageReceipt.Outcome outcome, double shield, double absorption, double health, Optional<String> death, boolean prevented) {
        public Observation {
            // Reuse the canonical receipt invariants, including unapplied/death contradictions.
            new DamageReceipt("validation", outcome, shield, absorption, health, death, prevented);
        }
        static Observation of(DamageReceipt receipt) { return new Observation(receipt.outcome(), receipt.shieldLoss(), receipt.absorptionLoss(), receipt.healthLoss(), receipt.deathId(), receipt.deathPrevented()); }
        boolean hit() { return outcome != DamageReceipt.Outcome.CANCELLED && outcome != DamageReceipt.Outcome.FAILED; }
    }
    public record Entry(Handle handle, Map<String, Observation> receipts) {
        public Entry {
            Objects.requireNonNull(handle); receipts = Collections.unmodifiableMap(new TreeMap<>(receipts));
            if (receipts.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Missing damage receipt identity");
            receipts.values().forEach(Objects::requireNonNull);
        }
    }
    public record Summary(int attempts, int hits, int effectiveHits, int kills, double shieldLoss, double absorptionLoss, double healthLoss) {
        public static final Summary ZERO = new Summary(0, 0, 0, 0, 0, 0, 0);
        public Summary {
            if (attempts < 0 || hits < 0 || effectiveHits < 0 || kills < 0 || hits > attempts || effectiveHits > hits || kills > hits) throw new IllegalArgumentException("Invalid damage tally counts");
            Numbers.nonnegative(shieldLoss, "tally shield loss"); Numbers.nonnegative(absorptionLoss, "tally absorption loss"); Numbers.nonnegative(healthLoss, "tally health loss");
            Numbers.finite(shieldLoss + absorptionLoss + healthLoss, "tally total damage");
        }
    }
    /** Read values are immutable snapshots; only Handle refers to the live shared entry. */
    public record Result(Optional<Summary> summary, boolean changed) implements RuleEngine.ActionResult {
        public Result { Objects.requireNonNull(summary); if (changed && summary.isEmpty()) throw new IllegalArgumentException("Unavailable tally cannot change"); }
        public Summary numbers() { return summary.orElse(Summary.ZERO); }
    }
    public static RuleEngine.Local<EffectState> begin(EffectState state, Handle handle) {
        if (handle.startedAt() != state.buffs().timeMicros() || state.damageTallies().containsKey(handle.id())) throw new IllegalArgumentException("Duplicate tally or wrong start boundary");
        return new RuleEngine.Local<>(state.withDamageTally(new Entry(handle, Map.of())), handle, List.of());
    }
    private static Optional<Entry> live(EffectState state, Handle handle) {
        var entry = state.damageTallies().get(handle.id());
        if (entry != null && !entry.handle().equals(handle)) throw new IllegalArgumentException("Conflicting damage tally handle");
        return entry == null || state.buffs().timeMicros() >= handle.dueAt() ? Optional.empty() : Optional.of(entry);
    }
    public static Result read(EffectState state, Handle handle) { return new Result(live(state, handle).map(DamageTallies::summarize), false); }
    public static RuleEngine.Local<EffectState> record(EffectState state, Handle handle, DamageReceipt receipt) {
        var entry = live(state, handle);
        if (entry.isEmpty()) return new RuleEngine.Local<>(state, new Result(Optional.empty(), false), List.of());
        var value = entry.orElseThrow(); var observation = Observation.of(receipt); var previous = value.receipts().get(receipt.damageId());
        if (previous != null && !previous.equals(observation)) throw new IllegalArgumentException("Conflicting facts for one damage receipt");
        var receipts = new TreeMap<>(value.receipts()); receipts.putIfAbsent(receipt.damageId(), observation);
        var next = new Entry(handle, receipts); var result = new Result(Optional.of(summarize(next)), previous == null);
        return new RuleEngine.Local<>(state.withDamageTally(next), result, List.of());
    }
    public static RuleEngine.Local<EffectState> close(EffectState state, Handle handle) {
        var entry = live(state, handle);
        return new RuleEngine.Local<>(state.withoutDamageTally(handle.id()), new Result(entry.map(DamageTallies::summarize), entry.isPresent()), List.of());
    }
    private static Summary summarize(Entry entry) {
        int hits = 0, effective = 0; double shield = 0, absorption = 0, health = 0; var deaths = new HashSet<String>();
        for (var receipt : entry.receipts().values()) {
            if (receipt.hit()) hits++;
            if (receipt.shield() + receipt.absorption() + receipt.health() > 0) effective++;
            shield += receipt.shield(); absorption += receipt.absorption(); health += receipt.health(); receipt.death().ifPresent(deaths::add);
        }
        return new Summary(entry.receipts().size(), hits, effective, deaths.size(), shield, absorption, health);
    }
}
