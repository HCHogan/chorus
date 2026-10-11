package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.weapon.WeaponDefinition;
import java.util.Optional;

/** Exact pre-handling wire shape. The reader validates it before supplying the new optional behavior. */
public record LegacyWeaponDefinitionV1(String item, WeaponDefinition.Ammunition ammunition,
        WeaponDefinition.Reload reload, Optional<WeaponDefinition.Fire> fire) {
    public LegacyWeaponDefinitionV1 { new WeaponDefinition(item, ammunition, reload, fire); }
    public WeaponDefinition current() { return new WeaponDefinition(item, ammunition, reload, fire); }
}
