package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.projectile.ProjectileFlight;

/** Preserve the pre-fuse outcome vocabulary under its original storage identity. */
public enum LegacyProjectileEndV1 {
    ENTITY, BLOCK, EXPIRED, UNLOADED, ARRIVED, TARGET_LOST, CAUGHT;
    public ProjectileFlight.End current() { return ProjectileFlight.End.valueOf(name()); }
}
