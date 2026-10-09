package com.imdomestic.chorus.effect.data;

import java.util.ArrayList;

/** Partial evaluation of the built-in DSL. Victim and impact inputs stay symbolic; source reads become constants. */
public final class SnapshotExpressions {
    private SnapshotExpressions() {}
    private static boolean target(Evaluation.Target target) {
        if (target instanceof Evaluation.BoundTarget) throw new IllegalArgumentException("A snapshot modifier cannot capture a loop binding");
        return target == Evaluation.Target.VICTIM;
    }
    private static Value literal(Value value, Evaluation e) {
        var amount = value.evaluate(e); return new Value.Constant(amount.value(), amount.unit());
    }
    public static Value value(Value value, Evaluation e) {
        return switch (value) {
            case Value.Constant ignored -> value;
            case Value.Resource v -> target(v.target()) ? v : literal(v, e);
            case Value.BuffCount v -> target(v.target()) ? v : literal(v, e);
            case Value.Component v -> target(v.target()) ? v : literal(v, e);
            case Value.ByStacks v -> literal(v, e);
            case Value.ByBuffTier v -> literal(v, e);
            case Value.BySourceTag v -> v.selected(e).snapshot(e);
            case Value.Choose v -> {
                var condition = v.condition().snapshot(e);
                yield condition instanceof Condition.Constant constant ? (constant.value() ? v.then() : v.otherwise()).snapshot(e)
                        : new Value.Choose(condition, v.then().snapshot(e), v.otherwise().snapshot(e));
            }
            case Value.EventNumber v -> literal(v, e);
            case Value.ImpactNumber v -> v;
            case Value.Arithmetic v -> new Value.Arithmetic(v.operation(), v.operands().stream().map(x -> x.snapshot(e)).toList());
            case Value.Scale v -> new Value.Scale(v.input().snapshot(e), v.factor(), v.from(), v.to());
            case Value.CurveValue v -> new Value.CurveValue(v.input().snapshot(e), v.curve(), v.from(), v.to());
            case Value.Enhanced v -> (e.enhanced() ? v.enhanced() : v.base()).snapshot(e);
            case Value.Mode v -> (e.state().mode() == com.imdomestic.chorus.effect.EffectState.Mode.PVP ? v.pvp() : v.pve()).snapshot(e);
            default -> throw new IllegalArgumentException("Value must implement snapshot binding: " + value.getClass().getName());
        };
    }
    public static Condition condition(Condition condition, Evaluation e) {
        return switch (condition) {
            case Condition.Constant ignored -> condition;
            case Condition.All c -> booleanList(c.of(), true, e);
            case Condition.Any c -> booleanList(c.of(), false, e);
            case Condition.Not c -> {
                var bound = c.value().snapshot(e);
                yield bound instanceof Condition.Constant constant ? new Condition.Constant(!constant.value()) : new Condition.Not(bound);
            }
            case Condition.HasBuff c -> target(c.target()) ? c : new Condition.Constant(c.test(e));
            case Condition.HasBuffTag c -> target(c.target()) ? c : new Condition.Constant(c.test(e));
            case Condition.HasShield c -> target(c.target()) ? c : new Condition.Constant(c.test(e));
            case Condition.TargetIs c -> target(c.left()) || target(c.right()) ? c : new Condition.Constant(c.test(e));
            case Condition.Compare c -> {
                var left = c.left().snapshot(e); var right = c.right().snapshot(e);
                var bound = new Condition.Compare(left, c.operation(), right);
                yield left instanceof Value.Constant && right instanceof Value.Constant ? new Condition.Constant(bound.test(e)) : bound;
            }
            case Condition.SourceIs c -> new Condition.Constant(c.test(e));
            case Condition.EventTag c -> new Condition.Constant(c.test(e));
            case Condition.LayerTag ignored -> condition;
            case Condition.SourceTag c -> new Condition.Constant(c.test(e));
            case Condition.EventReference c -> new Condition.Constant(c.test(e));
            case Condition.EventFlag c -> new Condition.Constant(c.test(e));
            case Condition.OwnSource c -> new Condition.Constant(c.test(e));
            case Condition.WeaponDrawn c -> new Condition.Constant(c.test(e));
            case Condition.OwnBuff c -> new Condition.Constant(c.test(e));
            case Condition.OwnShield c -> new Condition.Constant(c.test(e));
            case Condition.OwnTimer c -> new Condition.Constant(c.test(e));
            default -> throw new IllegalArgumentException("Condition must implement snapshot binding: " + condition.getClass().getName());
        };
    }
    private static Condition booleanList(java.util.List<Condition> input, boolean all, Evaluation e) {
        var output = new ArrayList<Condition>();
        for (var child : input) {
            var bound = child.snapshot(e);
            if (bound instanceof Condition.Constant c) {
                if (c.value() != all) return c;
            } else output.add(bound);
        }
        return output.isEmpty() ? new Condition.Constant(all) : all ? new Condition.All(output) : new Condition.Any(output);
    }
}
