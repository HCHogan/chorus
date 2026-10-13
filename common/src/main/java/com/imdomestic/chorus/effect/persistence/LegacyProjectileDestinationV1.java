package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import java.util.Optional;

/** Original identity-only destination; loading it must not invent a previously unobserved life. */
public record LegacyProjectileDestinationV1(String target, double turnRate, double arrivalRadius, TargetQuery.Anchor anchor,
        boolean collideEntities, Optional<ProjectileCatch> catching) {
    public ProjectileDestination current() { return new ProjectileDestination(target, turnRate, arrivalRadius, anchor, collideEntities, catching); }
}
