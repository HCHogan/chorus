package com.imdomestic.chorus.effect.finisher;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** An accepted pair is represented by two generation-bound locks and one detached watchdog timer. */
public final class Finisher {
    private Finisher() {}
    public static final String PARTICIPANT = "chorus:finisher_participant";
    public static final String PULSE = "chorus:internal/finisher_pulse", STRIKE = "chorus:internal/finisher_strike";
    public static final long POLL_MICROS = 50_000;
    public record Policy(double maximumHealthFraction, double range, String eligibleTag, boolean lineOfSight) {
        public Policy {
            Numbers.finite(maximumHealthFraction, "finisher health fraction"); Numbers.nonnegative(range, "finisher range");
            if (maximumHealthFraction <= 0 || maximumHealthFraction > 1) throw new IllegalArgumentException("Finisher threshold must be in (0, 1]");
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(eligibleTag);
        }
    }
    public enum Decision { ALLOWED, MISSING, DEAD, INVALID_ACTOR, INVALID_TARGET, ALLIED, INELIGIBLE, HEALTH, RANGE, OBSTRUCTED }
    /** Health qualification is sampled only on acceptance; continued validity still requires both live participants. */
    public record Check(String actor, String target, Policy policy, boolean starting) implements RuleEngine.WorldCommand {
        public Check { identity(actor); identity(target); Objects.requireNonNull(policy); }
    }
    public record Checked(Check request, Decision decision) implements RuleEngine.ActionResult {
        public Checked { Objects.requireNonNull(request); Objects.requireNonNull(decision); }
        public boolean allowed() { return decision == Decision.ALLOWED; }
    }
    public record Plan(String id, EffectEvent event, EffectState.Lifetime actorLock, EffectState.Lifetime targetLock,
            long startedAt, long dueAt, Policy policy, DamageCommand damage) implements RuleEngine.Payload {
        public Plan {
            identity(id); Objects.requireNonNull(event); Objects.requireNonNull(actorLock); Objects.requireNonNull(targetLock);
            Objects.requireNonNull(policy); Objects.requireNonNull(damage);
            if (startedAt < 0 || dueAt <= startedAt || dueAt == Long.MAX_VALUE || event.actor().equals(event.victim())
                    || !actorLock.key().holder().equals(event.actor()) || !targetLock.key().holder().equals(event.victim())
                    || !damage.source().equals(event.source()) || !damage.target().equals(event.victim())
                    || !damage.tags().contains("chorus:finisher_damage") || !damage.killTags().contains("chorus:finisher_kill"))
                throw new IllegalArgumentException("Invalid accepted finisher");
        }
        public String timerId() { return "finisher/" + id; }
        public Check check(boolean starting) { return new Check(event.actor(), event.victim(), policy, starting); }
        public boolean active(EffectState state) { return actorLock.active(state.buffs()) && targetLock.active(state.buffs()); }
        public EffectState.Timer timer(long now) {
            if (now < startedAt || now >= dueAt) throw new IllegalArgumentException("Finisher watchdog outside accepted duration");
            return new EffectState.Timer(timerId(), Math.min(dueAt, Math.addExact(now, POLL_MICROS)), 0, 1,
                    new RuleEngine.Signal(PULSE, this), Optional.empty());
        }
    }
    public enum Outcome { ACCEPTED, BUSY, REJECTED }
    /** Pure actor-side observation; absence is distinct from an execution with zero remaining time. */
    public record Observation(String actor, long at, Optional<Plan> plan) implements RuleEngine.ActionResult {
        public Observation {
            identity(actor); Objects.requireNonNull(plan);
            if (at < 0 || plan.filter(p -> !p.event().actor().equals(actor) || at < p.startedAt() || at >= p.dueAt()).isPresent())
                throw new IllegalArgumentException("Invalid finisher observation");
        }
        public Plan active() { return plan.orElseThrow(() -> new IllegalArgumentException("No active finisher")); }
    }
    public static Observation observe(EffectState state, String actor, Optional<String> identity) {
        long now = state.buffs().timeMicros();
        var found = state.timers().values().stream().map(t -> t.signal().payload()).filter(Plan.class::isInstance).map(Plan.class::cast)
                .filter(p -> p.event().actor().equals(actor) && identity.map(p.id()::equals).orElse(true)
                        && p.startedAt() <= now && now < p.dueAt() && p.active(state)).findFirst();
        return new Observation(actor, now, found);
    }
    public record Result(Outcome outcome, Optional<Checked> check, Optional<Plan> plan) implements RuleEngine.ActionResult {
        public Result {
            Objects.requireNonNull(outcome); Objects.requireNonNull(check); Objects.requireNonNull(plan);
            if ((outcome == Outcome.ACCEPTED) != plan.isPresent() || outcome == Outcome.ACCEPTED && check.filter(Checked::allowed).isEmpty()
                    || outcome == Outcome.REJECTED && check.filter(c -> !c.allowed()).isEmpty()) throw new IllegalArgumentException("Invalid finisher acceptance");
        }
    }
    public static boolean busy(EffectState state, String participant) {
        return state.buffs().instances().values().stream().anyMatch(b -> b.key().holder().equals(participant)
                && b.definition().tags().contains(PARTICIPANT) && b.activeCount(state.buffs().timeMicros()) > 0);
    }
    public static void validateLock(BuffDefinition b) {
        if (b.maximumStacks() != 1 || b.timer().durationMicros() != BuffDefinition.FOREVER
                || b.timer().mode() != BuffDefinition.TimerMode.SHARED || b.binding().attach() != BuffDefinition.Attach.TARGET
                || b.binding().instancedBy() != BuffDefinition.InstanceBy.NONE || b.binding().onStow() != BuffDefinition.OnStow.KEEP
                || !b.tags().contains(PARTICIPANT)) throw new IllegalArgumentException("Finisher lock must be a shared, permanent, single-stack target participant: " + b.id());
    }
    private static void identity(String s) { if (s == null || s.isBlank()) throw new IllegalArgumentException("Missing finisher identity"); }
}
