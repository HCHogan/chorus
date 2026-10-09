package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.stat.*;

/** Typed expressions resolved before submitting a spatial world observation. */
public sealed interface TargetArea permits TargetArea.Sphere, TargetArea.Cylinder, TargetArea.Cone {
    void validate(Validation v);
    TargetShape resolve(Evaluation e);
    private static void distance(Value value, Validation v, boolean positive) {
        Validation.same(value.unit(v), Unit.METER);
        if (value instanceof Value.Constant c) { Numbers.nonnegative(c.value(), "area distance"); if (positive && c.value() == 0) throw new IllegalArgumentException("Area length must be positive"); }
    }
    private static double distance(Value value, Evaluation e) { var result = value.evaluate(e); Validation.same(result.unit(), Unit.METER); return result.value(); }
    record Sphere(Value radius) implements TargetArea {
        @Override public void validate(Validation v) { distance(radius, v, false); }
        @Override public TargetShape resolve(Evaluation e) { return new TargetShape.Sphere(distance(radius, e)); }
    }
    record Cylinder(Value radius, Value height) implements TargetArea {
        @Override public void validate(Validation v) { distance(radius, v, false); distance(height, v, false); }
        @Override public TargetShape resolve(Evaluation e) { return new TargetShape.Cylinder(distance(radius, e), distance(height, e)); }
    }
    record Cone(Value length, Value radius, String direction) implements TargetArea {
        @Override public void validate(Validation v) { distance(length, v, true); distance(radius, v, false); v.result(direction).requireDirection(); }
        @Override public TargetShape resolve(Evaluation e) {
            return new TargetShape.Cone(distance(length, e), distance(radius, e), e.results().get(direction).direction(e.context().bindings().get(direction)));
        }
    }
}
