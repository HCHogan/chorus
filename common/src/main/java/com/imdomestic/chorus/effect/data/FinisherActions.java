package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Data-callable acceptance and engine-owned continuation; neither emits completion from requested damage. */
public final class FinisherActions {
    private FinisherActions() {}
    public static final ResultShape RESULT = new ResultShape(Map.of(), Map.of(
            "accepted", r -> ((Finisher.Result) r).outcome() == Finisher.Outcome.ACCEPTED,
            "busy", r -> ((Finisher.Result) r).outcome() == Finisher.Outcome.BUSY,
            "rejected", r -> ((Finisher.Result) r).outcome() == Finisher.Outcome.REJECTED));
    public static final ResultShape OBSERVATION = new ResultShape(Map.of(
            "remaining", new ResultShape.Field(Unit.SECOND, r -> { var o=(Finisher.Observation)r; return (o.active().dueAt()-o.at())/1_000_000.0; }),
            "elapsed", new ResultShape.Field(Unit.SECOND, r -> { var o=(Finisher.Observation)r; return (o.at()-o.active().startedAt())/1_000_000.0; }),
            "duration", new ResultShape.Field(Unit.SECOND, r -> { var p=((Finisher.Observation)r).active(); return (p.dueAt()-p.startedAt())/1_000_000.0; })),
            Map.of("active", r -> ((Finisher.Observation)r).plan().isPresent()));
    public record Observe(Evaluation.Target actor, boolean matchEvent) implements Action {
        public Observe { Objects.requireNonNull(actor); }
        @Override public ResultShape validate(Validation v) { v.target(actor); return OBSERVATION; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String holder=e.target(actor);
            var identity=EffectTimers.event(e.context().event().signal().payload()).map(event -> event.references().get("finisher_id")).filter(id -> !id.isBlank());
            var observed=matchEvent && identity.isEmpty() ? new Finisher.Observation(holder,e.state().buffs().timeMicros(),Optional.empty())
                    : Finisher.observe(e.state(),holder,matchEvent ? identity : Optional.empty());
            return new RuleEngine.Local<>(e.state(),observed,List.of());
        }
    }
    public record Begin(Evaluation.Target target, String actorBuff, String targetBuff, Value duration,
            Finisher.Policy policy, Value damage, String damageType, Set<String> tags, Optional<String> damageProfile,
            ActionOrigin origin) implements Action {
        public Begin { tags = Set.copyOf(tags); Objects.requireNonNull(policy); Objects.requireNonNull(damageProfile); Objects.requireNonNull(origin); }
        @Override public ResultShape validate(Validation v) {
            v.target(target); Finisher.validateLock(v.buff(actorBuff)); Finisher.validateLock(v.buff(targetBuff));
            Validation.same(duration.unit(v), Unit.SECOND); Validation.same(damage.unit(v), Unit.DAMAGE);
            com.imdomestic.chorus.effect.ability.AbilityDefinition.id(damageType); tags.forEach(com.imdomestic.chorus.effect.ability.AbilityDefinition::id);
            damageProfile.ifPresent(v::damageProfile); return RESULT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String actor = e.self(), victim = e.target(target);
            if (!origin.resolve(e).owner().equals(actor)) throw new IllegalArgumentException("Finisher actor must own its origin");
            // Resolve and validate numeric inputs before requesting world authorization.
            com.imdomestic.chorus.effect.weapon.WeaponReload.micros(duration.evaluate(e));
            Numbers.nonnegative(damage.evaluate(e).value(), "finisher damage");
            if (Finisher.busy(e.state(), actor) || Finisher.busy(e.state(), victim)) return busy(e.state(), Optional.empty());
            return new RuleEngine.Await<>(new Finisher.Check(actor, victim, policy, true));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult r) {
            var checked = (Finisher.Checked) r;
            if (!checked.request().equals(e.context().command(Finisher.Check.class))) throw new IllegalArgumentException("Foreign finisher authorization");
            if (!checked.allowed()) return new RuleEngine.Local<>(e.state(), new Finisher.Result(Finisher.Outcome.REJECTED, Optional.of(checked), Optional.empty()), List.of());
            var q = checked.request();
            if (Finisher.busy(e.state(), q.actor()) || Finisher.busy(e.state(), q.target())) return busy(e.state(), Optional.of(checked));
            long now = e.state().buffs().timeMicros(), end = Math.addExact(now, com.imdomestic.chorus.effect.weapon.WeaponReload.micros(duration.evaluate(e)));
            var source = origin.resolve(e); var op = e.context().operation(); String id = op.frame() + "/" + op.pc() + "/" + op.invocation();
            var allTags = new HashSet<>(tags); allTags.add("chorus:finisher_damage");
            var command = new DamageCommand(q.target(), source, damage.evaluate(e).value(), damageType, allTags, Set.of("chorus:finisher_kill"), false, damageProfile);
            var event = new EffectEvent(q.actor(), q.target(), source, allTags, Map.of("duration", new Measure((end - now) / 1_000_000.0, Unit.SECOND)), Map.of(), Map.of("finisher_id", id));
            var actor = Buffs.grant(e.state().buffs(), e.definition(actorBuff), q.actor(), q.actor(), source, 1, 1, BuffDefinition.FOREVER);
            var victim = Buffs.grant(actor.store(), e.definition(targetBuff), q.target(), q.target(), source, 1, 1, BuffDefinition.FOREVER);
            var a = victim.store().active(Buffs.key(e.definition(actorBuff), q.actor(), q.actor(), source)).orElseThrow();
            var b = victim.store().active(Buffs.key(e.definition(targetBuff), q.target(), q.target(), source)).orElseThrow();
            var plan = new Finisher.Plan(id, event, new EffectState.Lifetime(a.key(), a.generation()), new EffectState.Lifetime(b.key(), b.generation()), now, end, policy, command);
            var state = e.state().withBuffs(victim.store()).schedule(plan.timer(now));
            var facts = new ArrayList<RuleEngine.Signal>(); facts.addAll(actor.signals()); facts.addAll(victim.signals()); facts.add(new RuleEngine.Signal("chorus:finisher_started", event));
            return new RuleEngine.Local<>(state, new Finisher.Result(Finisher.Outcome.ACCEPTED, Optional.of(checked), Optional.of(plan)), facts);
        }
    }
    public record Cancel(Evaluation.Target participant, String reason) implements Action {
        @Override public ResultShape validate(Validation v) { v.target(participant); com.imdomestic.chorus.effect.ability.AbilityDefinition.id(reason); return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            String id = e.target(participant); var state = e.state(); var facts = new ArrayList<RuleEngine.Signal>();
            var plans = state.timers().values().stream().map(t -> t.signal().payload()).filter(Finisher.Plan.class::isInstance).map(Finisher.Plan.class::cast)
                    .filter(p -> p.event().actor().equals(id) || p.event().victim().equals(id)).toList();
            for (var plan : plans) { var cancelled = cancel(state, plan, reason); state = cancelled.state(); facts.addAll(cancelled.emitted()); }
            return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, facts);
        }
    }
    private static RuleEngine.Local<EffectState> busy(EffectState state, Optional<Finisher.Checked> check) {
        return new RuleEngine.Local<>(state, new Finisher.Result(Finisher.Outcome.BUSY, check, Optional.empty()), List.of());
    }
    private static Finisher.Plan plan(RuleEngine.Context c) { return (Finisher.Plan) c.event().signal().payload(); }
    private static RuleEngine.Local<EffectState> release(EffectState state, Finisher.Plan plan) {
        var facts = new ArrayList<RuleEngine.Signal>(); state = state.cancel(plan.timerId());
        for (var lock : List.of(plan.actorLock(), plan.targetLock())) if (lock.active(state.buffs())) {
            var result = Buffs.remove(state.buffs(), lock.key(), Buffs.Reason.REMOVED); state = state.withBuffs(result.store()); facts.addAll(result.signals());
        }
        return new RuleEngine.Local<>(state, RuleEngine.Empty.INSTANCE, facts);
    }
    private static RuleEngine.Local<EffectState> cancel(EffectState state, Finisher.Plan plan, String reason) {
        var released = release(state, plan); var facts = new ArrayList<>(released.emitted());
        var e = plan.event(); var refs = new HashMap<>(e.references()); refs.put("reason", reason);
        facts.add(new RuleEngine.Signal("chorus:finisher_cancelled", new EffectEvent(e.actor(), e.victim(), e.source(), e.tags(), e.numbers(), e.flags(), refs)));
        return new RuleEngine.Local<>(released.state(), RuleEngine.Empty.INSTANCE, facts);
    }
    public static List<RuleEngine.EventRule<EffectState>> definitions(CompiledEffects program) {
        var pulse = new RuleEngine.Action<EffectState>() {
            @Override public RuleEngine.Outcome<EffectState> step(EffectState s, RuleEngine.Context c) {
                var p = plan(c); if (!p.active(s)) return cancel(s, p, "interrupted");
                return new RuleEngine.Await<>(p.check(false));
            }
            @Override public RuleEngine.Local<EffectState> complete(EffectState s, RuleEngine.Context c, RuleEngine.ActionResult r) {
                var checked = (Finisher.Checked) r; var p = plan(c);
                if (!checked.request().equals(c.command(Finisher.Check.class))) throw new IllegalArgumentException("Foreign finisher continuation check");
                if (!p.active(s) || !checked.allowed()) return cancel(s, p, checked.allowed() ? "interrupted" : checked.decision().name().toLowerCase(Locale.ROOT));
                if (s.buffs().timeMicros() < p.dueAt()) return new RuleEngine.Local<>(s.schedule(p.timer(s.buffs().timeMicros())), r, List.of());
                return new RuleEngine.Local<>(s, r, List.of(new RuleEngine.Signal(Finisher.STRIKE, p)));
            }
        };
        var strike = program.evaluated("chorus:internal/finisher/strike",Map.of(),Map.of(),new CompiledEffects.EvaluatedAction() {
            @Override public RuleEngine.Outcome<EffectState> execute(Evaluation evaluation, RuleEngine.Context c) {
                var s=evaluation.state();
                var p = plan(c); if (!p.active(s)) return cancel(s, p, "interrupted");
                return new RuleEngine.Await<>(program.prepareDamage(s, p.damage(),evaluation.resourceView()));
            }
            @Override public RuleEngine.Local<EffectState> complete(Evaluation evaluation, RuleEngine.Context c, RuleEngine.ActionResult r) {
                var s=evaluation.state();
                var p = plan(c); var damage = (DamageReceipt) r;
                var finished = Action.finishDamage(s, c.command(DamageCommand.class), damage, Map.of("finisher_id", p.id()));
                var released = release(finished.state(), p); var facts = new ArrayList<>(finished.emitted()); facts.addAll(released.emitted());
                if (damage.lethal()) {
                    // Reuse confirmed death observations even if the corpse has already left the world.
                    var kill = facts.stream().filter(f -> f.type().equals("chorus:kill")).findFirst().orElseThrow();
                    facts.add(new RuleEngine.Signal("chorus:finisher", kill.payload()));
                } else {
                    var cancelled = cancel(released.state(), p, damage.deathPrevented() ? "death_prevented" : "not_lethal"); facts.addAll(cancelled.emitted());
                }
                return new RuleEngine.Local<>(released.state(), damage, facts);
            }
        });
        return List.of(new RuleEngine.EventRule<>(Finisher.PULSE, Finisher.PULSE, (_, _) -> true, List.of(new RuleEngine.Instruction<>(pulse, ""))),
                new RuleEngine.EventRule<>(Finisher.STRIKE, Finisher.STRIKE, (_, _) -> true, List.of(new RuleEngine.Instruction<>(strike, ""))));
    }
}
