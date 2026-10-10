package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ammo.Ammunition;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.math.*;
import java.util.*;

/** Authoritative acceptance is distinct from launch success, contact and once-per-shot resolution. */
public final class WeaponFire {
    private WeaponFire() {}
    public static final String REQUEST = "chorus:internal/fire_request", ACCEPTED = "chorus:fire_accepted";
    public enum Outcome { ACCEPTED, EMPTY_HANDS, NOT_CONFIGURED, COOLDOWN, CONDITION, NO_AMMUNITION }
    public record Request(String holder, String token) implements RuleEngine.Payload {
        public Request { identity(holder); identity(token); }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(REQUEST, this); }
    }
    /** Retained by physical instance, including across stowing and same-runtime ownership changes. */
    public record Shot(String holder, String token, Loadout.Gear gear, BuffInstance.Origin origin,
            long firedAt, long readyAt, Measure input, Measure interval, Optional<CalculationProfile.Result> calculation, Ammunition.Result cost) {
        public Shot {
            identity(holder); identity(token); Objects.requireNonNull(gear); Objects.requireNonNull(origin);
            Objects.requireNonNull(input); Objects.requireNonNull(interval); Objects.requireNonNull(calculation); Objects.requireNonNull(cost);
            if (firedAt < 0 || readyAt != Math.addExact(firedAt, micros(interval)) || readyAt == Long.MAX_VALUE
                    || !origin.owner().equals(holder) || !origin.weapon().equals(gear.instance()) || !origin.source().equals("shot/" + token)
                    || !cost.before().weapon().equals(gear.instance()) || cost.kind() != Ammunition.Kind.SPEND || cost.pool() != Ammunition.Pool.MAGAZINE || !cost.complete())
                throw new IllegalArgumentException("Invalid accepted weapon fire");
            calculation.ifPresent(result -> {
                if (!result.inputs().base().equals(input) || !result.output().equals(interval)) throw new IllegalArgumentException("Fire calculation differs from accepted interval");
            });
            if (calculation.isEmpty() && !input.equals(interval)) throw new IllegalArgumentException("Unexplained fire interval");
        }
    }
    public record Receipt(Outcome outcome, Optional<Shot> shot) implements RuleEngine.ActionResult {
        public Receipt { Objects.requireNonNull(outcome); Objects.requireNonNull(shot); if ((outcome == Outcome.ACCEPTED) != shot.isPresent()) throw new IllegalArgumentException("Invalid fire receipt"); }
    }
    public record Accepted(EffectEvent event, WeaponDefinition definition, Shot shot) implements EffectEvent.Carrier {}
    public record Scope(EffectEvent event) implements RuleEngine.Payload {}
    public record Commit(EffectState before, EffectState after) implements RuleEngine.Payload {
        public EffectState apply(EffectState current) {
            if (!before.equals(current)) throw new IllegalStateException("Fire acceptance changed before commit");
            return after;
        }
    }
    public static int rounds(Measure cost) {
        if (!cost.unit().equals(Unit.ROUND) || cost.value() < 0 || cost.value() > Integer.MAX_VALUE || cost.value() != Math.rint(cost.value()))
            throw new IllegalArgumentException("Fire cost must be nonnegative integer rounds");
        return (int) cost.value();
    }
    public static long micros(Measure interval) {
        if (!interval.unit().equals(Unit.SECOND) || interval.value() <= 0) throw new IllegalArgumentException("Fire interval must be positive seconds");
        long micros = BigDecimal.valueOf(interval.value()).movePointRight(6).setScale(0, RoundingMode.CEILING).longValueExact();
        if (micros <= 0 || micros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid finite fire interval");
        return micros;
    }
    private static void identity(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing fire identity"); }
}
