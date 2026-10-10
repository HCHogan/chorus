package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectTimers;
import com.imdomestic.chorus.effect.buff.BuffRules;
import com.imdomestic.chorus.effect.buff.Buffs;
import java.util.List;
import java.util.Optional;

public interface Condition {
    void validate(Validation validation);
    boolean test(Evaluation evaluation);
    /** Partial evaluation for an attack at use time; no world reads or retained executable closures. */
    default Condition snapshot(Evaluation evaluation) { return SnapshotExpressions.condition(this, evaluation); }
    record Constant(boolean value) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) { return value; }
    }
    record All(List<Condition> of) implements Condition {
        public All { of = List.copyOf(of); }
        @Override public void validate(Validation v) { of.forEach(c -> c.validate(v)); }
        @Override public boolean test(Evaluation e) { return of.stream().allMatch(c -> c.test(e)); }
    }
    record Any(List<Condition> of) implements Condition {
        public Any { of = List.copyOf(of); }
        @Override public void validate(Validation v) { of.forEach(c -> c.validate(v)); }
        @Override public boolean test(Evaluation e) { return of.stream().anyMatch(c -> c.test(e)); }
    }
    record Not(Condition value) implements Condition {
        @Override public void validate(Validation v) { value.validate(v); }
        @Override public boolean test(Evaluation e) { return !value.test(e); }
    }
    enum Source { THIS_WEAPON, THIS_ABILITY, OWNER }
    record WeaponDrawn() implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            var origin = e.origin(); var loadout = e.state().equipment().get(origin.owner());
            return loadout != null && !origin.weapon().isBlank() && loadout.drawn().map(slot -> loadout.slots().get(slot).instance().equals(origin.weapon())).orElse(false);
        }
    }
    record OwnSource() implements Condition {
        @Override public void validate(Validation v) { if (v.buffSource()) throw new IllegalArgumentException("Own source requires a static bundle"); }
        @Override public boolean test(Evaluation e) {
            if (!(e.context().scope() instanceof com.imdomestic.chorus.effect.EffectSource source)) return false;
            if (e.context().event().signal().payload() instanceof com.imdomestic.chorus.effect.SourceChange.Fact fact) return source.equals(fact.source());
            return EffectTimers.event(e.context().event().signal().payload()).filter(event -> source.instance().equals(event.references().get("source_instance"))
                    && source.bundle().equals(event.references().get("bundle"))).isPresent();
        }
    }
    record SourceIs(Source source) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            var payload = EffectTimers.event(e.context().event().signal().payload());
            if (payload.isEmpty()) return false;
            var event = payload.orElseThrow();
            String actual = switch (source) { case THIS_WEAPON -> event.source().weapon(); case THIS_ABILITY -> event.source().ability(); case OWNER -> event.source().owner(); };
            String expected = switch (source) { case THIS_WEAPON -> e.origin().weapon(); case THIS_ABILITY -> e.origin().ability(); case OWNER -> e.origin().owner(); };
            return !expected.isEmpty() && expected.equals(actual);
        }
    }
    record EventTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) { return EffectTimers.event(e.context().event().signal().payload()).filter(event -> event.tags().contains(tag)).isPresent(); }
    }
    record LayerTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            return e.context().event().signal().payload() instanceof com.imdomestic.chorus.effect.combat.ShieldQuery query
                    && query.layer().definition().tags().contains(tag);
        }
    }
    record SourceTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) { return e.origin().tags().contains(tag); }
    }
    record EventSourceTag(String tag) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            return EffectTimers.event(e.context().event().signal().payload()).filter(event -> event.source().tags().contains(tag)).isPresent();
        }
    }
    record EventReference(String name, String expected) implements Condition {
        public EventReference {
            if (name == null || name.isBlank() || expected == null || expected.isBlank()) throw new IllegalArgumentException("Missing event reference comparison");
        }
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            return EffectTimers.event(e.context().event().signal().payload()).filter(event -> expected.equals(event.references().get(name))).isPresent();
        }
    }
    enum Crossing { UP, DOWN }
    record ResourceCrossed(String resource, Evaluation.Target target, double threshold, Crossing direction) implements Condition {
        @Override public void validate(Validation v) { v.target(target); v.resource(resource).threshold(threshold); }
        @Override public boolean test(Evaluation e) {
            var event = EffectTimers.event(e.context().event().signal().payload());
            if (event.isEmpty() || !resource.equals(event.orElseThrow().references().get("resource"))
                    || !e.target(target).equals(event.orElseThrow().victim())) return false;
            var before = event.orElseThrow().numbers().get("before"); var after = event.orElseThrow().numbers().get("after");
            if (before == null || after == null) throw new IllegalArgumentException("Resource change is missing boundary measurements");
            Validation.same(before.unit(), com.imdomestic.chorus.stat.Unit.CHARGE); Validation.same(after.unit(), com.imdomestic.chorus.stat.Unit.CHARGE);
            return direction == Crossing.UP ? before.value() < threshold && after.value() >= threshold : before.value() > threshold && after.value() <= threshold;
        }
    }
    record TargetIs(Evaluation.Target left, Evaluation.Target right) implements Condition {
        @Override public void validate(Validation v) { v.target(left); v.target(right);}
        @Override public boolean test(Evaluation e) { return e.target(left).equals(e.target(right)); }
    }
    record EventFlag(String name, boolean expected) implements Condition {
        @Override public void validate(Validation v) {}
        @Override public boolean test(Evaluation e) {
            Boolean flag = e.event().flags().get(name);
            if (flag == null) throw new IllegalArgumentException("Missing event flag: " + name);
            return flag == expected;
        }
    }
    record ResultFlag(String binding, String field, boolean expected) implements Condition {
        @Override public void validate(Validation v) { v.result(binding).requireFlag(field); }
        @Override public boolean test(Evaluation e) {
            var result = e.context().bindings().get(binding);
            if (result == null) throw new IllegalArgumentException("Unbound result: " + binding);
            return e.results().get(binding).flag(field, result) == expected;
        }
    }
    enum BuffMatch { BOUND, ANY }
    record HasBuff(String buff, Evaluation.Target target, int minimum, BuffMatch match) implements Condition {
        public HasBuff { java.util.Objects.requireNonNull(match); }
        public HasBuff(String buff, Evaluation.Target target, int minimum) { this(buff, target, minimum, BuffMatch.BOUND); }
        @Override public void validate(Validation v) { v.target(target); v.buff(buff); if (minimum < 1) throw new IllegalArgumentException("Invalid minimum stacks"); }
        @Override public boolean test(Evaluation e) {
            if (match == BuffMatch.BOUND) return e.state().buffs().active(e.key(buff, target)).filter(value -> value.count() >= minimum).isPresent();
            String holder = e.target(target);
            return e.state().buffs().instances().values().stream().anyMatch(value -> value.key().holder().equals(holder) && value.definition().id().equals(buff)
                    && value.activeCount(e.state().buffs().timeMicros()) >= minimum);
        }
    }
    /** Query currently bound sources on a holder, independently of this action's retained origin. */
    record HasSourceTag(String tag, Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            String holder = e.target(target);
            return e.state().sources().values().stream().anyMatch(source -> source.holder().equals(holder)
                    && (source.tags().contains(tag) || source.origin().tags().contains(tag)));
        }
    }
    record HasBuffTag(String tag, Evaluation.Target target) implements Condition {
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) {
            String holder = e.target(target);
            return e.state().buffs().instances().values().stream().anyMatch(value -> value.key().holder().equals(holder)
                    && value.activeCount(e.state().buffs().timeMicros()) > 0 && value.definition().tags().contains(tag));
        }
    }
    record HasShield(java.util.Optional<String> tag, Evaluation.Target target) implements Condition {
        public HasShield { java.util.Objects.requireNonNull(tag); }
        @Override public void validate(Validation v) { v.target(target); }
        @Override public boolean test(Evaluation e) { return e.program().orElseThrow(() -> new IllegalStateException("Shield query requires a compiled program")).hasShield(e.state(), e.target(target), tag); }
    }
    enum Comparison { EQ, NE, LT, LE, GT, GE }
    record Compare(Value left, Comparison operation, Value right) implements Condition {
        @Override public void validate(Validation v) { Validation.same(left.unit(v), right.unit(v)); }
        @Override public boolean test(Evaluation e) {
            var a = left.evaluate(e); var b = right.evaluate(e); Validation.same(a.unit(), b.unit());
            return switch (operation) { case EQ -> a.value() == b.value(); case NE -> a.value() != b.value(); case LT -> a.value() < b.value();
                case LE -> a.value() <= b.value(); case GT -> a.value() > b.value(); case GE -> a.value() >= b.value(); };
        }
    }
    /** A layer fact belongs to this exact generation, even if its key has since been reused. */
    record OwnShield() implements Condition {
        @Override public void validate(Validation v) { v.requireBuffSource(); }
        @Override public boolean test(Evaluation e) {
            if (!(e.context().scope() instanceof BuffRules.Scope scope)) return false;
            var instance = scope.snapshot();
            return EffectTimers.event(e.context().event().signal().payload()).filter(event ->
                    instance.key().holder().equals(event.victim())
                    && instance.definition().id().equals(event.references().get("shield_definition"))
                    && Long.toString(instance.generation()).equals(event.references().get("shield_generation"))).isPresent();
        }
    }
    record OwnBuff(Optional<Buffs.Reason> reason) implements Condition {
        @Override public void validate(Validation v) { v.requireBuffSource(); }
        @Override public boolean test(Evaluation e) {
            return e.context().scope() instanceof BuffRules.Scope scope && e.context().event().signal().payload() instanceof Buffs.Change change
                    && scope.owns(change) && (reason.isEmpty() || reason.orElseThrow() == change.reason());
        }
    }
    record OwnTimer(String name) implements Condition {
        @Override public void validate(Validation v) { EffectTimers.localName(name); }
        @Override public boolean test(Evaluation e) {
            return e.context().event().signal().payload() instanceof EffectTimers.Scheduled scheduled && scheduled.name().equals(name)
                    && scheduled.owner().equals(EffectTimers.Owner.of(e.context().scope()));
        }
    }
}
