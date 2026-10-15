package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.projectile.ShotGroups;

/** Original local handle shape; only an explicit handoff can establish its issuing domain. */
public record LegacyShotGroupHandleV1(String id, BuffInstance.Origin origin, int pellets, long startedAt, long dueAt) {
    public LegacyShotGroupHandleV1 { new ShotGroups.Handle(id, origin, pellets, startedAt, dueAt); }
    public ShotGroups.Handle current() { return new ShotGroups.Handle(id, origin, pellets, startedAt, dueAt); }
}
