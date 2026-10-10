package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.stat.*;
import java.util.List;

/** An open, typed expression family. Compilation checks units before any rule can execute. */
public interface Value {
    Unit unit(Validation validation);
    Measure evaluate(Evaluation evaluation);
    /** Bind source operands now, preserving target operands as immutable expressions. Extensions must opt in explicitly. */
    default Value snapshot(Evaluation evaluation) { return SnapshotExpressions.value(this, evaluation); }

    record Constant(double value, Unit quantity) implements Value {
        @Override public Unit unit(Validation v) { Numbers.finite(value, "constant"); return quantity; }
        @Override public Measure evaluate(Evaluation e) { return new Measure(value, quantity); }
    }
    record Result(String binding, String field) implements Value {
        @Override public Unit unit(Validation v) { return v.result(binding).unit(field); }
        @Override public Measure evaluate(Evaluation e) {
            var result = e.context().bindings().get(binding);
            if (result == null) throw new IllegalArgumentException("Unbound result: " + binding);
            return e.results().get(binding).read(field, result);
        }
    }
    record Resource(String resource, Evaluation.Target target) implements Value {
        @Override public Unit unit(Validation v) { v.target(target); v.resource(resource); return Unit.CHARGE; }
        @Override public Measure evaluate(Evaluation e) {
            return new Measure(e.resource(resource, target).value(), Unit.CHARGE);
        }
    }
    record Ammo(Evaluation.Target weapon, com.imdomestic.chorus.effect.ammo.AmmoState.Field field) implements Value {
        @Override public Unit unit(Validation v) { v.target(weapon); return Unit.ROUND; }
        @Override public Measure evaluate(Evaluation e) {
            return new Measure(field == com.imdomestic.chorus.effect.ammo.AmmoState.Field.CAPACITY || field == com.imdomestic.chorus.effect.ammo.AmmoState.Field.MISSING
                    ? e.ammoView(weapon).read(field) : e.ammo(weapon).read(field), Unit.ROUND);
        }
    }
    enum Rounding { FLOOR, CEILING, HALF_UP }
    record Round(Value input, Rounding mode) implements Value {
        @Override public Unit unit(Validation v) { return input.unit(v); }
        @Override public Measure evaluate(Evaluation e) {
            var value = input.evaluate(e);
            double rounded = java.math.BigDecimal.valueOf(value.value()).setScale(0, java.math.RoundingMode.valueOf(mode.name())).doubleValue();
            return new Measure(rounded, value.unit());
        }
    }
    record EventNumber(String name, Unit quantity) implements Value {
        @Override public Unit unit(Validation v) { return quantity; }
        @Override public Measure evaluate(Evaluation e) {
            var value = e.event().numbers().get(name);
            if (value == null) throw new IllegalArgumentException("Missing event measurement: " + name);
            Validation.same(value.unit(), quantity); return value;
        }
    }
    /** Deliberately remains symbolic in an on_use modifier until each impact supplies its own input. */
    record ImpactNumber(String name, Unit quantity) implements Value {
        public ImpactNumber { com.imdomestic.chorus.effect.combat.ImpactData.requireName(name); java.util.Objects.requireNonNull(quantity); }
        @Override public Unit unit(Validation v) { return quantity; }
        @Override public Measure evaluate(Evaluation e) { return e.event().impact().number(name, quantity); }
    }
    record BuffCount(String buff, Evaluation.Target target) implements Value {
        @Override public Unit unit(Validation v) { v.target(target); v.buff(buff); return Unit.COUNT; }
        @Override public Measure evaluate(Evaluation e) { return new Measure(e.state().buffs().active(e.key(buff, target)).map(instance -> instance.count()).orElse(0), Unit.COUNT); }
    }
    record Component(String buff, Evaluation.Target target, String component) implements Value {
        @Override public Unit unit(Validation v) { v.target(target); return v.buff(buff).components().number(component).unit(); }
        @Override public Measure evaluate(Evaluation e) {
            var instance = e.readBuff(buff, target); Double value = instance.components().numbers().get(component);
            if (value == null) throw new IllegalArgumentException("Uninitialized component: " + component);
            return new Measure(value, instance.definition().components().number(component).unit());
        }
    }
    record ByStacks(List<Double> values, Unit quantity) implements Value {
        public ByStacks { values = List.copyOf(values); }
        @Override public Unit unit(Validation v) {
            v.requireBuffSource(); if (values.isEmpty()) throw new IllegalArgumentException("Empty stack table");
            values.forEach(value -> Numbers.finite(value, "stack table")); return quantity;
        }
        @Override public Measure evaluate(Evaluation e) {
            int count = e.ownBuff().count();
            if (count < 1 || count > values.size()) throw new IllegalArgumentException("Undefined stack table entry: " + count);
            return new Measure(values.get(count - 1), quantity);
        }
    }
    record ByBuffTier(List<Double> values, Unit quantity) implements Value {
        public ByBuffTier { values = List.copyOf(values); }
        @Override public Unit unit(Validation v) {
            v.requireBuffSource(); if (values.isEmpty()) throw new IllegalArgumentException("Empty buff tier table");
            values.forEach(value -> Numbers.finite(value, "buff tier table")); return quantity;
        }
        @Override public Measure evaluate(Evaluation e) {
            int tier = e.ownBuff().tier();
            if (tier < 1 || tier > values.size()) throw new IllegalArgumentException("Undefined buff tier table entry: " + tier);
            return new Measure(values.get(tier - 1), quantity);
        }
    }
    /** Exactly one source tag selects a branch; unknown or ambiguous classification is not a guessed value. */
    record BySourceTag(java.util.Map<String, Value> values) implements Value {
        public BySourceTag {
            values = java.util.Map.copyOf(values);
            if (values.isEmpty() || values.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Empty source tag table");
        }
        @Override public Unit unit(Validation v) {
            Unit unit = values.values().iterator().next().unit(v);
            values.values().forEach(value -> Validation.same(value.unit(v), unit)); return unit;
        }
        Value selected(Evaluation e) {
            var tags = e.origin().tags();
            var matching = values.keySet().stream().filter(tags::contains).sorted().toList();
            if (matching.size() != 1) throw new IllegalArgumentException("Expected exactly one source tag branch, got " + matching);
            return values.get(matching.getFirst());
        }
        @Override public Measure evaluate(Evaluation e) { return selected(e).evaluate(e); }
    }
    /** Both branches are type checked; only the selected branch is evaluated. */
    record Choose(Condition condition, Value then, Value otherwise) implements Value {
        public Choose { java.util.Objects.requireNonNull(condition); java.util.Objects.requireNonNull(then); java.util.Objects.requireNonNull(otherwise); }
        @Override public Unit unit(Validation v) {
            condition.validate(v); Unit unit = then.unit(v); Validation.same(otherwise.unit(v), unit); return unit;
        }
        @Override public Measure evaluate(Evaluation e) { return (condition.test(e) ? then : otherwise).evaluate(e); }
    }
    enum Operator { ADD, MIN, MAX, MUL }
    record Arithmetic(Operator operation, List<Value> operands) implements Value {
        public Arithmetic { operands = List.copyOf(operands); }
        @Override public Unit unit(Validation v) {
            if (operands.isEmpty()) throw new IllegalArgumentException("Empty arithmetic expression");
            Unit output = operands.getFirst().unit(v);
            for (int i = 1; i < operands.size(); i++) Validation.same(operands.get(i).unit(v), operation == Operator.MUL ? Unit.MULTIPLIER : output);
            return output;
        }
        @Override public Measure evaluate(Evaluation e) {
            Measure first = operands.getFirst().evaluate(e); var result = java.math.BigDecimal.valueOf(first.value());
            for (int i = 1; i < operands.size(); i++) {
                Measure next = operands.get(i).evaluate(e);
                Validation.same(next.unit(), operation == Operator.MUL ? Unit.MULTIPLIER : first.unit());
                var operand = java.math.BigDecimal.valueOf(next.value());
                result = switch (operation) {
                    case ADD -> result.add(operand); case MIN -> result.min(operand);
                    case MAX -> result.max(operand); case MUL -> result.multiply(operand);
                };
            }
            // Canonical decimal operands avoid a binary residue becoming an extra round under ceil.
            return new Measure(result.doubleValue(), first.unit());
        }
    }
    /** Explicit conversion, such as charge_fraction per credited stack. No implicit unit coercion. */
    record Scale(Value input, double factor, Unit from, Unit to) implements Value {
        @Override public Unit unit(Validation v) { Numbers.finite(factor, "conversion factor"); Validation.same(input.unit(v), from); return to; }
        @Override public Measure evaluate(Evaluation e) { var value = input.evaluate(e); Validation.same(value.unit(), from); return new Measure(value.value() * factor, to); }
    }
    /** Typed curve projection, shared with calculation profiles; useful for falloff and lookup tables. */
    record CurveValue(Value input, Curve curve, Unit from, Unit to) implements Value {
        public CurveValue { java.util.Objects.requireNonNull(input); java.util.Objects.requireNonNull(curve); java.util.Objects.requireNonNull(from); java.util.Objects.requireNonNull(to); }
        @Override public Unit unit(Validation v) { Validation.same(input.unit(v), from); return to; }
        @Override public Measure evaluate(Evaluation e) {
            var value = input.evaluate(e); Validation.same(value.unit(), from); return new Measure(curve.evaluate(value.value()), to);
        }
    }
    record Enhanced(Value base, Value enhanced) implements Value {
        @Override public Unit unit(Validation v) { Unit unit = base.unit(v); Validation.same(enhanced.unit(v), unit); return unit; }
        @Override public Measure evaluate(Evaluation e) { return (e.enhanced() ? enhanced : base).evaluate(e); }
    }
    record Mode(Value pve, Value pvp) implements Value {
        @Override public Unit unit(Validation v) { Unit unit = pve.unit(v); Validation.same(pvp.unit(v), unit); return unit; }
        @Override public Measure evaluate(Evaluation e) { return (e.state().mode() == EffectState.Mode.PVP ? pvp : pve).evaluate(e); }
    }
}
