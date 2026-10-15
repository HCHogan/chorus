package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import java.util.Optional;

/** Issuer-qualified storage before versioned shot references; existing once-set keys remain bare IDs. */
public record LegacyShotGroupHandleV2(String id, BuffInstance.Origin origin, int pellets, long startedAt, long dueAt,
        Optional<String> issuer) {
    public LegacyShotGroupHandleV2 { new ShotGroups.Handle(id, origin, pellets, startedAt, dueAt, issuer); }
    public ShotGroups.Handle current() { return new ShotGroups.Handle(id, origin, pellets, startedAt, dueAt, issuer); }
}
