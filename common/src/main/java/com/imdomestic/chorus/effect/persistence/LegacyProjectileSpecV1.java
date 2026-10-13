package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.data.*;
import java.util.Optional;

/** Original projectile declaration retains its unrestricted physical path. */
public record LegacyProjectileSpecV1(String position, String direction, Value speed, Value gravity, Value drag, Value lifetime,
        ProjectileSpec.Collisions collision, Optional<ProjectileSpec.Tracking> tracking, Optional<ProjectileSpec.Destination> destination, Evaluation.Target emitter) {
    public ProjectileSpec current() { return new ProjectileSpec(position, direction, speed, gravity, drag, lifetime, collision, tracking, destination, emitter); }
}
