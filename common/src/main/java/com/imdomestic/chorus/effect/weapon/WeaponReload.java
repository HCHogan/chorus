package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.math.*;
import java.util.*;

/** Accepted reloads are durable domain state. Host verification is an explicit read-only world operation. */
public final class WeaponReload {
    private WeaponReload() {}
    public static final String REQUEST = "chorus:internal/reload_request", DUE = "chorus:internal/reload_due";
    public enum Outcome { ACCEPTED, EMPTY_HANDS, NOT_CONFIGURED, BUSY, FULL, NO_RESERVES }
    public record Request(String holder, String token) implements RuleEngine.Payload {
        public Request { identity(holder); identity(token); }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(REQUEST, this); }
    }
    public record Plan(String holder, String token, Loadout.Gear gear, BuffInstance.Origin origin,
            long startedAt, long dueAt, Measure input, Measure duration, Optional<CalculationProfile.Result> calculation) implements RuleEngine.Payload {
        public Plan {
            identity(holder); identity(token); Objects.requireNonNull(gear); Objects.requireNonNull(origin);
            Objects.requireNonNull(input); Objects.requireNonNull(duration); Objects.requireNonNull(calculation);
            if (startedAt < 0 || dueAt != Math.addExact(startedAt, micros(duration)) || dueAt == Long.MAX_VALUE
                    || !origin.owner().equals(holder) || !origin.weapon().equals(gear.instance())) throw new IllegalArgumentException("Invalid accepted reload");
            calculation.ifPresent(result -> {
                if (!result.inputs().base().equals(input) || !result.output().equals(duration)) throw new IllegalArgumentException("Reload calculation differs from accepted duration");
            });
            if (calculation.isEmpty() && !input.equals(duration)) throw new IllegalArgumentException("Unexplained reload duration");
        }
        public String timerId() { return "reload/" + holder.length() + ":" + holder + "/" + token; }
        public EffectState.Timer timer() { return new EffectState.Timer(timerId(), dueAt, 0, 1, new RuleEngine.Signal(DUE, this), Optional.empty()); }
    }
    public record Receipt(Outcome outcome, Optional<Plan> plan) implements RuleEngine.ActionResult {
        public Receipt { Objects.requireNonNull(outcome); Objects.requireNonNull(plan); if ((outcome == Outcome.ACCEPTED) != plan.isPresent()) throw new IllegalArgumentException("Invalid reload receipt"); }
    }
    public record Scope(EffectEvent event) implements RuleEngine.Payload {}
    public record Verify(Plan plan) implements RuleEngine.WorldCommand { public Verify { Objects.requireNonNull(plan); } }
    public record Verified(Verify query, boolean allowed) implements RuleEngine.ActionResult { public Verified { Objects.requireNonNull(query); } }
    public record Commit(EffectState before, EffectState after) implements RuleEngine.Payload {
        public EffectState apply(EffectState current) {
            if (!before.equals(current)) throw new IllegalStateException("Reload acceptance changed before commit");
            return after;
        }
    }
    public static Optional<Loadout.Gear> drawn(Loadout loadout) { return loadout.drawn().map(loadout.slots()::get); }
    public static long micros(Measure duration) {
        if (!duration.unit().equals(Unit.SECOND) || duration.value() <= 0) throw new IllegalArgumentException("Reload duration must be positive seconds");
        long micros = BigDecimal.valueOf(duration.value()).movePointRight(6).setScale(0, RoundingMode.CEILING).longValueExact();
        if (micros <= 0 || micros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid finite reload duration");
        return micros;
    }
    private static void identity(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing reload identity"); }
}
