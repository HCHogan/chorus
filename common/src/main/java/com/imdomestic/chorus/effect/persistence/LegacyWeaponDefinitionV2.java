package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.weapon.WeaponDefinition;
import java.util.Optional;

/** Exact pre-aim wire shape, retaining the accepted handling configuration. */
public record LegacyWeaponDefinitionV2(String item, WeaponDefinition.Ammunition ammunition, WeaponDefinition.Reload reload,
        Optional<WeaponDefinition.Fire> fire, Optional<WeaponDefinition.Handling> handling) {
    public LegacyWeaponDefinitionV2 { new WeaponDefinition(item, ammunition, reload, fire, handling); }
    public WeaponDefinition current() { return new WeaponDefinition(item, ammunition, reload, fire, handling); }
}
