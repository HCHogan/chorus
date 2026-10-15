package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.combat.DamageTallies;

/** Original local handle shape; only an explicit handoff can establish its issuing domain. */
public record LegacyDamageTallyHandleV1(String id, long startedAt, long dueAt) {
    public LegacyDamageTallyHandleV1 { new DamageTallies.Handle(id, startedAt, dueAt); }
    public DamageTallies.Handle current() { return new DamageTallies.Handle(id, startedAt, dueAt); }
}
