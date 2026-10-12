package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** A server-accepted selection has separate stow/ready boundaries and never transfers item ownership. */
public final class WeaponHandling {
    private WeaponHandling() {}
    public static final String REQUEST = "chorus:internal/handling_request", DUE = "chorus:internal/handling_due", NEXT = "chorus:internal/handling_next";
    public enum Phase { STOW, READY }
    public enum Outcome { ACCEPTED, UNCHANGED, CANCELLED }
    public record Request(String holder, String token, Optional<String> slot) implements RuleEngine.Payload {
        public Request { identity(holder); identity(token); Objects.requireNonNull(slot); slot.ifPresent(WeaponHandling::slot); }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(REQUEST, this); }
    }
    public record Plan(String holder, String token, Optional<String> targetSlot, Optional<Loadout.Gear> target,
            String phaseSlot, Loadout.Gear gear, BuffInstance.Origin origin, Phase phase, long startedAt, long dueAt,
            Measure input, Measure duration, Optional<CalculationPipeline.Result> calculation, boolean completed) implements RuleEngine.Payload {
        public Plan {
            identity(holder); identity(token); Objects.requireNonNull(targetSlot); Objects.requireNonNull(target);
            targetSlot.ifPresent(WeaponHandling::slot); slot(phaseSlot);
            Objects.requireNonNull(gear); Objects.requireNonNull(origin); Objects.requireNonNull(phase);
            Objects.requireNonNull(input); Objects.requireNonNull(duration); Objects.requireNonNull(calculation);
            if (targetSlot.isPresent() != target.isPresent() || phase == Phase.READY && (!targetSlot.equals(Optional.of(phaseSlot)) || !target.equals(Optional.of(gear)))
                    || completed && phase != Phase.STOW || !origin.owner().equals(holder) || !origin.weapon().equals(gear.instance())
                    || startedAt < 0 || dueAt == Long.MAX_VALUE || dueAt != Math.addExact(startedAt, micros(duration)))
                throw new IllegalArgumentException("Invalid accepted handling phase");
            calculation.ifPresent(result -> {
                if (!result.input().equals(input) || !result.output().equals(duration)) throw new IllegalArgumentException("Handling calculation differs from accepted duration");
            });
            if (calculation.isEmpty() && !input.equals(duration)) throw new IllegalArgumentException("Unexplained handling duration");
        }
        public Plan between() { return new Plan(holder, token, targetSlot, target, phaseSlot, gear, origin, phase, startedAt, dueAt, input, duration, calculation, true); }
        public String timerId() { return "handling/" + holder.length() + ":" + holder + "/" + token + "/" + phase.name().toLowerCase(Locale.ROOT); }
        public EffectState.Timer timer() {
            if (completed || dueAt <= startedAt) throw new IllegalStateException("Only a pending positive duration owns a timer");
            return new EffectState.Timer(timerId(), dueAt, 0, 1, new RuleEngine.Signal(DUE, this), Optional.empty());
        }
        public boolean matches(Loadout loadout) {
            if (targetSlot.isPresent() && !target.equals(Optional.ofNullable(loadout.slots().get(targetSlot.orElseThrow())))) return false;
            if (phase == Phase.STOW && !completed)
                return loadout.drawn().equals(Optional.of(phaseSlot)) && gear.equals(loadout.slots().get(phaseSlot));
            return loadout.drawn().isEmpty();
        }
        public EquipmentChange change(Loadout before) {
            if (completed || !matches(before)) throw new IllegalArgumentException("Handling no longer owns this selection");
            return new EquipmentChange(holder, before, new Loadout(before.slots(), phase == Phase.STOW ? Optional.empty() : targetSlot, before.grants()));
        }
    }
    public record Receipt(Outcome outcome, Optional<Plan> plan) implements RuleEngine.ActionResult {
        public Receipt { Objects.requireNonNull(outcome); Objects.requireNonNull(plan); if ((outcome == Outcome.ACCEPTED) != plan.isPresent()) throw new IllegalArgumentException("Invalid handling receipt"); }
    }
    public record Scope(EffectEvent event) implements RuleEngine.Payload { public Scope { Objects.requireNonNull(event); } }
    public record Fact(EffectEvent event, Plan plan) implements EffectEvent.Carrier { public Fact { Objects.requireNonNull(event); Objects.requireNonNull(plan); } }
    /** Host checks its physical container, then atomically changes only the drawn slot and revision. */
    public record Apply(Plan plan, EquipmentChange change) implements RuleEngine.WorldCommand {
        public Apply {
            Objects.requireNonNull(plan); Objects.requireNonNull(change);
            if (!plan.change(change.before()).equals(change)) throw new IllegalArgumentException("Handling command changes more than its accepted selection");
        }
    }
    public record Applied(Apply query, boolean applied) implements RuleEngine.ActionResult { public Applied { Objects.requireNonNull(query); } }
    public record Commit(EffectState before, EffectState after) implements RuleEngine.Payload {
        public Commit { Objects.requireNonNull(before); Objects.requireNonNull(after); }
        public EffectState apply(EffectState current) { if (!current.equals(before)) throw new IllegalStateException("Handling changed before acceptance"); return after; }
    }
    public static long micros(Measure duration) {
        if (duration.unit().equals(Unit.SECOND) && duration.value() == 0) return 0;
        return WeaponReload.micros(duration);
    }
    private static void slot(String value) { if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid handling slot"); }
    private static void identity(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing handling identity"); }
}
