package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;

/** Steering policy is captured at launch; candidate positions and eligibility are observed in the world. */
public final class ProjectileTracking {
    private ProjectileTracking() {}
    public record Policy(double radius, double turnRate, double acquisitionAngle, TargetQuery.Anchor anchor,
            TargetQuery.Relation relation, boolean lineOfSight, boolean redirectOnContact) {
        public Policy {
            Numbers.nonnegative(radius, "tracking radius"); Numbers.nonnegative(turnRate, "tracking degrees per second");
            Numbers.nonnegative(acquisitionAngle, "tracking cone half angle");
            if (acquisitionAngle > 180) throw new IllegalArgumentException("Tracking cone half angle exceeds 180 degrees");
            Objects.requireNonNull(anchor); Objects.requireNonNull(relation);
        }
    }
    private static double dot(WorldDirection a, WorldDirection b) {
        if (!a.dimension().equals(b.dimension())) throw new IllegalArgumentException("Steering directions changed dimension");
        return Math.clamp(a.x() * b.x() + a.y() * b.y() + a.z() * b.z(), -1, 1);
    }
    public static boolean inCone(WorldDirection facing, WorldDirection towards, double halfAngle) {
        Numbers.nonnegative(halfAngle, "cone half angle");
        if (halfAngle > 180) throw new IllegalArgumentException("Invalid cone half angle");
        return dot(facing, towards) >= Math.cos(Math.toRadians(halfAngle)) - 1e-12;
    }
    /** Shortest-arc turn. The exact antipodal case uses a deterministic perpendicular, never a zero axis. */
    public static WorldDirection turn(WorldDirection facing, WorldDirection towards, double maximumDegrees) {
        Numbers.nonnegative(maximumDegrees, "turn angle"); double cosine = dot(facing, towards);
        double angle = Math.acos(cosine), step = Math.toRadians(Math.min(180, maximumDegrees));
        if (step == 0) return facing;
        if (angle <= step) return towards;
        double x = towards.x() - facing.x() * cosine, y = towards.y() - facing.y() * cosine, z = towards.z() - facing.z() * cosine;
        double length = Math.hypot(Math.hypot(x, y), z);
        if (length < 1e-12) {
            double ax = Math.abs(facing.x()), ay = Math.abs(facing.y()), az = Math.abs(facing.z());
            double bx = ax <= ay && ax <= az ? 1 : 0, by = bx == 0 && ay <= az ? 1 : 0, bz = bx == 0 && by == 0 ? 1 : 0;
            double projection = bx * facing.x() + by * facing.y() + bz * facing.z();
            x = bx - projection * facing.x(); y = by - projection * facing.y(); z = bz - projection * facing.z();
            length = Math.hypot(Math.hypot(x, y), z);
        }
        double s = Math.sin(step) / length, c = Math.cos(step);
        return new WorldDirection(facing.dimension(), facing.x() * c + x * s, facing.y() * c + y * s, facing.z() * c + z * s);
    }
}
