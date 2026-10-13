package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.EffectTimers;
import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.target.EntityAnchor;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Predicate;

/** Capture/resolve an entity life and reuse ordinary damage completion behind a checked world write. */
public final class EntityAnchorActions {
    private EntityAnchorActions() {}
    public static final ResultShape RESULT;
    static {
        var flags = new HashMap<String, Predicate<RuleEngine.ActionResult>>();
        for (var outcome : EntityAnchor.Outcome.values())
            flags.put(outcome.name().toLowerCase(Locale.ROOT), r -> ((EntityAnchor.Result) r).outcome() == outcome);
        flags.put("alive", r -> ((EntityAnchor.Result) r).observation().map(EntityAnchor.Observation::alive).orElse(false));
        RESULT = new ResultShape(Map.of(), flags, false, ResultShape.Reference.ENTITY_ANCHOR);
    }
    private static EntityAnchor.Observation anchor(Evaluation e, String binding) {
        return e.context().result(binding, EntityAnchor.Result.class).requireObservation();
    }
    private static RuleEngine.Local<EffectState> finish(Evaluation e, RuleEngine.ActionResult value) {
        var result = (EntityAnchor.Result) value;
        if (!result.query().equals(e.context().command(EntityAnchor.Query.class))) throw new IllegalArgumentException("Entity anchor receipt differs from query");
        return new RuleEngine.Local<>(e.state(), result, List.of());
    }
    public record Capture(Evaluation.Target target, TargetQuery.Anchor anchor) implements Action {
        public Capture { Objects.requireNonNull(target); Objects.requireNonNull(anchor); }
        @Override public ResultShape validate(Validation v) { v.target(target); return RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Await<>(new EntityAnchor.Query(e.target(target), anchor, Optional.empty()));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult r) { return finish(e, r); }
    }
    public record Resolve(String reference) implements Action {
        public Resolve { EffectTimers.localName(reference); }
        @Override public ResultShape validate(Validation v) { v.result(reference).requireEntityAnchor(); return RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var prior = anchor(e, reference);
            return new RuleEngine.Await<>(new EntityAnchor.Query(prior.reference().target(), prior.anchor(), Optional.of(prior.reference())));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult r) { return finish(e, r); }
    }
    /** Persist the complete typed result, including a known unavailable outcome; never re-observe it. */
    public record Write(String buff, Evaluation.Target target, String component, String reference) implements Action {
        public Write { Objects.requireNonNull(target); EffectTimers.localName(component); EffectTimers.localName(reference); }
        @Override public ResultShape validate(Validation v) {
            v.target(target); v.buff(buff).components().requireEntityAnchor(component); v.result(reference).requireEntityAnchor(); return RESULT;
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var key = e.key(buff, target); var instance = e.state().buffs().active(key).orElseThrow(() -> new IllegalArgumentException("Cannot update a missing buff"));
            instance.definition().components().requireEntityAnchor(component);
            var value = e.context().result(reference, EntityAnchor.Result.class);
            var components = instance.components().entityAnchor(component, value);
            var state = components.equals(instance.components()) ? e.state() : e.state().withBuffs(Buffs.components(e.state().buffs(), key, components));
            return new RuleEngine.Local<>(state, value, List.of());
        }
    }
    /** Unwritten is a content error, distinct from a recorded MISSING world observation. */
    public record Read(String buff, Evaluation.Target target, String component) implements Action {
        public Read { Objects.requireNonNull(target); EffectTimers.localName(component); }
        @Override public ResultShape validate(Validation v) { v.target(target); v.buff(buff).components().requireEntityAnchor(component); return RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var value = e.readBuff(buff, target).components().entityAnchors().get(component);
            if (value == null) throw new IllegalArgumentException("Entity anchor component has not been written: " + component);
            return new RuleEngine.Local<>(e.state(), value, List.of());
        }
    }
    /** Identity comparison of two observations, independent of position, anchor choice or observed health. */
    public record SameLife(String left, String right) implements Condition {
        public SameLife { EffectTimers.localName(left); EffectTimers.localName(right); }
        @Override public void validate(Validation v) { v.result(left).requireEntityAnchor(); v.result(right).requireEntityAnchor(); }
        @Override public boolean test(Evaluation e) {
            var a = e.context().result(left, EntityAnchor.Result.class).observation();
            var b = e.context().result(right, EntityAnchor.Result.class).observation();
            return a.isPresent() && b.isPresent() && a.orElseThrow().reference().equals(b.orElseThrow().reference());
        }
    }
    public record Damage(String reference, Action.DamageCaptured damage) implements Action {
        public Damage { EffectTimers.localName(reference); Objects.requireNonNull(damage); }
        @Override public ResultShape validate(Validation v) { v.result(reference).requireEntityAnchor(); return damage.validate(v); }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var ref = anchor(e, reference).reference();
            var requested = (RuleEngine.Await<EffectState>) damage.execute(e);
            return new RuleEngine.Await<>(new EntityAnchor.GuardedDamage(ref, (DamageCommand) requested.command()));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var wrapped = e.context().command(EntityAnchor.GuardedDamage.class);
            var c = e.context();
            var unwrapped = new RuleEngine.Context(c.event(), c.ruleInstance(), c.scope(), c.bindings(), c.operation(), c.retainedResults(), Optional.of(wrapped.damage()));
            return damage.complete(new Evaluation(e.state(), unwrapped, e.buffs(), e.results(), e.resources(), e.retainedResults(), e.program()), receipt);
        }
    }
}
