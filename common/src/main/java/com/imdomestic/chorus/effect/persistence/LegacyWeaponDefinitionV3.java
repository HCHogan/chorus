package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.weapon.WeaponDefinition;
import java.util.Optional;

/** Exact pre-charge wire shape; no charge mode is inferred for an old weapon. */
public record LegacyWeaponDefinitionV3(String item, WeaponDefinition.Ammunition ammunition, WeaponDefinition.Reload reload,
        Optional<WeaponDefinition.Fire> fire, Optional<WeaponDefinition.Handling> handling, Optional<WeaponDefinition.Aiming> aiming) {
    public LegacyWeaponDefinitionV3 { new WeaponDefinition(item, ammunition, reload, fire, handling, aiming); }
    public WeaponDefinition current() { return new WeaponDefinition(item, ammunition, reload, fire, handling, aiming); }
}
