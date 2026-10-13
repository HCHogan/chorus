package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.projectile.*;
import java.util.Optional;

/** Original ballistic parameters do not acquire a range or proximity fuse on restore. */
public record LegacyProjectileParametersV1(double speed, double gravity, double drag, long lifetimeMicros,
        ProjectileFlight.Collision collision, Optional<ProjectileTracking.Policy> tracking, Optional<ProjectileDestination> destination) {
    public ProjectileFlight.Parameters current() { return new ProjectileFlight.Parameters(speed, gravity, drag, lifetimeMicros, collision, tracking, destination); }
}
