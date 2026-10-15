package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.combat.DamageGroups;
import java.util.Optional;

/** Issuer-qualified storage before versioned fact references; its existing once-set keys stay valid. */
public record LegacyDamageGroupHandleV2(String id, String owner, long startedAt, long dueAt, Optional<String> issuer) {
    public LegacyDamageGroupHandleV2 { new DamageGroups.Handle(id, owner, startedAt, dueAt, issuer); }
    public DamageGroups.Handle current() { return new DamageGroups.Handle(id, owner, startedAt, dueAt, issuer); }
}
