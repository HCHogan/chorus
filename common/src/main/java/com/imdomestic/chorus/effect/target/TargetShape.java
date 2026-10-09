package com.imdomestic.chorus.effect.target;

import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Geometry operates on a sampled point relative to the chosen center, never a live entity hitbox. */
public sealed interface TargetShape permits TargetShape.Sphere, TargetShape.Cylinder, TargetShape.Cone {
    double boundingRadius();
    boolean contains(Offset point);
    record Offset(double x, double y, double z) {
        public Offset { Numbers.finite(x, "offset x"); Numbers.finite(y, "offset y"); Numbers.finite(z, "offset z"); }
        public double distance() { return Math.hypot(Math.hypot(x, y), z); }
    }
    record Sphere(double radius) implements TargetShape {
        public Sphere { Numbers.nonnegative(radius, "sphere radius"); }
        @Override public double boundingRadius() { return radius; }
        @Override public boolean contains(Offset p) { return p.distance() <= radius; }
    }
    /** World-Y cylinder centered vertically at the query center. Height is the full height. */
    record Cylinder(double radius, double height) implements TargetShape {
        public Cylinder { Numbers.nonnegative(radius, "cylinder radius"); Numbers.nonnegative(height, "cylinder height"); Numbers.finite(Math.hypot(radius, height / 2), "cylinder bound"); }
        @Override public double boundingRadius() { return Math.hypot(radius, height / 2); }
        @Override public boolean contains(Offset p) { return Math.abs(p.y()) <= height / 2 && Math.hypot(p.x(), p.z()) <= radius; }
    }
    /** Finite solid cone: length along its axis and radius at its flat far end. */
    record Cone(double length, double radius, Optional<WorldDirection> direction) implements TargetShape {
        public Cone {
            Numbers.nonnegative(length, "cone length"); Numbers.nonnegative(radius, "cone end radius"); Objects.requireNonNull(direction);
            if (length == 0) throw new IllegalArgumentException("Cone length must be positive"); Numbers.finite(Math.hypot(length, radius), "cone bound");
        }
        @Override public double boundingRadius() { return Math.hypot(length, radius); }
        @Override public boolean contains(Offset p) {
            if (direction.isEmpty()) return false; var d = direction.orElseThrow();
            double along = p.x() * d.x() + p.y() * d.y() + p.z() * d.z();
            if (along < 0 || along > length) return false;
            double radial = Math.hypot(Math.hypot(p.y() * d.z() - p.z() * d.y(), p.z() * d.x() - p.x() * d.z()), p.x() * d.y() - p.y() * d.x());
            return radial <= radius * (along / length);
        }
    }
}
