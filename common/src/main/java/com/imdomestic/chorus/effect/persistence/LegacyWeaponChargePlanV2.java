package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.EntityAnchor;
import com.imdomestic.chorus.effect.weapon.WeaponCharge;
import java.util.Optional;

/** Exact full/release plan before repeated cycles. */
public record LegacyWeaponChargePlanV2(String holder, String token, String slot, Loadout.Gear gear,
        BuffInstance.Origin origin, WeaponCharge.Phase phase, long startedAt, long fullAt,
        WeaponCharge.Timing minimum, WeaponCharge.Timing full, Optional<EntityAnchor.Reference> holderReference, WeaponCharge.Trigger trigger) {
    public LegacyWeaponChargePlanV2 { new WeaponCharge.Plan(holder, token, slot, gear, origin, phase, startedAt, fullAt, minimum, full, holderReference, trigger); }
    public WeaponCharge.Plan current() { return new WeaponCharge.Plan(holder, token, slot, gear, origin, phase, startedAt, fullAt, minimum, full, holderReference, trigger); }
}
