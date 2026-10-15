package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.combat.DamageGroups;

/** Original local handle shape; only an explicit handoff can establish its issuing domain. */
public record LegacyDamageGroupHandleV1(String id, String owner, long startedAt, long dueAt) {
    public LegacyDamageGroupHandleV1 { new DamageGroups.Handle(id, owner, startedAt, dueAt); }
    public DamageGroups.Handle current() { return new DamageGroups.Handle(id, owner, startedAt, dueAt); }
}
