package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.equipment.EquipmentSchema;
import java.util.Map;

/** Exact original socket shape; old catalogues did not authorize player editing. */
public record LegacyEquipmentSocketV1(Map<String, EquipmentSchema.Effect> options, boolean required) {
    public LegacyEquipmentSocketV1 { new EquipmentSchema.Socket(options, required); }
    public EquipmentSchema.Socket current() { return new EquipmentSchema.Socket(options, required); }
}
