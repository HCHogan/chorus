package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import java.util.Optional;

/** Exact original declaration shape, without a typed entity-life binding. */
public record LegacyProjectileDestinationSpecV1(Evaluation.Target target, Value turnRate, Value arrivalRadius,
        TargetQuery.Anchor anchor, boolean collideEntities, Optional<ProjectileSpec.Catch> catching) {
    public ProjectileSpec.Destination current() { return new ProjectileSpec.Destination(target, turnRate, arrivalRadius, anchor, collideEntities, catching); }
}
